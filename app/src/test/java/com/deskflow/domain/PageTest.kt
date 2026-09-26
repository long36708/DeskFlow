package com.deskflow.domain

import com.deskflow.domain.layout.DiffCalculator
import com.deskflow.domain.layout.DbOp
import com.deskflow.domain.layout.WorkspaceEditor
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.GridConfig
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import com.deskflow.domain.model.ScreenPage
import com.deskflow.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTest {

    private fun app(id: Long, x: Int = 0, y: Int = 0, screen: Int = 1, readOnly: Boolean = false, itemCode: Int = ItemType.APP) =
        ItemState(
            dbId = id, title = "app$id", intent = "pkg$id/.Main", itemCode = itemCode,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = screen,
            cellX = x, cellY = y, readOnly = readOnly,
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

    private fun fillPage(ws: Workspace, screen: Int, count: Int, startId: Long = 1000): List<ItemState> {
        val grid = ws.grid
        val out = ArrayList<ItemState>()
        var n = 0
        for (y in 0 until grid.rows) for (x in 0 until grid.columns) {
            if (n++ >= count) return out
            val item = app(startId + out.size, x, y, screen)
            out += item
            ws.items += item
        }
        return out
    }

    @Test
    fun addPageAppendsWithLastOrder() {
        val ws = workspace(listOf(1, 2))
        val ed = WorkspaceEditor(ws)
        val page = ed.addPage()
        assertNotNull(page)
        assertEquals(3, page!!.screenId)
        assertEquals(2, page.order)
        assertTrue(page.isNew)
        assertEquals(3, ws.pages.size)
    }

    @Test
    fun addPageDoesNotReuseDeletedScreenId() {
        val ws = workspace(listOf(1, 2, 3), app(1, screen = 3))
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.deletePage(3))
        val page = ed.addPage()
        assertNotNull(page)
        assertEquals(4, page!!.screenId)
    }

    @Test
    fun deletePageMigratesItsItems() {
        val ws = workspace(listOf(1, 2))
        val items = (1L..3L).map { app(it, screen = 2) }
        items.forEach { ws.items += it }
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.deletePage(2))
        assertTrue(ws.pages.first { it.screenId == 2 }.isDeleted)
        items.forEach { assertEquals(1, it.screen) }
        assertEquals(3, ws.pageItems(1).size)
        assertEquals(0, ws.pageItems(2).size)
        // 剩余页 order 重新紧凑化
        assertEquals(0, ws.pages.first { it.screenId == 1 }.order)
    }

    @Test
    fun deletePageRefusesWhenTargetPagesFull() {
        val ws = workspace(listOf(1, 2))
        fillPage(ws, 1, 26)
        val items = (1L..5L).map { app(it, screen = 2) }
        items.forEach { ws.items += it }
        val ed = WorkspaceEditor(ws)
        assertFalse(ed.deletePage(2))
        // 拒绝时不能留下半迁移的状态
        items.forEach { assertEquals(2, it.screen) }
        assertFalse(ws.pages.first { it.screenId == 2 }.isDeleted)
    }

    @Test
    fun deletePageRefusesWhenPageHasWidget() {
        val ws = workspace(listOf(1, 2))
        ws.items += app(9, screen = 2, readOnly = true, itemCode = 2)
        val ed = WorkspaceEditor(ws)
        assertFalse(ed.deletePage(2))
    }

    @Test
    fun deletePageRefusesWhenOnlyOnePageLeft() {
        val ws = workspace(listOf(1), app(1))
        val ed = WorkspaceEditor(ws)
        assertFalse(ed.deletePage(1))
    }

    @Test
    fun movePageSwapsOrder() {
        val ws = workspace(listOf(1, 2, 3))
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.movePage(3, -1))
        assertEquals(0, ws.pages.first { it.screenId == 1 }.order)
        assertEquals(1, ws.pages.first { it.screenId == 3 }.order)
        assertEquals(2, ws.pages.first { it.screenId == 2 }.order)

        assertTrue(ed.movePage(3, -1))
        assertEquals(0, ws.pages.first { it.screenId == 3 }.order)
        assertFalse(ed.movePage(3, -1)) // 已在首位

        assertTrue(ed.movePage(3, 1)) // 再后移回去
        assertEquals(1, ws.pages.first { it.screenId == 3 }.order)
    }

    @Test
    fun diffPlanEmitsScreenOpsInSafeOrder() {
        val ws = workspace(listOf(1, 2))
        val ed = WorkspaceEditor(ws)
        ed.addPage()
        ed.deletePage(1)
        val plan = DiffCalculator.plan(ws, 100)

        assertEquals(1, plan.screenAdded)
        assertEquals(1, plan.screenRemoved)
        // 新页先建、页删除放最后
        assertTrue(plan.ops.first() is DbOp.InsertScreen)
        assertTrue(plan.ops.last() is DbOp.DeleteScreen)
        val insertScreen = plan.ops.first() as DbOp.InsertScreen
        assertEquals(3, insertScreen.screenId)
        val deleteScreen = plan.ops.last() as DbOp.DeleteScreen
        assertEquals(1, deleteScreen.screenId)
        assertTrue(plan.toString().contains("新增页"))
    }

    @Test
    fun diffPlanEmitsScreenOrderUpdate() {
        val ws = workspace(listOf(1, 2))
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.movePage(2, -1))
        val plan = DiffCalculator.plan(ws, 100)
        val orders = plan.ops.filterIsInstance<DbOp.UpdateScreenOrder>()
        assertEquals(2, orders.size)
        assertEquals(1, orders.first { it.screenId == 1 }.order)
        assertEquals(0, orders.first { it.screenId == 2 }.order)
    }
}
