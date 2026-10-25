package com.airven.antidrop.integration;

import com.airven.antidrop.config.AntiDropConfig;
import com.airven.antidrop.config.AntiDropConfig.AddResult;
import com.airven.antidrop.config.AntiDropConfig.ListMode;
import com.airven.antidrop.config.AntiDropConfig.Target;
import com.airven.antidrop.util.Entry;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 名单编辑器：编辑**全局白名单**，以及三个防丢功能各自的黑/白名单。
 *
 * <p>交互：
 * <ul>
 *     <li>顶部「目标」按钮循环切换：全局 → 世界Q → 容器Q → 拖出；</li>
 *     <li>第二个按钮切换该目标的**模式**（白名单/黑名单；全局名单无模式）。
 *         模式只在按钮上显示，界面上不再重复一行说明（避免与条目列表重叠）；</li>
 *     <li>物品 ID 输入框 + 可选「标签」输入框（标签 = 堆的自定义名称，用于只保护改名后的物品），
 *         配合「添加」「删除」按钮；</li>
 *     <li><b>点击列表中的条目可选中</b>：选中行会高亮，并把该条目的 ID / 标签自动填入下方输入栏，
 *         便于修改（改完按「删除」再「添加」即可，或先删除再填新值）；</li>
 *     <li>列表支持鼠标滚轮翻看；底部状态行显示操作结果；ID 输入框内按回车 = 添加。</li>
 * </ul>
 *
 * <p>布局（自上而下，互不重叠）：目标按钮 16、模式按钮 38、条目计数 62、列表 72 起、
 * 状态行 height-104、ID 输入 height-92、标签输入 height-68、返回 height-30。
 *
 * <p>26.3 API 已核实：{@code EditBox(Font,int,int,int,int,Component)} + {@code setHint/setValue/getValue/isFocused}、
 * {@code mouseClicked(MouseButtonEvent, boolean)}、{@code MouseButtonEvent#x/y/button}、
 * {@code GuiGraphicsExtractor#fill(int,int,int,int,int)}（选中高亮）、{@code KeyEvent#key()}。
 */
public class AntiDropListScreen extends Screen {

	private static final int WIDGET_WIDTH = 240;
	private static final int BUTTON_HEIGHT = 20;
	private static final int LINE_HEIGHT = 10;
	private static final int INPUT_WIDTH = 130;

	private static final int TARGET_Y = 16;
	private static final int MODE_Y = 38;
	private static final int HEADER_Y = 62;
	private static final int LIST_TOP = 72;

	private static final int TITLE_COLOR = 0xFFFFFFFF;
	private static final int HINT_COLOR = 0xFFA0A0A0;
	private static final int ENTRY_COLOR = 0xFFE0E0E0;
	private static final int SELECTED_COLOR = 0xFFFFD54F;
	private static final int SELECTED_BG = 0x40FFFFFF;
	private static final int OK_COLOR = 0xFF55FF55;
	private static final int ERROR_COLOR = 0xFFFF5555;

	private final Screen parent;
	private Target target;

	private EditBox idInput;
	private EditBox tagInput;
	private Button modeButton;
	private Component status = Component.empty();
	private boolean statusIsError;

	private int scroll;
	private int selected = -1;
	private int listBottom;
	private int visibleLines;

	/**
	 * @param parent 返回目标
	 * @param target 初始目标
	 */
	public AntiDropListScreen(Screen parent, Target target) {
		super(Component.translatable("antidrop.list.title"));
		this.parent = parent;
		this.target = (target == null) ? Target.GLOBAL : target;
	}

	@Override
	protected void init() {
		int left = this.width / 2 - WIDGET_WIDTH / 2;

		// 目标选择
		this.addRenderableWidget(Button.builder(targetLabel(), button -> {
			this.target = nextTarget(this.target);
			this.scroll = 0;
			this.selected = -1;
			button.setMessage(targetLabel());
			refreshModeButton();
		}).pos(left, TARGET_Y).size(WIDGET_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("antidrop.list.target.tip")))
				.build());

		// 模式选择（全局名单时禁用）。模式只在这里显示，列表上方不再重复。
		this.modeButton = Button.builder(modeLabel(), button -> toggleMode())
				.pos(left, MODE_Y).size(WIDGET_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("antidrop.list.mode.tip")))
				.build();
		this.addRenderableWidget(this.modeButton);
		refreshModeButton();

		// 列表区域（在计数行之下，避免与任何文字重叠）
		this.listBottom = Math.max(LIST_TOP + LINE_HEIGHT, this.height - 116);
		this.visibleLines = Math.max(1, (this.listBottom - LIST_TOP) / LINE_HEIGHT);

		// 输入区
		int idY = this.height - 92;
		this.idInput = new EditBox(this.font, left, idY, INPUT_WIDTH, BUTTON_HEIGHT,
				Component.translatable("antidrop.list.input.id"));
		this.idInput.setHint(Component.translatable("antidrop.list.input.idHint"));
		this.idInput.setMaxLength(128);
		this.addRenderableWidget(this.idInput);

		this.addRenderableWidget(Button.builder(Component.translatable("antidrop.list.add"), button -> submitAdd())
				.pos(left + INPUT_WIDTH + 4, idY).size(52, BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(Component.translatable("antidrop.list.remove"), button -> submitRemove())
				.pos(left + INPUT_WIDTH + 58, idY).size(52, BUTTON_HEIGHT).build());

		int tagY = this.height - 68;
		this.tagInput = new EditBox(this.font, left, tagY, INPUT_WIDTH, BUTTON_HEIGHT,
				Component.translatable("antidrop.list.input.tag"));
		this.tagInput.setHint(Component.translatable("antidrop.list.input.tagHint"));
		this.tagInput.setMaxLength(128);
		this.addRenderableWidget(this.tagInput);

		// 返回
		this.addRenderableWidget(Button.builder(Component.translatable("antidrop.list.back"), button -> this.onClose())
				.pos(left, this.height - 30).size(WIDGET_WIDTH, BUTTON_HEIGHT).build());
	}

	/** @return 目标按钮文案 */
	private Component targetLabel() {
		return Component.translatable("antidrop.list.target", Component.translatable(this.target.translationKey()));
	}

	/** @return 模式按钮文案 */
	private Component modeLabel() {
		if (!this.target.isFeature()) {
			return Component.translatable("antidrop.list.mode.none");
		}
		ListMode mode = AntiDropConfig.get().modeFor(this.target);
		return Component.translatable("antidrop.list.mode",
				Component.translatable(mode == ListMode.BLACKLIST ? "antidrop.mode.blacklist" : "antidrop.mode.whitelist"));
	}

	/** 全局目标时禁用模式按钮。 */
	private void refreshModeButton() {
		if (this.modeButton != null) {
			this.modeButton.active = this.target.isFeature();
			this.modeButton.setMessage(modeLabel());
		}
	}

	/**
	 * 循环切换目标。
	 *
	 * @param current 当前
	 * @return 下一个
	 */
	private static Target nextTarget(Target current) {
		Target[] values = Target.values();
		return values[(current.ordinal() + 1) % values.length];
	}

	/** 切换黑/白名单。 */
	private void toggleMode() {
		if (!this.target.isFeature()) {
			return;
		}
		AntiDropConfig config = AntiDropConfig.get();
		ListMode next = (config.modeFor(this.target) == ListMode.BLACKLIST) ? ListMode.WHITELIST : ListMode.BLACKLIST;
		config.setMode(this.target, next);
		refreshModeButton();
		setStatus(Component.translatable("antidrop.list.status.mode",
				Component.translatable(next == ListMode.BLACKLIST ? "antidrop.mode.blacklist" : "antidrop.mode.whitelist")), false);
	}

	/** 添加条目到当前目标。 */
	private void submitAdd() {
		String id = (this.idInput == null) ? "" : this.idInput.getValue();
		String tag = (this.tagInput == null) ? "" : this.tagInput.getValue();
		if (id == null || id.trim().isEmpty()) {
			setStatus(Component.translatable("antidrop.list.status.empty"), true);
			return;
		}
		AddResult result = AntiDropConfig.get().addEntry(this.target, id, tag);
		String display = (tag == null || tag.trim().isEmpty()) ? id.trim() : id.trim() + " @" + tag.trim();
		switch (result) {
			case ADDED -> {
				setStatus(Component.translatable("antidrop.list.status.added", display), false);
				clearInputs();
				this.selected = -1;
			}
			case DUPLICATE -> setStatus(Component.translatable("antidrop.list.status.exists", display), false);
			case INVALID -> setStatus(Component.translatable("antidrop.list.status.invalid", id), true);
		}
	}

	/** 从当前目标删除条目。 */
	private void submitRemove() {
		String id = (this.idInput == null) ? "" : this.idInput.getValue();
		String tag = (this.tagInput == null) ? "" : this.tagInput.getValue();
		if (id == null || id.trim().isEmpty()) {
			setStatus(Component.translatable("antidrop.list.status.empty"), true);
			return;
		}
		String display = (tag == null || tag.trim().isEmpty()) ? id.trim() : id.trim() + " @" + tag.trim();
		if (AntiDropConfig.get().removeEntry(this.target, id, tag)) {
			setStatus(Component.translatable("antidrop.list.status.removed", display), false);
			clearInputs();
			this.selected = -1;
		} else {
			setStatus(Component.translatable("antidrop.list.status.missing", display), true);
		}
	}

	private void clearInputs() {
		if (this.idInput != null) {
			this.idInput.setValue("");
		}
		if (this.tagInput != null) {
			this.tagInput.setValue("");
		}
	}

	/**
	 * 选中某个条目：高亮它并把 ID / 标签填进下方输入栏。
	 *
	 * <p>按需求**不**在状态行显示选中提示——选中效果由高亮与输入栏内容体现。
	 *
	 * @param index 条目下标
	 * @param entry 条目
	 */
	private void selectEntry(int index, Entry entry) {
		this.selected = index;
		if (this.idInput != null) {
			this.idInput.setValue(entry.id == null ? "" : entry.id);
		}
		if (this.tagInput != null) {
			this.tagInput.setValue(entry.tag == null ? "" : entry.tag);
		}
	}

	private void setStatus(Component message, boolean error) {
		this.status = message;
		this.statusIsError = error;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
		super.extractRenderState(drawContext, mouseX, mouseY, delta);

		int left = this.width / 2 - WIDGET_WIDTH / 2;
		List<Entry> entries = AntiDropConfig.get().viewOf(this.target);

		drawContext.centeredText(this.font, this.title, this.width / 2, 4, TITLE_COLOR);

		// 条目计数（紧贴模式按钮下方，位于列表之上，不再有第二行“规则”文字）
		drawContext.text(this.font, Component.translatable("antidrop.list.count", entries.size()),
				left, HEADER_Y, HINT_COLOR);
		if (entries.size() > this.visibleLines) {
			drawContext.text(this.font, Component.translatable("antidrop.list.scrollHint"),
					left + WIDGET_WIDTH - 76, HEADER_Y, HINT_COLOR);
		}

		int maxScroll = Math.max(0, entries.size() - this.visibleLines);
		if (this.scroll > maxScroll) {
			this.scroll = maxScroll;
		}
		if (this.scroll < 0) {
			this.scroll = 0;
		}
		if (this.selected >= entries.size()) {
			this.selected = entries.size() - 1;
		}
		if (this.selected < -1) {
			this.selected = -1;
		}

		if (entries.isEmpty()) {
			drawContext.text(this.font, Component.translatable("antidrop.list.empty"), left, LIST_TOP, HINT_COLOR);
		} else {
			int end = Math.min(entries.size(), this.scroll + this.visibleLines);
			for (int i = this.scroll; i < end; i++) {
				int y = LIST_TOP + (i - this.scroll) * LINE_HEIGHT;
				boolean isSelected = (i == this.selected);
				if (isSelected) {
					drawContext.fill(left - 2, y - 1, left + WIDGET_WIDTH + 2, y + LINE_HEIGHT - 1, SELECTED_BG);
				}
				String prefix = isSelected ? "> " : "- ";
				drawContext.text(this.font, prefix + entries.get(i).display(), left, y,
						isSelected ? SELECTED_COLOR : ENTRY_COLOR);
			}
		}

		// 标签输入框旁的解释
		if (this.tagInput != null) {
			drawContext.text(this.font, Component.translatable("antidrop.list.input.tagHint2"),
					left + INPUT_WIDTH + 4, this.tagInput.getY() + 6, HINT_COLOR);
		}

		if (!this.status.getString().isEmpty()) {
			drawContext.centeredText(this.font, this.status, this.width / 2, this.height - 104,
					this.statusIsError ? ERROR_COLOR : OK_COLOR);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 先处理列表行，再交给 super 派发给按钮/输入框。
		// 注意：26.3 的鼠标键是 1-based —— InputConstants.MOUSE_BUTTON_LEFT = 1，
		// 早先按 0 判断会让左键永远被视为“非左键”，条目因此点不中。
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && selectRowAt(event.x(), event.y())) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	/**
	 * 命中列表行则选中并回填输入框。
	 *
	 * @param mouseX 鼠标 x（GUI 缩放后坐标，与布局同一坐标系）
	 * @param mouseY 鼠标 y
	 * @return 是否命中某一行
	 */
	private boolean selectRowAt(double mouseX, double mouseY) {
		int left = this.width / 2 - WIDGET_WIDTH / 2;
		if (mouseX < left - 2 || mouseX > left + WIDGET_WIDTH + 2) {
			return false;
		}
		if (mouseY < LIST_TOP || mouseY >= this.listBottom) {
			return false;
		}
		int row = (int) ((mouseY - LIST_TOP) / LINE_HEIGHT);
		int index = this.scroll + row;
		List<Entry> entries = AntiDropConfig.get().viewOf(this.target);
		if (index < 0 || index >= entries.size()) {
			return false;
		}
		selectEntry(index, entries.get(index));
		return true;
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
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN && this.idInput != null && this.idInput.isFocused()) {
			submitAdd();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		Minecraft minecraft = this.minecraft;
		if (minecraft != null) {
			minecraft.gui.setScreen(this.parent);
		}
	}
}
