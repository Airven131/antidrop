package com.airven.antidrop.mixin;

import com.airven.antidrop.AntiDropClient;
import com.airven.antidrop.handler.DropInterceptor;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 三条丢弃路径的拦截点。**全部集中在这一个类里**，因为 26.3 之后这三个方法都属于
 * {@code MultiPlayerGameMode}（26.2 时第 ① 条还在 {@code LocalPlayer#drop(boolean)}）。
 *
 * <p>调用链用 {@code javap -c -p} 反汇编 26.3 官方 client.jar 逐条确认：
 * <pre>
 * ① 常规界面按 Q
 *   Minecraft#handleKeybinds（Options.keyDrop.consumeClick()）
 *     → MultiPlayerGameMode#dropItem(LocalPlayer, boolean)          ← 注入点 A（HEAD, cancellable）
 *       26.3 方法体：Inventory#removeFromSelected(boolean)
 *                    → ServerboundPlayerActionPacket(DROP_ITEM / DROP_ALL_ITEMS)
 *                    → LocalPlayer#swing(...)
 *       即「先本地取出、再发包」。HEAD 取消时物品**尚未**被取出，所以取消是安全的
 *       （这也与 26.2 不同：26.2 的 LocalPlayer#drop 只发包，不移除本地物品）。
 *
 * ② 容器界面按 Q
 *   AbstractContainerScreen#keyPressed(KeyEvent)
 *     → slotClicked(Slot, slotId, mouseButton, ContainerInput.THROW)
 *       → MultiPlayerGameMode#handleContainerInput(int, int, int, ContainerInput, Player)
 *                                                                  ← 注入点 B（HEAD, cancellable）
 *         → ServerboundContainerClickPacket → 服务端丢弃「悬停槽位」的物品
 *
 * ③ 鼠标把物品拖出界面
 *   AbstractContainerScreen#mouseReleased(MouseButtonEvent)
 *     → slotClicked(…, ContainerInput.PICKUP)，槽位号 = AbstractContainerMenu.SLOT_CLICKED_OUTSIDE（-999）
 *       → MultiPlayerGameMode#handleContainerInput(…)                ← 注入点 B
 *         → 服务端 AbstractContainerMenu#clicked(…) 丢弃「光标上」的整堆物品
 *
 * ④ 创造模式拖出界面（不走槽位点击）
 *   AbstractContainerScreen#mouseReleased → MultiPlayerGameMode#handleCreativeModeItemDrop(ItemStack)
 *                                                                  ← 注入点 C（HEAD, cancellable）
 *     → ServerboundSetCreativeModeSlotPacket(slot = -1)
 * </pre>
 *
 * <p>26.3 签名（javap 已确认）：
 * <ul>
 *     <li>{@code public void dropItem(net.minecraft.client.player.LocalPlayer, boolean)}</li>
 *     <li>{@code public void handleContainerInput(int, int, int, ContainerInput, Player)}</li>
 *     <li>{@code public void handleCreativeModeItemDrop(ItemStack)}</li>
 * </ul>
 *
 * <p>「注入失败不要崩溃」由 {@code antidrop.mixins.json} 的 {@code injectors.defaultRequire: 0} 统一保证
 * （配置层能力，不依赖具体注解成员，跨 Mixin 版本更稳妥）。
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

	/**
	 * 注入点 A：常规界面按 Q 丢弃。
	 *
	 * @param player  本地玩家（原版传入）
	 * @param dropAll 是否整堆丢弃（Ctrl+Q）
	 * @param ci      取消回调
	 */
	@Inject(
			method = "dropItem(Lnet/minecraft/client/player/LocalPlayer;Z)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void antidrop$blockProtectedHotbarDrop(LocalPlayer player, boolean dropAll, CallbackInfo ci) {
		try {
			if (DropInterceptor.onHotbarDrop(player)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] dropItem injection failed; allowing the drop", t);
		}
	}

	/**
	 * 注入点 B：容器界面内的 Q 键丢弃与鼠标拖出丢弃。
	 *
	 * @param containerId 容器 id
	 * @param slotId      槽位 id（{@code SLOT_CLICKED_OUTSIDE} = -999 表示界面外）
	 * @param mouseButton 鼠标键
	 * @param clickType   输入类型（THROW / PICKUP …）
	 * @param player      玩家
	 * @param ci          取消回调
	 */
	@Inject(
			method = "handleContainerInput(IIILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void antidrop$blockProtectedContainerDrop(int containerId, int slotId, int mouseButton,
													  ContainerInput clickType, Player player, CallbackInfo ci) {
		try {
			if (DropInterceptor.onContainerInput(player, containerId, slotId, mouseButton, clickType)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] handleContainerInput injection failed; allowing the click", t);
		}
	}

	/**
	 * 注入点 C：创造模式把物品拖出界面丢弃。
	 *
	 * @param stack 即将被丢出的物品堆
	 * @param ci    取消回调
	 */
	@Inject(
			method = "handleCreativeModeItemDrop(Lnet/minecraft/world/item/ItemStack;)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void antidrop$blockProtectedCreativeDrop(ItemStack stack, CallbackInfo ci) {
		try {
			if (DropInterceptor.onCreativeModeItemDrop(stack)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] handleCreativeModeItemDrop injection failed; allowing the drop", t);
		}
	}
}
