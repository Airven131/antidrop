package com.airven.antidrop.integration;

import com.airven.antidrop.config.AntiDropConfig;
import com.airven.antidrop.config.AntiDropConfig.ListMode;
import com.airven.antidrop.config.AntiDropConfig.Target;
import com.airven.antidrop.util.Entry;
import com.airven.antidrop.util.ItemMatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 主配置界面（Mod Menu 的「配置」按钮打开的就是它）。
 *
 * <p>布局：4 个保护开关（2×2）+ **全部名单条目的滚动展示** + 入口按钮。
 * 列表依次展示：全局名单、世界 Q、容器 Q、拖出（每个功能显示其模式与条目），
 * 用鼠标滚轮翻看。名单的增删与黑/白名单在 {@link AntiDropListScreen} 中编辑。
 *
 * <p>26.3 GUI API（均已反编译核实）：{@code Screen#extractRenderState(GuiGraphicsExtractor, int, int, float)}、
 * {@code GuiGraphicsExtractor#text/centeredText}、{@code Button.Builder#pos/size/tooltip}、
 * {@code Minecraft.gui.setScreen(...)}。
 */
public class AntiDropConfigScreen extends Screen {

	private static final int WIDGET_WIDTH = 240;
	private static final int COLUMN_WIDTH = 118;
	private static final int BUTTON_HEIGHT = 20;
	private static final int LINE_HEIGHT = 10;
	private static final int LIST_TOP = 78;

	private static final int TITLE_COLOR = 0xFFFFFFFF;
	private static final int HEADER_COLOR = 0xFFFFF59D;
	private static final int ENTRY_COLOR = 0xFFE0E0E0;
	private static final int HINT_COLOR = 0xFFA0A0A0;
	private static final int OK_COLOR = 0xFF55FF55;
	private static final int ERROR_COLOR = 0xFFFF5555;

	private final Screen parent;

	private Component status = Component.empty();
	private boolean statusIsError;
	private int scroll;

	/** 列表中的一行（小节标题或条目）。 */
	private record Row(Component text, int color) {
	}

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
				.pos(left, this.height - 86).size(WIDGET_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("antidrop.config.editLists.tip")))
				.build());

		// 添加手持物品 / 从文件重载
		this.addRenderableWidget(Button.builder(Component.translatable("antidrop.config.addHeld"),
						button -> this.addHeldItem())
				.pos(left, this.height - 62).size(COLUMN_WIDTH, BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(Component.translatable("antidrop.config.reload"),
						button -> this.reload())
				.pos(right, this.height - 62).size(COLUMN_WIDTH, BUTTON_HEIGHT).build());

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
				.pos(left, this.height - 36).size(WIDGET_WIDTH, BUTTON_HEIGHT).build());
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
		this.scroll = 0;
		setStatus(Component.translatable("antidrop.config.status.reloaded", size), false);
	}

	private void setStatus(Component message, boolean error) {
		this.status = message;
		this.statusIsError = error;
	}

	/**
	 * 构造全部名单的展示行：全局 + 三个功能（各带模式标题与条目）。
	 *
	 * @return 行列表
	 */
	private static List<Row> buildRows() {
		AntiDropConfig config = AntiDropConfig.get();
		List<Row> rows = new ArrayList<>();

		rows.add(new Row(Component.translatable("antidrop.config.section.global",
				config.countFor(Target.GLOBAL)), HEADER_COLOR));
		addEntryRows(rows, config.viewOf(Target.GLOBAL));

		for (Target target : new Target[]{Target.HOTBAR, Target.INVENTORY_Q, Target.DRAG_OUT}) {
			boolean black = config.modeFor(target) == ListMode.BLACKLIST;
			rows.add(new Row(Component.translatable("antidrop.config.section.feature",
					Component.translatable(target.translationKey()),
					Component.translatable(black ? "antidrop.mode.blacklist" : "antidrop.mode.whitelist"),
					config.countFor(target)), HEADER_COLOR));
			addEntryRows(rows, config.viewOf(target));
		}
		return rows;
	}

	private static void addEntryRows(List<Row> rows, List<Entry> entries) {
		if (entries.isEmpty()) {
			rows.add(new Row(Component.translatable("antidrop.config.section.empty"), HINT_COLOR));
			return;
		}
		for (Entry entry : entries) {
			rows.add(new Row(Component.literal("- " + entry.display()), ENTRY_COLOR));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
		super.extractRenderState(drawContext, mouseX, mouseY, delta);

		int left = this.width / 2 - WIDGET_WIDTH / 2;
		List<Row> rows = buildRows();

		drawContext.centeredText(this.font, this.title, this.width / 2, 4, TITLE_COLOR);
		drawContext.centeredText(this.font, Component.translatable("antidrop.config.fileHint"),
				this.width / 2, 15, HINT_COLOR);

		int listBottom = Math.max(LIST_TOP + LINE_HEIGHT, this.height - 100);
		int visibleLines = Math.max(1, (listBottom - LIST_TOP) / LINE_HEIGHT);

		int maxScroll = Math.max(0, rows.size() - visibleLines);
		if (this.scroll > maxScroll) {
			this.scroll = maxScroll;
		}
		if (this.scroll < 0) {
			this.scroll = 0;
		}

		int end = Math.min(rows.size(), this.scroll + visibleLines);
		for (int i = this.scroll; i < end; i++) {
			Row row = rows.get(i);
			drawContext.text(this.font, row.text(), left, LIST_TOP + (i - this.scroll) * LINE_HEIGHT, row.color());
		}

		if (!this.status.getString().isEmpty()) {
			drawContext.centeredText(this.font, this.status, this.width / 2, this.height - 100,
					this.statusIsError ? ERROR_COLOR : OK_COLOR);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
			return true;
		}
		if (scrollY != 0) {
			this.scroll -= (int) Math.signum(scrollY);
			return true;
		}
		return false;
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(this.parent);
		}
	}
}
