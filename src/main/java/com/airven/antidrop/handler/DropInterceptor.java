package com.airven.antidrop.handler;

import com.airven.antidrop.AntiDropClient;
import com.airven.antidrop.config.AntiDropConfig;
import com.airven.antidrop.config.AntiDropConfig.Target;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * 统一的「是否阻止本次丢弃」判断逻辑。
 *
 * <p>三条被拦截的路径与对应实现点（<b>全部用 26.3 客户端 jar 反编译核实过</b>）：
 * <ol>
 *     <li><b>常规界面按 Q</b> —— {@code MultiPlayerGameMode#dropItem(LocalPlayer, boolean)}
 *         （可能映射到 {@link Target#HOTBAR} 或 {@link Target#INVENTORY_Q}，取决于当前是否有容器界面）；</li>
 *     <li><b>容器界面按 Q</b> 与 <b>鼠标拖出界面</b> ——
 *         {@code MultiPlayerGameMode#handleContainerInput(int, int, int, ContainerInput, Player)}；</li>
 *     <li><b>创造模式拖出界面</b> —— {@code MultiPlayerGameMode#handleCreativeModeItemDrop(ItemStack)}。</li>
 * </ol>
 *
 * <p><b>生效名单</b>由 {@link AntiDropConfig#isProtected(Target, ItemStack)} 计算：
 * 白名单模式 = 全局 ∪ 功能名单；黑名单模式 = 全局 − 功能名单。
 * 三个功能开关（{@code blockHotbarDrop} / {@code blockInventoryHotbarDrop} / {@code blockInventoryDragDrop}）
 * 仍然决定该功能是否启用。
 *
 * <p>异常策略：任何判断异常都只记录 WARN 并<b>放行</b>（fail-open），避免卡住玩家物品。
 */
public final class DropInterceptor {

	/** actionbar 提示的最小间隔（纳秒），避免连按 Q 时刷屏。 */
	private static final long NOTIFY_COOLDOWN_NANOS = 200_000_000L;

	private static long lastNotifyNanos;

	private DropInterceptor() {
	}

	/**
	 * 处理「常规界面按 Q 丢弃手持物品」（26.3 的 {@code MultiPlayerGameMode#dropItem}）。
	 *
	 * @param player 触发丢弃的本地玩家；物品堆必须在本方法内、原版移除之前读取
	 * @return {@code true} 表示需要取消本次丢弃
	 */
	public static boolean onHotbarDrop(LocalPlayer player) {
		try {
			ItemStack selectedStack = (player == null) ? ItemStack.EMPTY : player.getInventory().getSelectedItem();
			// 容器界面打开时原版走槽位点击；不同版本对「Q 在界面内是否仍注册按键」处理不同，
			// 这里按当前界面动态选目标，两条路径都不会漏。
			Target target = isContainerScreenOpen() ? Target.INVENTORY_Q : Target.HOTBAR;
			return blockIfNeeded(target, selectedStack, "MultiPlayerGameMode#dropItem");
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] onHotbarDrop failed; allowing the drop", t);
			return false;
		}
	}

	/**
	 * 处理容器界面内的槽位点击，覆盖「按 Q 丢弃（THROW）」与「拖出界面丢弃（SLOT_CLICKED_OUTSIDE）」。
	 *
	 * @param player      点击的玩家（原版传入的就是本地玩家）
	 * @param containerId 容器 id
	 * @param slotId      槽位 id；{@link AbstractContainerMenu#SLOT_CLICKED_OUTSIDE}（-999）表示界面外
	 * @param mouseButton 鼠标键（THROW 时 0 = 整堆，1 = 单个）
	 * @param clickType   点击类型（{@link ContainerInput}）
	 * @return {@code true} 表示需要取消这次点击（即取消发包，服务端不会执行丢弃）
	 */
	public static boolean onContainerInput(Player player, int containerId, int slotId, int mouseButton, ContainerInput clickType) {
		try {
			if (clickType != ContainerInput.THROW && clickType != ContainerInput.PICKUP) {
				return false;
			}
			AbstractContainerMenu menu = player.containerMenu;
			if (menu == null || containerId != menu.containerId) {
				return false;
			}

			ItemStack targetStack;
			Target target;
			if (clickType == ContainerInput.THROW) {
				// 容器界面按 Q：丢弃鼠标悬停槽位里的物品。
				if (slotId < 0 || slotId >= menu.slots.size()) {
					return false;
				}
				targetStack = menu.getSlot(slotId).getItem();
				target = Target.INVENTORY_Q;
			} else if (slotId == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) {
				// 拖出界面：光标上的那一堆会被丢出去。
				targetStack = menu.getCarried();
				target = Target.DRAG_OUT;
			} else {
				return false;
			}

			AntiDropConfig config = AntiDropConfig.get();
			if (config.debugLogging) {
				AntiDropClient.LOGGER.info(
						"[AntiDrop] debug: handleContainerInput container={} slot={} button={} input={} target={} stack={}",
						containerId, slotId, mouseButton, clickType, target, targetStack);
			}
			return blockIfNeeded(target, targetStack, "handleContainerInput");
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] onContainerInput failed; allowing the click", t);
			return false;
		}
	}

	/**
	 * 处理创造模式下的「拖出界面丢弃」。
	 *
	 * @param stack 即将被丢出的物品堆
	 * @return {@code true} 表示需要取消
	 */
	public static boolean onCreativeModeItemDrop(ItemStack stack) {
		try {
			return blockIfNeeded(Target.DRAG_OUT, stack, "handleCreativeModeItemDrop");
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] onCreativeModeItemDrop failed; allowing the drop", t);
			return false;
		}
	}

	/**
	 * 核心判断：功能开关 + 生效名单。
	 *
	 * @param target 功能目标
	 * @param stack  即将被丢弃的物品堆
	 * @param source 调用来源（仅日志）
	 * @return 是否需要阻止
	 */
	private static boolean blockIfNeeded(Target target, ItemStack stack, String source) {
		AntiDropConfig config = AntiDropConfig.get();
		boolean blocked = isEnabled(config, target) && config.isProtected(target, stack);

		if (blocked) {
			if (config.debugLogging) {
				AntiDropClient.LOGGER.info("[AntiDrop] debug: blocked {} for {} (mode={}) from {}",
						stack, target, config.modeFor(target), source);
			}
			notifyBlocked();
		} else if (config.debugLogging && stack != null && !stack.isEmpty()) {
			AntiDropClient.LOGGER.info("[AntiDrop] debug: allowed {} for {} (mode={}) from {}",
					stack, target, config.modeFor(target), source);
		}
		return blocked;
	}

	/**
	 * 读取对应功能的开关。
	 *
	 * @param config 配置
	 * @param target 目标
	 * @return 该功能是否启用
	 */
	private static boolean isEnabled(AntiDropConfig config, Target target) {
		return switch (target) {
			case GLOBAL -> false;
			case HOTBAR -> config.blockHotbarDrop;
			case INVENTORY_Q -> config.blockInventoryHotbarDrop;
			case DRAG_OUT -> config.blockInventoryDragDrop;
		};
	}

	/**
	 * 通过 actionbar 提示玩家「已经拦下来了」。
	 *
	 * <p>26.3 的 API 是 {@code Player#sendOverlayMessage(Component)}（{@code displayClientMessage} 已移除）。
	 */
	private static void notifyBlocked() {
		try {
			long now = System.nanoTime();
			if (now - lastNotifyNanos < NOTIFY_COOLDOWN_NANOS) {
				return;
			}
			lastNotifyNanos = now;

			Player player = Minecraft.getInstance().player;
			if (player != null) {
				player.sendOverlayMessage(Component.translatable("antidrop.blocked"));
			}
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] failed to show the actionbar notice", t);
		}
	}

	/**
	 * 当前是否打开了容器界面。
	 *
	 * @return 当前是 {@link AbstractContainerScreen} 时返回 {@code true}
	 */
	private static boolean isContainerScreenOpen() {
		try {
			Screen screen = Minecraft.getInstance().gui.screen();
			return screen instanceof AbstractContainerScreen;
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] could not read the current screen; assuming no container screen", t);
			return false;
		}
	}
}
