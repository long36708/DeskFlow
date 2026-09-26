package com.deskflow.domain

import com.deskflow.domain.layout.DiffCalculator
import com.deskflow.domain.layout.DbOp
import com.deskflow.domain.layout.WorkspaceEditor
import com.deskflow.domain.layout.WorkspaceHistory
import com.deskflow.domain.layout.commitApplied
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

class HistoryTest {

    private fun app(id: Long, x: Int = 0, y: Int = 0, screen: Int = 1, container: Long = CONTAINER_DESKTOP) =
        ItemState(
            dbId = id, title = "app$id", intent = "pkg$id/.Main", itemCode = ItemType.APP,
            iconPackage = null, iconResource = null, container = container, screen = screen, cellX = x, cellY = y,
        )

    private fun workspace(pages: List<Int>, vararg items: ItemState): Workspace {
        items.forEach {
            it.originalContainer = it.container
            it.originalScreen = it.screen
            it.originalX = it.cellX
            it.originalY = it.cellY
        }
        return Workspace(
            GridConfig(),
            pages.mapIndexed { i, s -> ScreenPage(s, i) }.toMutableList(),
            items.toMutableList(),
        )
    }

    @Test
    fun commitAppliedReplacesTempIdsAndResetsBaseline() {
        val a = app(1, 0, 0)
        val b = app(2, 1, 0)
        val ws = workspace(listOf(1), a, b)
        val ed = WorkspaceEditor(ws)
        ed.dropOnItem(a, b) // 新文件夹 temp id -1
        val plan = DiffCalculator.plan(ws, 100)
        commitApplied(ws, plan)

        val folder = ws.liveItems.single { it.isFolder }
        assertEquals(101L, folder.dbId)
        assertFalse(folder.isNew)
        assertFalse(folder.positionChanged())
        assertEquals(101L, a.container)
        assertEquals(101L, b.container)
        assertFalse(a.positionChanged())
    }

    @Test
    fun secondPlanAfterCommitHasNoDuplicateInserts() {
        val a = app(1, 0, 0)
        val b = app(2, 1, 0)
        val c = app(3, 2, 0)
        val ws = workspace(listOf(1), a, b, c)
        val ed = WorkspaceEditor(ws)
        ed.dropOnItem(a, b)
        commitApplied(ws, DiffCalculator.plan(ws, 100))

        // 应用成功后再改一次，不能再产生 INSERT（否则会因主键冲突写库失败）
        ed.placeAt(c, CONTAINER_DESKTOP, 1, 3, 3)
        val second = DiffCalculator.plan(ws, 100)
        assertEquals(0, second.insertedIds.size)
        assertTrue(second.ops.none { it is DbOp.Insert })
        assertTrue(second.ops.any { it is DbOp.UpdatePosition })
    }

    @Test
    fun commitAppliedDropsDeletedRows() {
        val a = app(1)
        val b = app(2, 1, 0)
        val ws = workspace(listOf(1), a, b)
        val ed = WorkspaceEditor(ws)
        ed.delete(b, 1)
        commitApplied(ws, DiffCalculator.plan(ws, 100))
        assertFalse(ws.items.any { it.dbId == 2L })
        assertTrue(ws.items.any { it.dbId == 1L })
    }

    @Test
    fun commitAppliedResetsTitleBaseline() {
        val folder = ItemState(dbId = 9, title = "工具", intent = "", itemCode = ItemType.FOLDER,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 0, cellY = 0)
        val ws = workspace(listOf(1), folder)
        val ed = WorkspaceEditor(ws)
        ed.renameFolder(folder, "实用工具")
        assertEquals(1, DiffCalculator.plan(ws, 100).ops.count { it is DbOp.UpdateTitle })

        commitApplied(ws, DiffCalculator.plan(ws, 100))
        assertTrue(DiffCalculator.plan(ws, 100).ops.isEmpty())
    }

    @Test
    fun historyUndoRestoresFolderTitle() {
        val folder = ItemState(dbId = 9, title = "工具", intent = "", itemCode = ItemType.FOLDER,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 0, cellY = 0)
        val ws = workspace(listOf(1), folder)
        val ed = WorkspaceEditor(ws)
        val h = WorkspaceHistory(ws)
        h.push()
        ed.renameFolder(folder, "我的夹")
        assertEquals("我的夹", folder.title)

        assertTrue(h.undo())
        assertEquals("工具", ws.items.first { it.dbId == 9L }.title)
    }

    @Test
    fun historyUndoRestoresMovedItem() {
        val ws = workspace(listOf(1), app(1, 0, 0))
        val ed = WorkspaceEditor(ws)
        val h = WorkspaceHistory(ws)
        h.push()
        ed.placeAt(ws.items.first(), CONTAINER_DESKTOP, 1, 2, 3)
        assertEquals(2 to 3, ws.items.first().cellX to ws.items.first().cellY)

        assertTrue(h.undo())
        val restored = ws.items.first { it.dbId == 1L }
        assertEquals(0 to 0, restored.cellX to restored.cellY)
    }

    @Test
    fun historyUndoRemovesNewFolder() {
        val a = app(1)
        val b = app(2, 1, 0)
        val ws = workspace(listOf(1), a, b)
        val ed = WorkspaceEditor(ws)
        val h = WorkspaceHistory(ws)
        h.push()
        ed.dropOnItem(a, b)
        assertEquals(1, ws.liveItems.count { it.isFolder })

        assertTrue(h.undo())
        assertEquals(0, ws.liveItems.count { it.isFolder })
        assertEquals(CONTAINER_DESKTOP, ws.items.first { it.dbId == 1L }.container)
    }

    @Test
    fun historyUndoRestoresDeletedPage() {
        val ws = workspace(listOf(1, 2), app(1, screen = 2))
        val ed = WorkspaceEditor(ws)
        val h = WorkspaceHistory(ws)
        h.push()
        assertTrue(ed.deletePage(2))

        assertTrue(h.undo())
        assertEquals(2, ws.pages.size)
        assertFalse(ws.pages.any { it.isDeleted })
        assertEquals(2, ws.items.first { it.dbId == 1L }.screen)
    }

    @Test
    fun tempIdsSkipReservedContainerIds() {
        // 120 个新文件夹 → 会穿过 -100 / -101 这两个保留 container 值
        val items = (1L..240L).map { app(it) }
        val ws = workspace(listOf(1), *items.toTypedArray())
        val ed = WorkspaceEditor(ws)
        var i = 0
        while (i + 1 < items.size) {
            ed.dropOnItem(items[i], items[i + 1])
            i += 2
        }
        val tempIds = ws.items.map { it.dbId }.filter { it < 0 }.toSet()
        assertEquals(120, tempIds.size)
        assertFalse(tempIds.contains(CONTAINER_DESKTOP))
        assertFalse(tempIds.contains(CONTAINER_DOCK))
    }

    @Test
    fun historyUndoOnEmptyStackReturnsFalse() {
        val ws = workspace(listOf(1))
        val h = WorkspaceHistory(ws)
        assertFalse(h.canUndo)
        assertFalse(h.undo())
        h.push()
        assertTrue(h.canUndo)
    }
}
