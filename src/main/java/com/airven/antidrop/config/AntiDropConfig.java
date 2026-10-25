package com.airven.antidrop.config;

import com.airven.antidrop.AntiDropClient;
import com.airven.antidrop.util.Entry;
import com.airven.antidrop.util.ItemMatcher;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * AntiDrop 配置（v2）。
 *
 * <p><b>名单模型</b>：
 * <ul>
 *     <li>{@link #globalEntries} —— 全局白名单（旧版直接叫 {@code protectedItems}，功能不变）；</li>
 *     <li>三个防丢功能各自一份 {@link RuleList}：{@link #hotbarRules}（世界 Q）、
 *         {@link #inventoryRules}（容器 Q）、{@link #dragOutRules}（拖出界面，含创造模式）。</li>
 * </ul>
 *
 * <p><b>黑/白名单语义</b>（需求原文）：
 * <ul>
 *     <li>{@link ListMode#WHITELIST}：生效名单 = 全局 ∪ 该功能名单（在全局基础上新增）；</li>
 *     <li>{@link ListMode#BLACKLIST}：生效名单 = 全局 − 该功能名单（把全局里的条目排除掉）。</li>
 * </ul>
 * 默认全部是 {@code WHITELIST} 且名单为空 ⇒ 生效名单就是全局名单，与旧版行为一致。
 *
 * <p><b>条目匹配</b>：见 {@link Entry} / {@link ItemMatcher}——物品 ID + 可选标签（堆的自定义名称）。
 *
 * <p><b>配置迁移</b>：文件里的 {@code configVersion} 低于 {@link #CURRENT_VERSION} 时会自动迁移
 * （v1 的 {@code protectedItems: ["minecraft:x", "#tag"]} → v2 的 {@code globalEntries: [{id: ...}]}），
 * 迁移前会把原文件备份成 {@code antidrop.v1.bak.json}；旧版 {@code #tag}（注册表标签直配）
 * 已不支持，会被丢弃并打 WARN。
 *
 * <p><b>单例稳定性</b>：{@link #get()} 的实例在进程内保持不变；{@link #reloadFromDisk()} 是就地覆盖字段，
 * 因此界面/命令持有的引用不会失效。
 */
public class AntiDropConfig {

	/** 配置文件名。 */
	public static final String FILE_NAME = "antidrop.json";

	/** 当前配置结构版本。 */
	public static final int CURRENT_VERSION = 2;

	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private static AntiDropConfig instance;

	// ------------------------------------------------------------------
	// 持久化字段（顺序即 JSON 中的顺序）
	// ------------------------------------------------------------------

	/** 配置结构版本，用于升级时迁移。 */
	public int configVersion = CURRENT_VERSION;

	/** 是否阻止常规游戏界面（无容器界面）按 Q 丢弃手持物品。 */
	public boolean blockHotbarDrop = true;

	/** 是否阻止容器界面（背包/箱子等）内按 Q 丢弃物品。 */
	public boolean blockInventoryHotbarDrop = true;

	/** 是否阻止用鼠标把物品拖出容器界面丢弃（含创造模式）。 */
	public boolean blockInventoryDragDrop = true;

	/** 是否输出调试日志。 */
	public boolean debugLogging = false;

	/** 全局白名单。 */
	public List<Entry> globalEntries = new ArrayList<>(defaultGlobal());

	/** 世界 Q 的功能名单。 */
	public RuleList hotbarRules = new RuleList();

	/** 容器界面 Q 的功能名单。 */
	public RuleList inventoryRules = new RuleList();

	/** 拖出界面（含创造模式）的功能名单。 */
	public RuleList dragOutRules = new RuleList();

	/** 编译缓存，{@code transient} 不写盘。 */
	private transient Map<Target, ItemMatcher.Compiled> cache;

	// ------------------------------------------------------------------
	// 嵌套类型
	// ------------------------------------------------------------------

	/** 名单模式。 */
	public enum ListMode {
		/** 在全局名单基础上新增这些条目。 */
		WHITELIST,
		/** 把全局名单里的这些条目排除掉。 */
		BLACKLIST
	}

	/** 名单目标（全局 + 三个防丢功能）。 */
	public enum Target {
		/** 全局白名单。 */
		GLOBAL("antidrop.target.global"),
		/** 世界 Q 丢弃。 */
		HOTBAR("antidrop.target.hotbar"),
		/** 容器界面 Q 丢弃。 */
		INVENTORY_Q("antidrop.target.inventoryQ"),
		/** 拖出界面丢弃（含创造模式）。 */
		DRAG_OUT("antidrop.target.dragOut");

		private final String translationKey;

		Target(String translationKey) {
			this.translationKey = translationKey;
		}

		/** @return 翻译键 */
		public String translationKey() {
			return this.translationKey;
		}

		/** @return 是否为「全局」以外的功能名单 */
		public boolean isFeature() {
			return this != GLOBAL;
		}
	}

	/** 单个防丢功能的名单：模式 + 条目。 */
	public static class RuleList {

		/** 名单模式，默认白名单（= 在全局基础上新增）。 */
		public ListMode mode = ListMode.WHITELIST;

		/** 条目。 */
		public List<Entry> entries = new ArrayList<>();

		/** Gson 需要无参构造。 */
		public RuleList() {
		}

		/**
		 * @param mode    模式
		 * @param entries 条目
		 */
		public RuleList(ListMode mode, List<Entry> entries) {
			this.mode = mode;
			this.entries = entries;
		}
	}

	/** {@link #addEntry} 的结果。 */
	public enum AddResult {
		/** 已添加。 */
		ADDED,
		/** 已存在（幂等）。 */
		DUPLICATE,
		/** 无法解析。 */
		INVALID
	}

	// ------------------------------------------------------------------
	// 单例与 IO
	// ------------------------------------------------------------------

	/**
	 * 获取全局配置实例（懒加载）。
	 *
	 * @return 配置实例
	 */
	public static AntiDropConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	/**
	 * 从磁盘加载配置；必要时先迁移旧版本。已存在的实例会被**就地更新**。
	 *
	 * @return 配置实例（始终是同一个单例）
	 */
	public static synchronized AntiDropConfig load() {
		Path path = configPath();
		boolean missing = !Files.exists(path);
		Loaded loaded = missing ? null : readAndMigrate(path);

		if (instance == null) {
			instance = (loaded != null) ? loaded.config : new AntiDropConfig();
		} else if (loaded != null) {
			instance.copyFrom(loaded.config);
		} else if (!missing) {
			AntiDropClient.LOGGER.warn("[AntiDrop] keeping the in-memory configuration ({} unreadable)", path);
		}

		instance.sanitize();
		instance.invalidateCaches();

		if (missing || (loaded != null && loaded.migrated)) {
			instance.save();
		}
		return instance;
	}

	/**
	 * 就地重新读取磁盘配置（{@code /antidrop reload}）。
	 *
	 * @return 全局名单条目数
	 */
	public int reloadFromDisk() {
		load();
		return get().globalEntries.size();
	}

	/** 读写结果。 */
	private record Loaded(AntiDropConfig config, boolean migrated) {
	}

	/**
	 * 读取文件并按需迁移。
	 *
	 * @param path 配置路径
	 * @return 读取结果；不可读时返回 {@code null}
	 */
	private static Loaded readAndMigrate(Path path) {
		try {
			String text = Files.readString(path, StandardCharsets.UTF_8);
			JsonElement parsed = JsonParser.parseString(text);
			if (!parsed.isJsonObject()) {
				AntiDropClient.LOGGER.warn("[AntiDrop] {} is not a JSON object; using defaults", path);
				return null;
			}
			JsonObject root = parsed.getAsJsonObject();
			int version = detectVersion(root);
			boolean migrated = false;
			if (version < CURRENT_VERSION) {
				backup(path, version);
				migrate(root);
				migrated = true;
			}
			AntiDropConfig config = GSON.fromJson(root, AntiDropConfig.class);
			return (config == null) ? null : new Loaded(config, migrated);
		} catch (JsonParseException e) {
			AntiDropClient.LOGGER.warn("[AntiDrop] {} is not valid JSON; using defaults", path, e);
		} catch (IOException e) {
			AntiDropClient.LOGGER.warn("[AntiDrop] failed to read {}; using defaults", path, e);
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] unexpected error while reading {}; using defaults", path, t);
		}
		return null;
	}

	/**
	 * 探测配置结构版本（没有该字段 = v1）。
	 *
	 * @param root JSON 根
	 * @return 版本号
	 */
	private static int detectVersion(JsonObject root) {
		if (root.has("configVersion")) {
			JsonElement element = root.get("configVersion");
			if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
				try {
					return element.getAsInt();
				} catch (Throwable ignored) {
					// fall through
				}
			}
		}
		return 1;
	}

	/**
	 * 迁移到当前版本（目前只有 v1 → v2）。
	 *
	 * @param root JSON 根（就地修改）
	 */
	private static void migrate(JsonObject root) {
		JsonArray old = null;
		if (root.has("protectedItems") && root.get("protectedItems").isJsonArray()) {
			old = root.getAsJsonArray("protectedItems");
		}

		JsonArray global = new JsonArray();
		List<String> dropped = new ArrayList<>();
		if (old != null) {
			for (JsonElement element : old) {
				if (!element.isJsonPrimitive()) {
					continue;
				}
				String raw = element.getAsString().trim();
				if (raw.isEmpty()) {
					continue;
				}
				if (raw.startsWith("#")) {
					// 旧版「注册表物品标签直配」已按需求移除
					dropped.add(raw);
					continue;
				}
				String id = ItemMatcher.normalizeItemId(raw);
				if (id == null) {
					// 无法规范化时**保留原值**（需求 3：迁移要尽量保留玩家配置）。
					// 运行时 ItemMatcher.compile 会再校验一次，非法条目只会被排除出匹配并打 WARN。
					id = raw;
				}
				JsonObject entry = new JsonObject();
				entry.addProperty("id", id);
				global.add(entry);
			}
		}

		root.remove("protectedItems");
		root.add("globalEntries", global);
		root.addProperty("configVersion", CURRENT_VERSION);

		AntiDropClient.LOGGER.info("[AntiDrop] migrated configuration to v{}; kept {} global entr{}",
				CURRENT_VERSION, global.size(), global.size() == 1 ? "y" : "ies");
		if (!dropped.isEmpty()) {
			AntiDropClient.LOGGER.warn("[AntiDrop] dropped {} legacy tag entr{} (registry-tag matching is no longer supported): {}",
					dropped.size(), dropped.size() == 1 ? "y" : "ies", dropped);
		}
	}

	/**
	 * 迁移前备份旧文件。
	 *
	 * @param path        原文件
	 * @param fromVersion 旧版本号
	 */
	private static void backup(Path path, int fromVersion) {
		try {
			Path backup = path.resolveSibling("antidrop.v" + fromVersion + ".bak.json");
			if (!Files.exists(backup)) {
				Files.copy(path, backup);
				AntiDropClient.LOGGER.info("[AntiDrop] backed up the old configuration to {}", backup.getFileName());
			}
		} catch (IOException e) {
			AntiDropClient.LOGGER.warn("[AntiDrop] failed to back up {}", path, e);
		}
	}

	/**
	 * 把另一份配置的字段深拷贝到本实例（保持单例引用不变）。
	 *
	 * @param other 来源
	 */
	private void copyFrom(AntiDropConfig other) {
		this.blockHotbarDrop = other.blockHotbarDrop;
		this.blockInventoryHotbarDrop = other.blockInventoryHotbarDrop;
		this.blockInventoryDragDrop = other.blockInventoryDragDrop;
		this.debugLogging = other.debugLogging;
		this.globalEntries = copyEntries(other.globalEntries, defaultGlobal());
		this.hotbarRules = copyRules(other.hotbarRules);
		this.inventoryRules = copyRules(other.inventoryRules);
		this.dragOutRules = copyRules(other.dragOutRules);
	}

	private static List<Entry> copyEntries(List<Entry> source, List<Entry> fallback) {
		if (source == null) {
			return fallback;
		}
		List<Entry> copy = new ArrayList<>(source.size());
		for (Entry entry : source) {
			if (entry == null) {
				continue;
			}
			copy.add(new Entry(entry.id, entry.tag));
		}
		return copy;
	}

	private static RuleList copyRules(RuleList source) {
		if (source == null) {
			return new RuleList();
		}
		return new RuleList(source.mode == null ? ListMode.WHITELIST : source.mode,
				copyEntries(source.entries, new ArrayList<>()));
	}

	/** 兜底修正缺失/非法字段。 */
	private void sanitize() {
		if (this.configVersion != CURRENT_VERSION) {
			this.configVersion = CURRENT_VERSION;
		}
		if (this.globalEntries == null) {
			this.globalEntries = defaultGlobal();
		}
		this.hotbarRules = normalizeRules(this.hotbarRules);
		this.inventoryRules = normalizeRules(this.inventoryRules);
		this.dragOutRules = normalizeRules(this.dragOutRules);
		int dropped = countInvalid(this.globalEntries)
				+ countInvalid(this.hotbarRules.entries)
				+ countInvalid(this.inventoryRules.entries)
				+ countInvalid(this.dragOutRules.entries);
		if (dropped > 0) {
			AntiDropClient.LOGGER.warn("[AntiDrop] dropped {} malformed list entr{} while loading",
					dropped, dropped == 1 ? "y" : "ies");
		}
	}

	private static RuleList normalizeRules(RuleList rules) {
		if (rules == null) {
			return new RuleList();
		}
		if (rules.mode == null) {
			rules.mode = ListMode.WHITELIST;
		}
		if (rules.entries == null) {
			rules.entries = new ArrayList<>();
		}
		return rules;
	}

	private static int countInvalid(List<Entry> entries) {
		if (entries == null) {
			return 0;
		}
		int before = entries.size();
		entries.removeIf(entry -> entry == null || !entry.sanitize());
		return before - entries.size();
	}

	/**
	 * 写回磁盘。
	 */
	public synchronized void save() {
		Path path = configPath();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
			invalidateCaches();
		} catch (IOException e) {
			AntiDropClient.LOGGER.warn("[AntiDrop] failed to write {}", path, e);
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] unexpected error while writing {}", path, t);
		}
	}

	// ------------------------------------------------------------------
	// 名单访问
	// ------------------------------------------------------------------

	/**
	 * 取指定目标的条目列表（可直接修改后调用 {@link #save()}）。
	 *
	 * @param target 目标
	 * @return 条目列表
	 */
	public List<Entry> entriesFor(Target target) {
		return (target == Target.GLOBAL) ? this.globalEntries : rulesFor(target).entries;
	}

	/**
	 * 取指定目标的只读条目视图。
	 *
	 * @param target 目标
	 * @return 只读列表
	 */
	public List<Entry> viewOf(Target target) {
		return Collections.unmodifiableList(entriesFor(target));
	}

	/**
	 * 取指定功能的名单（全局返回 {@code null}）。
	 *
	 * @param target 目标
	 * @return 名单或 {@code null}
	 */
	public RuleList rulesFor(Target target) {
		return switch (target) {
			case GLOBAL -> null;
			case HOTBAR -> this.hotbarRules;
			case INVENTORY_Q -> this.inventoryRules;
			case DRAG_OUT -> this.dragOutRules;
		};
	}

	/**
	 * 取指定目标的模式（全局返回 {@code null}）。
	 *
	 * @param target 目标
	 * @return 模式或 {@code null}
	 */
	public ListMode modeFor(Target target) {
		RuleList rules = rulesFor(target);
		return (rules == null) ? null : rules.mode;
	}

	/**
	 * 设置指定功能的模式。
	 *
	 * @param target 目标（不可为全局）
	 * @param mode   模式
	 */
	public void setMode(Target target, ListMode mode) {
		RuleList rules = rulesFor(target);
		if (rules != null && mode != null) {
			rules.mode = mode;
			save();
		}
	}

	/**
	 * 添加条目。
	 *
	 * @param target 目标
	 * @param itemId 物品 ID（可省略命名空间）
	 * @param tag    可选标签（堆的自定义名称）
	 * @return 结果
	 */
	public synchronized AddResult addEntry(Target target, String itemId, String tag) {
		String id = ItemMatcher.normalizeItemId(itemId);
		if (id == null) {
			return AddResult.INVALID;
		}
		String normalizedTag = ItemMatcher.normalizeTag(tag);
		List<Entry> list = entriesFor(target);
		for (Entry existing : list) {
			if (existing.sameAs(id, normalizedTag)) {
				return AddResult.DUPLICATE;
			}
		}
		list.add(new Entry(id, normalizedTag));
		save();
		return AddResult.ADDED;
	}

	/**
	 * 删除条目。
	 *
	 * @param target 目标
	 * @param itemId 物品 ID
	 * @param tag    可选标签
	 * @return 是否删除成功
	 */
	public synchronized boolean removeEntry(Target target, String itemId, String tag) {
		String id = ItemMatcher.normalizeItemId(itemId);
		if (id == null) {
			return false;
		}
		String normalizedTag = ItemMatcher.normalizeTag(tag);
		boolean removed = entriesFor(target).removeIf(entry -> entry.sameAs(id, normalizedTag));
		if (removed) {
			save();
		}
		return removed;
	}

	/**
	 * 取编译后的名单（带缓存）。
	 *
	 * @param target 目标
	 * @return 编译结果
	 */
	public ItemMatcher.Compiled matcherFor(Target target) {
		if (this.cache == null) {
			this.cache = new EnumMap<>(Target.class);
		}
		return this.cache.computeIfAbsent(target, key -> ItemMatcher.compile(entriesFor(key)));
	}

	/**
	 * 判断某物品在指定功能下是否受保护（应用黑/白名单规则）。
	 *
	 * @param target 功能目标（{@code GLOBAL} 表示只看全局名单）
	 * @param stack  物品堆
	 * @return 是否受保护
	 */
	public boolean isProtected(Target target, net.minecraft.world.item.ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		boolean inGlobal = matcherFor(Target.GLOBAL).matches(stack);
		if (target == Target.GLOBAL) {
			return inGlobal;
		}
		RuleList rules = rulesFor(target);
		boolean inLocal = matcherFor(target).matches(stack);
		return switch (rules.mode) {
			case WHITELIST -> inGlobal || inLocal;
			case BLACKLIST -> inGlobal && !inLocal;
		};
	}

	/** 清空编译缓存。 */
	public void invalidateCaches() {
		if (this.cache != null) {
			this.cache.clear();
		}
	}

	/**
	 * 原始条目数（用于界面显示）。
	 *
	 * @param target 目标
	 * @return 条目数
	 */
	public int countFor(Target target) {
		return entriesFor(target).size();
	}

	/**
	 * 默认全局名单。
	 *
	 * @return 新的可变列表
	 */
	private static List<Entry> defaultGlobal() {
		List<Entry> entries = new ArrayList<>();
		entries.add(new Entry("minecraft:diamond_sword", null));
		entries.add(new Entry("minecraft:netherite_sword", null));
		return entries;
	}

	/**
	 * 配置文件路径。
	 *
	 * @return {@code config/antidrop.json}
	 */
	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}
}
