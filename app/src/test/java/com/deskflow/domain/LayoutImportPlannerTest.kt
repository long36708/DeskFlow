package com.deskflow.domain

import com.deskflow.domain.layout.ArchiveEntry
import com.deskflow.domain.layout.ArchiveFolder
import com.deskflow.domain.layout.ArchivePage
import com.deskflow.domain.layout.LayoutArchive
import com.deskflow.domain.layout.LayoutArchiveCodec
import com.deskflow.domain.layout.LayoutImportPlanner
import com.deskflow.domain.layout.LayoutImportPlanner.DOCK_REF
import com.deskflow.domain.layout.WorkspaceEditor
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.CONTAINER_DOCK
import com.deskflow.domain.model.GridConfig
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import com.deskflow.domain.model.ScreenPage
import com.deskflow.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutImportPlannerTest {

    private fun app(id: Long, x: Int, y: Int, screen: Int = 1, pkg: String = "pkg$id", container: Long = CONTAINER_DESKTOP) =
        ItemState(dbId = id, title = "app$id", intent = "$pkg/.Main", itemCode = ItemType.APP,
            iconPackage = null, iconResource = null, container = container, screen = screen, cellX = x, cellY = y)

    private fun workspace(vararg items: ItemState, pages: List<ScreenPage> = listOf(ScreenPage(1, 0), ScreenPage(2, 1))) =
        Workspace(GridConfig(columns = 4, rows = 7), pages.toMutableList(), items.toMutableList())

    private fun entry(id: Long, x: Int, y: Int, pkg: String = "pkg$id", kind: String = LayoutArchiveCodec.KIND_APP) =
        ArchiveEntry(kind, dbId = id, title = "t$id", intent = "$pkg/.Main", cellX = x, cellY = y,
            spanX = 1, spanY = 1, iconPackage = null, iconResource = null)

    private fun archive(
        vararg pages: ArchivePage,
        dock: List<ArchiveEntry> = emptyList(),
        columns: Int = 4,
        rows: Int = 7,
    ) = LayoutArchive(1, "t", GridConfig(columns, rows, 5), pages.toList(), dock)

    // ---------- 匹配 ----------

    @Test
    fun matchesByDbIdFirstThenByPkg() {
        val a = app(7, 0, 0) // dbId 相同 → 按 id 匹配
        val b = app(50, 1, 0, pkg = "pkg8") // dbId 不同、包名同 → 按包名匹配
        val ws = workspace(a, b)
        val plan = LayoutImportPlanner.plan(ws, archive(ArchivePage(listOf(entry(7, 0, 0), entry(8, 1, 0)), emptyList())))

        assertEquals(2, plan.stats.matchedIcons)
        assertEquals(0, plan.stats.skippedArchive)
        val byItem = plan.moves.associateBy { it.item.dbId }
        assertEquals(0 to 0, byItem.getValue(7L).let { it.x to it.y })
        assertEquals(1 to 0, byItem.getValue(50L).let { it.x to it.y })
    }

    @Test
    fun unmatchedCurrentLooseAppsParkedAndUnmatchedArchiveSkipped() {
        val onDesktop = app(1, 0, 0)
        val stranger = app(99, 3, 0, pkg = "stranger") // 档案里没有 → 收容
        val ws = workspace(onDesktop, stranger)
        val plan = LayoutImportPlanner.plan(ws, archive(ArchivePage(listOf(entry(1, 0, 0), entry(2, 1, 0)), emptyList())))

        assertEquals(1, plan.stats.matchedIcons)
        assertEquals(1, plan.stats.skippedArchive) // 档案里的 pkg2 找不到
        assertEquals(listOf(99L), plan.parked.map { it.dbId })
    }

    @Test
    fun widgetMatchedByIdIsKeptInPlaceNotMoved() {
        val widget = ItemState(dbId = 5, title = "时钟", intent = "w/.Clock", itemCode = ItemType.APP,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 0, cellY = 0,
            spanX = 2, spanY = 1, readOnly = true)
        val ws = workspace(widget)
        // 档案要求 widget 在 (2,0)——不可移动，原位保持
        val wEntry = entry(5, 2, 0, kind = LayoutArchiveCodec.KIND_WIDGET).copy(spanX = 2)
        val plan = LayoutImportPlanner.plan(ws, archive(ArchivePage(listOf(wEntry), emptyList())))

        assertEquals(1, plan.stats.widgetKept)
        assertTrue(plan.moves.isEmpty())
        assertEquals(0, widget.cellX) // 未动
    }

    // ---------- 文件夹 ----------

    @Test
    fun folderReusedByTitleAndMembersMoveIn() {
        val folder = ItemState(dbId = 9, title = "工具", intent = "", itemCode = ItemType.FOLDER,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 0, cellY = 1)
        val member = app(1, 0, 0) // 目前散放在桌面，档案要求进夹
        val extra = app(2, 2, 2, container = 9L) // 现有夹里的额外成员，档案没有 → 留在夹里
        val ws = workspace(folder, member, extra)
        val af = ArchiveFolder(dbId = 777, title = "工具", cellX = 3, cellY = 3, children = listOf(entry(1, 0, 0)))
        val plan = LayoutImportPlanner.plan(ws, archive(ArchivePage(emptyList(), listOf(af))))

        val pf = plan.folders.single()
        assertEquals(folder, pf.existing)
        assertEquals(listOf(1L), pf.members.map { it.dbId })
        assertEquals(3 to 3, pf.x to pf.y)
        assertEquals(1, plan.stats.foldersReused)

        LayoutImportPlanner.apply(plan, WorkspaceEditor(ws))
        assertEquals(3 to 3, folder.cellX to folder.cellY)
        assertEquals(9L, member.container) // 收进沿用夹
        assertEquals(9L, extra.container) // 额外成员不动
        assertEquals("t1", member.title) // 标题对齐
    }

    @Test
    fun folderCreatedWhenNothingMatches() {
        val loose = listOf(app(1, 0, 0), app(2, 1, 0), app(3, 2, 0))
        val ws = workspace(*loose.toTypedArray())
        // 档案夹按包名收编三个散放项
        val af = ArchiveFolder(
            dbId = 500, title = "新夹", cellX = 0, cellY = 2,
            children = listOf(entry(1, 0, 0), entry(2, 0, 0), entry(3, 0, 0)),
        )
        val plan = LayoutImportPlanner.plan(ws, archive(ArchivePage(emptyList(), listOf(af))))

        assertEquals(0, plan.stats.foldersReused)
        assertEquals(1, plan.stats.foldersCreated)
        assertEquals(3, plan.folders.single().members.size)

        LayoutImportPlanner.apply(plan, WorkspaceEditor(ws))
        val created = ws.liveItems.single { it.isFolder }
        assertEquals("新夹", created.title)
        assertEquals(3, ws.childrenOf(created).size)
        assertTrue(loose.all { it.container == created.dbId })
    }

    // ---------- 落位与容错 ----------

    @Test
    fun dockItemsPlacedByArchiveOrder() {
        val d1 = app(1, 0, 0, container = CONTAINER_DOCK)
        val d2 = app(2, 1, 0, container = CONTAINER_DOCK)
        val ws = workspace(d1, d2)
        val plan = LayoutImportPlanner.plan(
            ws,
            archive(ArchivePage(emptyList(), emptyList()), dock = listOf(entry(1, 4, 0), entry(2, 0, 0))),
        )
        val byItem = plan.moves.associateBy { it.item.dbId }
        assertEquals(DOCK_REF, byItem.getValue(1L).screenRef)
        assertEquals(4, byItem.getValue(1L).x)
        assertEquals(0, byItem.getValue(2L).x)
    }

    @Test
    fun outOfGridTargetFallsBackToParkAndFlagsMismatch() {
        val a = app(1, 0, 0)
        val ws = workspace(a)
        // 档案是 5×9，目标是 (6, 8) —— 越界 → 收容 + 标记
        val plan = LayoutImportPlanner.plan(ws, archive(ArchivePage(listOf(entry(1, 6, 8)), emptyList()), columns = 5, rows = 9))
        assertTrue(plan.stats.gridMismatch)
        assertEquals(listOf(1L), plan.parked.map { it.dbId })
        assertTrue(plan.moves.isEmpty())
    }

    @Test
    fun missingPagesAreCreatedAndParkGoesToLastPage() {
        val matched = app(1, 0, 0)
        val extra = app(99, 2, 0, pkg = "stranger")
        val ws = workspace(matched, extra, pages = listOf(ScreenPage(1, 0))) // 当前只有 1 页，档案要 2 页
        val plan = LayoutImportPlanner.plan(
            ws,
            archive(ArchivePage(listOf(entry(1, 0, 0)), emptyList()), ArchivePage(emptyList(), emptyList())),
        )
        assertEquals(1, plan.stats.newPages)
        assertTrue(plan.screenIds[1] < 0) // 第二页是待新建的临时 id

        LayoutImportPlanner.apply(plan, WorkspaceEditor(ws))
        assertEquals(0 to 0, matched.cellX to matched.cellY) // 档案位 (0,0)
        assertEquals(2, ws.pages.count { !it.isDeleted }) // 新页已建
        // 收容到最后的页（新页）首个空格
        assertEquals(0 to 0, extra.cellX to extra.cellY)
        assertTrue(extra.screen != 1)
    }

    @Test
    fun stayersCellsAreRespected() {
        val mover = app(1, 0, 0) // 档案要求去 (0,1)
        val stickyWidget = ItemState(dbId = 5, title = "时钟", intent = "w/.C", itemCode = ItemType.APP,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 0, cellY = 1,
            readOnly = true) // 不动的 widget 占着档案目标格
        val ws = workspace(mover, stickyWidget)
        val plan = LayoutImportPlanner.plan(ws, archive(ArchivePage(listOf(entry(1, 0, 1)), emptyList())))

        assertTrue(plan.moves.isEmpty()) // 目标被不动项占着 → 退化收容
        assertEquals(listOf(1L), plan.parked.map { it.dbId })
    }
}
