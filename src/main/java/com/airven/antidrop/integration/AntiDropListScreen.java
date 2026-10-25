package com.airven.antidrop.integration;

import com.airven.antidrop.config.AntiDropConfig;
import com.airven.antidrop.config.AntiDropConfig.AddResult;
import com.airven.antidrop.config.AntiDropConfig.ListMode;
import com.airven.antidrop.config.AntiDropConfig.Target;
import com.airven.antidrop.util.Entry;
import com.airven.antidrop.util.ItemMatcher;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 名单编辑器：编辑**全局白名单**，以及三个防丢功能各自的黑/白名单。
 *
 * <p>交互：
 * <ul>
 *     <li>顶部「目标」按钮循环切换：全局 → 世界Q → 容器Q → 拖出；</li>
 *     <li>第二个按钮切换该目标的**模式**（仅功能名单可用）：
 *         白名单 = 全局 ∪ 本名单；黑名单 = 全局 − 本名单；全局名单没有模式；</li>
 *     <li>物品 ID 输入框 + 可选「标签」输入框（标签 = 堆的自定义名称，用于只保护改名后的物品），
 *         配合「添加」「删除」按钮；</li>
 *     <li>列表支持鼠标滚轮翻看；底部状态行显示操作结果；</li>
 *     <li>ID 输入框内按回车 = 添加。</li>
 * </ul>
 *
 * <p>26.3 API 已核实：{@code EditBox(Font,int,int,int,int,Component)} + {@code setHint/setValue/getValue/isFocused}、
 * {@code GuiEventListener#mouseScrolled(double,double,double,double)}、{@code KeyEvent#key()}。
 */
public class AntiDropListScreen extends Screen {

	private static final int WIDGET_WIDTH = 240;
	private static final int BUTTON_HEIGHT = 20;
	private static final int LINE_HEIGHT = 10;
	private static final int INPUT_WIDTH = 130;

	private static final int TITLE_COLOR = 0xFFFFFFFF;
	private static final int HINT_COLOR = 0xFFA0A0A0;
	private static final int ENTRY_COLOR = 0xFFE0E0E0;
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
	private int listTop;
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
			button.setMessage(targetLabel());
			refreshModeButton();
		}).pos(left, 18).size(WIDGET_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("antidrop.list.target.tip")))
				.build());

		// 模式选择（全局名单时禁用）
		this.modeButton = Button.builder(modeLabel(), button -> toggleMode())
				.pos(left, 40).size(WIDGET_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("antidrop.list.mode.tip")))
				.build();
		this.addRenderableWidget(this.modeButton);
		refreshModeButton();

		// 列表区域
		this.listTop = 72;
		this.listBottom = Math.max(this.listTop + LINE_HEIGHT, this.height - 116);
		this.visibleLines = Math.max(1, (this.listBottom - this.listTop) / LINE_HEIGHT);

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
				this.idInput.setValue("");
				if (this.tagInput != null) {
					this.tagInput.setValue("");
				}
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
			this.idInput.setValue("");
			if (this.tagInput != null) {
				this.tagInput.setValue("");
			}
		} else {
			setStatus(Component.translatable("antidrop.list.status.missing", display), true);
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
		AntiDropConfig config = AntiDropConfig.get();
		List<Entry> entries = config.viewOf(this.target);

		drawContext.centeredText(this.font, this.title, this.width / 2, 4, TITLE_COLOR);

		// 生效规则说明
		drawContext.text(this.font, Component.translatable(this.target.isFeature()
						? "antidrop.list.rule.feature" : "antidrop.list.rule.global",
				Component.translatable(config.modeFor(this.target) == ListMode.BLACKLIST
						? "antidrop.mode.blacklist" : "antidrop.mode.whitelist")),
				left, 62, HINT_COLOR);

		drawContext.text(this.font, Component.translatable("antidrop.list.count", entries.size()),
				left, this.listTop - 11, HINT_COLOR);
		if (entries.size() > this.visibleLines) {
			drawContext.text(this.font, Component.translatable("antidrop.list.scrollHint"),
					left + WIDGET_WIDTH - 80, this.listTop - 11, HINT_COLOR);
		}

		int maxScroll = Math.max(0, entries.size() - this.visibleLines);
		if (this.scroll > maxScroll) {
			this.scroll = maxScroll;
		}
		if (this.scroll < 0) {
			this.scroll = 0;
		}

		if (entries.isEmpty()) {
			drawContext.text(this.font, Component.translatable("antidrop.list.empty"), left, this.listTop, HINT_COLOR);
		} else {
			int end = Math.min(entries.size(), this.scroll + this.visibleLines);
			for (int i = this.scroll; i < end; i++) {
				drawContext.text(this.font, "- " + entries.get(i).display(), left,
						this.listTop + (i - this.scroll) * LINE_HEIGHT, ENTRY_COLOR);
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

	/**
	 * 便捷校验：标签是否为合法输入（供其它类复用）。
	 *
	 * @param raw 输入
	 * @return 规范化后的标签
	 */
	static String normalizeTag(String raw) {
		return ItemMatcher.normalizeTag(raw);
	}
}
