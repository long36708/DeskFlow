package com.deskflow.domain

import com.deskflow.domain.classifier.AppClassifier
import com.deskflow.domain.classifier.ArrangeResult
import com.deskflow.domain.classifier.AutoArranger
import com.deskflow.domain.classifier.Category
import com.deskflow.domain.classifier.CategoryRules
import com.deskflow.domain.classifier.DefaultRules
import com.deskflow.domain.layout.WorkspaceEditor
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.CONTAINER_DOCK
import com.deskflow.domain.model.GridConfig
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import com.deskflow.domain.model.ScreenPage
import com.deskflow.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {

    private fun app(
        id: Long,
        pkg: String,
        title: String = "app$id",
        x: Int = 0,
        y: Int = 0,
        screen: Int = 1,
        container: Long = CONTAINER_DESKTOP,
        itemCode: Int = ItemType.APP,
        readOnly: Boolean = false,
        spanX: Int = 1,
        spanY: Int = 1,
    ) = ItemState(
        dbId = id, title = title, intent = if (pkg.isEmpty()) "" else "$pkg/.Main", itemCode = itemCode,
        iconPackage = null, iconResource = null, container = container, screen = screen, cellX = x, cellY = y,
        spanX = spanX, spanY = spanY, readOnly = readOnly,
    )

    /** 非首页散放应用：行优先铺在第 [screen] 页，格位由序号决定。 */
    private fun looseApps(ids: LongRange, pkg: String, screen: Int = 2): List<ItemState> =
        ids.mapIndexed { i, id -> app(id, pkg, "app$id", x = i % 4, y = (i / 4) % 7, screen = screen) }

    /** 非首页散放应用，格位显式给出。 */
    private fun looseAt(ids: LongRange, pkg: String, screen: Int, cells: List<Pair<Int, Int>>): List<ItemState> =
        ids.mapIndexed { i, id -> app(id, pkg, "app$id", x = cells[i].first, y = cells[i].second, screen = screen) }

    private fun workspace(
        pages: List<Int>,
        vararg items: ItemState,
        grid: GridConfig = GridConfig(),
    ): Workspace {
        items.forEach {
            it.originalContainer = it.container
            it.originalScreen = it.screen
            it.originalX = it.cellX
            it.originalY = it.cellY
        }
        return Workspace(grid, pages.mapIndexed { i, s -> ScreenPage(s, i) }.toMutableList(), items.toMutableList())
    }

    private fun cellsOf(items: List<ItemState>): List<Triple<Int, Int, Int>> =
        items.map { Triple(it.screen, it.cellX, it.cellY) }

    // ---------- classification ----------

    @Test
    fun classifyByPackageSegment() {
        assertEquals(Category.SOCIAL, AppClassifier.classify(app(1, "com.tencent.mm", "微信")))
        assertEquals(Category.MEDIA, AppClassifier.classify(app(2, "com.tencent.qqmusic", "QQ音乐")))
        assertEquals(Category.GAME, AppClassifier.classify(app(3, "com.mihoyo.yuanshen", "原神")))
        assertEquals(Category.OTHER, AppClassifier.classify(app(4, "com.example.foobar", "Foobar")))
    }

    @Test
    fun longestKeywordWins() {
        // 段 browser(7) 长于 vivo(4)，vivo 浏览器应归工具而非系统
        assertEquals(Category.TOOLS, AppClassifier.classify(app(1, "com.vivo.browser", "浏览器")))
        assertEquals(Category.TOOLS, AppClassifier.classify(app(2, "com.vivo.weather", "天气")))
        assertEquals(Category.SYSTEM, AppClassifier.classify(app(3, "com.vivo.space", "空间")))
    }

    @Test
    fun classifyFallsBackToTitle() {
        assertEquals(Category.FINANCE, AppClassifier.classify(app(1, "", "支付宝")))
        assertEquals(Category.SHOPPING, AppClassifier.classify(app(2, "com.unknown.xyz", "拼多多")))
        assertEquals(Category.OTHER, AppClassifier.classify(app(3, "com.unknown.xyz", "某个应用")))
    }

    @Test
    fun customRulesOverrideDefault() {
        val custom = CategoryRules(
            exactPackages = mapOf("com.tencent.mm" to Category.WORK),
            keywords = mapOf("zzz" to Category.GAME),
            titleKeywords = mapOf("自定义" to Category.PHOTO),
        )
        val merged = DefaultRules.rules.merge(custom)
        assertEquals(Category.WORK, AppClassifier.classify(app(1, "com.tencent.mm", "微信"), merged))
        // 未覆盖的部分仍走内置规则
        assertEquals(Category.MEDIA, AppClassifier.classify(app(2, "com.tencent.qqmusic", "QQ音乐"), merged))
        assertEquals(Category.PHOTO, AppClassifier.classify(app(3, "com.unknown.xyz", "自定义相机"), merged))
    }

    /**
     * 精确包名表里写的是全小写键，而被查包名会被 lowercase 后再查表。
     * 用真实大小写混排的包名（支付宝）打这条判据，防止哪次重构把归一化去掉。
     */
    @Test
    fun exactPackageMatchingIsCaseInsensitive() {
        val rules = CategoryRules(exactPackages = mapOf("com.eg.android.alipaygphone" to Category.GAME))
        assertEquals(Category.GAME, AppClassifier.classify(app(1, "com.eg.android.AlipayGphone", "Xyz"), rules))
        assertEquals(
            Category.FINANCE,
            AppClassifier.classify(app(2, "com.eg.android.AlipayGphone", "支付宝")),
        )
    }

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
                keywords = mapOf("xposed" to Category.XP, "riru" to Category.XP),
                titleKeywords = mapOf("模块" to Category.XP),
            )
        )
        assertEquals(Category.XP, AppClassifier.classify(app(1, "org.lsposed.manager", "LSPosed"), rules))
        assertEquals(Category.XP, AppClassifier.classify(app(2, "com.tsng.riru.test", "测试"), rules))
        assertEquals(Category.XP, AppClassifier.classify(app(3, "com.example.app", "某模块"), rules))
        // 命中 xp 的应用不改变其它类别判定
        assertEquals(Category.SOCIAL, AppClassifier.classify(app(4, "com.tencent.mm", "微信"), rules))
    }

    // ---------- 锁定层：首页 / 既有夹 / 只读 / Dock ----------

    @Test
    fun homePageIsNeverRearranged() {
        val home = (1L..6L).map {
            app(it, "com.tencent.qqmusic", "音乐$it", x = ((it - 1) % 4).toInt(), y = ((it - 1) / 4).toInt(), screen = 1)
        }
        val ws = workspace(listOf(1, 2, 3), *home.toTypedArray())
        val before = cellsOf(home)
        val result = AutoArranger.arrange(ws)

        assertEquals(0, result.looseCount)
        assertEquals(0, result.newFolderCount)
        assertEquals(0, result.placedCount)
        assertEquals(0, result.movedCount)
        assertEquals(6, result.lockedCount)
        assertEquals(before, cellsOf(result.pages[0].placements.map { it.item }))

        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertEquals(before, cellsOf(home))
        assertTrue(ws.liveItems.none { it.isFolder })
    }

    @Test
    fun newFolderNeverLandsOnHomePage() {
        val homeApp = app(1L, "com.tencent.mm", "微信", x = 0, y = 0, screen = 1)
        val media = looseApps(10L..15L, "com.tencent.qqmusic", screen = 2)
        val ws = workspace(listOf(1, 2), homeApp, *media.toTypedArray())
        val result = AutoArranger.arrange(ws)

        assertEquals(1, result.newFolderCount)
        // 首页有 27 个空格也不许用：新夹只落非首页
        assertTrue(result.pages.first { it.screenId == 1 }.placements.none { it.isNewFolder })
        val placed = result.pages.first { it.screenId == 2 }.placements.single { it.isNewFolder }
        assertEquals(0 to 0, placed.cellX to placed.cellY)
    }

    @Test
    fun existingFolderWithItsChildrenStaysPut() {
        val folder = app(9L, "", "影音", itemCode = ItemType.FOLDER, x = 2, y = 3, screen = 2)
        val children = (10L..12L).map {
            app(it, "com.tencent.qqmusic", "音乐$it", container = 9L, x = ((it - 10) % 3).toInt(), y = 0, screen = 2)
        }
        val socials = looseAt(20L..23L, "com.tencent.mm", 2, listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0))
        val ws = workspace(listOf(1, 2), folder, *children.toTypedArray(), *socials.toTypedArray())
        val result = AutoArranger.arrange(ws)

        // 既有夹不是待归类项，整体原位保留、也不参与新夹计数
        assertEquals(1, result.newFolderCount)
        assertEquals(4, result.placedCount)
        val lockedFolder = result.pages.first { it.screenId == 2 }.placements.single { it.item.dbId == 9L }
        assertFalse(lockedFolder.isNewFolder)
        assertEquals(2 to 3, lockedFolder.cellX to lockedFolder.cellY)

        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertFalse(folder.isDeleted)
        assertEquals(2 to 3, folder.cellX to folder.cellY)
        assertEquals(3, ws.childrenOf(folder).size)
        children.forEach { assertEquals(9L, it.container) }
    }

    @Test
    fun readOnlyAndDockItemsAreUntouched() {
        val widget = app(999L, "com.vivo.widget", "widget", itemCode = 2, readOnly = true, spanX = 2, spanY = 2, screen = 2, x = 0, y = 0)
        val dock = app(50L, "com.tencent.mm", "dock微信", container = CONTAINER_DOCK, screen = 0, x = 2, y = 0)
        val media = looseAt(10L..13L, "com.tencent.qqmusic", 2, listOf(2 to 0, 3 to 0, 2 to 1, 3 to 1))
        val ws = workspace(listOf(1, 2), widget, dock, *media.toTypedArray())
        val result = AutoArranger.arrange(ws)

        assertEquals(1, result.newFolderCount)
        // 2x2 widget 占了 (0,0)-(1,1)，新夹只能落在腾空的 (2,0)
        val placed = result.pages.first { it.screenId == 2 }.placements.single { it.isNewFolder }
        assertEquals(2 to 0, placed.cellX to placed.cellY)
        assertTrue(result.pages.all { page -> page.placements.none { it.item.dbId == 50L } })

        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertEquals(0 to 0, widget.cellX to widget.cellY)
        assertEquals(CONTAINER_DOCK, dock.container)
        assertEquals(2, dock.cellX)
    }

    @Test
    fun folderIsFiledUnderItsOwnCategoryNotCollected() {
        val folder = app(9, "", "影音 2", itemCode = ItemType.FOLDER, x = 1, y = 1, screen = 2)
        val other = app(10, "com.tencent.mm", "微信", x = 3, y = 3, screen = 2)
        val ws = workspace(listOf(1, 2), folder, other)
        val result = AutoArranger.arrange(ws)

        // 标题就是类别名（含续编号）的既有文件夹能被认领类别，但仍然是锁定项
        val placed = result.pages.first { it.screenId == 2 }.placements.single { it.item.dbId == 9L }
        assertEquals(Category.MEDIA, placed.category)
        assertFalse(placed.isNewFolder)
        assertEquals(0, result.newFolderCount)
    }

    // ---------- 待归类：非首页散放应用 ----------

    @Test
    fun looseAppsOnLaterPagesAreCollected() {
        val homeApp = app(1L, "com.tencent.mm", "微信", screen = 1)
        val socials = looseApps(10L..15L, "com.tencent.qqmusic", screen = 2)
        val ws = workspace(listOf(1, 2), homeApp, *socials.toTypedArray())
        val result = AutoArranger.arrange(ws)

        assertEquals(6, result.looseCount)
        assertEquals(1, result.newFolderCount)
        assertEquals(6, result.placedCount)
        val folder = result.pages.first { it.screenId == 2 }.placements.single { it.isNewFolder }
        assertEquals("影音", folder.item.title)
        assertEquals(6, folder.members.size)

        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        val created = ws.liveItems.single { it.isFolder }
        assertTrue(created.isNew)
        assertEquals(1, ws.pageItems(2).count { it.isFolder })
        assertEquals(6, ws.childrenOf(created).size)
        childrenInsideGrid(ws.childrenOf(created))
    }

    @Test
    fun undersizedAndUnclassifiedLooseAppsStayPut() {
        val media = looseAt(1L..3L, "com.tencent.qqmusic", 2, listOf(0 to 0, 1 to 0, 2 to 0))
        val unknown = looseAt(10L..15L, "com.example.zzz", 2, listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1, 0 to 2, 1 to 2))
        val ws = workspace(listOf(1, 2), *(media + unknown).toTypedArray())
        val before = cellsOf(media + unknown)
        val result = AutoArranger.arrange(ws)

        // 3 个同类不满 4、6 个未分类不收夹：全员原地不动
        assertEquals(0, result.newFolderCount)
        assertEquals(0, result.placedCount)
        assertEquals(0, result.movedCount)
        assertEquals(9, result.looseCount)
        assertEquals(before, cellsOf(result.pages.flatMap { it.placements }.map { it.item }))
        assertEquals(
            listOf(Category.MEDIA, Category.OTHER),
            result.groups.map { it.first },
        )
        assertTrue(result.groups.all { (_, items) -> items.none { it.positionChanged() } })
    }

    @Test
    fun dottedPackageWithoutKeywordStaysOther() {
        // com.live.translator 段内含 translator 而不是 translate，不该被工具关键词捞走
        val app1 = app(1L, "com.live.translator", "对话翻译", screen = 2)
        assertEquals(Category.OTHER, AppClassifier.classify(app1))
    }

    // ---------- 收夹切块 ----------

    @Test
    fun newFolderNameSkipsTitlesTakenByLockedFolders() {
        // 首页已有「影音」夹（锁定不动），第 2 页再收同类时不能也叫「影音」
        val existing = app(9L, "", "影音", itemCode = ItemType.FOLDER, x = 0, y = 0, screen = 1)
        val media = looseApps(1L..5L, "com.tencent.qqmusic", screen = 2)
        val ws = workspace(listOf(1, 2), existing, *media.toTypedArray())
        val result = AutoArranger.arrange(ws)

        val folder = result.pages.first { it.screenId == 2 }.placements.single { it.isNewFolder }
        assertEquals("影音 2", folder.item.title)
    }

    @Test
    fun newFolderNamesStayUniqueAcrossChunks() {
        val existing = listOf(
            app(9L, "", "影音", itemCode = ItemType.FOLDER, x = 0, y = 0, screen = 1),
            app(8L, "", "影音 2", itemCode = ItemType.FOLDER, x = 1, y = 0, screen = 1),
        )
        val media = looseApps(1L..13L, "com.tencent.qqmusic", screen = 2)
        val ws = workspace(listOf(1, 2), *(existing + media).toTypedArray())
        val result = AutoArranger.arrange(ws)

        assertEquals(
            listOf("影音 3", "影音 4"),
            result.pages.first { it.screenId == 2 }.placements.filter { it.isNewFolder }.map { it.item.title },
        )
    }

    @Test
    fun xpModulesGetTheirOwnFolder() {
        val modules = (1L..4L).map { app(it, "com.example.xposed$it", "模块$it", x = it.toInt() - 1, screen = 2) }
        val ws = workspace(listOf(1, 2), *modules.toTypedArray())
        val rules = DefaultRules.rules.merge(CategoryRules(keywords = mapOf("xposed" to Category.XP)))
        val result = AutoArranger.arrange(ws, rules)

        assertEquals(1, result.newFolderCount)
        val xp = result.pages.first { it.screenId == 2 }.placements.single()
        assertTrue(xp.isNewFolder)
        assertEquals("XP插件", xp.item.title)
        assertEquals(4, xp.members.size)
    }

    @Test
    fun xpTailMergesIntoOneFolder() {
        // 11 = 9 + 2：尾组不足 4 个不另起夹，整个类别就是一个 11 个图标的「XP插件」夹
        val modules = looseApps(1L..11L, "com.example.xposed1", screen = 2)
        val ws = workspace(listOf(1, 2), *modules.toTypedArray())
        val rules = DefaultRules.rules.merge(CategoryRules(keywords = mapOf("xposed" to Category.XP)))
        val result = AutoArranger.arrange(ws, rules)

        val folder = result.pages.first { it.screenId == 2 }.placements.single { it.isNewFolder }
        assertEquals("XP插件", folder.item.title)
        assertEquals(11, folder.members.size)
    }

    @Test
    fun twelveOfAKindStayInOneFolder() {
        // 12 = 9 + 3：尾组不足 4 个不收成「影音 2」，而是一个 12 个图标的夹
        val media = looseApps(1L..12L, "com.tencent.qqmusic", screen = 2)
        val ws = workspace(listOf(1, 2), *media.toTypedArray())
        val result = AutoArranger.arrange(ws)

        assertEquals(
            listOf("影音"),
            result.pages.first { it.screenId == 2 }.placements.filter { it.isNewFolder }.map { it.item.title },
        )
        assertEquals(12, result.placedCount)
        assertEquals(12, result.movedCount)

        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        val children = ws.childrenOf(ws.liveItems.single { it.isFolder })
        assertEquals(12, children.size)
        // 夹内 3 列多行：第 4 行（cellY=3）在 vivo 上照常渲染，且成员按行优先紧密排列
        assertEquals(
            (0..11).toList(),
            children.sortedBy { it.cellY * 1000 + it.cellX }.map { it.cellX + it.cellY * 3 },
        )
    }

    @Test
    fun tailSplitsOnlyWhenItFillsAFolder() {
        // 13 = 9 + 4：尾组够 4 个才另起一夹并续编号
        val media = looseApps(1L..13L, "com.tencent.qqmusic", screen = 2)
        val ws = workspace(listOf(1, 2), *media.toTypedArray())
        val result = AutoArranger.arrange(ws)

        assertEquals(
            listOf("影音", "影音 2"),
            result.pages.first { it.screenId == 2 }.placements.filter { it.isNewFolder }.map { it.item.title },
        )
        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertEquals(listOf(9, 4), ws.liveItems.filter { it.isFolder }.map { ws.childrenOf(it).size })
    }

    @Test
    fun undersizedTailMergesIntoPreviousFolder() {
        // 21 = 9 + 9 + 3：尾组不满 4 个，并回上一夹变成 9 + 12
        val media = looseApps(1L..21L, "com.tencent.qqmusic", screen = 2)
        val ws = workspace(listOf(1, 2), *media.toTypedArray())
        val result = AutoArranger.arrange(ws)

        assertEquals(21, result.placedCount)
        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertEquals(listOf(9, 12), ws.liveItems.filter { it.isFolder }.map { ws.childrenOf(it).size })
    }

    // ---------- 落位 ----------

    @Test
    fun newFolderTakesTheVaultedCells() {
        val widget = app(999L, "com.vivo.widget", "widget", itemCode = 2, readOnly = true, spanX = 4, spanY = 5, screen = 2, x = 0, y = 0)
        val media = looseAt(1L..8L, "com.tencent.qqmusic", 2, listOf(3 to 6, 2 to 6, 1 to 6, 0 to 6, 3 to 5, 2 to 5, 1 to 5, 0 to 5))
        val ws = workspace(listOf(1, 2), widget, *media.toTypedArray())
        val result = AutoArranger.arrange(ws)

        // 页面上半页被 widget 占死，散放项集中在页尾；新夹落在本次腾空的最靠前一格（行优先）
        val folder = result.pages.first { it.screenId == 2 }.placements.single { it.isNewFolder }
        assertEquals(0 to 5, folder.cellX to folder.cellY)
    }

    @Test
    fun overCapacityFallsBackToNewPage() {
        // 退化容量下的兜底：所有图标挤在同一格，腾出的格子放不下第二个夹，只能加页
        val media = looseAt(1L..13L, "com.tencent.qqmusic", 2, List(13) { 0 to 0 })
        val ws = workspace(listOf(1, 2), *media.toTypedArray(), grid = GridConfig(columns = 1, rows = 1))
        val result = AutoArranger.arrange(ws)

        assertEquals(2, result.newFolderCount)
        assertEquals(1, result.newScreens.size)
        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertEquals(3, ws.pages.size)
        assertTrue(ws.pages.single { it.isNew }.screenId >= 0)
    }

    @Test
    fun arrangementHasNoOverlapAndStaysInBounds() {
        val texts = (1L..40L).map { id ->
            app(id, if (id % 2 == 0L) "com.tencent.qqmusic" else "com.mihoyo.genshin", "a$id", x = ((id - 1) % 4).toInt(), y = (((id - 1) / 4) % 7).toInt(), screen = 2)
        }
        val ws = workspace(listOf(1, 2, 3), *texts.toTypedArray())
        val result = AutoArranger.arrange(ws)
        val grid = ws.grid

        result.pages.forEach { page ->
            val seen = HashSet<Long>()
            page.placements.forEach { pl ->
                assertTrue(pl.cellX in 0 until grid.columns)
                assertTrue(pl.cellY in 0 until grid.rows)
                assertTrue(seen.add(pl.cellY.toLong() * 1000 + pl.cellX))
            }
        }
        assertEquals(40, result.placedCount)
    }

    @Test
    fun arrangingTwiceChangesNothing() {
        val homeApp = app(99L, "com.tencent.mm", "微信", screen = 1)
        val media = looseApps(1L..13L, "com.tencent.qqmusic", screen = 2)
        val ws = workspace(listOf(1, 2), homeApp, *media.toTypedArray())
        val first = AutoArranger.arrange(ws)
        assertEquals(2, first.newFolderCount)
        AutoArranger.apply(ws, first, WorkspaceEditor(ws))

        // 整理过一遍之后桌面上不再有散放项，再点一次应该是彻底的空操作
        val second = AutoArranger.arrange(ws)
        assertEquals(0, second.looseCount)
        assertEquals(0, second.newFolderCount)
        assertEquals(0, second.movedCount)
        assertTrue(second.newScreens.isEmpty())
    }

    @Test
    fun applyWritesPositionsAndCountsMoves() {
        val homeApp = app(1L, "com.tencent.mm", "微信", x = 3, y = 5, screen = 1)
        val media = looseAt(2L..5L, "com.tencent.qqmusic", 2, listOf(3 to 5, 1 to 0, 2 to 0, 3 to 0))
        val ws = workspace(listOf(1, 2), homeApp, *media.toTypedArray())
        val result = AutoArranger.arrange(ws)
        AutoArranger.apply(ws, result, WorkspaceEditor(ws))

        // 首页项一格没动；4 个散放项被收进 1 个夹，全部计为移位
        assertEquals(3 to 5, homeApp.cellX to homeApp.cellY)
        assertEquals(4, result.movedCount)
        assertEquals(1, result.lockedCount)
        assertEquals(1, ws.pageItems(2).count { it.isFolder })
        // 新夹的 dbId 是临时负值，成员 container 指向它
        val created = ws.pageItems(2).single { it.isFolder }
        media.forEach { assertEquals(created.dbId, it.container) }
    }

    @Test
    fun emptyPagesProduceEmptyPlacements() {
        val ws = workspace(listOf(1, 2, 3), app(1, "com.tencent.mm", "微信", screen = 1))
        val result: ArrangeResult = AutoArranger.arrange(ws)
        assertEquals(3, result.pages.size)
        assertTrue(result.pages[1].placements.isEmpty())
        assertEquals("空页", result.pages[1].summary())
        assertEquals("社交 x1", result.pages[0].summary())
    }

    @Test
    fun arrangeIgnoresPagesMarkedDeleted() {
        val ws = workspace(
            listOf(1, 2, 3),
            app(1, "com.tencent.mm", "微信", screen = 1),
            *looseApps(10L..13L, "com.tencent.qqmusic", screen = 2).toTypedArray(),
        )
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.deletePage(3)) // 页 3 待删除、尚未写库
        val result = AutoArranger.arrange(ws)

        assertEquals(2, result.pages.size)
        assertEquals(listOf(1, 2), result.pages.map { it.screenId })
        assertEquals(1, result.newFolderCount)
        assertTrue(result.newScreens.isEmpty())
    }

    @Test
    fun resultGroupsAllLooseItemsByCategory() {
        val ws = workspace(
            listOf(1, 2),
            app(1, "com.tencent.mm", "微信", screen = 1),
            app(2, "com.tencent.qqmusic", "音乐", screen = 2, x = 0, y = 0),
            app(3, "com.mihoyo.genshin", "游戏", screen = 2, x = 1, y = 0),
            app(4, "com.unknown.zzz", "未知", screen = 2, x = 2, y = 0),
        )
        val result: ArrangeResult = AutoArranger.arrange(ws)

        assertEquals(listOf(Category.MEDIA, Category.GAME, Category.OTHER), result.groups.map { it.first })
        assertEquals(3, result.looseCount)
        // 都不满 4 个 → 全部原地不动
        assertEquals(0, result.newFolderCount)
        assertEquals(0, result.placedCount)
        assertEquals(4, result.lockedCount)
    }

    private fun childrenInsideGrid(children: List<ItemState>) {
        val grid = GridConfig()
        children.forEach {
            assertTrue(it.cellX in 0 until grid.folderColumns)
            assertTrue(it.cellY >= 0)
        }
    }
}
