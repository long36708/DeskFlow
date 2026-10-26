package com.deskflow.domain

import com.deskflow.domain.layout.DiffCalculator
import com.deskflow.domain.layout.DbOp
import com.deskflow.domain.layout.ReversePlanner
import com.deskflow.domain.layout.WorkspaceDiff
import com.deskflow.domain.layout.WorkspaceEditor
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.GridConfig
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import com.deskflow.domain.model.ScreenPage
import com.deskflow.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReversePlanTest {

    private fun app(
        id: Long,
        x: Int,
        y: Int,
        screen: Int = 1,
        container: Long = CONTAINER_DESKTOP,
        readOnly: Boolean = false,
    ) = ItemState(
        dbId = id, title = "app$id", intent = "pkg$id/.Main", itemCode = ItemType.APP,
        iconPackage = "pkg$id", iconResource = null, container = container, screen = screen,
        cellX = x, cellY = y, readOnly = readOnly,
    )

    private fun folder(id: Long, x: Int, y: Int) = ItemState(
        dbId = id, title = "夹$id", intent = "", itemCode = ItemType.FOLDER,
        iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = x, cellY = y,
    )

    private fun workspace(vararg items: ItemState, pages: List<ScreenPage> = listOf(ScreenPage(1, 0), ScreenPage(2, 1))): Workspace {
        items.forEach {
            it.originalContainer = it.container
            it.originalScreen = it.screen
            it.originalX = it.cellX
            it.originalY = it.cellY
            it.originalTitle = it.title
        }
        return Workspace(GridConfig(), pages.toMutableList(), items.toMutableList())
    }

    @Test
    fun reverseOfMoveRestoresOriginalPosition() {
        val a = app(1, 0, 0)
        val ws = workspace(a)
        WorkspaceEditor(ws).placeAt(a, CONTAINER_DESKTOP, 1, 3, 2)
        val reverse = ReversePlanner.reverse(ws, DiffCalculator.plan(ws, 100))
        assertEquals(listOf<DbOp>(DbOp.UpdatePosition(1, CONTAINER_DESKTOP, 1, 0, 0)), reverse.ops)
    }

    @Test
    fun reverseOfInsertIsDeleteWithRealId() {
        val ws = workspace()
        val ed = WorkspaceEditor(ws)
        ed.addApp("com.x/.Main", "X", CONTAINER_DESKTOP, 1)!!
        val reverse = ReversePlanner.reverse(ws, DiffCalculator.plan(ws, 100))
        // 新增行 id = maxId(100) + 1
        assertEquals(listOf<DbOp>(DbOp.Delete(101)), reverse.ops)
        assertEquals(listOf(101L), reverse.deletedIds)
        assertTrue(reverse.insertedIds.isEmpty())
    }

    @Test
    fun reverseOfDeleteRestoresFullRow() {
        val a = app(7, 0, 0)
        val ws = workspace(a)
        WorkspaceEditor(ws).delete(a, 1)
        val reverse = ReversePlanner.reverse(ws, DiffCalculator.plan(ws, 100))
        val ins = reverse.ops.filterIsInstance<DbOp.Insert>().single()
        assertEquals(7L, ins.row.id)
        assertEquals("app7", ins.row.title)
        assertEquals("pkg7/.Main", ins.row.intent)
        assertEquals("pkg7", ins.row.iconPackage)
        assertEquals(CONTAINER_DESKTOP, ins.row.container)
        assertEquals(1, ins.row.screen)
        assertEquals(0, ins.row.cellX)
    }

    @Test
    fun reverseSkipsRowsThatNeverExisted() {
        val ws = workspace()
        val ed = WorkspaceEditor(ws)
        val added = ed.addApp("com.x/.Main", "X", CONTAINER_DESKTOP, 1)!!
        ed.delete(added, 1) // 会话内新增又删除：从未落库
        val reverse = ReversePlanner.reverse(ws, DiffCalculator.plan(ws, 100))
        assertTrue(reverse.ops.isEmpty())
    }

    @Test
    fun reverseOfRenameRestoresOriginalTitle() {
        val f = folder(9, 0, 0)
        val ws = workspace(f)
        WorkspaceEditor(ws).renameFolder(f, "新名字")
        val reverse = ReversePlanner.reverse(ws, DiffCalculator.plan(ws, 100))
        assertEquals(listOf<DbOp>(DbOp.UpdateTitle(9, "夹9")), reverse.ops)
    }

    @Test
    fun reverseOfPageDeleteRestoresScreenBeforeItems() {
        val b = app(2, 0, 0, screen = 2)
        val ws = workspace(b)
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.deletePage(2)) // b 迁到第 1 页空位
        val reverse = ReversePlanner.reverse(ws, DiffCalculator.plan(ws, 100))
        // 反向必须先重建页（originalOrder=1），再把 b 迁回 (2,0,screen2)
        assertEquals(
            listOf<DbOp>(
                DbOp.InsertScreen(2, 1),
                DbOp.UpdatePosition(2, CONTAINER_DESKTOP, 2, 0, 0),
            ),
            reverse.ops,
        )
    }

    @Test
    fun reverseOfNewPageAndMoveDeletesScreenLast() {
        val a = app(1, 0, 0)
        val ws = workspace(a)
        val ed = WorkspaceEditor(ws)
        val page = ed.addPage()!! // screenId=3, order=2
        assertTrue(ed.placeFirstFree(a, CONTAINER_DESKTOP, page.screenId))
        val reverse = ReversePlanner.reverse(ws, DiffCalculator.plan(ws, 100))
        // 反向先把 a 迁回原页，最后删新页
        assertEquals(
            listOf<DbOp>(
                DbOp.UpdatePosition(1, CONTAINER_DESKTOP, 1, 0, 0),
                DbOp.DeleteScreen(3),
            ),
            reverse.ops,
        )
    }

    @Test
    fun reverseOfFolderDeleteRestoresFolderAndChildren() {
        val f = folder(9, 0, 0)
        val c = app(10, 0, 0, container = 9, readOnly = true) // 只读子项迁不出 → 级联删除
        val ws = workspace(f, c)
        WorkspaceEditor(ws).delete(f, 1)
        val reverse = ReversePlanner.reverse(ws, DiffCalculator.plan(ws, 100))
        val inserts = reverse.ops.filterIsInstance<DbOp.Insert>()
        assertEquals(2, inserts.size)
        val child = inserts.map { it.row }.single { it.id == 10L }
        assertEquals(9L, child.container)
        assertEquals("app10", child.title)
        assertTrue(inserts.any { it.row.id == 9L && it.row.itemType == ItemType.FOLDER })
    }

    @Test
    fun reverseOfMoveIntoNewFolderKeepsOrder() {
        val a = app(1, 0, 0)
        val b = app(2, 1, 0)
        val ws = workspace(a, b)
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.dropOnItem(a, b)) // 合成新文件夹（temp id），a、b 进夹
        val reverse = ReversePlanner.reverse(ws, DiffCalculator.plan(ws, 100))
        // 反向：正序是 [Insert 夹, U(a), U(b)]，倒序取逆 → b 先回原格、a 后回、最后删夹（真实 id=101）
        assertEquals(
            listOf<DbOp>(
                DbOp.UpdatePosition(b.dbId, CONTAINER_DESKTOP, 1, 1, 0),
                DbOp.UpdatePosition(a.dbId, CONTAINER_DESKTOP, 1, 0, 0),
                DbOp.Delete(101),
            ),
            reverse.ops,
        )
    }

    // ---------- WorkspaceDiff ----------

    private fun snapshot(vararg items: ItemState, pages: List<ScreenPage> = listOf(ScreenPage(1, 0))): Workspace =
        Workspace(GridConfig(), pages.toMutableList(), items.toMutableList())

    @Test
    fun diffCleanWhenIdentical() {
        val a = app(1, 0, 0)
        val before = snapshot(a)
        val after = snapshot(app(1, 0, 0))
        assertTrue(WorkspaceDiff.summarize(before, after).isEmpty())
    }

    @Test
    fun diffDetectsMoveAddRemoveRetitle() {
        val before = snapshot(app(1, 0, 0), app(2, 1, 0), app(3, 2, 0))
        val moved = app(1, 3, 3)
        val after = snapshot(moved, app(4, 0, 1), app(3, 2, 0))
        val summary = WorkspaceDiff.summarize(before, after)
        assertTrue(summary.contains("位置变动 1 项"))
        assertTrue(summary.contains("新增 1 项"))
        assertTrue(summary.contains("移除 1 项"))
    }

    @Test
    fun diffDetectsTitleAndSpan() {
        val before = snapshot(app(1, 0, 0))
        val renamed = app(1, 0, 0).also { it.title = "改名" }
        val after = snapshot(renamed)
        val summary = WorkspaceDiff.summarize(before, after)
        assertTrue(summary.contains("改名 1 项"))
    }

    @Test
    fun diffDetectsPageChanges() {
        val before = snapshot(app(1, 0, 0), pages = listOf(ScreenPage(1, 0), ScreenPage(2, 1)))
        val after = snapshot(app(1, 0, 0), pages = listOf(ScreenPage(1, 0), ScreenPage(3, 1)))
        val summary = WorkspaceDiff.summarize(before, after)
        assertTrue(summary.single().startsWith("页面变化"))
    }
}
