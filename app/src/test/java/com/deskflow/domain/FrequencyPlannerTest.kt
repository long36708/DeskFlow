package com.deskflow.domain

import com.deskflow.domain.classifier.FrequencyPlanner
import com.deskflow.domain.classifier.FrequencyPlanner.COLD_FOLDER_LABEL
import com.deskflow.domain.classifier.FrequencyPlanner.MIN_COLD_ITEMS
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

class FrequencyPlannerTest {

    private fun app(
        id: Long,
        x: Int,
        y: Int,
        screen: Int = 1,
        pkg: String = "pkg$id",
        container: Long = CONTAINER_DESKTOP,
    ) = ItemState(dbId = id, title = "app$id", intent = "$pkg/.Main", itemCode = ItemType.APP,
        iconPackage = null, iconResource = null, container = container, screen = screen, cellX = x, cellY = y)

    private fun workspace(vararg items: ItemState, pages: List<ScreenPage> = listOf(ScreenPage(1, 0), ScreenPage(2, 1))) =
        Workspace(GridConfig(), pages.toMutableList(), items.toMutableList())

    /** 全部冷门（计数 map 为空 = 谁都没启动过）。 */
    private fun allCold() = emptyMap<String, Int>()

    @Test
    fun collectsOnlyColdLooseDesktopApps() {
        val hot = app(1, 0, 0)
        val coldA = app(2, 1, 0)
        val coldB = app(3, 2, 0, screen = 2)
        val dock = app(4, 0, 0, container = CONTAINER_DOCK)
        val widget = ItemState(dbId = 5, title = "时钟", intent = "", itemCode = ItemType.APP,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 3, cellY = 0,
            readOnly = true)
        val folder = ItemState(dbId = 6, title = "工具", intent = "", itemCode = ItemType.FOLDER,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 0, cellY = 1)
        val inFolder = app(7, 0, 0, container = 6)

        val plan = FrequencyPlanner.plan(
            workspace(hot, coldA, coldB, dock, widget, folder, inFolder),
            launchCounts = mapOf("pkg1" to 99), // pkg2/pkg3 不在 map = 零启动
        )

        assertEquals(2, plan.coldCount)
        val members = plan.slots.single().members
        assertEquals(listOf(2L, 3L), members.map { it.dbId }) // 页序在前，首页冷门也参与
    }

    @Test
    fun fewerThanMinColdItemsNotCollected() {
        val plan = FrequencyPlanner.plan(workspace(app(1, 0, 0)), allCold())
        assertEquals(1, plan.coldCount)
        assertTrue(plan.slots.isEmpty())
    }

    @Test
    fun chunksByCapacityAndMergesSmallTail() {
        // 容量 9：12 个冷门 = 9 + 3，尾组 3 < 4 并回上一夹 → 单夹 12 个
        val items = (1L..12L).map { app(it, (it % 4).toInt(), (it / 4).toInt()) }
        val plan = FrequencyPlanner.plan(workspace(*items.toTypedArray()), allCold())
        assertEquals(1, plan.slots.size)
        assertEquals(12, plan.slots.single().members.size)
        assertEquals(COLD_FOLDER_LABEL, plan.slots.single().title)

        // 20 个 = 9 + 9 + 2 → 2 并回 → 9 + 11 两夹，续编号
        val many = (1L..20L).map { app(it, (it % 4).toInt(), (it / 4).toInt()) }
        val plan2 = FrequencyPlanner.plan(workspace(*many.toTypedArray()), allCold())
        assertEquals(listOf(9, 11), plan2.slots.map { it.members.size })
        assertEquals("$COLD_FOLDER_LABEL 2", plan2.slots[1].title)
    }

    @Test
    fun folderTitleAvoidsExistingCollision() {
        val existing = ItemState(dbId = 9, title = COLD_FOLDER_LABEL, intent = "", itemCode = ItemType.FOLDER,
            iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1, cellX = 0, cellY = 2)
        val items = (1L..MIN_COLD_ITEMS.toLong()).map { app(it, it.toInt(), 0) }
        val plan = FrequencyPlanner.plan(workspace(existing, *items.toTypedArray()), allCold())
        assertEquals("$COLD_FOLDER_LABEL 2", plan.slots.single().title)
    }

    @Test
    fun placesOnLastPageFreeCellAndCountsFreedCells() {
        // 第 2 页（最后）右下角留空，夹应落在第 2 页；冷门项自己的格子视为已腾出
        val cold = listOf(app(1, 0, 0), app(2, 1, 0))
        val keeper = app(9, 0, 0, screen = 2)
        val plan = FrequencyPlanner.plan(workspace(*cold.toTypedArray(), keeper), mapOf("pkg9" to 5))
        val slot = plan.slots.single()
        assertEquals(2, slot.screenId)
        // 第 2 页 keeper 占 (0,0)，冷门项腾出 (1,0)(2,0) → 首个空格 (1,0)
        assertEquals(1, slot.cellX)
        assertEquals(0, slot.cellY)
        assertTrue(plan.newScreens.isEmpty())
    }

    @Test
    fun fallsBackToEarlierPageWhenLastPageFull() {
        // 最后页（2）被 keeper 填满；冷门项在页 1，其格子腾出 → 夹应落在页 1 的首个空格
        val keepers = mutableListOf<ItemState>()
        var id = 100L
        for (y in 0 until 7) for (x in 0 until 4) {
            keepers += ItemState(dbId = id++, title = "k$id", intent = "k$id/.M", itemCode = ItemType.APP,
                iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 2,
                cellX = x, cellY = y)
        }
        for (y in 0 until 7) for (x in 0 until 4) {
            if (x <= 1 && y == 0) continue // 冷门项腾出的两格
            keepers += ItemState(dbId = id++, title = "k$id", intent = "k$id/.M", itemCode = ItemType.APP,
                iconPackage = null, iconResource = null, container = CONTAINER_DESKTOP, screen = 1,
                cellX = x, cellY = y)
        }
        val cold = listOf(app(1, 0, 0), app(2, 1, 0))
        val counts = keepers.associate { "k${it.dbId}" to 5 }
        val plan = FrequencyPlanner.plan(workspace(*keepers.toTypedArray(), *cold.toTypedArray()), counts)
        val slot = plan.slots.single()
        assertEquals(1, slot.screenId)
        assertEquals(0, slot.cellX)
        assertEquals(0, slot.cellY)
        assertTrue(plan.newScreens.isEmpty())
    }

    @Test
    fun applyCreatesFolderViaEditorAndMembersMoveIn() {
        val cold = listOf(app(1, 0, 0), app(2, 1, 0))
        val ws = workspace(*cold.toTypedArray())
        val plan = FrequencyPlanner.plan(ws, allCold())
        FrequencyPlanner.apply(plan, WorkspaceEditor(ws))
        val folder = ws.liveItems.single { it.isFolder }
        assertEquals(COLD_FOLDER_LABEL, folder.title)
        assertEquals(2, ws.childrenOf(folder).size)
        assertTrue(cold.all { it.container == folder.dbId })
    }
}
