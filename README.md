# AntiDrop

纯客户端 Fabric mod：防止手滑把贵重物品丢出去。支持**全局名单 + 每个防丢功能各自的黑/白名单**，
名单条目可以是「物品 ID」或「物品 ID + 标签（改名后的物品）」，拦截时用 actionbar 提示。

| 项 | 版本 |
| --- | --- |
| Minecraft | **26.3** |
| Fabric Loader | **0.20.0+** |
| Fabric API | **0.162.0+26.3** |
| Mod Menu（可选） | **21.0.0**（只用于提供配置界面入口） |
| 运行环境 | **仅客户端**（服务器不需要安装） |

---

## 1. 安装

1. 装好 **Fabric Loader 0.20.0+** 与对应 **Fabric API（26.3）**；
2. 把 `antidrop-2.x.y.jar` 放进 `.minecraft/mods/`；
3. （可选）装 **Mod Menu 21.0.0**，之后可在「Mods → AntiDrop → 配置」里改设置；
   不装也能正常用，只是改名单要用命令或手动编辑配置文件。

---

## 2. 会被拦截的三类误丢弃

| 场景 | 对应开关 | 默认 |
| --- | --- | --- |
| 常规游戏界面按 **Q**（丢手持物） | 世界 Q 丢弃 | 开 |
| 容器界面（背包/箱子…）按 **Q** | 容器 Q 丢弃 | 开 |
| 用鼠标把物品**拖出**容器界面（含创造模式） | 拖出丢弃 | 开 |

三个开关互相独立。被拦截时 actionbar 会提示「该物品受保护，已阻止丢弃」（最多每 0.2 秒一次）。

> 说明：容器界面按 Q 丢弃的是**鼠标悬停槽位**里的物品（原版行为）；把物品拖到界面外松手则是丢弃**光标上**的那一堆。

---

## 3. 名单体系

### 3.1 全局名单 + 三个功能名单

* **全局名单（Global）**：最基础的白名单，默认保护 `minecraft:diamond_sword` 与 `minecraft:netherite_sword`。
  命令默认只改它。
* **每个防丢功能各有一份名单**（世界 Q / 容器 Q / 拖出），每份都有一个**模式**：

| 模式 | 生效名单 | 用途 |
| --- | --- | --- |
| **白名单**（默认） | 全局名单 **∪** 本名单 | 在全局保护之外，让本功能多保护几样东西 |
| **黑名单** | 全局名单 **−** 本名单 | 让本功能**不**保护全局名单里的某些物品 |

举例：

* 全局保护钻石剑；把「世界 Q」设为**黑名单**并加入 `minecraft:diamond_sword`
  ⇒ 世界里按 Q 可以丢钻石剑，但背包内按 Q、拖出界面仍然保护它。
* 全局只保护剑；把「容器 Q」设为**白名单**并加入 `minecraft:shield`
  ⇒ 背包里按 Q 时盾也受保护，而世界里按 Q / 拖出界面不保护盾。

三个功能名单的默认值是「白名单 + 空」，所以**默认生效名单就是全局名单**（与旧版行为一致）。

### 3.2 条目格式：物品 ID + 可选标签

一条条目 = 物品 ID +（可选的）标签：

| 写法 | 含义 |
| --- | --- |
| `minecraft:diamond_sword` | 保护该物品的**所有变体**（无论有没有改名） |
| `diamond_sword` | 同上，省略命名空间时默认 `minecraft:` |
| `minecraft:diamond_sword` + 标签 `Excalibur` | **只**保护改名为 `Excalibur` 的那把剑；没改名的同 ID 剑不受保护 |

* **标签 = 物品的自定义名称**（铁砧改名后的名字，显示文本、忽略格式与大小写）。
  留空表示「不限标签」。
* 物品被重新改名后标签会变，需要同步修改名单里的标签。
* **不再支持** 旧版直接写 `#food`、`#minecraft:tools` 这类**注册表物品标签**（2.0 起移除）。

---

## 4. 在游戏里编辑名单（Mod Menu）

`Mods → AntiDrop → 配置`：

* 4 个开关：世界 Q 丢弃 / 容器 Q 丢弃 / 拖出丢弃 / 调试日志；
* **编辑受保护名单…** 打开名单编辑器：
  * 顶部「目标」按钮循环切换：**全局名单 → 世界 Q → 容器 Q → 拖出**；
  * 第二个按钮切换该功能的**模式**（白名单/黑名单，全局名单没有模式）；
  * 输入「物品 ID」和可选的「标签」，点 **添加** / **删除**（标签输入框留空 = 所有变体）；
  * 列表可用**鼠标滚轮**翻看，底部状态行显示操作结果；ID 输入框内按 **回车** 也能添加；
* **添加手持物品**：把当前主手物品（无标签）加入**全局名单**；
* **从文件重载**：等同于 `/antidrop reload`。

> 命令只操作全局名单；功能名单的详细规则请在这里编辑。

---

## 5. 命令

都是**客户端命令**（服务器无需安装本 mod），补全会列出物品注册名。

| 命令 | 说明 |
| --- | --- |
| `/antidrop reload` | 重新读取配置文件（含旧版本自动迁移），无需重启游戏 |
| `/antidrop list` | 列出全局名单，以及三个功能的模式与条目数 |
| `/antidrop add` | 把当前**主手物品**（无标签）加入全局名单 |
| `/antidrop add <物品ID> [标签]` | 加入全局名单；第二个空格之后的内容都算标签 |
| `/antidrop remove <物品ID> [标签]` | 从全局名单删除 |

示例：

```
/antidrop add                                  # 主手物品，无标签
/antidrop add minecraft:elytra                 # 指定 ID，保护所有鞘翅
/antidrop add minecraft:diamond_sword Excalibur  # 只保护改名为 Excalibur 的钻石剑
/antidrop remove minecraft:elytra
```

---

## 6. 配置文件

位置：`.minecraft/config/antidrop.json`（首次启动自动生成）。手动编辑后执行 `/antidrop reload` 生效。

```json
{
  "configVersion": 2,
  "blockHotbarDrop": true,
  "blockInventoryHotbarDrop": true,
  "blockInventoryDragDrop": true,
  "debugLogging": false,
  "globalEntries": [
    { "id": "minecraft:diamond_sword" },
    { "id": "minecraft:netherite_sword" }
  ],
  "hotbarRules": {
    "mode": "WHITELIST",
    "entries": []
  },
  "inventoryRules": {
    "mode": "WHITELIST",
    "entries": [ { "id": "minecraft:shield" } ]
  },
  "dragOutRules": {
    "mode": "BLACKLIST",
    "entries": [ { "id": "minecraft:diamond_sword" } ]
  }
}
```

* `mode`：`WHITELIST` 或 `BLACKLIST`；
* 条目的 `tag` 字段可以省略（= 所有变体），例如 `{ "id": "minecraft:diamond_sword", "tag": "Excalibur" }`；
* JSON 损坏时 mod 会回退默认值并打 WARN，不会崩溃。

### 从 1.x 升级

首次启动会自动迁移，并把旧文件备份为 **`config/antidrop.v1.bak.json`**：

* 4 个开关、以及所有普通物品 ID 条目**原样保留**；
* 旧的 `#food` / `#minecraft:tools`（注册表标签直配）**已被移除**，迁移时会被丢弃并在日志中 WARN
  —— 请改成具体物品 ID，或用「物品 ID + 改名标签」的方式。

---

## 7. 常见问题

**Q：保护没生效？**
依次检查：① 对应功能的开关是否打开；② 该功能的名单模式（黑名单会不会把它排除了）；
③ 物品 ID 是否写对（可用 `/antidrop list` 看全局名单）；④ 打开「调试日志」后看 `logs/latest.log`，
每次拦截/放行都会打印 `[AntiDrop] debug: ...`，含目标、模式、物品。

**Q：为什么 `#food` 不生效了？**
2.0 起移除了「注册表物品标签」直配，改为「物品 ID + 可选标签（改名）」。请列出具体物品 ID。

**Q：想只保护某一把特定的剑？**
铁砧把剑改名为 `Excalibur`，然后在名单里加「`minecraft:diamond_sword` + 标签 `Excalibur`」。

**Q：多人服务器能用吗？**
能。纯客户端，不向服务端发任何额外包，也不影响别人。

**Q：改了配置文件没反应？**
执行 `/antidrop reload`（或配置界面里的「从文件重载」）。

---

## 8. 已知限制

1. 纯客户端：只保护**你自己**主动丢弃的操作；漏斗、其他玩家、服务端插件不受影响。
2. 只拦截「丢弃」这个行为本身，因此手柄的丢弃键同样会被拦截；但第三方 mod 若用完全不同的途径移除物品，不在覆盖范围内。
3. 标签依赖**物品的自定义名称**：改名后需要同步更新名单；未改名物品不会命中带标签的条目。
4. 不支持注册表物品标签（`#xxx`）匹配。
5. 创造模式的「拖出界面」走独立代码路径，已单独覆盖。
