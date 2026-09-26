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

    private fun workspace(pages: List<Int>, vararg items: ItemState): Workspace {
        items.forEach {
            it.originalContainer = it.container
            it.originalScreen = it.screen
            it.originalX = it.cellX
            it.originalY = it.cellY
        }
        return Workspace(GridConfig(), pages.mapIndexed { i, s -> ScreenPage(s, i) }.toMutableList(), items.toMutableList())
    }

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

    // ---------- arrangement ----------

    @Test
    fun arrangeFillsPagesInCategoryOrder() {
        val socials = (1L..30L).map { app(it, "com.tencent.mm", "微信$it") }
        val games = (100L..104L).map { app(it, "com.mihoyo.genshin", "游戏$it") }
        val ws = workspace(listOf(1, 2), *(socials + games).toTypedArray())
        val result = AutoArranger.arrange(ws)

        // 社交 30 → 4 夹（9/9/9/3），游戏 5 → 1 夹：35 个图标只占 5 格，一页就装得下
        assertEquals(35, result.placedCount)
        assertEquals(0, result.unplaced.size)
        assertEquals(5, result.newFolderCount)
        assertEquals(5, result.pages[0].placements.size)
        assertEquals(0, result.pages[1].placements.size)
        assertEquals(4, result.pages[0].placements.count { it.isNewFolder && it.category == Category.SOCIAL })
        assertEquals(1, result.pages[0].placements.count { it.isNewFolder && it.category == Category.GAME })
        assertEquals(9, result.pages[0].placements.first { it.isNewFolder }.members.size)
    }

    @Test
    fun arrangeSkipsReadOnlyWidgetCells() {
        val widget = app(999, "com.vivo.widget", "widget", itemCode = 2, readOnly = true, spanX = 2, spanY = 2)
        val apps = (1L..25L).map { app(it, "com.tencent.mm", "微信$it") }
        val ws = workspace(listOf(1), widget, *apps.toTypedArray())
        val result = AutoArranger.arrange(ws)

        // 25 个同类图标收进 3 个文件夹，只占 3 格，因此不再放不下
        assertEquals(25, result.placedCount)
        assertEquals(3, result.newFolderCount)
        assertEquals(0, result.unplaced.size)
        val forbidden = setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
        assertTrue(result.pages[0].placements.none { (it.cellX to it.cellY) in forbidden })
        // widget 本身不在重排结果里
        assertTrue(result.pages[0].placements.none { it.item.dbId == 999L })
    }

    @Test
    fun arrangeReservesCellsOfUnplacedItems() {
        // 未分类不收夹：一页放不下时余下的保持原位，但它们原来的格子必须让出来
        val apps = (1L..31L).map {
            val slot = (it - 1) % 28
            app(it, "com.unknown.pkg$it", "未知$it", x = (slot % 4).toInt(), y = (slot / 4).toInt())
        }
        val ws = workspace(listOf(1), *apps.toTypedArray())
        val result = AutoArranger.arrange(ws)

        assertEquals(0, result.newFolderCount)
        assertTrue(result.unplaced.isNotEmpty())
        assertEquals(31, result.placedCount + result.unplaced.size)
        val placedCells = result.pages[0].placements.map { it.cellY * 1000L + it.cellX }.toSet()
        result.unplaced.forEach {
            assertFalse(it.originalY * 1000L + it.originalX in placedCells)
        }
    }

    @Test
    fun arrangeKeepsDockUntouched() {
        val dockItem = app(50, "com.tencent.mm", "微信dock", container = CONTAINER_DOCK, screen = 0, x = 2, y = 0)
        val desktopItem = app(1, "com.tencent.mm", "微信")
        val ws = workspace(listOf(1), dockItem, desktopItem)
        val result = AutoArranger.arrange(ws)

        assertEquals(1, result.placedCount)
        assertTrue(result.pages[0].placements.none { it.item.dbId == 50L })
        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertEquals(CONTAINER_DOCK, dockItem.container)
        assertEquals(2, dockItem.cellX)
    }

    @Test
    fun arrangementHasNoOverlapAndStaysInBounds() {
        val apps = (1L..40L).map { id ->
            app(id, if (id % 2 == 0L) "com.tencent.mm" else "com.mihoyo.genshin", "a$id")
        }
        val ws = workspace(listOf(1, 2), *apps.toTypedArray())
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
    fun applyWritesPositionsAndCountsMoves() {
        val a = app(1, "com.tencent.mm", "微信", x = 3, y = 5)
        val b = app(2, "com.tencent.mm", "微信2", x = 1, y = 0)
        val ws = workspace(listOf(1), a, b)
        val result = AutoArranger.arrange(ws)
        AutoArranger.apply(ws, result, WorkspaceEditor(ws))

        // 排序后 a/b 落在前两格；b 原本就在 (1,0)，不计入移动
        assertEquals(0 to 0, a.cellX to a.cellY)
        assertEquals(1 to 0, b.cellX to b.cellY)
        assertEquals(1, result.movedCount)
        assertTrue(ws.pageItems(1).isNotEmpty())
        assertFalse(a.readOnly)
    }

    @Test
    fun folderIsMovedAsAWhole() {
        val folder = app(9, "", "文件夹", itemCode = ItemType.FOLDER)
        val child = app(10, "com.tencent.mm", "微信", container = 9L)
        val other = app(11, "com.tencent.mm", "微信2")
        val ws = workspace(listOf(1), folder, child, other)
        val result = AutoArranger.arrange(ws)

        // 子项不在桌面上，不应被重排
        assertEquals(2, result.placedCount)
        assertTrue(result.pages[0].placements.any { it.item.dbId == 9L })
        assertTrue(result.pages[0].placements.none { it.item.dbId == 10L })
        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertEquals(9L, child.container)
    }

    @Test
    fun emptyPagesProduceEmptyPlacements() {
        val ws = workspace(listOf(1, 2, 3), app(1, "com.tencent.mm", "微信"))
        val result = AutoArranger.arrange(ws)
        assertEquals(1, result.placedCount)
        assertEquals(3, result.pages.size)
        assertTrue(result.pages[1].placements.isEmpty())
        assertEquals("空页", result.pages[1].summary())
        assertEquals("社交 x1", result.pages[0].summary())
    }

    @Test
    fun arrangeIgnoresPagesMarkedDeleted() {
        val ws = workspace(listOf(1, 2), app(1, "com.tencent.mm", "微信"))
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.deletePage(2)) // 页 2 待删除、尚未写库
        val result = AutoArranger.arrange(ws)
        assertEquals(1, result.pages.size)
        assertEquals(1, result.pages[0].screenId)
        assertEquals(1, result.placedCount)
    }

    @Test
    fun resultGroupsAllItemsByCategory() {
        val ws = workspace(listOf(1), app(1, "com.tencent.mm", "微信"), app(2, "com.mihoyo.genshin", "游戏"), app(3, "com.unknown.zzz", "未知"))
        val result: ArrangeResult = AutoArranger.arrange(ws)
        val order = result.groups.map { it.first }
        assertEquals(listOf(Category.SOCIAL, Category.GAME, Category.OTHER), order)
        assertEquals(3, result.placedCount)
    }

    // ---------- folder grouping ----------

    @Test
    fun sameCategoryAppsAreCollectedIntoFolder() {
        val socials = (1L..6L).map { app(it, "com.tencent.mm", "微信$it") }
        val ws = workspace(listOf(1), *socials.toTypedArray())
        val result = AutoArranger.arrange(ws)

        assertEquals(1, result.newFolderCount)
        val slot = result.pages[0].placements.single { it.isNewFolder }
        assertEquals("社交", slot.item.title)
        assertEquals(6, slot.members.size)
        assertEquals(6, slot.iconCount)
        assertEquals(1, result.pages[0].placements.size) // 6 个图标只占 1 格

        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        val folder = ws.liveItems.single { it.isFolder }
        assertTrue(folder.isNew)
        assertEquals("社交", folder.title)
        val children = ws.childrenOf(folder)
        assertEquals(6, children.size)
        children.forEach { assertEquals(folder.dbId, it.container) }
        // 夹内 3x3 网格不越界
        children.forEach { assertTrue(it.cellX in 0..2 && it.cellY in 0..2) }
    }

    @Test
    fun overflowingCategoryGetsNumberedFolders() {
        val media = (1L..12L).map { app(it, "com.tencent.qqmusic", "音乐$it") }
        val ws = workspace(listOf(1), *media.toTypedArray())
        val result = AutoArranger.arrange(ws)

        assertEquals(
            listOf("影音", "影音 2"),
            result.pages[0].placements.filter { it.isNewFolder }.map { it.item.title },
        )
        assertEquals(12, result.placedCount)
        assertEquals(12, result.movedCount)

        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        assertEquals(listOf(9, 3), ws.liveItems.filter { it.isFolder }.map { ws.childrenOf(it).size })
    }

    @Test
    fun uncategorizedAndSmallGroupsStayLoose() {
        val others = (1L..6L).map { app(it, "com.unknown.pkg$it", "未知$it") }
        val games = (20L..22L).map { app(it, "com.mihoyo.genshin", "游戏$it") }
        val ws = workspace(listOf(1), *(others + games).toTypedArray())
        val result = AutoArranger.arrange(ws)

        assertEquals(0, result.newFolderCount)
        assertEquals(9, result.placedCount)
        assertTrue(result.pages[0].placements.none { it.isNewFolder })
    }

    @Test
    fun existingFolderIsFiledUnderItsOwnCategory() {
        val folder = app(9, "", "影音 2", itemCode = ItemType.FOLDER)
        val other = app(10, "com.tencent.mm", "微信")
        val ws = workspace(listOf(1), folder, other)
        val result = AutoArranger.arrange(ws)

        // 标题就是类别名（含续编号）的既有文件夹能被认领，不会被当成未分类
        val placed = result.pages[0].placements.single { it.item.dbId == 9L }
        assertEquals(Category.MEDIA, placed.category)
        assertFalse(placed.isNewFolder)
    }
}
