package com.airven.antidrop.util;

import com.airven.antidrop.AntiDropClient;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 名单条目的解析、编译与匹配。
 *
 * <p><b>匹配规则</b>（与需求一致）：
 * <ol>
 *     <li>先按物品注册名匹配（{@code BuiltInRegistries.ITEM.getKey(item)} → {@link Identifier}）；</li>
 *     <li>条目没有标签 → 命中该物品的任意变体；</li>
 *     <li>条目有标签 → 还要求堆的**自定义名称**（26.3 的 {@code ItemStack#getCustomName()}）与之相等
 *         （忽略大小写）。未改名的堆不会命中带标签条目。</li>
 * </ol>
 *
 * <p>旧的「注册表物品标签」直配（{@code #food}、{@code #minecraft:tools} 这类 {@code TagKey} 匹配）
 * 已按需求移除；配置迁移时这类条目会被丢弃并记录 WARN（见 {@code AntiDropConfig}）。
 */
public final class ItemMatcher {

	private ItemMatcher() {
	}

	/**
	 * 取物品的注册名。
	 *
	 * @param stack 物品堆
	 * @return 形如 {@code minecraft:diamond_sword}；空气或无法解析时返回 {@code null}
	 */
	public static String itemIdOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id == null ? null : id.toString();
	}

	/**
	 * 取堆的「标签」= 自定义名称（{@code DataComponents.CUSTOM_NAME}）。
	 *
	 * @param stack 物品堆
	 * @return 去空白后的名称；未改名或名称为空时返回 {@code null}
	 */
	public static String tagOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		Component customName = stack.getCustomName();
		if (customName == null) {
			return null;
		}
		String text = customName.getString();
		if (text == null || text.isBlank()) {
			return null;
		}
		return text.trim();
	}

	/**
	 * 规范化玩家输入的物品 ID。
	 *
	 * @param raw 输入，如 {@code minecraft:diamond_sword} 或 {@code diamond_sword}
	 * @return 规范形式（{@code namespace:path}），无法解析时返回 {@code null}
	 */
	public static String normalizeItemId(String raw) {
		if (raw == null) {
			return null;
		}
		String text = raw.trim();
		if (text.isEmpty() || text.startsWith("#")) {
			// "#xxx" 是旧版的标签直配写法，已不再支持
			return null;
		}
		try {
			Identifier id = Identifier.tryParse(text);
			return id == null ? null : id.toString();
		} catch (Throwable t) {
			AntiDropClient.LOGGER.warn("[AntiDrop] failed to parse item id '{}'", raw, t);
			return null;
		}
	}

	/**
	 * 规范化标签输入。
	 *
	 * @param raw 原始标签（可为 {@code null}）
	 * @return 去空白后的标签；空串/空白返回 {@code null}（表示「不限标签」）
	 */
	public static String normalizeTag(String raw) {
		if (raw == null) {
			return null;
		}
		String text = raw.trim();
		if (text.isEmpty()) {
			return null;
		}
		return (text.length() > 128) ? text.substring(0, 128) : text;
	}

	/**
	 * 编译后的名单：按物品 ID 分桶，避免每次丢弃都做线性扫描。
	 */
	public static final class Compiled {

		/** 空名单。 */
		public static final Compiled EMPTY = new Compiled(Map.of(), List.of());

		private final Map<String, List<Entry>> byId;
		private final List<String> dropped;

		private Compiled(Map<String, List<Entry>> byId, List<String> dropped) {
			this.byId = byId;
			this.dropped = dropped;
		}

		/**
		 * 判断物品堆是否命中本名单。
		 *
		 * @param stack 物品堆
		 * @return 命中返回 {@code true}
		 */
		public boolean matches(ItemStack stack) {
			String id = itemIdOf(stack);
			if (id == null) {
				return false;
			}
			List<Entry> bucket = this.byId.get(id);
			if (bucket == null) {
				return false;
			}
			String stackTag = tagOf(stack);
			for (Entry entry : bucket) {
				if (entry.tag == null) {
					// 无标签条目：匹配该物品的所有变体
					return true;
				}
				if (stackTag != null && stackTag.equalsIgnoreCase(entry.tag)) {
					return true;
				}
			}
			return false;
		}

		/** @return 名单是否为空 */
		public boolean isEmpty() {
			return this.byId.isEmpty();
		}

		/** @return 被丢弃的无效条目（用于日志/统计） */
		public List<String> dropped() {
			return this.dropped;
		}
	}

	/**
	 * 把条目列表编译为 {@link Compiled}。
	 *
	 * @param entries 条目列表，允许为 {@code null}
	 * @return 编译结果，永不为 {@code null}
	 */
	public static Compiled compile(List<Entry> entries) {
		if (entries == null || entries.isEmpty()) {
			return Compiled.EMPTY;
		}
		Map<String, List<Entry>> byId = new LinkedHashMap<>();
		List<String> dropped = new ArrayList<>();

		for (Entry entry : entries) {
			if (entry == null || !entry.sanitize()) {
				continue;
			}
			String id = normalizeItemId(entry.id);
			if (id == null) {
				dropped.add(String.valueOf(entry.id));
				continue;
			}
			entry.id = id;
			byId.computeIfAbsent(id, key -> new ArrayList<>()).add(entry);
		}

		if (!dropped.isEmpty()) {
			AntiDropClient.LOGGER.warn("[AntiDrop] ignored {} invalid list entr{}: {}",
					dropped.size(), dropped.size() == 1 ? "y" : "ies", dropped);
		}
		Map<String, List<Entry>> frozen = new HashMap<>(byId.size());
		byId.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
		return new Compiled(Map.copyOf(frozen), List.copyOf(dropped));
	}
}
