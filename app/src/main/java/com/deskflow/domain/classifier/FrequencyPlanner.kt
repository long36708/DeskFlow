package com.deskflow.domain.classifier

import com.deskflow.domain.layout.WorkspaceEditor
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.Workspace

/** 一个待新建的「冷门」文件夹槽位：成员、标题与落定格位（screen/cell 由落位阶段回填）。 */
data class ColdFolderSlot(
    val title: String,
    val members: List<ItemState>,
    var screenId: Int = 0,
    var cellX: Int = 0,
    var cellY: Int = 0,
)

data class ColdCollectPlan(
    val slots: List<ColdFolderSlot>,
    /** 槽位需要的待新建页（临时负 screenId，[apply] 时换成真实 id）。 */
    val newScreens: List<Int> = emptyList(),
    /** 窗口期内零启动的散放应用总数（含因数量不足未收的）。 */
    val coldCount: Int,
) {
    val collectedCount: Int get() = slots.sumOf { it.members.size }
}

/**
 * 「冷门收夹」计划器：把近 N 天（使用统计窗口）零启动的**散放应用**收进以「冷门」命名的文件夹。
 *
 * 与 [AutoArranger] 的分工：一键整理按类别归拢非首页散放项；这里只认使用频率，且**首页也参与**
 * ——冷门收夹的价值正是回收首页的黄金格子。widget（只读）、既有文件夹、Dock 一律不动；
 * 一次 edit 落地、一次「撤销上一步」可回退，写库仍走「应用」链路。
 *
 * 刻意不做全量「按频率重排」：§11 已验证用户否决搬动既有布局的整理方式，冷门收夹是增量形态。
 */
object FrequencyPlanner {

    const val COLD_FOLDER_LABEL = "冷门"

    /** 少于该数量的冷门项不收——单项收夹没有意义，提示用户即可。 */
    const val MIN_COLD_ITEMS = 2

    /** 待新建页的临时 screenId 起点；与 AutoArranger 的 -200 命名空间区分开。 */
    private const val TEMP_SCREEN_START = -300

    fun plan(ws: Workspace, launchCounts: Map<String, Int>): ColdCollectPlan {
        val grid = ws.grid
        val cold = ws.liveItems
            .filter { it.container == CONTAINER_DESKTOP && !it.readOnly && !it.isFolder }
            .filter { (launchCounts[pkgOf(it)] ?: 0) == 0 }
            .sortedWith(compareBy({ it.screen }, { it.cellY }, { it.cellX }, { it.dbId }))
        if (cold.size < MIN_COLD_ITEMS) return ColdCollectPlan(emptyList(), coldCount = cold.size)

        val usedTitles = ws.liveItems.filter { it.isFolder }.map { it.title }.toMutableSet()
        val slots = cold.chunked(grid.folderCapacity.coerceAtLeast(1)).mergeUndersizedTail()
            .map { chunk -> ColdFolderSlot(claimLabel(usedTitles), chunk) }

        place(ws, cold, slots)
        return ColdCollectPlan(slots, coldCount = cold.size)
    }

    /** 把槽位落到 workspace：追加需要的页，逐槽建夹收员。整个 plan 一次 edit 落地 = 一次撤销。 */
    fun apply(plan: ColdCollectPlan, editor: WorkspaceEditor) {
        val screenMap = HashMap<Int, Int>()
        plan.newScreens.forEach { temp -> editor.addPage()?.let { screenMap[temp] = it.screenId } }
        plan.slots.forEach { slot ->
            val sid = screenMap[slot.screenId] ?: slot.screenId
            editor.createFolderAt(slot.cellX, slot.cellY, CONTAINER_DESKTOP, sid, slot.members, slot.title)
        }
    }

    // ---------- 内部 ----------

    private fun pkgOf(item: ItemState): String? =
        item.componentPackage()?.takeIf { it.isNotEmpty() } ?: item.iconPackage?.takeIf { it.isNotEmpty() }

    /**
     * 落位：占用图 = 桌面上除冷门项外的所有项（冷门项的格子已腾出）；从最后一页向前找首个空格，
     * 全满则分配临时负 id 待 [apply] 换成真实新页。
     */
    private fun place(ws: Workspace, cold: List<ItemState>, slots: List<ColdFolderSlot>) {
        val grid = ws.grid
        val coldIds = cold.map { it.dbId }.toHashSet()
        val screenIds = ws.pages.filter { !it.isDeleted }.sortedBy { it.order }.map { it.screenId }
        val occupancy = LinkedHashMap<Int, MutableSet<Long>>()
        screenIds.forEach { occupancy[it] = HashSet() }
        ws.liveItems
            .filter { it.container == CONTAINER_DESKTOP && it.dbId !in coldIds }
            .forEach { item ->
                val taken = occupancy[item.screen] ?: return@forEach
                for (dx in 0 until item.spanX) for (dy in 0 until item.spanY) {
                    taken += cellKey(item.cellX + dx, item.cellY + dy)
                }
            }

        val ordered = screenIds.asReversed()
        var newScreens = 0
        slots.forEach { slot ->
            val cell = ordered.asSequence()
                .map { sid -> sid to firstFree(grid.columns, grid.rows, occupancy.getValue(sid)) }
                .firstOrNull { it.second != null }
            if (cell != null) {
                val (sid, xy) = cell
                occupancy.getValue(sid) += cellKey(xy!!.first, xy.second)
                slot.screenId = sid
                slot.cellX = xy.first
                slot.cellY = xy.second
            } else {
                val sid = TEMP_SCREEN_START - newScreens
                newScreens++
                occupancy[sid] = HashSet()
                slot.screenId = sid
            }
        }
    }

    /** 「冷门」「冷门 2」「冷门 3」…避开既有文件夹同名。 */
    private fun claimLabel(used: MutableSet<String>): String {
        var n = 0
        while (true) {
            val candidate = if (n == 0) COLD_FOLDER_LABEL else "$COLD_FOLDER_LABEL ${n + 1}"
            if (used.add(candidate)) return candidate
            n++
        }
    }

    /** 尾组不足 [FOLDER_MIN_ITEMS] 个并回上一夹，与一键整理同规则（11 节实测 vivo 夹内装得下）。 */
    private fun List<List<ItemState>>.mergeUndersizedTail(): List<List<ItemState>> {
        if (size < 2 || last().size >= FOLDER_MIN_ITEMS) return this
        return dropLast(2) + listOf(this[size - 2] + last())
    }

    private fun cellKey(x: Int, y: Int): Long = y.toLong() * 1000 + x

    private fun firstFree(cols: Int, rows: Int, taken: Set<Long>): Pair<Int, Int>? {
        for (y in 0 until rows) for (x in 0 until cols) {
            if (cellKey(x, y) !in taken) return x to y
        }
        return null
    }
}
