package com.deskflow.domain

import com.deskflow.data.adapter.vivo.VivoDesktopStyles
import com.deskflow.data.adapter.vivo.VivoDesktopStyles.DRAWER
import com.deskflow.data.adapter.vivo.VivoDesktopStyles.STANDARD
import com.deskflow.domain.classifier.AutoArranger
import com.deskflow.domain.layout.DiffCalculator
import com.deskflow.domain.layout.DbOp
import com.deskflow.domain.layout.WorkspaceEditor
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.CONTAINER_DOCK
import com.deskflow.domain.model.GridConfig
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import com.deskflow.domain.model.ScreenPage
import com.deskflow.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉风格（desktopStyle.currentDesktop=1002）桌面：数据在 location_jan_drawer / screen_jan_drawer，
 * 桌面通常只有 1 页、图标全部算首页；_id 与标准表共享命名空间。
 */
class DrawerTest {

    private fun app(
        id: Long,
        pkg: String,
        title: String = "app$id",
        x: Int = 0,
        y: Int = 0,
        screen: Int = 1,
        container: Long = CONTAINER_DESKTOP,
        itemCode: Int = ItemType.APP,
    ) = ItemState(
        dbId = id, title = title, intent = "$pkg/.Main", itemCode = itemCode,
        iconPackage = null, iconResource = null, container = container, screen = screen, cellX = x, cellY = y,
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

    // ---------- desktopStyle 值 → 表组映射 ----------

    @Test
    fun styleValueMapsToTablesAndUnknownIsRejected() {
        // 表缺失（null）按标准处理；1000 标准、1002 抽屉；其余一律不猜表
        assertSame(STANDARD, VivoDesktopStyles.resolve(null))
        assertSame(STANDARD, VivoDesktopStyles.resolve(1000L))
        assertSame(DRAWER, VivoDesktopStyles.resolve(1002L))
        assertNull(VivoDesktopStyles.resolve(1001L))
        assertNull(VivoDesktopStyles.resolve(2000L))
        assertEquals("location_jan_drawer", DRAWER.locationTable)
        assertEquals("screen_jan_drawer", DRAWER.screenTable)
    }

    // ---------- 跨样式共享的 _id 命名空间 ----------

    @Test
    fun newFolderIdSkipsIdsOccupiedByStandardBackupTable() {
        // 抽屉表里最大只有 27，但标准备用表已用到 361；调用方必须传全局 max，新行才不撞备用表
        val a = app(10L, "com.tencent.mm", "微信", x = 0, y = 0)
        val b = app(11L, "com.tencent.mm", "企业微信", x = 1, y = 0)
        val ws = workspace(listOf(1), a, b)
        val ed = WorkspaceEditor(ws)
        assertTrue(ed.dropOnItem(a, b))
        val plan = DiffCalculator.plan(ws, maxExistingId = 361L)

        val inserted = plan.ops.filterIsInstance<DbOp.Insert>().map { it.row.id }
        assertEquals(listOf(362L), inserted)
        val backupTableIds = (0L..361L).toSet() // 标准表 + attribute 的占用集
        assertTrue(inserted.none { it in backupTableIds })
    }

    // ---------- 单页抽屉桌面的一键整理语义 ----------

    @Test
    fun singlePageDrawerDesktopArrangesToNoOp() {
        val systemFolder = app(0L, "", "com.bbk.launcher2:string/folder_tools", x = 3, y = 2, itemCode = ItemType.FOLDER)
        val child = app(31L, "com.android.settings", "设置", x = 0, y = 0, container = 0L)
        val pinned = (10L..15L).map { app(it, "com.tencent.mm", "应用$it", x = ((it - 10) % 4).toInt(), y = ((it - 10) / 4).toInt()) }
        val dock = (20L..23L).map { app(it, "com.tencent.mm", "dock$it", x = (it - 20).toInt(), container = CONTAINER_DOCK, screen = 0) }
        val ws = workspace(listOf(1), *(((listOf(systemFolder, child) + pinned) + dock)).toTypedArray())

        // 只有一页时该页即首页，全部锁定：不散放、不建夹、不移位，即便同类 ≥4
        val result = AutoArranger.arrange(ws)
        assertEquals(0, result.looseCount)
        assertEquals(0, result.newFolderCount)
        assertEquals(0, result.movedCount)
        assertEquals(1 + pinned.size, result.lockedCount) // 桌面 7 项；Dock 不参与排布
        assertTrue(result.pages.single().placements.none { it.item.container == CONTAINER_DOCK })

        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        val plan = DiffCalculator.plan(ws, maxExistingId = 361L)
        assertTrue(plan.ops.isEmpty())
    }

    // ---------- 抽屉桌面出现多页时的散放项 ----------

    @Test
    fun looseItemsOnSecondDrawerPageStillGetCollected() {
        val home = app(1L, "com.tencent.mm", "微信", x = 0, y = 0, screen = 1)
        val socials = (10L..13L).map { app(it, "com.tencent.mm", "社交$it", x = ((it - 10) % 4).toInt(), y = 0, screen = 6) }
        val ws = workspace(listOf(1, 6), *(listOf(home) + socials).toTypedArray())

        val result = AutoArranger.arrange(ws)
        assertEquals(4, result.looseCount)
        assertEquals(1, result.newFolderCount)
        assertTrue(result.pages.first { it.screenId == 1 }.placements.none { it.isNewFolder })

        AutoArranger.apply(ws, result, WorkspaceEditor(ws))
        val created = ws.liveItems.single { it.isFolder }
        assertEquals(6, created.screen)
        assertEquals(4, ws.childrenOf(created).size)
    }
}
