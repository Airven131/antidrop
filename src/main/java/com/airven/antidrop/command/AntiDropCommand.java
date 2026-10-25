package com.airven.antidrop.command;

import com.airven.antidrop.AntiDropClient;
import com.airven.antidrop.config.AntiDropConfig;
import com.airven.antidrop.config.AntiDropConfig.AddResult;
import com.airven.antidrop.config.AntiDropConfig.ListMode;
import com.airven.antidrop.config.AntiDropConfig.Target;
import com.airven.antidrop.util.Entry;
import com.airven.antidrop.util.ItemMatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /antidrop} 客户端命令。
 *
 * <p>按需求，命令**只操作全局名单**（{@code globalEntries}）；三个防丢功能各自的黑/白名单
 * 属于「详细规则」，在 Mod Menu 配置界面里编辑。
 *
 * <ul>
 *     <li>{@code /antidrop reload} —— 重读 {@code config/antidrop.json}（含版本迁移），无需重启；</li>
 *     <li>{@code /antidrop list} —— 打印全局名单与三个功能的名单模式/条目数；</li>
 *     <li>{@code /antidrop add} —— 把当前主手物品（无标签）加入全局名单；</li>
 *     <li>{@code /antidrop add <物品ID> [标签]} —— 指定 ID；第二个参数起是可选标签
 *         （= 堆的自定义名称，用于只保护某个改名后的物品）；</li>
 *     <li>{@code /antidrop remove <物品ID> [标签]} —— 从全局名单删除。</li>
 * </ul>
 *
 * <p>参数用 {@code greedyString}，因此 {@code minecraft:xxx} 无需引号；若给了标签，
 * 需写成 {@code /antidrop add minecraft:diamond_sword My Sword}（空格后的全部内容为标签）。
 */
public final class AntiDropCommand {

	/** 命令根名。 */
	public static final String ROOT = "antidrop";

	private static final String ARG = "item";

	private AntiDropCommand() {
	}

	/**
	 * 注册客户端命令。应在 {@code onInitializeClient} 中调用一次。
	 *
	 * <p>整体包 try/catch：命令注册失败不应影响核心防丢功能。
	 */
	public static void register() {
		try {
			ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
					dispatcher.register(ClientCommands.literal(ROOT)
							.then(ClientCommands.literal("reload")
									.executes(ctx -> reload(ctx.getSource())))
							.then(ClientCommands.literal("list")
									.executes(ctx -> list(ctx.getSource())))
							.then(ClientCommands.literal("add")
									// 缺省参数：把当前主手物品（无标签）加入全局名单
									.executes(ctx -> addHeldItem(ctx.getSource()))
									.then(ClientCommands.argument(ARG, StringArgumentType.greedyString())
											.suggests(AntiDropCommand::suggestEntries)
											.executes(ctx -> addGlobal(ctx.getSource(),
													StringArgumentType.getString(ctx, ARG)))))
							.then(ClientCommands.literal("remove")
									.then(ClientCommands.argument(ARG, StringArgumentType.greedyString())
											.suggests(AntiDropCommand::suggestEntries)
											.executes(ctx -> removeGlobal(ctx.getSource(),
													StringArgumentType.getString(ctx, ARG)))))
					));
			AntiDropClient.LOGGER.info("[AntiDrop] registered client command: /{} reload|list|add [id [tag]]|remove <id [tag]>", ROOT);
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] failed to register the /{} client command", ROOT, t);
		}
	}

	/**
	 * 把「ID [标签]」形式的参数拆成两部分。
	 *
	 * @param raw 原始参数
	 * @return {@code [id, tag]}，tag 可能为 {@code null}
	 */
	private static String[] splitIdAndTag(String raw) {
		String text = (raw == null) ? "" : raw.trim();
		if (text.isEmpty()) {
			return new String[]{null, null};
		}
		int space = text.indexOf(' ');
		if (space < 0) {
			return new String[]{text, null};
		}
		String id = text.substring(0, space).trim();
		String tag = text.substring(space + 1).trim();
		return new String[]{id.isEmpty() ? null : id, tag.isEmpty() ? null : tag};
	}

	/**
	 * {@code /antidrop reload}
	 *
	 * @param source 来源
	 * @return 全局条目数
	 */
	private static int reload(FabricClientCommandSource source) {
		try {
			int size = AntiDropConfig.get().reloadFromDisk();
			source.sendFeedback(Component.translatable("antidrop.command.reload.success", size));
			return size;
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] /{} reload failed", ROOT, t);
			source.sendError(Component.translatable("antidrop.command.reload.failed"));
			return 0;
		}
	}

	/**
	 * {@code /antidrop list} —— 全局名单 + 三个功能的模式与条目数。
	 *
	 * @param source 来源
	 * @return 全局条目数
	 */
	private static int list(FabricClientCommandSource source) {
		try {
			AntiDropConfig config = AntiDropConfig.get();
			List<Entry> global = config.viewOf(Target.GLOBAL);
			source.sendFeedback(Component.translatable("antidrop.command.list.header", global.size()));
			if (global.isEmpty()) {
				source.sendFeedback(Component.translatable("antidrop.command.list.empty"));
			} else {
				for (Entry entry : global) {
					source.sendFeedback(Component.literal(" - " + entry.display()));
				}
			}
			// 三个功能的规则摘要
			for (Target target : new Target[]{Target.HOTBAR, Target.INVENTORY_Q, Target.DRAG_OUT}) {
				ListMode mode = config.modeFor(target);
				source.sendFeedback(Component.translatable("antidrop.command.list.feature",
						Component.translatable(target.translationKey()),
						Component.translatable(mode == ListMode.BLACKLIST
								? "antidrop.mode.blacklist" : "antidrop.mode.whitelist"),
						config.countFor(target)));
			}
			return global.size();
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] /{} list failed", ROOT, t);
			source.sendError(Component.translatable("antidrop.command.reload.failed"));
			return 0;
		}
	}

	/**
	 * {@code /antidrop add}（缺省参数）—— 当前主手物品，无标签。
	 *
	 * @param source 来源
	 * @return 成功 1
	 */
	private static int addHeldItem(FabricClientCommandSource source) {
		try {
			LocalPlayer player = source.getPlayer();
			ItemStack held = (player == null) ? ItemStack.EMPTY : player.getInventory().getSelectedItem();
			String id = ItemMatcher.itemIdOf(held);
			if (id == null) {
				source.sendError(Component.translatable("antidrop.command.add.noitem"));
				return 0;
			}
			// 默认只添加无标签物品 ID（匹配该物品的所有变体）
			return addGlobal(source, id, true);
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] /{} add (held) failed", ROOT, t);
			source.sendError(Component.translatable("antidrop.command.add.invalid", "?"));
			return 0;
		}
	}

	/**
	 * {@code /antidrop add <id> [tag]}
	 *
	 * @param source 来源
	 * @param raw    参数
	 * @return 成功 1
	 */
	private static int addGlobal(FabricClientCommandSource source, String raw) {
		return addGlobal(source, raw, false);
	}

	private static int addGlobal(FabricClientCommandSource source, String raw, boolean held) {
		try {
			String[] parts = splitIdAndTag(raw);
			if (parts[0] == null) {
				source.sendError(Component.translatable("antidrop.command.add.invalid", String.valueOf(raw)));
				return 0;
			}
			AddResult result = AntiDropConfig.get().addEntry(Target.GLOBAL, parts[0], parts[1]);
			switch (result) {
				case ADDED -> source.sendFeedback(Component.translatable(
						held ? "antidrop.command.add.held" : "antidrop.command.add.success",
						parts[1] == null ? parts[0] : parts[0] + " @" + parts[1]));
				case DUPLICATE -> source.sendFeedback(Component.translatable("antidrop.command.add.duplicate",
						parts[1] == null ? parts[0] : parts[0] + " @" + parts[1]));
				case INVALID -> {
					source.sendError(Component.translatable("antidrop.command.add.invalid", parts[0]));
					return 0;
				}
			}
			return 1;
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] /{} add '{}' failed", ROOT, raw, t);
			source.sendError(Component.translatable("antidrop.command.add.invalid", String.valueOf(raw)));
			return 0;
		}
	}

	/**
	 * {@code /antidrop remove <id> [tag]}
	 *
	 * @param source 来源
	 * @param raw    参数
	 * @return 成功 1
	 */
	private static int removeGlobal(FabricClientCommandSource source, String raw) {
		try {
			String[] parts = splitIdAndTag(raw);
			if (parts[0] == null) {
				source.sendError(Component.translatable("antidrop.command.remove.missing", String.valueOf(raw)));
				return 0;
			}
			String display = (parts[1] == null) ? parts[0] : parts[0] + " @" + parts[1];
			if (AntiDropConfig.get().removeEntry(Target.GLOBAL, parts[0], parts[1])) {
				source.sendFeedback(Component.translatable("antidrop.command.remove.success", display));
				return 1;
			}
			source.sendError(Component.translatable("antidrop.command.remove.missing", display));
			return 0;
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] /{} remove '{}' failed", ROOT, raw, t);
			source.sendError(Component.translatable("antidrop.command.remove.missing", String.valueOf(raw)));
			return 0;
		}
	}

	/**
	 * 参数补全：全局名单条目 + 全部物品注册名。
	 *
	 * @param context 上下文
	 * @param builder 构造器
	 * @return 补全结果
	 */
	private static CompletableFuture<Suggestions> suggestEntries(CommandContext<FabricClientCommandSource> context,
																SuggestionsBuilder builder) {
		try {
			for (Entry entry : AntiDropConfig.get().viewOf(Target.GLOBAL)) {
				builder.suggest(entry.display());
			}
			String remaining = builder.getRemainingLowerCase();
			for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
				String text = id.toString();
				if (text.startsWith(remaining)) {
					builder.suggest(text);
				}
			}
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] failed to build /{} suggestions", ROOT, t);
		}
		return builder.buildFuture();
	}
}
