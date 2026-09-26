package com.deskflow.domain.classifier

import com.deskflow.domain.layout.WorkspaceEditor
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.GridConfig
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import com.deskflow.domain.model.Workspace

/** 同类图标达到该数量才收进文件夹，否则桌面会被两两成的小夹子占满。 */
const val FOLDER_MIN_ITEMS = 4

/** 排布计划里待新建页的临时 screenId 起点；避开桌面(-100)/Dock(-101) 等保留值，apply 时换成真实 id。 */
private const val TEMP_SCREEN_START = -200

data class Placement(
    val item: ItemState,
    val screen: Int,
    val cellX: Int,
    val cellY: Int,
    val category: Category,
    /**
     * 非空表示这一格是**待新建文件夹**槽位：[item] 只是承载标题的占位对象，不会进入 workspace，
     * 真正的行在 [AutoArranger.apply] 里由编辑器创建。
     * 为空且 [item] 是既有图标时，表示该项保持原页原格不动。
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
    /** 待归类的散放应用，按类别顺序排列；只有达到收夹阈值的类别会真的落进新夹。 */
    val groups: List<Pair<Category, List<ItemState>>>,
    /** 现有页铺满后追加的待新建页（临时负 screenId，[apply] 时换成真实 id），按顺序排在最后。 */
    val newScreens: List<Int> = emptyList(),
) {
    /** 待归类的散放应用数（非首页、非夹、非只读）。 */
    val looseCount: Int get() = groups.sumOf { it.second.size }

    /** 本次收进新夹的图标数；未达收夹阈值的散放项原地不动，不计入。 */
    val placedCount: Int get() = pages.sumOf { page ->
        page.placements.sumOf { if (it.isNewFolder) it.members.size else 0 }
    }

    /** 本次保持原页原格的图标数（首页全部 + 既有夹 + 达标类别之外的散放项）。 */
    val lockedCount: Int get() = pages.sumOf { page -> page.placements.count { !it.isNewFolder } }

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
 * 以当前桌面为模板的增量整理，只碰「散放」的图标：
 *
 * - **原位锁死**：首页（第一张未删除页）上的全部图标、任意页上的既有文件夹、只读项（widget 等）、
 *   Dock 项一律保持原页原格，首页连空格都不给新夹用。
 * - **待归类**：非首页的散放应用（container 为桌面、非夹、非只读）。同类达到 [FOLDER_MIN_ITEMS] 个
 *   就收进以类别命名的夹，每夹 [GridConfig.folderCapacity] 个，装不下的续编号「影音 2」；
 *   尾组不足 [FOLDER_MIN_ITEMS] 个时并回上一夹（12 个同类就是一个夹，不会切出 9+3）。
 *   数量不够或类别为未分类的散放项原地不动。
 * - **新夹落位**：优先用本次腾空出来的格子（被收走的散放项原位），其次是其余非首页空位，
 *   都没有就追加新页承接，因此不存在「放不下」的项。
 *
 * 覆盖范围之外还有一件更重要的事：**首页永不重排**。用户精心排的首页是模板本身，不是待整理对象。
 */
object AutoArranger {

    fun arrange(ws: Workspace, rules: CategoryRules = DefaultRules.rules): ArrangeResult {
        val grid = ws.grid
        val screenIds = ws.pages.filter { !it.isDeleted }.sortedBy { it.order }
            .map { it.screenId }.toMutableList()
        val home = screenIds.firstOrNull()

        val groups = ws.liveItems
            .filter { isLooseApp(it, home) }
            .groupBy { AppClassifier.classify(it, rules) }
            .toList()
            .sortedWith(compareBy({ it.first.order }, { it.first.id }))
            .map { (cat, items) -> cat to items.sortedWith(compareBy({ it.title }, { it.dbId })) }

        val slots = folderSlots(groups, grid.folderCapacity, ws.liveItems.filter { it.isFolder }.map { it.title }.toSet())
        val collectedIds = slots.flatMap { s -> s.members.map { it.dbId } }.toHashSet()

        // 除被收走的散放项外，桌面上的一切都原地占位；首页整体占位以保证新夹不会落到首页
        val frozen = ws.liveItems.filter { it.container == CONTAINER_DESKTOP && it.dbId !in collectedIds }
        val fixed = LinkedHashMap<Int, MutableSet<Long>>()
        screenIds.forEach { fixed[it] = HashSet() }
        home?.let { fixed.getValue(it).addAll(allCells(grid)) }
        frozen.forEach { item ->
            val taken = fixed[item.screen] ?: return@forEach
            for (dx in 0 until item.spanX) for (dy in 0 until item.spanY) {
                taken += cellKey(item.cellX + dx, item.cellY + dy)
            }
        }

        val laid = layoutOf(screenIds, grid, frozen, fixed, slots, rules)
        return ArrangeResult(laid.pages, groups, laid.newScreens)
    }

    /** 把排布结果写回 workspace：追加新页，锁定项按原位回写（无变化），文件夹槽位经编辑器真正建行并把成员收进去。 */
    fun apply(ws: Workspace, result: ArrangeResult, editor: WorkspaceEditor) {
        val screenMap = HashMap<Int, Int>()
        result.newScreens.forEach { temp -> editor.addPage()?.let { screenMap[temp] = it.screenId } }
        result.pages.forEach { page ->
            val sid = screenMap[page.screenId] ?: page.screenId
            page.placements.forEach { pl ->
                if (pl.isNewFolder) {
                    editor.createFolderAt(pl.cellX, pl.cellY, CONTAINER_DESKTOP, sid, pl.members, pl.item.title)
                } else {
                    pl.item.container = CONTAINER_DESKTOP
                    pl.item.screen = sid
                    pl.item.cellX = pl.cellX
                    pl.item.cellY = pl.cellY
                }
            }
        }
    }

    // ---------- candidates ----------

    /** 待归类 = 散放：摆在非首页桌面上、既不是文件夹也不是只读项的应用 / 快捷方式。 */
    private fun isLooseApp(item: ItemState, home: Int?): Boolean =
        item.container == CONTAINER_DESKTOP && !item.readOnly && !item.isFolder && item.screen != home

    // ---------- slot building ----------

    /** 待新建的文件夹槽位。 */
    private class Slot(val category: Category, val members: List<ItemState>, val title: String)

    /**
     * 只有达到收夹阈值的类别才产出槽位；其余散放项留在原地，不产生任何槽。
     * [reservedTitles] 是既有夹已占用的名字——既有夹是锁定项、不会与新夹合并，
     * 名字撞车就会在桌面上出现两个同名的「影音」，所以编号要跳过它们。
     */
    private fun folderSlots(
        groups: List<Pair<Category, List<ItemState>>>,
        capacity: Int,
        reservedTitles: Set<String>,
    ): List<Slot> {
        val used = HashSet(reservedTitles)
        return groups.flatMap { (cat, items) ->
            if (cat == Category.OTHER || items.size < FOLDER_MIN_ITEMS) return@flatMap emptyList()
            items.chunked(capacity.coerceAtLeast(1)).mergeUndersizedTail()
                .mapIndexed { i, chunk -> Slot(cat, chunk, claimLabel(cat.label, i, used)) }
        }
    }

    /**
     * 尾组不足 [FOLDER_MIN_ITEMS] 个就并回上一夹：12 个同类宁可装成一个夹，
     * 也不在桌面上留下「影音 2」这种只有两三个图标的小夹子。
     * 代价是最后一夹能到 capacity + FOLDER_MIN_ITEMS - 1 个；vivo 夹内是 3 列多行（实测 10 个渲染 4 行），装得下。
     */
    private fun List<List<ItemState>>.mergeUndersizedTail(): List<List<ItemState>> {
        if (size < 2 || last().size >= FOLDER_MIN_ITEMS) return this
        return dropLast(2) + listOf(this[size - 2] + last())
    }

    /** 首个文件夹用类别名，之后的续编号：影音 / 影音 2 / 影音 3；已被占用的名字往后顺延。 */
    private fun claimLabel(label: String, index: Int, used: MutableSet<String>): String {
        var n = index
        while (true) {
            val candidate = numberedLabel(label, n)
            if (used.add(candidate)) return candidate
            n++
        }
    }

    private fun numberedLabel(label: String, index: Int): String =
        if (index == 0) label else "$label ${index + 1}"

    /** 文件夹自身没有 intent，按标题认领类别（含我们自己生成的「影音 2」这类续编号）。 */
    private fun categoryOf(item: ItemState, rules: CategoryRules): Category =
        if (item.isFolder) Category.byLabel(item.title) ?: AppClassifier.classify(item, rules)
        else AppClassifier.classify(item, rules)

    // ---------- layout ----------

    private class LaidOut(val pages: List<ArrangedPage>, val newScreens: List<Int>)

    /**
     * 行优先逐格铺放待新建的文件夹。锁定项先按原位登记到各页，
     * 所以预览看到的是「原布局 + 新夹」；现有页铺满就追加待新建页（临时负 id，[apply] 换成真实 screenId）。
     */
    private fun layoutOf(
        screenIds: MutableList<Int>,
        grid: GridConfig,
        frozen: List<ItemState>,
        fixed: Map<Int, Set<Long>>,
        slots: List<Slot>,
        rules: CategoryRules,
    ): LaidOut {
        val occupancy = LinkedHashMap<Int, MutableSet<Long>>()
        val byScreen = LinkedHashMap<Int, MutableList<Placement>>()
        screenIds.forEach { sid ->
            occupancy[sid] = HashSet<Long>().apply { addAll(fixed[sid] ?: emptySet()) }
            byScreen[sid] = ArrayList()
        }
        frozen.sortedWith(compareBy({ it.screen }, { it.cellY }, { it.cellX }, { it.dbId }))
            .forEach { item ->
                val list = byScreen[item.screen] ?: return@forEach
                list += Placement(item, item.screen, item.cellX, item.cellY, categoryOf(item, rules))
            }

        val newScreens = ArrayList<Int>()
        var pageIdx = 0
        slots.forEach { slot ->
            while (true) {
                if (pageIdx >= screenIds.size) {
                    val sid = TEMP_SCREEN_START - newScreens.size
                    screenIds += sid
                    occupancy[sid] = HashSet()
                    byScreen[sid] = ArrayList()
                    newScreens += sid
                }
                val sid = screenIds[pageIdx]
                val cell = firstFree(grid.columns, grid.rows, occupancy.getValue(sid))
                if (cell == null) {
                    pageIdx++
                    continue
                }
                occupancy.getValue(sid) += cellKey(cell.first, cell.second)
                byScreen.getValue(sid) += Placement(
                    item = folderPlaceholder(slot.title),
                    screen = sid,
                    cellX = cell.first,
                    cellY = cell.second,
                    category = slot.category,
                    members = slot.members,
                )
                break
            }
        }
        return LaidOut(screenIds.map { ArrangedPage(it, byScreen.getValue(it)) }, newScreens)
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

    private fun allCells(grid: GridConfig): Set<Long> =
        (0 until grid.rows).flatMap { y -> (0 until grid.columns).map { x -> cellKey(x, y) } }.toSet()

    private fun cellKey(x: Int, y: Int): Long = y.toLong() * 1000 + x

    private fun firstFree(cols: Int, rows: Int, taken: Set<Long>): Pair<Int, Int>? {
        for (y in 0 until rows) for (x in 0 until cols) {
            if (cellKey(x, y) !in taken) return x to y
        }
        return null
    }
}
