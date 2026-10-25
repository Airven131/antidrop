package com.airven.antidrop.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 集成。
 *
 * <p>实现 {@link ModMenuApi} 并通过 {@code fabric.mod.json} 里的
 * {@code "entrypoints": { "modmenu": [ ... ] }} 注册。
 *
 * <p>未安装 Mod Menu 时：Fabric Loader 不会去实例化 "modmenu" 类型的 entrypoint
 * （没有任何 mod 请求该类型），所以本类不会被加载，也就不会出现 {@code NoClassDefFoundError}，
 * mod 功能完全不受影响 —— 这正是「软依赖」的标准做法。因此本类<b>不要</b>在
 * {@code AntiDropClient} 里直接 new。
 *
 * <p>已核实的 Mod Menu 26.2 API（取自已安装 26.2 支持的分支源码）：
 * <pre>
 * package com.terraformersmc.modmenu.api;
 * public interface ModMenuApi {
 *     default ConfigScreenFactory&lt;?&gt; getModConfigScreenFactory();
 * }
 * &#64;FunctionalInterface
 * public interface ConfigScreenFactory&lt;S extends Screen&gt; { S create(Screen parent); }
 * </pre>
 */
public class AntiDropModMenu implements ModMenuApi {

	/**
	 * 提供 Mod Menu 「配置」按钮点击后打开的界面。
	 *
	 * @return 配置界面工厂；返回 {@code AntiDropConfigScreen::new}
	 */
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return AntiDropConfigScreen::new;
	}
}
