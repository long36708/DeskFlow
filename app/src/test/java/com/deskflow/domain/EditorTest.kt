package com.deskflow.domain

import com.deskflow.domain.layout.DiffCalculator
import com.deskflow.domain.layout.DbOp
import com.deskflow.domain.layout.WorkspaceEditor
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.CONTAINER_DOCK
import com.deskflow.domain.model.GridConfig
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import com.deskflow.domain.model.MAX_TITLE_LEN
import com.deskflow.domain.model.ScreenPage
import com.deskflow.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTest {

    private fun app(id: Long, x: Int, y: Int, screen: Int = 1, container: Long = CONTAINER_DESKTOP) =
        ItemState(dbId = id, title = "app$id", intent = "pkg$id/.Main", itemCode = ItemType.APP,
            iconPackage = null, iconResource = null, container = container, screen = screen, cellX = x, cellY = y)

    private fun workspace(vararg items: ItemState): Workspace {
        items.forEach {
            it.originalContainer = it.container
            it.originalScreen = it.screen
            it.originalX = it.cellX
            it.originalY = it.cellY
        }
        return Workspace(GridConfig(), mutableListOf(ScreenPage(1, 0), ScreenPage(2, 1)), items.toMutableList())
    }

    @Test
    fun placeAtMovesItem() {
        val a = app(1, 0, 0)
        val ws = workspace(a)
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.placeAt(a, CONTAINER_DESKTOP, 1, 2, 3))
        assertEquals(2, a.cellX)
        assertEquals(3, a.cellY)
    }

    @Test
    fun placeAtRejectsOccupiedAndSwapWorks() {
        val a = app(1, 0, 0)
        val b = app(2, 1, 0)
        val ws = workspace(a, b)
        val ed = WorkspaceEditor(ws)
        // swap a into b's cell
        assertTrue(ed.placeAt(a, CONTAINER_DESKTOP, 1, 1, 0))
        assertEquals(1 to 0, a.cellX to a.cellY)
        assertEquals(0 to 0, b.cellX to b.cellY)
    }

    @Test
    fun outOfBoundsRejected() {
        val a = app(1, 0, 0)
        val ed = WorkspaceEditor(workspace(a))
        assertFalse(ed.placeAt(a, CONTAINER_DESKTOP, 1, 4, 0))
        assertFalse(ed.placeAt(a, CONTAINER_DESKTOP, 1, 0, 7))
    }

    @Test
    fun dropOnAppCreatesFolderWithBothChildren() {
        val a = app(1, 0, 0)
        val b = app(2, 1, 0)
        val ws = workspace(a, b)
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.dropOnItem(a, b))
        val folders = ws.liveItems.filter { it.isFolder }
        assertEquals(1, folders.size)
        val folder = folders.single()
        assertTrue(folder.isNew)
        val children = ws.childrenOf(folder)
        assertEquals(setOf(1L, 2L), children.map { it.dbId }.toSet())
        assertEquals(folder.cellX, 1)
    }

    @Test
    fun dropIntoExistingFolder() {
        val folder = ItemState(dbId = 9, title = "f", intent = "", itemCode = ItemType.FOLDER,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 3, cellY = 0)
        val a = app(1, 0, 0)
        val ws = workspace(folder, a)
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.dropOnItem(a, folder))
        assertEquals(9L, a.container)
    }

    @Test
    fun deleteFolderRestoresChildren() {
        val folder = ItemState(dbId = 9, title = "f", intent = "", itemCode = ItemType.FOLDER,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 3, cellY = 0)
        val child = app(1, 0, 0, container = 9L)
        val other = app(2, 0, 1)
        val ws = workspace(folder, child, other)
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.delete(folder, restoreScreen = 2))
        assertTrue(folder.isDeleted)
        assertFalse(child.isDeleted)
        assertEquals(CONTAINER_DESKTOP, child.container)
        // child must not land on the occupied (0,1) cell
        assertFalse(child.cellX == 0 && child.cellY == 1)
    }

    @Test
    fun readOnlyItemCannotMove() {
        val w = ItemState(dbId = 5, title = "widget", intent = "", itemCode = 2,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 0, cellY = 0,
            spanX = 2, spanY = 2, readOnly = true)
        val ws = workspace(w)
        val ed = WorkspaceEditor(ws)
        assertFalse(ed.placeAt(w, CONTAINER_DESKTOP, 1, 2, 2))
        val a = app(1, 2, 2)
        val ws2 = workspace(w, a)
        val ed2 = WorkspaceEditor(ws2)
        // widget occupies (0..1, 0..1): moving onto it (swap) must fail
        assertFalse(ed2.placeAt(a, CONTAINER_DESKTOP, 1, 1, 1))
        assertTrue(ed2.placeAt(a, CONTAINER_DESKTOP, 1, 3, 3))
        // widget cells block first free cell search
        val free = ed2.findFreeCell(CONTAINER_DESKTOP, 1)
        assertNotNull(free)
    }

    @Test
    fun diffCalculatorRemapsTempIdsAndCascades() {
        val a = app(1, 0, 0)
        val b = app(2, 1, 0)
        val c = app(3, 2, 0)
        val ws = workspace(a, b, c)
        val ed = WorkspaceEditor(ws)
        ed.dropOnItem(a, b) // new folder temp id -1 with children a,b
        ed.placeAt(c, CONTAINER_DESKTOP, 2, 0, 0)
        val plan = DiffCalculator.plan(ws, maxExistingId = 100)
        val inserts = plan.ops.filterIsInstance<DbOp.Insert>()
        assertEquals(1, inserts.size)
        val folderRow = inserts.single().row
        assertEquals(101L, folderRow.id)
        // children must reference the real folder id, not temp -1
        val updates = plan.ops.filterIsInstance<DbOp.UpdatePosition>().associateBy { it.id }
        assertEquals(101L, updates.getValue(1L).container)
        assertEquals(101L, updates.getValue(2L).container)
        assertEquals(CONTAINER_DESKTOP, updates.getValue(3L).container)
        assertEquals(2, updates.getValue(3L).screen)
        assertEquals(0, plan.deletedIds.size)
        assertNotNull(ws.byId(-1)) // folder keeps its temp id in workspace until applied
    }

    @Test
    fun dockMoveClampsSpan() {
        val a = app(1, 0, 0)
        val ws = workspace(a)
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.placeAt(a, CONTAINER_DOCK, 0, 3, 0))
        assertEquals(CONTAINER_DOCK, a.container)
        assertEquals(3, a.cellX)
        assertFalse(ed.placeAt(a, CONTAINER_DOCK, 0, 5, 0))
    }

    // ---------- folder rename ----------

    private fun folderItem(id: Long, title: String) =
        ItemState(dbId = id, title = title, intent = "", itemCode = ItemType.FOLDER,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 0, cellY = 0)

    @Test
    fun renameFolderProducesSingleUpdateTitleOp() {
        val f = folderItem(9, "工具")
        val ws = workspace(f)
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.renameFolder(f, "  影音娱乐  "))
        assertEquals("影音娱乐", f.title)

        val plan = DiffCalculator.plan(ws, 100)
        assertEquals(listOf(9L to "影音娱乐"), plan.ops.filterIsInstance<DbOp.UpdateTitle>().map { it.id to it.title })
        assertEquals(0, plan.moved)
    }

    @Test
    fun renameTrimsLengthAndRejectsBlank() {
        val f = folderItem(9, "工具")
        val ed = WorkspaceEditor(workspace(f))
        assertFalse(ed.renameFolder(f, "   "))
        assertEquals("工具", f.title)
        assertTrue(ed.renameFolder(f, "一二三四五六七八九十一二三四五"))
        assertEquals(MAX_TITLE_LEN, f.title.length)
    }

    @Test
    fun renameRejectsNonFolderAndNewFolderGoesThroughInsert() {
        val a = app(1, 0, 0)
        val b = app(2, 1, 0)
        val ws = workspace(a, b)
        val ed = WorkspaceEditor(ws)
        assertFalse(ed.renameFolder(a, "x"))

        ed.dropOnItem(a, b)
        val folder = ws.liveItems.single { it.isFolder }
        assertTrue(ed.renameFolder(folder, "社交"))
        val plan = DiffCalculator.plan(ws, 100)
        assertEquals("社交", plan.ops.filterIsInstance<DbOp.Insert>().single().row.title)
        assertTrue(plan.ops.none { it is DbOp.UpdateTitle }) // 新行走 INSERT，不再补 UPDATE
    }

    @Test
    fun createdFolderCarriesGivenTitle() {
        val members = listOf(app(1, 0, 0), app(2, 1, 0), app(3, 2, 0))
        val ws = workspace(*members.toTypedArray())
        val ed = WorkspaceEditor(ws)
        val folder = ed.createFolderAt(0, 0, CONTAINER_DESKTOP, 1, members, "游戏")
        assertEquals("游戏", folder.title)
        assertEquals(3, ws.childrenOf(folder).size)
    }

    // ---------- add app to desktop ----------

    @Test
    fun addAppPlacesNewItemOnFirstFreeCellAndInsertsItemType30() {
        val occupied = app(1, 0, 0)
        val ws = workspace(occupied)
        val ed = WorkspaceEditor(ws)
        val added = ed.addApp("com.tencent.mm/com.tencent.mm.ui.LauncherUI", "微信", CONTAINER_DESKTOP, 1)
        assertNotNull(added)
        assertTrue(added!!.isNew)
        assertEquals(ItemType.APP, added.itemCode) // 30
        // (0,0) 被占，首个空位是 (1,0)
        assertEquals(1 to 0, added.cellX to added.cellY)
        assertEquals(101L, DiffCalculator.plan(ws, 100).let {
            val insert = it.ops.filterIsInstance<DbOp.Insert>().single()
            assertEquals("com.tencent.mm/com.tencent.mm.ui.LauncherUI", insert.row.intent)
            assertEquals(30, insert.row.itemType)
            insert.row.id
        })
    }

    @Test
    fun addAppRejectsMalformedIntent() {
        val ed = WorkspaceEditor(workspace(app(1, 0, 0)))
        assertNull(ed.addApp("", "x", CONTAINER_DESKTOP, 1))
        assertNull(ed.addApp("com.tencent.mm", "x", CONTAINER_DESKTOP, 1)) // 缺 /activity
    }

    @Test
    fun addAppReturnsNullWhenPageFull() {
        val cells = (0 until 7).flatMap { y -> (0 until 4).map { x -> x to y } }
        val items = cells.mapIndexed { i, (x, y) -> app((100 + i).toLong(), x, y) }
        val ed = WorkspaceEditor(workspace(*items.toTypedArray()))
        assertNull(ed.addApp("com.tencent.mm/.LauncherUI", "微信", CONTAINER_DESKTOP, 1))
        // 换 Dock 页仍有 5 个空位
        assertNotNull(ed.addApp("com.tencent.mm/.LauncherUI", "微信", CONTAINER_DOCK, 0))
    }
}
