package com.airven.antidrop.integration;

import com.airven.antidrop.config.AntiDropConfig;
import com.airven.antidrop.config.AntiDropConfig.Target;
import com.airven.antidrop.util.Entry;
import com.airven.antidrop.util.ItemMatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 主配置界面（Mod Menu 的「配置」按钮打开的就是它）。
 *
 * <p>布局：4 个保护开关（2×2）+ 名单摘要 + 若干入口按钮。
 * 名单的增删与「每个防丢功能的黑/白名单」在 {@link AntiDropListScreen} 中编辑。
 *
 * <p>26.3 GUI API（均已反编译核实）：{@code Screen#extractRenderState(GuiGraphicsExtractor, int, int, float)}、
 * {@code GuiGraphicsExtractor#text/centeredText}、{@code Button.Builder#pos/size/tooltip}、
 * {@code Minecraft.gui.setScreen(...)}。
 */
public class AntiDropConfigScreen extends Screen {

	private static final int WIDGET_WIDTH = 240;
	private static final int COLUMN_WIDTH = 118;
	private static final int BUTTON_HEIGHT = 20;

	private static final int TITLE_COLOR = 0xFFFFFFFF;
	private static final int HINT_COLOR = 0xFFA0A0A0;
	private static final int OK_COLOR = 0xFF55FF55;
	private static final int ERROR_COLOR = 0xFFFF5555;

	private final Screen parent;

	private Component status = Component.empty();
	private boolean statusIsError;

	/**
	 * @param parent 关闭后返回的界面（Mod Menu 会传入其 Mods 界面）
	 */
	public AntiDropConfigScreen(Screen parent) {
		super(Component.translatable("antidrop.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		AntiDropConfig config = AntiDropConfig.get();
		int left = this.width / 2 - WIDGET_WIDTH / 2;
		int right = left + WIDGET_WIDTH - COLUMN_WIDTH;

		int row1 = 26;
		int row2 = row1 + BUTTON_HEIGHT + 2;

		this.addRenderableWidget(toggle(left, row1, "antidrop.config.shortHotbar",
				"antidrop.config.blockHotbarDrop", config.blockHotbarDrop, value -> config.blockHotbarDrop = value));
		this.addRenderableWidget(toggle(right, row1, "antidrop.config.shortInventoryQ",
				"antidrop.config.blockInventoryHotbarDrop", config.blockInventoryHotbarDrop,
				value -> config.blockInventoryHotbarDrop = value));
		this.addRenderableWidget(toggle(left, row2, "antidrop.config.shortDrag",
				"antidrop.config.blockInventoryDragDrop", config.blockInventoryDragDrop,
				value -> config.blockInventoryDragDrop = value));
		this.addRenderableWidget(toggle(right, row2, "antidrop.config.shortDebug",
				"antidrop.config.debugLogging", config.debugLogging, value -> config.debugLogging = value));

		// 名单编辑入口
		this.addRenderableWidget(Button.builder(Component.translatable("antidrop.config.editLists"),
						button -> this.openListEditor(Target.GLOBAL))
				.pos(left, this.height - 80).size(WIDGET_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("antidrop.config.editLists.tip")))
				.build());

		// 添加手持物品 / 从文件重载
		this.addRenderableWidget(Button.builder(Component.translatable("antidrop.config.addHeld"),
						button -> this.addHeldItem())
				.pos(left, this.height - 56).size(COLUMN_WIDTH, BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(Component.translatable("antidrop.config.reload"),
						button -> this.reload())
				.pos(right, this.height - 56).size(COLUMN_WIDTH, BUTTON_HEIGHT).build());

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
				.pos(left, this.height - 30).size(WIDGET_WIDTH, BUTTON_HEIGHT).build());
	}

	/**
	 * 打开名单编辑器。
	 *
	 * @param target 初始目标
	 */
	private void openListEditor(Target target) {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(new AntiDropListScreen(this, target));
		}
	}

	/**
	 * 生成开关按钮（短标签 + 完整说明 tooltip）。
	 *
	 * @param x          左上角 x
	 * @param y          左上角 y
	 * @param shortKey   短标签翻译键
	 * @param tooltipKey 说明翻译键
	 * @param initial    初始值
	 * @param setter     写回配置
	 * @return 按钮
	 */
	private Button toggle(int x, int y, String shortKey, String tooltipKey, boolean initial, Consumer<Boolean> setter) {
		final boolean[] state = { initial };
		return Button.builder(stateLabel(shortKey, state[0]), button -> {
			state[0] = !state[0];
			setter.accept(state[0]);
			AntiDropConfig.get().save();
			button.setMessage(stateLabel(shortKey, state[0]));
		}).pos(x, y).size(COLUMN_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable(tooltipKey)))
				.build();
	}

	private static Component stateLabel(String shortKey, boolean value) {
		return Component.translatable(shortKey)
				.append(Component.literal(": "))
				.append(Component.translatable(value ? "options.on" : "options.off"));
	}

	/** 把当前主手物品（无标签）加入全局名单。 */
	private void addHeldItem() {
		Minecraft minecraft = this.minecraft;
		String id = (minecraft == null || minecraft.player == null)
				? null
				: ItemMatcher.itemIdOf(minecraft.player.getInventory().getSelectedItem());
		if (id == null) {
			setStatus(Component.translatable("antidrop.config.status.noitem"), true);
			return;
		}
		switch (AntiDropConfig.get().addEntry(Target.GLOBAL, id, null)) {
			case ADDED -> setStatus(Component.translatable("antidrop.config.status.added", id), false);
			case DUPLICATE -> setStatus(Component.translatable("antidrop.config.status.exists", id), false);
			case INVALID -> setStatus(Component.translatable("antidrop.config.status.invalid", id), true);
		}
	}

	/** 重新读取磁盘配置（含版本迁移）。 */
	private void reload() {
		int size = AntiDropConfig.get().reloadFromDisk();
		setStatus(Component.translatable("antidrop.config.status.reloaded", size), false);
	}

	private void setStatus(Component message, boolean error) {
		this.status = message;
		this.statusIsError = error;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
		super.extractRenderState(drawContext, mouseX, mouseY, delta);

		int left = this.width / 2 - WIDGET_WIDTH / 2;
		AntiDropConfig config = AntiDropConfig.get();

		drawContext.centeredText(this.font, this.title, this.width / 2, 4, TITLE_COLOR);
		drawContext.centeredText(this.font, Component.translatable("antidrop.config.fileHint"),
				this.width / 2, 15, HINT_COLOR);

		// 名单摘要
		drawContext.text(this.font, Component.translatable("antidrop.config.summary.global",
				config.countFor(Target.GLOBAL)), left, 74, TITLE_COLOR);
		drawContext.text(this.font, Component.translatable("antidrop.config.summary.features",
				featureSummary(config, Target.HOTBAR), featureSummary(config, Target.INVENTORY_Q),
				featureSummary(config, Target.DRAG_OUT)),
				left, 86, HINT_COLOR);
		drawContext.text(this.font, Component.translatable("antidrop.config.summary.hint"),
				left, 98, HINT_COLOR);

		// 全局名单前几条预览
		int previewY = 114;
		int shown = 0;
		for (Entry entry : config.viewOf(Target.GLOBAL)) {
			if (shown >= 3) {
				drawContext.text(this.font, Component.translatable("antidrop.config.summary.more",
						Math.max(0, config.countFor(Target.GLOBAL) - 3)), left, previewY, HINT_COLOR);
				break;
			}
			drawContext.text(this.font, "- " + entry.display(), left, previewY, 0xFFE0E0E0);
			previewY += 10;
			shown++;
		}

		if (!this.status.getString().isEmpty()) {
			drawContext.centeredText(this.font, this.status, this.width / 2, this.height - 94,
					this.statusIsError ? ERROR_COLOR : OK_COLOR);
		}
	}

	/**
	 * 生成形如 {@code 白(0)} 的功能名单摘要。
	 *
	 * @param config 配置
	 * @param target 目标
	 * @return 组件
	 */
	private static Component featureSummary(AntiDropConfig config, Target target) {
		boolean black = config.modeFor(target) == AntiDropConfig.ListMode.BLACKLIST;
		return Component.translatable(black ? "antidrop.config.summary.black" : "antidrop.config.summary.white",
				config.countFor(target));
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(this.parent);
		}
	}
}
