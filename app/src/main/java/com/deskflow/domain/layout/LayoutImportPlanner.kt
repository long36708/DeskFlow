package com.deskflow.domain.layout

import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.CONTAINER_DOCK
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.Workspace

/** 一个移动意图：[screenRef] ≥0 为档案页序（桌面页），[LayoutImportPlanner.DOCK_REF] 为 Dock。 */
data class PlannedMove(val item: ItemState, val screenRef: Int, val x: Int, val y: Int)

data class PlannedFolder(
    val title: String,
    val screenRef: Int,
    val x: Int,
    val y: Int,
    /** 非空 = 沿用现有文件夹（按 dbId 或标题匹配），成员收进去；空 = 新建。 */
    val existing: ItemState?,
    val members: List<ItemState>,
)

data class LayoutImportPlan(
    /** 档案页序 → 当前页 id；负数为待新建页（[LayoutImportPlanner.apply] 换真实 id）。 */
    val screenIds: List<Int>,
    val moves: List<PlannedMove>,
    val folders: List<PlannedFolder>,
    /** 档案外多出来的散放项 / 放不下的项：apply 时从最后一页向前找空格安置。 */
    val parked: List<ItemState>,
    /** 匹配项的标题对齐（恢复改名），item → 档案标题。 */
    val titleSync: List<Pair<ItemState, String>>,
    val stats: Stats,
) {
    data class Stats(
        val archivePages: Int,
        val archiveIcons: Int,
        val matchedIcons: Int,
        /** widget 只匹配原位（编辑器不允许移动只读项），匹配上也不动。 */
        val widgetKept: Int,
        val skippedArchive: Int,
        val foldersTotal: Int,
        val foldersReused: Int,
        val foldersCreated: Int,
        val parkedItems: Int,
        val newPages: Int,
        /** 档案网格与当前不同，或有格位越界。 */
        val gridMismatch: Boolean,
    )
}

/**
 * 布局方案导入规划器：把 [LayoutArchive] 套用到当前工作区。
 *
 * **匹配**：同设备恢复靠 dbId 精确对上；跨设备 / 恢复出厂迁移靠 intent 包名（componentPackage，
 * 双开兜底 iconPackage）。夹按 dbId、再按标题沿用；widget 只读项编辑器不允许移动，
 * 匹配上即算原位保持，位置不同也只能跳过。
 *
 * **落位**：占用图只含「不会动的项」（未匹配夹、widget 等）——所有匹配项都要腾窝，先把它们从
 * 占用图排除即可顺序落位、无需交换；档案格位越界（如 5×9 档案导入 4×7 桌面）或撞上不动项时
 * 退化到备用位。多余页保留在末尾承接备用项，档案缺页则新建。
 *
 * 档案里找不到的当前散放应用进 [LayoutImportPlan.parked]（含 Dock 溢出项）；未匹配的现有文件夹
 * 及其成员**原地不动**——导入不销毁用户没提过的东西。全程一次 edit 落地 = 一次「撤销上一步」，
 * 真正写库仍走「应用」链路。
 */
object LayoutImportPlanner {

    const val DOCK_REF = -1
    private const val TEMP_SCREEN_START = -1000

    fun plan(ws: Workspace, archive: LayoutArchive): LayoutImportPlan {
        val grid = ws.grid
        val live = ws.liveItems
        val byDbId = live.associateBy { it.dbId }
        val unclaimedFolders = live.filter { it.isFolder }.toMutableSet()
        val claimed = HashSet<Long>()

        fun pkgOf(item: ItemState): String? =
            item.componentPackage()?.takeIf { it.isNotEmpty() } ?: item.iconPackage?.takeIf { it.isNotEmpty() }

        fun kindCompat(item: ItemState, entry: ArchiveEntry): Boolean =
            if (entry.kind == LayoutArchiveCodec.KIND_WIDGET) item.readOnly else !item.readOnly && !item.isFolder

        fun match(entry: ArchiveEntry): ItemState? {
            byDbId[entry.dbId]?.takeIf { it.dbId !in claimed && kindCompat(it, entry) }?.let {
                claimed += it.dbId
                return it
            }
            if (entry.kind == LayoutArchiveCodec.KIND_WIDGET) return null // widget 只认 dbId
            val pkg = entry.iconPackage?.takeIf { it.isNotEmpty() }
                ?: entry.intent.substringBeforeLast('/').takeIf { '/' in entry.intent }
            if (pkg == null) return null
            return live.firstOrNull {
                it.dbId !in claimed && kindCompat(it, entry) && pkgOf(it) == pkg
            }?.also { claimed += it.dbId }
        }

        fun matchFolder(archiveFolder: ArchiveFolder): ItemState? {
            byDbId[archiveFolder.dbId]?.takeIf { it.isFolder && it.dbId !in claimed }?.let {
                claimed += it.dbId
                unclaimedFolders.remove(it)
                return it
            }
            return unclaimedFolders.firstOrNull { it.title == archiveFolder.title }?.also {
                claimed += it.dbId
                unclaimedFolders.remove(it)
            }
        }

        // ---------- 第一阶段：匹配 ----------
        val existingPages = ws.pages.filter { !it.isDeleted }.sortedBy { it.order }
        val screenIds = MutableList(archive.pages.size) { i ->
            if (i < existingPages.size) existingPages[i].screenId else TEMP_SCREEN_START - (i - existingPages.size)
        }

        val folders = mutableListOf<PlannedFolder>()
        val moves = mutableListOf<PlannedMove>()
        val titleSync = mutableListOf<Pair<ItemState, String>>()
        var iconsTotal = 0
        var matched = 0
        var widgetKept = 0
        var skipped = 0

        archive.pages.forEachIndexed { pageRef, page ->
            page.folders.forEach { af ->
                val existing = matchFolder(af)
                val members = mutableListOf<ItemState>()
                af.children.forEach { child ->
                    iconsTotal++
                    val item = match(child)
                    if (item == null) skipped++
                    else {
                        matched++
                        members += item
                        titleSync += item to child.title
                    }
                }
                if (existing == null && members.isEmpty()) return@forEach // 空夹没东西可建，跳过
                if (existing != null) titleSync += existing to af.title
                folders += PlannedFolder(af.title, pageRef, af.cellX, af.cellY, existing, members)
            }
            page.entries.forEach { e ->
                iconsTotal++
                val item = match(e)
                when {
                    item == null -> skipped++
                    e.kind == LayoutArchiveCodec.KIND_WIDGET -> widgetKept++ // 原位保持，不产生 move
                    else -> {
                        matched++
                        titleSync += item to e.title
                        moves += PlannedMove(item, pageRef, e.cellX, e.cellY)
                    }
                }
            }
        }
        archive.dock.forEach { e ->
            iconsTotal++
            val item = match(e)
            when {
                item == null -> skipped++
                e.kind == LayoutArchiveCodec.KIND_WIDGET -> widgetKept++
                else -> {
                    matched++
                    titleSync += item to e.title
                    moves += PlannedMove(item, DOCK_REF, e.cellX, 0)
                }
            }
        }

        // 档案外多出来的散放项 → 备用位（夹与其成员原地不动，widget 不可动）
        val parked = live
            .filter {
                !it.isFolder && !it.readOnly && it.dbId !in claimed &&
                    (it.container == CONTAINER_DESKTOP || it.container == CONTAINER_DOCK)
            }
            .toMutableList()

        // ---------- 第二阶段：落位（占用图只含不动项） ----------
        val movers = HashSet<ItemState>()
        movers.addAll(parked)
        movers.addAll(moves.map { it.item })
        folders.forEach { f ->
            f.existing?.let { movers += it }
            movers.addAll(f.members)
        }

        val gridMismatch = archive.grid.columns != grid.columns || archive.grid.rows != grid.rows
        // 键：真实页 id / 待新建页的临时 id / DOCK_REF
        val stayCells = HashMap<Int, MutableSet<Long>>()
        screenIds.forEach { stayCells[it] = HashSet() }
        stayCells[DOCK_REF] = HashSet()
        live.forEach { item ->
            if (item in movers || item.container != CONTAINER_DESKTOP) return@forEach
            val cells = stayCells[item.screen] ?: return@forEach
            for (dx in 0 until item.spanX) for (dy in 0 until item.spanY) {
                cells += cellKey(item.cellX + dx, item.cellY + dy)
            }
        }
        live.filter { it.container == CONTAINER_DOCK && it !in movers }.forEach {
            stayCells.getValue(DOCK_REF) += cellKey(it.cellX, it.cellY)
        }
        val claimedCells = HashMap<Int, MutableSet<Long>>()

        fun boundsOk(ref: Int, x: Int, y: Int, sx: Int, sy: Int): Boolean {
            val cols = if (ref == DOCK_REF) grid.dockCells else grid.columns
            val rows = if (ref == DOCK_REF) 1 else grid.rows
            return x >= 0 && y >= 0 && x + sx <= cols && y + sy <= rows
        }

        fun cellFree(ref: Int, x: Int, y: Int, sx: Int, sy: Int): Boolean {
            val stayKey = if (ref == DOCK_REF) DOCK_REF else screenIds[ref]
            val stay = stayCells.getValue(stayKey)
            val taken = claimedCells.getOrPut(stayKey) { HashSet() }
            for (dx in 0 until sx) for (dy in 0 until sy) {
                val key = cellKey(x + dx, y + dy)
                if (key in stay || key in taken) return false
            }
            return true
        }

        fun claim(ref: Int, x: Int, y: Int, sx: Int, sy: Int) {
            val stayKey = if (ref == DOCK_REF) DOCK_REF else screenIds[ref]
            val taken = claimedCells.getOrPut(stayKey) { HashSet() }
            for (dx in 0 until sx) for (dy in 0 until sy) {
                taken += cellKey(x + dx, y + dy)
            }
        }

        // 夹先落位（夹自身占 1 格，成员收进夹后与其无关）
        var outOfGrid = false
        val placedFolders = mutableListOf<PlannedFolder>()
        folders.forEach { f ->
            val sx = f.existing?.spanX ?: 1
            val sy = f.existing?.spanY ?: 1
            if (!boundsOk(f.screenRef, f.x, f.y, sx, sy)) {
                outOfGrid = true
                parked += listOfNotNull(f.existing) + f.members
            } else if (!cellFree(f.screenRef, f.x, f.y, sx, sy)) {
                parked += listOfNotNull(f.existing) + f.members
            } else {
                claim(f.screenRef, f.x, f.y, sx, sy)
                placedFolders += f
            }
        }

        val placedMoves = mutableListOf<PlannedMove>()
        moves.forEach { m ->
            val item = m.item
            val sx = if (m.screenRef == DOCK_REF) 1 else item.spanX
            val sy = if (m.screenRef == DOCK_REF) 1 else item.spanY
            when {
                !boundsOk(m.screenRef, m.x, m.y, sx, sy) -> {
                    outOfGrid = true
                    parked += item
                }
                !cellFree(m.screenRef, m.x, m.y, sx, sy) -> parked += item
                else -> {
                    claim(m.screenRef, m.x, m.y, sx, sy)
                    placedMoves += m
                }
            }
        }

        return LayoutImportPlan(
            screenIds = screenIds,
            moves = placedMoves,
            folders = placedFolders,
            parked = parked,
            titleSync = titleSync,
            stats = LayoutImportPlan.Stats(
                archivePages = archive.pages.size,
                archiveIcons = iconsTotal,
                matchedIcons = matched,
                widgetKept = widgetKept,
                skippedArchive = skipped,
                foldersTotal = archive.pages.sumOf { it.folders.size },
                foldersReused = placedFolders.count { it.existing != null },
                foldersCreated = placedFolders.count { it.existing == null },
                parkedItems = parked.size,
                newPages = maxOf(0, archive.pages.size - existingPages.size),
                gridMismatch = gridMismatch || outOfGrid,
            ),
        )
    }

    /** 把计划落到 workspace（一次 edit 一次撤销）：建页 → 夹 → 图标 → 备用位 → 标题对齐。 */
    fun apply(plan: LayoutImportPlan, editor: WorkspaceEditor) {
        val tempMap = HashMap<Int, Int>()
        plan.screenIds.filter { it < 0 }.forEach { temp ->
            editor.addPage()?.let { tempMap[temp] = it.screenId }
        }

        fun resolveRef(ref: Int): Pair<Long, Int> =
            if (ref == DOCK_REF) CONTAINER_DOCK to 0
            else {
                val sid = plan.screenIds[ref]
                CONTAINER_DESKTOP to if (sid < 0) tempMap[sid] ?: sid else sid
            }

        plan.folders.forEach { f ->
            val (container, sid) = resolveRef(f.screenRef)
            val existing = f.existing
            if (existing != null) {
                editor.placePlanned(existing, container, sid, f.x, f.y)
                f.members.forEach { m -> editor.placeFirstFree(m, existing.dbId, existing.screen) }
            } else {
                editor.createFolderAt(f.x, f.y, container, sid, f.members, f.title)
            }
        }
        plan.moves.forEach { m ->
            val (container, sid) = resolveRef(m.screenRef)
            editor.placePlanned(m.item, container, sid, m.x, m.y)
        }
        plan.parked.forEach { item ->
            for (ref in plan.screenIds.indices.reversed()) { // 从最后一页（含新页）向前找空格
                val (container, sid) = resolveRef(ref)
                if (container == CONTAINER_DESKTOP && editor.placeFirstFree(item, container, sid)) return@forEach
            }
        }
        // 标题对齐最后做：产生的 UpdateTitle 与位置无关
        plan.titleSync.forEach { (item, title) -> item.title = title }
    }

    private fun cellKey(x: Int, y: Int): Long = y.toLong() * 1000 + x
}
