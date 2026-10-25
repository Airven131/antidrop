package com.airven.antidrop.util;

/**
 * 名单中的一条受保护条目：**物品 ID + 可选标签**。
 *
 * <p>匹配语义（对应需求「默认仅添加无标签物品ID并匹配所有标签，仅在有标签时匹配该标签」）：
 * <ul>
 *     <li>{@code tag == null}：匹配该物品的**所有**变体（无论有没有被改名）；</li>
 *     <li>{@code tag != null}：只匹配「物品 ID 相同**且**自定义名称等于该标签」的堆，
 *         例如只保护被改名成 {@code MySword} 的那把剑。</li>
 * </ul>
 *
 * <p>这里的「标签」是**堆级的自定义名称**（{@code DataComponents.CUSTOM_NAME}），
 * 不是注册表物品标签（{@code TagKey}）。旧版直接写 {@code #food} 的标签直配方式已移除。
 *
 * <p>本类是可变的普通 POJO，便于 Gson 直接读写；不要改成 record（Gson 对 record 的兼容性随版本变化）。
 */
public final class Entry {

	/** 物品注册名，形如 {@code minecraft:diamond_sword}。 */
	public String id;

	/** 可选标签（堆的自定义名称）；{@code null} 表示匹配该物品的所有变体。 */
	public String tag;

	/** Gson 需要无参构造。 */
	public Entry() {
	}

	/**
	 * @param id  物品注册名
	 * @param tag 可选标签，{@code null} 表示不限
	 */
	public Entry(String id, String tag) {
		this.id = id;
		this.tag = tag;
	}

	/**
	 * 人类可读形式，用于界面与命令输出。
	 *
	 * @return {@code minecraft:x} 或 {@code minecraft:x @tag}
	 */
	public String display() {
		return (tag == null) ? id : id + " @" + tag;
	}

	/**
	 * 判断是否与给定 id/tag 是同一条目（tag 用忽略大小写比较）。
	 *
	 * @param otherId  物品注册名
	 * @param otherTag 标签，{@code null} 表示无标签
	 * @return 是否相同
	 */
	public boolean sameAs(String otherId, String otherTag) {
		if (this.id == null || !this.id.equalsIgnoreCase(otherId)) {
			return false;
		}
		if (this.tag == null) {
			return otherTag == null;
		}
		return otherTag != null && this.tag.equalsIgnoreCase(otherTag);
	}

	/**
	 * 清理字段：id 为空的条目应被丢弃，tag 空串归一为 {@code null}。
	 *
	 * @return 是否仍是有效条目
	 */
	public boolean sanitize() {
		if (this.id == null || this.id.isBlank()) {
			return false;
		}
		this.id = this.id.trim();
		if (this.tag != null) {
			this.tag = this.tag.trim();
			if (this.tag.isEmpty()) {
				this.tag = null;
			}
		}
		return true;
	}

	@Override
	public String toString() {
		return display();
	}
}
