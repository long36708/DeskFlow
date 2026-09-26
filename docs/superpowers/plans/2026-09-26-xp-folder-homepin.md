# XP插件文件夹 + 钉选首页应用 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 一键整理新增「XP插件」类别（成员由 category_mapping.json 配置），并把 7 个钉选应用强制固定到首页（所有文件夹在前，钉选按序随后）。

**Architecture:** 全部改动集中在分类/整理域层：`Category` 加 `XP` 枚举值；新增 `PinnedApps` 包名清单；`AutoArranger.arrange()` 在建槽前选出钉选项并排除出分类分组，槽位重排为「文件夹 → 钉选 → 其余」；从既有文件夹/Dock 强制抽出钉选项，抽空的夹（剩 <2 成员）记录进 `ArrangeResult.dissolvedFolders`，由 `apply()` 标记删除并兜底安置孤儿成员。

**Tech Stack:** Kotlin + JUnit4（JVM 单测，`build_test.bat` 已处理 JDK 21）。注意：单测类路径没有 `org.json`，`CategoryMappingLoader.parse` 不做 JVM 测试，分类规则测试一律用 `DefaultRules.rules.merge(CategoryRules(...))` 构造。

**规格文档：** `docs/superpowers/specs/2026-09-26-xp-folder-homepin-design.md`

**运行测试：** `cmd //c "build_test.bat"`（脚本内部已修好 JDK 21 取用逻辑；在仓库根目录执行，输出末尾应见 BUILD SUCCESSFUL）。

**提交：** 每个 Task 末尾提交一次。仓库直接在 main 上工作（本项目无远端、无 PR 流程）。

---

### Task 1: Category.XP 类别 + 映射表预置规则

**Files:**
- Modify: `app/src/main/java/com/deskflow/domain/classifier/Category.kt:4-15`
- Modify: `app/src/main/assets/category_mapping.json`
- Test: `app/src/test/java/com/deskflow/domain/ClassifierTest.kt`

- [ ] **Step 1: 写失败测试**

在 `ClassifierTest.kt` 的 `// ---------- classification ----------` 小节末尾（`customRulesOverrideDefault` 测试之后）追加：

```kotlin
    @Test
    fun xpCategoryOrderAndLabels() {
        assertEquals("XP插件", Category.XP.label)
        assertEquals(Category.XP, Category.byLabel("XP插件"))
        assertEquals(Category.XP, Category.byLabel("XP插件 2"))
        assertEquals(Category.OTHER, Category.ordered.last())
        assertTrue(Category.ordered.indexOf(Category.XP) < Category.ordered.indexOf(Category.OTHER))
    }

    @Test
    fun xpRulesFromMappingTable() {
        val rules = DefaultRules.rules.merge(
            CategoryRules(
                exactPackages = mapOf("org.lsposed.manager" to Category.XP),
                keywords = mapOf("xposed" to Category.XP),
                titleKeywords = mapOf("模块" to Category.XP),
            )
        )
        assertEquals(Category.XP, AppClassifier.classify(app(1, "org.lsposed.manager", "LSPosed"), rules))
        assertEquals(Category.XP, AppClassifier.classify(app(2, "com.tsng.riru.test", "测试"), rules))
        assertEquals(Category.XP, AppClassifier.classify(app(3, "com.example.app", "某模块"), rules))
        // 命中 xp 的应用不改变其它类别判定
        assertEquals(Category.SOCIAL, AppClassifier.classify(app(4, "com.tencent.mm", "微信"), rules))
    }

    @Test
    fun xpModulesGetTheirOwnFolder() {
        val modules = (1L..4L).map { app(it, "com.example.xposed$it", "模块$it") }
        val ws = workspace(listOf(1), *modules.toTypedArray())
        val rules = DefaultRules.rules.merge(CategoryRules(keywords = mapOf("xposed" to Category.XP)))
        val result = AutoArranger.arrange(ws, rules)

        // ≥4 才收夹，与其它类别同规则
        assertEquals(1, result.newFolderCount)
        val xp = result.pages[0].placements.single()
        assertTrue(xp.isNewFolder)
        assertEquals("XP插件", xp.item.title)
        assertEquals(4, xp.members.size)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "build_test.bat"`
Expected: FAIL，`xpCategoryOrderAndLabels` 报 `XP` 未解析引用（编译错误即视为失败）。

- [ ] **Step 3: 实现 Category.XP**

修改 `Category.kt` 枚举声明（`OTHER` 的 order 从 10 改为 11）：

```kotlin
enum class Category(val id: String, val label: String, val order: Int) {
    SYSTEM("system", "系统", 0),
    SOCIAL("social", "社交", 1),
    MEDIA("media", "影音", 2),
    READING("reading", "资讯阅读", 3),
    SHOPPING("shopping", "购物生活", 4),
    FINANCE("finance", "金融支付", 5),
    WORK("work", "办公效率", 6),
    GAME("game", "游戏", 7),
    PHOTO("photo", "拍摄美化", 8),
    TOOLS("tools", "实用工具", 9),
    XP("xp", "XP插件", 10),
    OTHER("other", "未分类", 11),
    ;
```

`byId` / `byLabel` / `ordered` 无需改动（均遍历 entries）。`CategoryMappingLoader` 无需改动（`Category.byId("xp")` 自动生效）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd //c "build_test.bat"`
Expected: PASS（原有 50 个用例 + 新 3 个。没有任何内置规则把应用分到 XP，现有用例里 `Category.ordered` 的相对顺序未变——XP 插在 TOOLS 与 OTHER 之间）。

- [ ] **Step 5: 预置映射表规则**

用以下内容整体替换 `app/src/main/assets/category_mapping.json`（保留原三条示例，追加 xp 条目；用户后续直接改这个文件增删 XP 模块成员，无需重编译）：

```json
{
  "exactPackages": {
    "com.tencent.mm": "social",
    "com.eg.android.AlipayGphone": "finance",
    "com.ss.android.ugc.aweme": "media",
    "org.lsposed.manager": "xp",
    "de.robv.android.xposed.installer": "xp"
  },
  "keywords": {
    "vshop": "shopping",
    "tmgp": "game",
    "xposed": "xp",
    "lsposed": "xp",
    "edxposed": "xp",
    "zygisk": "xp",
    "riru": "xp"
  },
  "titleKeywords": {
    "搞机": "tools",
    "模块": "xp"
  }
}
```

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/deskflow/domain/classifier/Category.kt app/src/main/assets/category_mapping.json app/src/test/java/com/deskflow/domain/ClassifierTest.kt
git commit -m "feat: 新增 XP插件 类别并在映射表预置 Xposed 模块规则"
```

---

### Task 2: PinnedApps 清单 + 钉选槽位（夹在前、钉选按序随后）

**Files:**
- Create: `app/src/main/java/com/deskflow/domain/classifier/PinnedApps.kt`
- Modify: `app/src/main/java/com/deskflow/domain/classifier/AutoArranger.kt`
- Test: `app/src/test/java/com/deskflow/domain/ClassifierTest.kt`

背景：`AutoArranger.arrange()` 当前流程是「桌面项按类别分组 → `slotsOf` 建槽 → `layoutOf` 顺序铺页」，见 AutoArranger.kt:82-119。本任务把钉选项从分组池里剔除、单独立槽，并把槽位顺序改为「文件夹槽（新建+既有）→ 钉选 → 其余」。「从文件夹/Dock 抽出 + 解散」在 Task 3，本任务钉选项先只来自桌面与 Dock（按包名去重取首个）。

- [ ] **Step 1: 写失败测试**

在 `ClassifierTest.kt` 的 `// ---------- folder grouping ----------` 小节末尾追加：

```kotlin
    @Test
    fun pinnedAppsPlacedAfterFoldersInGivenOrder() {
        val socials = (1L..4L).map { app(it, "com.tencent.qqmusic", "音乐$it") }
        val mt = app(10L, "bin.mt.plus", "MT管理器")
        val qq = app(11L, "com.tencent.mobileqq", "QQ")
        val wechat = app(12L, "com.tencent.mm", "微信")
        val coolapk = app(13L, "com.coolapk.market", "酷安")
        val ws = workspace(listOf(1), *(socials + mt + qq + wechat + coolapk).toTypedArray())
        val result = AutoArranger.arrange(ws)

        // 4 个影音收 1 夹占首格，随后钉选应用按 PinnedApps 顺序铺
        assertEquals(1, result.newFolderCount)
        assertEquals(listOf("MT管理器", "QQ", "微信", "酷安"), result.pinned.map { it.title })
        val placements = result.pages[0].placements
        assertEquals(5, placements.size)
        assertTrue(placements[0].isNewFolder)
        assertEquals(listOf(10L, 11L, 12L, 13L), placements.drop(1).map { it.item.dbId })
        assertEquals(listOf(1 to 0, 2 to 0, 3 to 0, 0 to 1), placements.drop(1).map { it.cellX to it.cellY })
        // 钉选项不参与分类分组
        assertTrue(result.groups.none { (_, items) -> items.any { it.dbId in 10L..13L } })
    }

    @Test
    fun missingPinnedAppsAreSkipped() {
        val wechat = app(1L, "com.tencent.mm", "微信")
        val ws = workspace(listOf(1), wechat)
        val result = AutoArranger.arrange(ws)

        assertEquals(listOf("微信"), result.pinned.map { it.title })
        assertEquals(0, result.newFolderCount)
        val pl = result.pages[0].placements.single()
        assertFalse(pl.isNewFolder)
        assertEquals(1L, pl.item.dbId)
    }

    @Test
    fun duplicatePackagePinsOnlyFirst() {
        val a = app(1L, "bin.mt.plus", "MT1")
        val b = app(2L, "bin.mt.plus", "MT2")
        val ws = workspace(listOf(1), a, b)
        val result = AutoArranger.arrange(ws)

        assertEquals(listOf(1L), result.pinned.map { it.dbId })
        val otherPl = result.pages[0].placements.first { it.item.dbId == 2L }
        assertFalse(otherPl.isNewFolder)
    }
```

- [ ] **Step 2: 同步修正受影响的既有测试（钉选语义下保持原意）**

以下测试原本用 `com.tencent.mm` / 会受钉选影响，改用非钉选包名，断言全部保持不变：

1. `arrangeFillsPagesInCategoryOrder`：`val socials = (1L..30L).map { app(it, "com.tencent.qqmusic", "音乐$it") }`（注释里的「社交」改为「影音」）。
2. `sameCategoryAppsAreCollectedIntoFolder`：`val socials = (1L..6L).map { app(it, "com.tencent.qqmusic", "音乐$it") }`。
3. `applyWritesPositionsAndCountsMoves`：两行改为 `app(1, "com.tencent.qqmusic", "微信", x = 3, y = 5)`、`app(2, "com.tencent.qqmusic", "微信2", x = 1, y = 0)`。
4. `resultGroupsAllItemsByCategory`：`app(1, "com.tencent.qqmusic", "微信")`，期望顺序改为 `assertEquals(listOf(Category.MEDIA, Category.GAME, Category.OTHER), order)`。
5. `folderIsMovedAsAWhole`：`val child = app(10, "com.tencent.qqmusic", "微信", container = 9L)`。
6. `arrangeKeepsDockUntouched`：`val dockItem = app(50, "com.tencent.qqmusic", "音乐dock", container = CONTAINER_DOCK, screen = 0, x = 2, y = 0)`（桌面上的 `com.tencent.mm` 保留——它现在会被钉选到首页，`placedCount == 1` 与「Dock 不动」断言依然成立）。

不改：`arrangeSkipsReadOnlyWidgetCells`（1 钉选 + 24 社交 → 3 夹，所有断言仍成立）、`emptyPagesProduceEmptyPlacements`（钉选项分类仍是 SOCIAL，summary 不变）、`arrangementHasNoOverlapAndStaysInBounds`、`existingFolderIsFiledUnderItsOwnCategory`、`uncategorizedAndSmallGroupsStayLoose`、`arrangeReservesCellsOfUnplacedItems`、`overflowingCategoryGetsNumberedFolders`、`arrangeIgnoresPagesMarkedDeleted`。

- [ ] **Step 3: 跑测试确认失败**

Run: `cmd //c "build_test.bat"`
Expected: FAIL，新 3 个用例编译错误（`result.pinned` 不存在）。

- [ ] **Step 4: 实现**

新建 `app/src/main/java/com/deskflow/domain/classifier/PinnedApps.kt`：

```kotlin
package com.deskflow.domain.classifier

/**
 * 一键整理时固定到首页（第一张未删除页）的应用，按此顺序铺放；包名精确匹配，
 * 缺失即跳过、重复包名只取第一个。钉选项不参与分类分组与收夹，
 * 且会从既有文件夹 / Dock 中强制抽出（见 AutoArranger）。
 */
object PinnedApps {
    val packages = listOf(
        "bin.mt.plus",                 // MT管理器
        "com.tencent.mobileqq",        // QQ
        "com.tencent.mm",              // 微信
        "com.coolapk.market",          // 酷安
        "com.autonavi.minimap",        // 高德地图
        "cc.rikka.rikkahub",           // RikkaHub
        "com.eg.android.AlipayGphone", // 支付宝
    )
}
```

修改 `AutoArranger.kt`：

(a) `ArrangeResult` 增加字段（AutoArranger.kt:46-49）：

```kotlin
data class ArrangeResult(
    val pages: List<ArrangedPage>,
    val unplaced: List<ItemState>,
    val groups: List<Pair<Category, List<ItemState>>>,
    /** 钉选到首页的应用，按 PinnedApps 顺序，只含实际存在的。 */
    val pinned: List<ItemState> = emptyList(),
) {
```

(b) `arrange()` 开头（AutoArranger.kt:82-86）改为：

```kotlin
    fun arrange(ws: Workspace, rules: CategoryRules = DefaultRules.rules): ArrangeResult {
        val grid = ws.grid
        // 已标记删除（尚未写库）的页不能参与排布，否则图标会被塞进用户看不见的页
        val screenIds = ws.pages.filter { !it.isDeleted }.map { it.screenId }

        val pinned = pinnedItems(ws)
        val pinnedSet = pinned.toHashSet()
        val desktopItems = ws.liveItems.filter { it.container == CONTAINER_DESKTOP && it !in pinnedSet }
```

(c) 槽位构建（AutoArranger.kt:105）改为：

```kotlin
        val slots = groups.flatMap { (cat, items) -> slotsOf(cat, items, grid.folderCapacity) }
        val (folderSlots, looseSlots) =
            slots.partition { it.folderTitle.isNotEmpty() || it.members.single().isFolder }
        val ordered = folderSlots + pinnedSlots(pinned, rules) + looseSlots
```

其后两处 `slots` 形参传 `ordered`（`layoutOf(screenIds, grid, fixed, blocked, ordered)`，含 while 收敛循环里那处），返回值改为：

```kotlin
        return ArrangeResult(laid.pages, laid.unplaced, groups, pinned)
```

(d) 在 `// ---------- slot building ----------` 小节（`slotsOf` 之后）新增两个私有函数：

```kotlin
    /** 钉选槽：按 PinnedApps 顺序的单项槽位，仍带各自分类（仅用于预览概览着色）。 */
    private fun pinnedSlots(pinned: List<ItemState>, rules: CategoryRules): List<Slot> =
        pinned.map { Slot(categoryOf(it, rules), listOf(it), "") }

    /** 全 workspace 按包名找钉选项：跳过只读项与文件夹，重复包名只取第一个。 */
    private fun pinnedItems(ws: Workspace): List<ItemState> {
        val found = HashMap<String, ItemState>()
        ws.liveItems.forEach { item ->
            if (item.readOnly || item.isFolder) return@forEach
            val pkg = item.componentPackage()?.lowercase()?.trim() ?: return@forEach
            if (pkg !in found && pkg in PinnedApps.packages) found[pkg] = item
        }
        return PinnedApps.packages.mapNotNull { found[it] }
    }
```

同时把 `AutoArranger` 的 KDoc（AutoArranger.kt:75-79）末尾补一行：

```kotlin
 * 钉选应用（[PinnedApps]）不参与分类与收夹，排在所有文件夹之后按固定顺序铺上首页。
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cmd //c "build_test.bat"`
Expected: PASS（53 + 新 3 = 56）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/deskflow/domain/classifier/PinnedApps.kt app/src/main/java/com/deskflow/domain/classifier/AutoArranger.kt app/src/test/java/com/deskflow/domain/ClassifierTest.kt
git commit -m "feat: 一键整理把钉选应用排在所有文件夹之后固定到首页"
```

---

### Task 3: 强制抽出（文件夹/Dock）+ 抽空自动解散

**Files:**
- Modify: `app/src/main/java/com/deskflow/domain/classifier/AutoArranger.kt`
- Test: `app/src/test/java/com/deskflow/domain/ClassifierTest.kt`

背景：钉选项若在既有文件夹里（`container == 文件夹dbId`）或 Dock（`container == -101`），当前实现只把它钉上首页但夹/Dock 里的壳不动。本任务：抽出后若夹内剩 <2 个成员则解散夹（夹行标记删除，剩余成员回桌面参与重排）；`apply()` 负责落地，并对因页满而未排布的解散夹成员做兜底安置。抽出的钉选项 `originalContainer != CONTAINER_DESKTOP`，`movedCount` 自动计入，无需特判。

- [ ] **Step 1: 写失败测试**

在 `ClassifierTest.kt` 的 `// ---------- folder grouping ----------` 小节末尾追加：

```kotlin
    @Test
    fun pinnedAppsExtractedFromFolderAndEmptyFolderDissolves() {
        val folder = app(9L, "", "社交", itemCode = ItemType.FOLDER)
        val qq = app(10L, "com.tencent.mobileqq", "QQ", container = 9L, x = 0, y = 0)
        val wechat = app(11L, "com.tencent.mm", "微信", container = 9L, x = 1, y = 0)
        val other = app(12L, "com.unknown.pkg", "未知", x = 2, y = 0)
        val ws = workspace(listOf(1), folder, qq, wechat, other)
        val result = AutoArranger.arrange(ws)

        // 夹里全是钉选应用：抽空后剩 0 个成员，夹解散；抽出的成员计为移位
        assertEquals(listOf(9L), result.dissolvedFolders.map { it.dbId })
        assertEquals(listOf("QQ", "微信"), result.pinned.map { it.title })
        assertEquals(2, result.movedCount)

        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertTrue(folder.isDeleted)
        assertEquals(CONTAINER_DESKTOP, qq.container)
        assertEquals(CONTAINER_DESKTOP, wechat.container)
    }

    @Test
    fun folderWithEnoughRemainingMembersSurvivesExtraction() {
        val folder = app(9L, "", "社交", itemCode = ItemType.FOLDER)
        val qq = app(10L, "com.tencent.mobileqq", "QQ", container = 9L, x = 0, y = 0)
        val others = (20L..23L).map {
            app(it, "com.tencent.qqmusic", "音乐$it", container = 9L, x = ((it - 20) % 3).toInt(), y = ((it - 20) / 3).toInt())
        }
        val ws = workspace(listOf(1), folder, qq, *others.toTypedArray())
        val result = AutoArranger.arrange(ws)

        // 抽走 QQ 后夹里还剩 4 个，夹保留并整体挪位，剩余成员留在夹内
        assertTrue(result.dissolvedFolders.isEmpty())
        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertFalse(folder.isDeleted)
        assertEquals(CONTAINER_DESKTOP, qq.container)
        others.forEach { assertEquals(9L, it.container) }
    }

    @Test
    fun pinnedAppIsExtractedFromDock() {
        val qq = app(1L, "com.tencent.mobileqq", "QQ", container = CONTAINER_DOCK, screen = 0, x = 2, y = 0)
        val ws = workspace(listOf(1), qq)
        val result = AutoArranger.arrange(ws)

        assertEquals(listOf("QQ"), result.pinned.map { it.title })
        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertEquals(CONTAINER_DESKTOP, qq.container)
        assertEquals(0 to 0, qq.cellX to qq.cellY)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "build_test.bat"`
Expected: FAIL，`result.dissolvedFolders` 编译错误。

- [ ] **Step 3: 实现**

修改 `AutoArranger.kt`：

(a) `ArrangeResult` 再加一个字段（带缺省值，不影响其它构造点）：

```kotlin
    /** 因钉选抽出而剩余成员 <2、将被解散的既有文件夹。 */
    val dissolvedFolders: List<ItemState> = emptyList(),
```

(b) `arrange()` 在 `val pinned = pinnedItems(ws)` 之后、`val desktopItems = ...` 之前插入解散判定；`desktopItems` 池改为「桌面项 − 钉选 − 待解散夹 + 解散夹的剩余成员」。Task 2 后该函数开头至 `desktopItems` 一段最终为：

```kotlin
        val pinned = pinnedItems(ws)
        val pinnedSet = pinned.toHashSet()
        val dissolved = ArrayList<ItemState>()
        val rescued = ArrayList<ItemState>()
        pinned.forEach { p ->
            val parent = ws.items.firstOrNull { it.dbId == p.container && it.isFolder } ?: return@forEach
            if (dissolved.any { it.dbId == parent.dbId }) return@forEach
            val remaining = ws.childrenOf(parent).filter { it !in pinnedSet }
            if (remaining.size < 2) {
                dissolved += parent
                rescued += remaining
            }
        }
        val dissolvedIds = dissolved.map { it.dbId }.toHashSet()
        val desktopItems = ws.liveItems.filter {
            it.container == CONTAINER_DESKTOP && it !in pinnedSet && it.dbId !in dissolvedIds
        } + rescued
```

返回值改为：

```kotlin
        return ArrangeResult(laid.pages, laid.unplaced, groups, pinned, dissolved)
```

(c) `apply()`（AutoArranger.kt:121-135）整体替换为：

```kotlin
    /** 把排布结果写回 workspace：先解散被抽空的夹，单项改坐标，文件夹槽位经编辑器真正建行并把成员收进去。 */
    fun apply(ws: Workspace, result: ArrangeResult, editor: WorkspaceEditor) {
        result.dissolvedFolders.forEach { it.isDeleted = true }
        result.pages.forEach { page ->
            page.placements.forEach { pl ->
                if (pl.isNewFolder) {
                    editor.createFolderAt(pl.cellX, pl.cellY, CONTAINER_DESKTOP, pl.screen, pl.members, pl.item.title)
                } else {
                    pl.item.container = CONTAINER_DESKTOP
                    pl.item.screen = pl.screen
                    pl.item.cellX = pl.cellX
                    pl.item.cellY = pl.cellY
                }
            }
        }
        // 解散夹的成员若因页满没进排布计划，兜底挪回桌面；实在无处安放则与手删夹行为一致地删除
        val dissolvedIds = result.dissolvedFolders.map { it.dbId }.toHashSet()
        ws.liveItems.filter { it.container in dissolvedIds }.forEach { child ->
            val home = result.dissolvedFolders.first { it.dbId == child.container }.screen
            if (!editor.placeFirstFree(child, CONTAINER_DESKTOP, home)) child.isDeleted = true
        }
    }
```

注意：不要复用 `editor.delete()`——它会把子项 `placeFirstFree` 到指定页，与排布计划冲突；这里只需要标记删除 + 兜底安置，机制与撤销快照（`copyState`）兼容。

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd //c "build_test.bat"`
Expected: PASS（59 个用例：56 + 新 3）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/deskflow/domain/classifier/AutoArranger.kt app/src/test/java/com/deskflow/domain/ClassifierTest.kt
git commit -m "feat: 钉选应用强制抽出文件夹与Dock，抽空夹自动解散"
```

---

### Task 4: 全量验证 + dev-log 记录

**Files:**
- Modify: `docs/dev-log.md`

- [ ] **Step 1: 全量单测**

Run: `cmd //c "build_test.bat"`
Expected: BUILD SUCCESSFUL，59 个用例全绿。

- [ ] **Step 2: 打 debug 包**

Run: `cmd //c "build_debug.bat"`
Expected: `app-debug.apk` 生成，BUILD SUCCESSFUL。

- [ ] **Step 3: 更新 dev-log**

在 `docs/dev-log.md` 末尾「下一步」小节之前追加：

```markdown
## 7. XP插件文件夹 + 钉选首页应用（2026-09-26）

- `Category` 新增 `XP("xp", "XP插件", 10)`，OTHER 顺延为 11（仍排最后）；成员规则全部走
  `assets/category_mapping.json`（id `"xp"`，已预置 xposed/lsposed/edxposed/zygisk/riru 关键词、
  LSPosed/Xposed Installer 包名、标题「模块」），改 JSON 即可增删成员，无需重编译
- `PinnedApps`：7 个钉选包名（MT管理器/QQ/微信/酷安/高德/RikkaHub/支付宝），精确匹配、缺失跳过、
  重复包名取首个；钉选项不参与分类与收夹（RikkaHub 命中 XP 规则也上首页）
- `AutoArranger`：槽位顺序改为「所有文件夹（新建+既有）→ 钉选 → 其余」，首页天然形成夹在前、
  钉选随后；钉选项从既有文件夹/Dock 强制抽出，抽空后剩 <2 成员的夹解散（`apply` 标记删除 +
  页满成员兜底 `placeFirstFree`，无处安放才删除，与手删夹行为一致）
- 单测 59 个全绿（新增 9：XP 类别/标签认领/映射规则/≥4 收夹、钉选顺序/缺失跳过/去重、抽出解散/夹保留/Dock 抽出；
  6 个既有用例换用非钉选包名保持原意）

### 待真机验证

1. 一键整理后首页是否为「夹 → 7 钉选 → 其它」；RikkaHub 包名 `cc.rikka.rikkahub` 是否与实机一致
   （不一致时该应用被静默跳过，改 `PinnedApps.packages` 即可）
2. XP 模块（按 JSON 规则命中）是否正确收进「XP插件」夹
```

同时把原文档「下一步」小节中已被本轮覆盖的条目保留不动（收夹配额等仍在）。

- [ ] **Step 4: 提交**

```bash
git add docs/dev-log.md
git commit -m "docs: dev-log 记录 XP插件文件夹与钉选首页实现"
```

---

## 真机验证（计划外，用户执行）

设备 10AF59276U006BH / vivo PD2419：装 `app-debug.apk` → 更多 → 一键整理，检查预览概览与首页布局；异常走「撤销上一步」。
