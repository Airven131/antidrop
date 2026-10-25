# AGENT.md — AntiDrop 开发方案（面向 AI / 自动化 agent）

> 这是**开发**文档：架构、数据模型、配置迁移、构建、CI、API 核实流程、排障。
> 玩家使用说明见 [README.md](README.md)。
>
> 读完本文件应能独立完成「改功能 → 编译 → 打包 → 验证」。当前版本：**AntiDrop 2.0.0 / Minecraft 26.3 / Loader 0.20.0**。

---

## 1. 版本矩阵与产物

| 项 | 值 | 备注 |
| --- | --- | --- |
| Minecraft | **26.3** | `fabric.mod.json`: `"minecraft": "~26.3"` |
| Fabric Loader | **0.20.0** | `>=0.20.0`；官方 26.3 模板仍用 0.19.5 |
| Fabric API | **0.162.0+26.3** | 最新 0.163.0+26.3 可升；客户端命令 API 需要它 |
| Loom / Gradle / Java | **1.18.4** / **9.5.1** / **25** | 模板用 `1.18-SNAPSHOT` |
| Mod Menu（软依赖） | **21.0.0** | 20.0.x=26.2，22.0.0-alpha=26.4 |
| 包名 / group | **`com.airven.antidrop`** / `com.airven` | 2.0 起从 `com.example.antidrop` 迁来 |
| 产物 | `build/libs/antidrop-2.0.0.jar` | 44.9 KB / 38 条目 |

入口：`com.airven.antidrop.AntiDropClient`（client）+ `...integration.AntiDropModMenu`（modmenu）。
Mixin 配置 `antidrop.mixins.json` 只含 `MultiPlayerGameModeMixin`（client）。

---

## 2. 项目结构

```
src/main/java/com/airven/antidrop/
├── AntiDropClient.java                // client 入口：load 配置 → 注册命令 → 反射自检注入点 → 日志
├── command/AntiDropCommand.java       // /antidrop reload|list|add [id [tag]]|remove
├── config/AntiDropConfig.java         // v2 配置模型 + 迁移 + 名单读改 + 编译缓存
├── handler/DropInterceptor.java       // 唯一判断入口（开关 + 生效名单 + 提示 + 调试日志）
├── util/
│   ├── Entry.java                     // 条目 POJO：id + 可选 tag（Gson 直接读写）
│   └── ItemMatcher.java               // ID/标签规范化、编译（按 ID 分桶）、匹配
├── mixin/MultiPlayerGameModeMixin.java// 三个 @Inject（HEAD, cancellable）
└── integration/
    ├── AntiDropModMenu.java           // ModMenuApi（软依赖，禁止在别处引用）
    ├── AntiDropConfigScreen.java      // 主界面：4 开关 + 摘要 + 入口按钮
    └── AntiDropListScreen.java        // 名单编辑器：目标/模式切换 + ID/标签输入 + 列表
```

---

## 3. 数据模型与「生效名单」

### 3.1 JSON（v2）

```json
{
  "configVersion": 2,
  "blockHotbarDrop": true, "blockInventoryHotbarDrop": true, "blockInventoryDragDrop": true,
  "debugLogging": false,
  "globalEntries": [ { "id": "minecraft:diamond_sword" } ],
  "hotbarRules":    { "mode": "WHITELIST", "entries": [] },
  "inventoryRules": { "mode": "WHITELIST", "entries": [] },
  "dragOutRules":   { "mode": "BLACKLIST", "entries": [ { "id": "minecraft:shield" } ] }
}
```

### 3.2 Java 类型

* `AntiDropConfig.Target`：`GLOBAL / HOTBAR / INVENTORY_Q / DRAG_OUT`（`GLOBAL` 不是功能，无 mode）
* `AntiDropConfig.RuleList`：`{ ListMode mode; List<Entry> entries; }`
* `AntiDropConfig.ListMode`：`WHITELIST / BLACKLIST`
* `util.Entry`：`{ String id; String tag; }`（`tag == null` = 该物品所有变体）

### 3.3 生效名单计算（唯一实现：`AntiDropConfig#isProtected`）

```java
boolean inGlobal = matcherFor(GLOBAL).matches(stack);
boolean inLocal  = matcherFor(target).matches(stack);
switch (mode) {
    case WHITELIST -> inGlobal || inLocal;   // 全局 ∪ 功能名单
    case BLACKLIST -> inGlobal && !inLocal;  // 全局 − 功能名单
}
```

`DropInterceptor.isEnabled(config, target)` 另外负责三个功能开关；`GLOBAL` 恒为 `false`。

### 3.4 条目匹配（`ItemMatcher.Compiled#matches`）

1. 先按物品注册名（`BuiltInRegistries.ITEM.getKey` → `Identifier`）分桶查找；
2. 条目 `tag == null` → 命中**所有变体**；
3. 条目有 `tag` → 还要求 `ItemStack#getCustomName().getString()` 与之 `equalsIgnoreCase`；
   未改名（`getCustomName() == null`）不命中。

> 「标签」= **堆的自定义名称**（`DataComponents.CUSTOM_NAME`），不是 `TagKey`。
> 注册表标签直配（`#food`）在 2.0 已按需求移除。

---

## 4. 配置版本与迁移

* 常量：`AntiDropConfig.CURRENT_VERSION = 2`，字段 `configVersion`。
* 加载流程：`Files.readString` → `JsonParser.parseString` → `detectVersion`（无字段 = v1）
  → 若 `< CURRENT_VERSION`：`backup()` + `migrate()` → `GSON.fromJson` → `copyFrom`（就地更新单例）
  → `sanitize()` → 迁移后立即 `save()`。
* `migrate()`（v1 → v2）：
  * `protectedItems: ["minecraft:x", "#tag"]` → `globalEntries: [{id: "minecraft:x"}]`，删除旧字段；
  * `#tag` 条目**丢弃**并 WARN（需求明确取消该能力）；
  * 无法规范化的 ID **保留原值**（迁移要尽量不丢配置；运行时 `compile` 会再校验并只排除出匹配）；
  * 断言：4 个开关原样保留。
* `backup()`：`config/antidrop.v1.bak.json`（已存在则不覆盖）。
* `sanitize()`：补默认值、mode 兜底 `WHITELIST`、清掉 `id` 为空的条目并统计 WARN。

### 4.1 headless 迁移测试（本机已验证）

迁移逻辑是纯 JSON 变换，可脱离游戏运行。做法：反射调用私有 `migrate(JsonObject)`：

```java
var migrate = AntiDropConfig.class.getDeclaredMethod("migrate", JsonObject.class);
migrate.setAccessible(true);
migrate.invoke(null, root);          // root = 解析出来的 v1 JSON
var sanitize = AntiDropConfig.class.getDeclaredMethod("sanitize");
sanitize.setAccessible(true);
sanitize.invoke(cfg);                // sanitize 也是 private
```

运行时的 classpath = 编译产物 + 26.3 依赖（见 §5.2），**测试类目录也要加进去**。
注意两个坑：

* `AntiDropConfig`/`Entry`/`ItemMatcher` 的静态初始化不碰 MC 注册表 ✔；
  但**不要**在测试里 `new ItemStack(...)` —— `ItemStack` 的静态初始化会拉 `netty`
  （`ComponentSerialization` → `StreamCodec`），headless 环境会 `NoClassDefFoundError`。
* `Identifier` 同样有静态 codec，若 classpath 缺 netty，`Identifier.tryParse` 会抛 `NoClassDefFoundError`；
  `ItemMatcher.normalizeItemId` 捕获 `Throwable` 后返回 `null`。这正是迁移里「规范化失败也要保留原值」的原因。

本机实测结果（v1 示例 → v2）：`diamond_sword / mypack:special_sword / elytra` 全部保留，
`#food`、`#minecraft:tools` 被丢弃，`configVersion` 变 2，开关保持 `false/true/false/true`；
另外 `{"configVersion":2}`（缺名单字段）能正确回落到默认全局名单 + `WHITELIST`。

---

## 5. 构建

### 5.1 正常环境

```bash
# JDK 25；仓库未提交 gradle-wrapper.jar，所以用本机 gradle 或先 `gradle wrapper --gradle-version 9.5.1`
gradle build        # 产物 build/libs/antidrop-2.0.0.jar
```

### 5.2 本机（受限网络）实测流程

`./gradlew build` 在本机**跑不完**：`maven.fabricmc.net` 只有 250 B/s ~ 20 KB/s，
`maven.terraformersmc.com` 约 33 B/s（详见 §9 的速度表）。26.x 无重映射，因此改用
**javac + jar**，产物与 Loom 等价。

```powershell
$jdk  = 'E:\Minecraft\JDK\graalvm-jdk-25.0.1+8.1\bin'
$libs = '<ws>\.tools\libs'                       # client-26.3.jar / fabric-loader-0.20.0.jar /
                                                 # fabric-api-0.162.0+26.3.jar / fapi-nested-263\*.jar
                                                 # modmenu-21.0.0.jar / sponge-mixin / 原版库
$out  = '<ws>\.tools\out263'
$jars = @(Get-ChildItem $libs -Filter *.jar).FullName +
        @(Get-ChildItem (Join-Path $libs 'fapi-nested-263') -Filter *.jar).FullName
$src  = (Get-ChildItem '<ws>\antidrop\src\main\java' -Recurse -Filter *.java).FullName
$argf = '<ws>\.tools\javac263.args'
[System.IO.File]::WriteAllLines($argf,
  (@('-encoding','UTF-8','--release','25','-cp',($jars -join ';'),'-d',$out) + $src),
  (New-Object System.Text.UTF8Encoding($false)))
Start-Process (Join-Path $jdk 'javac.exe') -ArgumentList @('-J-Duser.language=en', "@$argf") `
  -NoNewWindow -Wait -RedirectStandardOutput out.log -RedirectStandardError err.log
```

打包：把 `$out` 的 class 与 `src/main/resources` 拷进 staging，替换 `fabric.mod.json` 里的
`${version}`（**必须无 BOM**），再 `jar --create --file build/libs/antidrop-2.0.0.jar -C staging .`。

依赖来源（实测可用）：Mojang `piston-data`（client.jar，7 MB/s）、`libraries.minecraft.net`、
`cdn.modrinth.com`（Fabric API / Mod Menu）、`bmclapi2.bangbang93.com`（只镜像 loader）。
**Fabric API 必须解嵌套**：聚合 jar 的类都在 `META-INF/jars/*.jar` 里，手工编译需要展开（Loom 自动做）。

### 5.3 CI（`.github/workflows/build.yml`）

* `push`（main/master、`v*` tag）/ `pull_request` / 手动触发；
* `actions/setup-java@v4`（temurin **25**）+ `gradle/actions/setup-gradle@v4`（**9.5.1**，因为仓库没有 wrapper jar）；
* `gradle build` → `upload-artifact`（`build/libs/*.jar`）；
* 打 `v*` tag 时用 `softprops/action-gh-release@v2` 自动建 Release 并附带 jar；
* 末尾留了发布 Modrinth 的注释片段（需要 `MODRINTH_TOKEN` / `MODRINTH_PROJECT_ID` secrets）。

> 若仓库不是「项目根 = 仓库根」（例如把 `antidrop/` 整个作为子目录提交），
> 需要给 build 相关 step 加 `working-directory: antidrop`。

---

## 6. API 核实工作流（升级 MC 版本必做）

26.x 已去混淆，直接对官方 jar 用 `javap`。

```powershell
# 1) 方法是否存在 + 精确描述符（写进 mixin 的字符串必须逐字符一致）
javap -s -p -classpath client-26.3.jar net.minecraft.client.multiplayer.MultiPlayerGameMode |
  Select-String 'dropItem|handleContainerInput|handleCreativeModeItemDrop' -Context 0,1

# 2) 调用链 / 方法体（判断「HEAD 取消是否安全」：副作用必须在 HEAD 之后）
javap -c -p -classpath client-26.3.jar net.minecraft.client.multiplayer.MultiPlayerGameMode

# 3) 谁引用了某个类（扫 jar 内 class 常量池字符串）
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
foreach ($e in $zip.Entries) { if ($e.FullName -like '*.class') {
  $s=$e.Open(); $ms=New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Close()
  $t=[Text.Encoding]::ASCII.GetString($ms.ToArray()); $ms.Dispose()
  if ($t.Contains('ServerboundPlayerActionPacket')) { $e.FullName } } }
$zip.Dispose()
```

### 6.1 必须回归的探针（22 项）

`MultiPlayerGameMode.{dropItem,handleContainerInput,handleCreativeModeItemDrop}` ·
`ContainerInput.{THROW,PICKUP}` · `AbstractContainerMenu.{SLOT_CLICKED_OUTSIDE,getCarried,getSlot,containerId,slots}` ·
`Player.{sendOverlayMessage,sendSystemMessage,containerMenu}` · `Inventory.{getSelectedItem,removeFromSelected}` ·
`ItemStack.{typeHolder,isEmpty,getCustomName,get(DataComponent)}` · `DataComponents.CUSTOM_NAME` · `Component.getString` ·
`Holder.is(TagKey)` · `Screen.{extractRenderState,onClose,addRenderableWidget}` · `GuiGraphicsExtractor.{text,centeredText}` ·
`Button$Builder.{pos,size,tooltip,build}` · `EditBox.{setHint,setValue,getValue,isFocused,getY}` ·
`KeyEvent.key` · `InputConstants.KEY_RETURN` · `Minecraft.{gui,font,getInstance}` · `Gui.{screen(),setScreen}` ·
`Registry.keySet` · `Identifier.{tryParse,parse}` · `Slot.{getItem,index}` · `Commands.literal` ·
`BuiltInRegistries.ITEM` / `Registries.ITEM`
Fabric API：`ClientCommands.{literal,argument}`、`ClientCommandRegistrationCallback.EVENT`、
`FabricClientCommandSource.{sendFeedback,sendError,getPlayer}` · Mod Menu：`ModMenuApi` / `ConfigScreenFactory`

### 6.2 26.2 → 26.3 的唯一破坏性变化

| 项 | 26.2 | 26.3 |
| --- | --- | --- |
| 世界 Q 丢弃 | `LocalPlayer#drop(boolean)`（只发包） | **`MultiPlayerGameMode#dropItem(LocalPlayer, boolean)`**（方法体内先 `Inventory#removeFromSelected` 再发包） |

其余 21 项一致。`dropItem` 在 HEAD 取消仍安全（此时尚未移除物品），且比 26.2 更彻底。

---

## 7. 代码不变量

1. **单一判断入口**：所有拦截路径都走 `DropInterceptor`，mixin 里不写业务判断。
2. **fail-open**：`DropInterceptor` 每个入口 + 每个 `@Inject` 方法体都 `try/catch(Throwable)`，
   异常只 WARN 并放行。
3. **mixin 内绝不向原版抛异常**。
4. **注入失败不崩溃**：靠 `antidrop.mixins.json` 的 `injectors.defaultRequire: 0`；
   **不要**在 `@Inject` 上加 `require = 0`（编译期与运行时 Mixin 版本的注解成员可能不同）。
5. **单例就地更新**：`load()` / `reloadFromDisk()` 只覆盖字段，不替换实例（否则已打开界面持有过期对象）。
6. **翻译键全部字面量、双语齐全**（`en_us` + `zh_cn`），键名 `antidrop.<区段>.<名>`。
7. **不引入 refmap**（26.x 无混淆）。
8. **软依赖隔离**：只有 `AntiDropModMenu` 引用 Mod Menu 类，且只经 `modmenu` entrypoint 加载。
9. **配置只增不减**：新增字段必须有默认值（Gson 缺字段时保留声明处初始值）；
   结构性变更必须提升 `CURRENT_VERSION` 并写迁移，且迁移前备份。
10. **`Entry` 保持 POJO**（可变 + 无参构造），不要改 record（Gson 兼容性）。

---

## 8. 改动配方

**新增一个防丢功能（新 Target）**
1. `AntiDropConfig.Target` 加枚举值 + 翻译键；
2. `AntiDropConfig` 加 `RuleList` 字段（默认 `WHITELIST` + 空）并接入
   `rulesFor`/`entriesFor`/`copyFrom`/`sanitize`/`isProtected`；
3. `DropInterceptor.isEnabled` 加开关分支（或在配置加新开关字段）；
4. 新拦截点：`javap -s -p` 拿描述符 → `javap -c -p` 确认 HEAD 前无副作用 →
   在 `MultiPlayerGameModeMixin`（或其他目标类）加 `@Inject(HEAD, cancellable)`；
5. `AntiDropClient.verifyHookTargets()` 加反射自检；
6. `AntiDropListScreen` 无需改（它按 `Target.values()` 循环）；
7. 双语 lang 各加键；README/AGENT 更新。

**给条目加新属性**（例如数量、附魔）
`util.Entry` 加字段（默认值）→ `ItemMatcher.Compiled#matches` 使用 → `Entry.sameAs/sanitize/display` 同步 →
`AntiDropListScreen` 加输入框 → 命令解析（`splitIdAndTag` 改成通用解析）→ 迁移保持旧数据可读（缺字段 = 默认）。

**新增命令子命令**
`AntiDropCommand.register()` 里 `.then(ClientCommands.literal("xxx")...)`；反馈用
`source.sendFeedback/sendError`，成功返回 ≥1、失败返回 0。

**升级 MC 版本**
改 `gradle.properties`（mc/loader/loom/fabric-api/modmenu）+ `fabric.mod.json` → 重新拉依赖 →
§6 全量探针 → 编译修错 → 迁移/打包/校验 → 更新 README 版本表与 AGENT §1/§6。

---

## 9. 排障速查

| 症状 | 原因 / 处理 |
| --- | --- |
| `pwsh` 退出码 `3221225794`、零输出 | `workspace-write` 沙箱下 PowerShell 起不来；用 full-access 策略 |
| javac `非法字符: '\ufeff'` | 源文件带了 UTF-8 BOM；用 `UTF8Encoding($false)` 写文件 |
| javac `无效的标记: -J-D...` | `-J` 不能写进 `@argfile`，只能放命令行 |
| javac `invalid flag: >` | `.bat` + 超长 classpath；改 `@argfile`（classpath > 40 个 jar 时必然遇到） |
| 日志被当成二进制 | `*>` 重定向写出 UTF-16；用 `Out-File -Encoding utf8` 或 `Start-Process -Redirect*` |
| PowerShell 解析报 `Missing closing ')'` | 表达式里用了分号（`(cmd; $LASTEXITCODE)`）；先赋值再判断 |
| `java.lang.ClassNotFoundException`（自测程序） | 运行时 classpath 漏了测试类目录或编译输出目录 |
| `NoClassDefFoundError: io/netty/...`（自测程序） | `ItemStack`/`Identifier` 静态初始化需要 netty；headless 测试别碰 ItemStack |
| `fabric.mod.json` 被判非法 | 写文件带 BOM |
| 启动日志 `hook target MISSING` | 目标方法改名/移动；按 §6 重新定位并更新 descriptor |
| mixin 静默不生效 | `defaultRequire: 0` 吞掉注入失败；看 WARN + 开 `debugLogging` |
| 命令提示未知命令 | 没装 Fabric API（客户端命令 API 在 fabric-api 内） |
| CI 找不到 `gradlew` | 仓库无 wrapper jar；workflow 用 `gradle/actions/setup-gradle` 提供 Gradle（已是这样） |

---

## 10. 仍需真机验证（agent 无法代办）

1. 三处拦截在 26.3 实际生效（世界 Q / 容器 Q / 拖出，含创造模式）。
2. 容器内按 Q 是否也走 `dropItem`（双路径；`debugLogging` 可看）。
3. 拖出界面时 `slotId == -999` 且 `input == PICKUP`。
4. 主界面与名单编辑界面的渲染/交互（26.3 GUI 重构后的实际表现）。
5. 全局 + 功能名单的黑/白名单组合是否符合预期（README §3.1 的两个例子）。
6. 带标签条目：改名物品被保护、未改名同 ID 物品不被保护。
7. `/antidrop` 各子命令与 Tab 补全；`/antidrop add <id> <tag>` 的空格标签解析。
8. 真实 v1 配置文件升级后：开关保持、普通条目保留、`#tag` 被丢弃并 WARN、备份文件生成。
9. 未装 Mod Menu 时无 `NoClassDefFoundError`。
