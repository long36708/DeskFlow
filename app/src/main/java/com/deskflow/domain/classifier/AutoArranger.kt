package com.deskflow.domain.classifier

import com.deskflow.domain.layout.WorkspaceEditor
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.GridConfig
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import com.deskflow.domain.model.Workspace

/** 同类图标达到该数量才收进文件夹，否则桌面会被两两成的小夹子占满。 */
const val FOLDER_MIN_ITEMS = 4

data class Placement(
    val item: ItemState,
    val screen: Int,
    val cellX: Int,
    val cellY: Int,
    val category: Category,
    /**
     * 非空表示这一格是**待新建文件夹**槽位：[item] 只是承载标题的占位对象，不会进入 workspace，
     * 真正的行在 [AutoArranger.apply] 里由编辑器创建。
     */
    val members: List<ItemState> = emptyList(),
) {
    val isNewFolder: Boolean get() = members.isNotEmpty()

    /** 该槽位落定的图标数：单个图标为 1，文件夹为其成员数。 */
    val iconCount: Int get() = if (members.isEmpty()) 1 else members.size
}

data class ArrangedPage(val screenId: Int, val placements: List<Placement>) {
    /** 本页按类别聚合的概览，形如「社交 x12(2夹) / 影音 x5」。 */
    fun summary(): String {
        if (placements.isEmpty()) return "空页"
        return placements.groupBy { it.category }
            .toList()
            .sortedBy { it.first.order }
            .joinToString(" / ") { (c, list) ->
                val icons = list.sumOf { it.iconCount }
                val folders = list.count { it.isNewFolder }
                "${c.label} x$icons" + if (folders > 0) "（$folders 夹）" else ""
            }
    }
}

data class ArrangeResult(
    val pages: List<ArrangedPage>,
    val unplaced: List<ItemState>,
    val groups: List<Pair<Category, List<ItemState>>>,
    /** 钉选到首页的应用，按 PinnedApps 顺序，只含实际存在的。 */
    val pinned: List<ItemState> = emptyList(),
) {
    val placedCount: Int get() = pages.sumOf { page -> page.placements.sumOf { it.iconCount } }

    /** 本次将要新建的文件夹数。 */
    val newFolderCount: Int get() = pages.sumOf { page -> page.placements.count { it.isNewFolder } }

    fun folderCountFor(category: Category): Int =
        pages.sumOf { page -> page.placements.count { it.isNewFolder && it.category == category } }

    /** 相对原始位置真正发生位移的图标数（apply 前即可计算）。 */
    val movedCount: Int get() = pages.sumOf { page ->
        page.placements.sumOf { pl ->
            if (pl.isNewFolder) {
                pl.members.size // 收进文件夹必然改 container 与格内坐标，成员一律计为移位
            } else {
                val moved = pl.item.originalContainer != CONTAINER_DESKTOP ||
                    pl.item.originalScreen != pl.screen ||
                    pl.item.originalX != pl.cellX ||
                    pl.item.originalY != pl.cellY
                if (moved) 1 else 0
            }
        }
    }
}

/**
 * 桌面页重排：按类别分组，同类图标（未分类除外，且数量达到 [FOLDER_MIN_ITEMS]）收进以类别命名的文件夹，
 * 再按类别顺序铺满各页（行优先）。一个类别装进多夹时续编号「影音 2」。
 * 只读项（widget 等）保持原位并占位，Dock 不参与重排，既有文件夹整体移动且不并入新夹。
 * 钉选应用（[PinnedApps]）不参与分类与收夹，排在所有文件夹之后按固定顺序铺上首页。
 */
object AutoArranger {

    fun arrange(ws: Workspace, rules: CategoryRules = DefaultRules.rules): ArrangeResult {
        val grid = ws.grid
        // 已标记删除（尚未写库）的页不能参与排布，否则图标会被塞进用户看不见的页
        val screenIds = ws.pages.filter { !it.isDeleted }.map { it.screenId }
        val pinned = pinnedItems(ws)
        val pinnedSet = pinned.toHashSet()
        val desktopItems = ws.liveItems.filter { it.container == CONTAINER_DESKTOP && it !in pinnedSet }

        val fixed = LinkedHashMap<Int, Set<Long>>()
        screenIds.forEach { sid ->
            val taken = HashSet<Long>()
            desktopItems.filter { it.readOnly && it.screen == sid }.forEach { item ->
                for (dx in 0 until item.spanX) for (dy in 0 until item.spanY) {
                    taken += cellKey(item.cellX + dx, item.cellY + dy)
                }
            }
            fixed[sid] = taken
        }

        val groups = desktopItems.filter { !it.readOnly }
            .groupBy { categoryOf(it, rules) }
            .toList()
            .sortedWith(compareBy({ it.first.order }, { it.first.id }))
            .map { (cat, items) -> cat to items.sortedWith(compareBy({ it.isFolder }, { it.title }, { it.dbId })) }

        val slots = groups.flatMap { (cat, items) -> slotsOf(cat, items, grid.folderCapacity) }
        val (folderSlots, looseSlots) =
            slots.partition { it.folderTitle.isNotEmpty() || it.members.single().isFolder }
        val ordered = folderSlots + pinnedSlots(pinned, rules) + looseSlots

        // 放不下而保持原位的图标仍占着原来的格子；把它们标成占位后重排，否则会和已排好的图标重叠。
        // 每轮只会新增占位格（格子有限），故必然收敛
        var blocked = emptyMap<Int, Set<Long>>()
        var laid = layoutOf(screenIds, grid, fixed, blocked, ordered)
        while (laid.unplaced.isNotEmpty()) {
            val next = merge(blocked, originalCells(screenIds, laid.unplaced))
            if (next == blocked) break // 占位已收敛，说明确实放不下，保持现状
            blocked = next
            laid = layoutOf(screenIds, grid, fixed, blocked, ordered)
        }

        return ArrangeResult(laid.pages, laid.unplaced, groups, pinned)
    }

    /** 把排布结果写回 workspace：单项改坐标，文件夹槽位经编辑器真正建行并把成员收进去。 */
    fun apply(ws: Workspace, result: ArrangeResult, editor: WorkspaceEditor) {
        result.pages.forEach { page ->
            page.placements.forEach { pl ->
                if (pl.isNewFolder) {
                    editor.createFolderAt(pl.cellX, pl.cellY, CONTAINER_DESKTOP, pl.screen, pl.members, pl.item.title)
                } else {
                    pl.item.container = CONTAINER_DESKTOP
                    pl.item.screen = pl.screen
                    pl.item.cellX = pl.cellX
                    pl.item.cellY = pl.cellY
                }
            }
        }
    }

    // ---------- slot building ----------

    /** 待放置单位：单个图标（[members] 一项、[folderTitle] 为空），或一个待新建文件夹。 */
    private class Slot(val category: Category, val members: List<ItemState>, val folderTitle: String)

    private fun slotsOf(cat: Category, items: List<ItemState>, capacity: Int): List<Slot> {
        val single = { item: ItemState -> Slot(cat, listOf(item), "") }
        // 既有文件夹整体搬动，既不进新夹也不参与新夹计数
        val existingFolders = items.filter { it.isFolder }.map(single)
        val apps = items.filter { !it.isFolder }
        if (cat == Category.OTHER || apps.size < FOLDER_MIN_ITEMS) return apps.map(single) + existingFolders
        val folders = apps.chunked(capacity.coerceAtLeast(1)).mapIndexed { i, chunk ->
            // 装剩一个图标没必要单独成夹
            if (chunk.size == 1) Slot(cat, chunk, "") else Slot(cat, chunk, numberedLabel(cat.label, i))
        }
        return folders + existingFolders
    }

    /** 钉选槽：按 PinnedApps 顺序的单项槽位，仍带各自分类（仅用于预览概览着色）。 */
    private fun pinnedSlots(pinned: List<ItemState>, rules: CategoryRules): List<Slot> =
        pinned.map { Slot(categoryOf(it, rules), listOf(it), "") }

    /** 全 workspace 按包名找钉选项：跳过只读项与文件夹，重复包名只取第一个。 */
    private fun pinnedItems(ws: Workspace): List<ItemState> {
        val found = HashMap<String, ItemState>()
        ws.liveItems.forEach { item ->
            if (item.readOnly || item.isFolder) return@forEach
            val pkg = item.componentPackage()?.lowercase()?.trim() ?: return@forEach
            if (pkg !in found && pkg in PinnedApps.packages) found[pkg] = item
        }
        return PinnedApps.packages.mapNotNull { found[it] }
    }

    /** 首个文件夹用类别名，之后的续编号：影音 / 影音 2 / 影音 3。 */
    private fun numberedLabel(label: String, index: Int): String =
        if (index == 0) label else "$label ${index + 1}"

    /** 文件夹自身没有 intent，按标题认领类别（含我们自己生成的「影音 2」这类续编号）。 */
    private fun categoryOf(item: ItemState, rules: CategoryRules): Category =
        if (item.isFolder) Category.byLabel(item.title) ?: AppClassifier.classify(item, rules)
        else AppClassifier.classify(item, rules)

    // ---------- layout ----------

    private class LaidOut(val pages: List<ArrangedPage>, val unplaced: List<ItemState>)

    private fun layoutOf(
        screenIds: List<Int>,
        grid: GridConfig,
        fixed: Map<Int, Set<Long>>,
        blocked: Map<Int, Set<Long>>,
        slots: List<Slot>,
    ): LaidOut {
        val occupancy = LinkedHashMap<Int, MutableSet<Long>>()
        screenIds.forEach { sid ->
            occupancy[sid] = HashSet<Long>().apply {
                addAll(fixed[sid] ?: emptySet())
                addAll(blocked[sid] ?: emptySet())
            }
        }
        val byScreen = LinkedHashMap<Int, MutableList<Placement>>()
        screenIds.forEach { byScreen[it] = ArrayList() }
        val unplaced = ArrayList<ItemState>()
        var pageIdx = 0

        for (slot in slots) {
            var placed = false
            while (pageIdx < screenIds.size && !placed) {
                val sid = screenIds[pageIdx]
                val taken = occupancy.getValue(sid)
                val cell = firstFree(grid.columns, grid.rows, taken)
                if (cell == null) {
                    pageIdx++
                } else {
                    taken += cellKey(cell.first, cell.second)
                    val newFolder = slot.folderTitle.isNotEmpty()
                    byScreen.getValue(sid) += Placement(
                        item = if (newFolder) folderPlaceholder(slot.folderTitle) else slot.members.single(),
                        screen = sid,
                        cellX = cell.first,
                        cellY = cell.second,
                        category = slot.category,
                        members = if (newFolder) slot.members else emptyList(),
                    )
                    placed = true
                }
            }
            if (!placed) unplaced += slot.members
        }
        return LaidOut(screenIds.map { ArrangedPage(it, byScreen.getValue(it)) }, unplaced)
    }

    /** 文件夹槽位的占位对象：只带标题，不落库、不进 workspace。 */
    private fun folderPlaceholder(title: String): ItemState = ItemState(
        dbId = 0L,
        title = title,
        intent = "",
        itemCode = ItemType.FOLDER,
        iconPackage = null,
        iconResource = null,
        container = CONTAINER_DESKTOP,
        screen = 0,
        cellX = 0,
        cellY = 0,
        isNew = true,
    )

    private fun merge(base: Map<Int, Set<Long>>, extra: Map<Int, Set<Long>>): Map<Int, Set<Long>> {
        val out = HashMap<Int, MutableSet<Long>>()
        (base.keys + extra.keys).forEach { sid ->
            out[sid] = HashSet<Long>().apply {
                addAll(base[sid] ?: emptySet())
                addAll(extra[sid] ?: emptySet())
            }
        }
        return out
    }

    /** 未安排项的原始终态格子：它们会留在原地，必须从可用格子里扣掉。 */
    private fun originalCells(screenIds: List<Int>, items: List<ItemState>): Map<Int, Set<Long>> {
        val out = HashMap<Int, MutableSet<Long>>()
        items.forEach { item ->
            if (item.originalContainer != CONTAINER_DESKTOP) return@forEach
            if (item.originalScreen !in screenIds) return@forEach
            out.getOrPut(item.originalScreen) { HashSet() } += cellKey(item.originalX, item.originalY)
        }
        return out
    }

    private fun cellKey(x: Int, y: Int): Long = y.toLong() * 1000 + x

    private fun firstFree(cols: Int, rows: Int, taken: Set<Long>): Pair<Int, Int>? {
        for (y in 0 until rows) for (x in 0 until cols) {
            if (cellKey(x, y) !in taken) return x to y
        }
        return null
    }
}
