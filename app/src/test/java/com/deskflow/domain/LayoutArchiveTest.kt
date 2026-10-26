package com.deskflow.domain

import com.deskflow.domain.layout.LayoutArchiveCodec
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

class LayoutArchiveTest {

    private fun app(id: Long, x: Int, y: Int, screen: Int = 1, title: String = "app$id") =
        ItemState(dbId = id, title = title, intent = "pkg$id/.Main", itemCode = ItemType.APP,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = screen, cellX = x, cellY = y)

    private fun sample(): Workspace {
        val folder = ItemState(dbId = 9, title = "工具", intent = "", itemCode = ItemType.FOLDER,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 0, cellY = 1)
        val child = ItemState(dbId = 10, title = "计算器", intent = "pkg10/.Calc", itemCode = ItemType.APP,
            iconPackage = null, iconResource = null, container = folder.dbId, screen = 1, cellX = 0, cellY = 0)
        val widget = ItemState(dbId = 11, title = "时钟", intent = "pkg11/.Clock", itemCode = ItemType.APP,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 2, cellY = 0,
            spanX = 2, spanY = 1, readOnly = true)
        val dock = ItemState(dbId = 12, title = "电话", intent = "pkg12/.Dial", itemCode = ItemType.APP,
            iconPackage = null, iconResource = null, container = CONTAINER_DOCK, screen = 0, cellX = 0, cellY = 0)
        return Workspace(
            GridConfig(columns = 5, rows = 9, dockCells = 5),
            mutableListOf(ScreenPage(1, 0), ScreenPage(2, 1)),
            mutableListOf(app(1, 0, 0), app(2, 1, 0), app(3, 0, 0, screen = 2), folder, child, widget, dock),
        )
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val ws = sample()
        val decoded = LayoutArchiveCodec.decode(LayoutArchiveCodec.encode(ws, "2026-10-27 12:00")).getOrThrow()

        assertEquals(LayoutArchiveCodec.VERSION, decoded.version)
        assertEquals("2026-10-27 12:00", decoded.exportedAt)
        assertEquals(5, decoded.grid.columns)
        assertEquals(9, decoded.grid.rows)
        assertEquals(2, decoded.pages.size)

        val page1 = decoded.pages[0]
        // widget（时钟）也是普通条目，与 app 同列
        assertEquals(listOf("app1", "app2", "时钟"), page1.entries.map { it.title }.sorted())
        assertEquals("app1", page1.entries.first { it.dbId == 1L }.title)
        val folder = page1.folders.single()
        assertEquals("工具", folder.title)
        assertEquals(0, folder.cellX)
        assertEquals(1, folder.cellY)
        assertEquals(listOf("计算器"), folder.children.map { it.title })

        // widget 带 kind 与 span
        val widget = page1.entries.first { it.dbId == 11L }
        assertEquals("widget", widget.kind)
        assertEquals(2, widget.spanX)

        assertEquals(listOf("电话"), decoded.dock.map { it.title })
    }

    @Test
    fun decodeRejectsGarbageAndFutureVersion() {
        assertTrue(LayoutArchiveCodec.decode("not json").isFailure)
        assertTrue(LayoutArchiveCodec.decode("""{"version":99}""").isFailure)
        assertTrue(LayoutArchiveCodec.decode(LayoutArchiveCodec.encode(sample(), "")).isSuccess)
    }
}
