package com.airven.antidrop;

import com.airven.antidrop.command.AntiDropCommand;
import com.airven.antidrop.config.AntiDropConfig;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AntiDrop 客户端入口。
 *
 * <p>本 mod 是纯客户端 mod：不注册任何网络包、不修改服务端逻辑，
 * 只在客户端拦截「玩家主动丢弃白名单物品」的三条路径。
 *
 * <p>关于 Minecraft 26.3 的重要前提（已反编译 {@code client-26.3.jar} 核实）：
 * <ul>
 *     <li>自 26.1 起 Minecraft: Java Edition <b>不再混淆</b>，Fabric Yarn 映射已停止维护，
 *         因此本工程使用的全部是 Mojang 官方名称（例如 {@code ClientPlayerEntity} → {@link LocalPlayer}），
 *         {@code build.gradle} 中也不再声明 mappings。</li>
 *     <li>26.2 起「当前 Screen」的读写位于 {@code Gui}：
 *         {@code Minecraft.getInstance().gui.screen()} / {@code gui.setScreen(...)}。</li>
 *     <li><b>26.3 变更</b>：常规界面的 Q 丢弃从 {@code LocalPlayer#drop(boolean)} 移到了
 *         {@code MultiPlayerGameMode#dropItem(LocalPlayer, boolean)}，因此三个注入点现在都位于
 *         {@code MultiPlayerGameMode}（详见 {@code MultiPlayerGameModeMixin}）。</li>
 * </ul>
 */
public class AntiDropClient implements ClientModInitializer {

	/** mod id，必须与 fabric.mod.json 中的 "id" 一致。 */
	public static final String MOD_ID = "antidrop";

	/** 全局日志器。任何异常都不允许静默失败，至少 warn 一次。 */
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		AntiDropConfig.load();
		AntiDropCommand.register();
		verifyHookTargets();
		LOGGER.info("[AntiDrop] loaded; protection = hotbar:{}, inventoryQ:{}, inventoryDrag:{}",
				AntiDropConfig.get().blockHotbarDrop,
				AntiDropConfig.get().blockInventoryHotbarDrop,
				AntiDropConfig.get().blockInventoryDragDrop);
	}

	/**
	 * 用反射自检 Mixin 目标方法是否存在。
	 *
	 * <p>为什么需要它：{@code antidrop.mixins.json} 里用
	 * {@code "injectors": { "defaultRequire": 0 }} 让所有 injector 都是「可选的」
	 * （宁可少一层保护也不要让玩家游戏崩溃）。代价是「目标方法被改名」时会静默失效，
	 * 所以这里在启动时主动检查一次并打印 WARN，方便定位问题。
	 *
	 * <p>26.1+ 已不再混淆，因此可以直接用字符串反射，无需 intermediary 名称。
	 */
	private static void verifyHookTargets() {
		checkTarget(MultiPlayerGameMode.class, "dropItem", LocalPlayer.class, boolean.class);
		checkTarget(MultiPlayerGameMode.class, "handleContainerInput",
				int.class, int.class, int.class, ContainerInput.class, Player.class);
		checkTarget(MultiPlayerGameMode.class, "handleCreativeModeItemDrop", ItemStack.class);
	}

	/**
	 * 检查某个方法是否存在于给定类（含父类）上。
	 *
	 * @param owner      目标类
	 * @param name       方法名
	 * @param parameters 形参类型
	 */
	private static void checkTarget(Class<?> owner, String name, Class<?>... parameters) {
		try {
			owner.getMethod(name, parameters);
			LOGGER.debug("[AntiDrop] hook target present: {}#{}", owner.getSimpleName(), name);
		} catch (NoSuchMethodException e) {
			LOGGER.warn("[AntiDrop] hook target MISSING: {}#{}({}) - this drop path is NOT protected. "
							+ "Minecraft 26.3 may have renamed/moved it; update the mixin descriptor.",
					owner.getSimpleName(), name, java.util.Arrays.toString(parameters));
		} catch (Throwable t) {
			LOGGER.warn("[AntiDrop] failed to verify hook target {}#{}", owner.getSimpleName(), name, t);
		}
	}
}
