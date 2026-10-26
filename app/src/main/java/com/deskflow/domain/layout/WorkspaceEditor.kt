package com.deskflow.domain.layout

import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.CONTAINER_DOCK
import com.deskflow.domain.model.MAX_TITLE_LEN
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ItemType
import com.deskflow.domain.model.Workspace
import com.deskflow.domain.model.GridConfig
import com.deskflow.domain.model.ScreenPage

const val DEFAULT_FOLDER_TITLE = "新文件夹"

class WorkspaceEditor(private val ws: Workspace) {

    private val grid: GridConfig get() = ws.grid
    private var tempIdSeq: Long = -1

    /** 临时 id 必须避开 Dock(-101) / 桌面(-100) 这类保留 container 值。 */
    private fun nextTempId(): Long {
        var id = tempIdSeq--
        while (id == CONTAINER_DESKTOP || id == CONTAINER_DOCK) id = tempIdSeq--
        return id
    }

    private fun cellKey(x: Int, y: Int) = y.toLong() * 1000 + x

    private fun siblings(item: ItemState): List<ItemState> =
        ws.liveItems.filter { it !== item && sharesContainer(it, item) }

    private fun sharesContainer(a: ItemState, b: ItemState): Boolean {
        if (a.container != b.container) return false
        val inGrid = a.container == CONTAINER_DESKTOP || a.container == CONTAINER_DOCK
        return if (inGrid) a.screen == b.screen else true
    }

    private fun occupants(container: Long, screen: Int, exclude: ItemState?): List<ItemState> =
        ws.liveItems.filter {
            it !== exclude && it.container == container &&
                ((container != CONTAINER_DESKTOP && container != CONTAINER_DOCK) || it.screen == screen)
        }

    private fun cellsOf(item: ItemState): List<Long> {
        val keys = ArrayList<Long>()
        for (dx in 0 until item.spanX) for (dy in 0 until item.spanY) keys += cellKey(item.cellX + dx, item.cellY + dy)
        return keys
    }

    private fun gridDims(container: Long): Pair<Int, Int> = when (container) {
        CONTAINER_DOCK -> grid.dockCells to 1
        CONTAINER_DESKTOP -> grid.columns to grid.rows
        else -> grid.folderColumns to grid.folderRows
    }

    fun canPlace(item: ItemState, container: Long, screen: Int, x: Int, y: Int): Boolean {
        val (cols, rows) = gridDims(container)
        val spanX = if (container == CONTAINER_DOCK) 1 else item.spanX
        val spanY = if (container == CONTAINER_DOCK) 1 else item.spanY
        if (x < 0 || y < 0 || x + spanX > cols || y + spanY > rows) return false
        val wanted = (0 until spanX).flatMap { dx -> (0 until spanY).map { dy -> cellKey(x + dx, y + dy) } }.toSet()
        val taken = occupants(container, screen, item).flatMap { other ->
            val (oc, or) = gridDims(container)
            val sx = if (container == CONTAINER_DOCK) 1 else other.spanX.coerceAtMost(oc - other.cellX)
            val sy = if (container == CONTAINER_DOCK) 1 else other.spanY.coerceAtMost(or - other.cellY)
            (0 until sx).flatMap { dx -> (0 until sy).map { dy -> cellKey(other.cellX + dx, other.cellY + dy) } }
        }.toSet()
        return wanted.intersect(taken).isEmpty()
    }

    fun findFreeCell(container: Long, screen: Int, exclude: ItemState? = null): Pair<Int, Int>? {
        val (cols, rows) = gridDims(container)
        val taken = occupants(container, screen, exclude).flatMap { cellsOf(it) }.toSet()
        for (y in 0 until rows) for (x in 0 until cols) {
            val item = exclude
            val sx = if (container == CONTAINER_DOCK) 1 else item?.spanX ?: 1
            val sy = if (container == CONTAINER_DOCK) 1 else item?.spanY ?: 1
            if (x + sx > cols || y + sy > rows) continue
            val wanted = (0 until sx).flatMap { dx -> (0 until sy).map { dy -> cellKey(x + dx, y + dy) } }.toSet()
            if (wanted.intersect(taken).isEmpty()) return x to y
        }
        return null
    }

    /** Move item; if target cells are held by exactly one movable item, swap them. */
    fun placeAt(item: ItemState, container: Long, screen: Int, x: Int, y: Int): Boolean {
        if (item.readOnly) return false
        if (canPlace(item, container, screen, x, y)) return placeDirect(item, container, screen, x, y)
        val (cols, rows) = gridDims(container)
        val spanX = if (container == CONTAINER_DOCK) 1 else item.spanX
        val spanY = if (container == CONTAINER_DOCK) 1 else item.spanY
        val wanted = (0 until spanX).flatMap { dx -> (0 until spanY).map { dy -> cellKey(x + dx, y + dy) } }.toSet()
        val blockers = occupants(container, screen, item)
            .filter { other -> cellsOf(other).any { it in wanted } }
        if (blockers.size != 1) return false
        val occupant = blockers.single()
        if (occupant.readOnly || occupant.isFolder || item.isFolder) return false
        if (occupant.spanX > spanX || occupant.spanY > spanY) return false
        val old = OldPos(item.container, item.screen, item.cellX, item.cellY)
        item.cellX = x; item.cellY = y // tentatively so occupant can validate against item's old slot
        val okOccupant = canPlace(occupant, old.container, old.screen, old.x, old.y)
        if (!okOccupant) {
            item.cellX = old.x; item.cellY = old.y
            return false
        }
        placeDirect(occupant, old.container, old.screen, old.x, old.y)
        return placeDirect(item, container, screen, x, y)
    }

    private data class OldPos(val container: Long, val screen: Int, val x: Int, val y: Int)

    private fun placeDirect(item: ItemState, container: Long, screen: Int, x: Int, y: Int): Boolean {
        item.container = container
        item.screen = screen
        item.cellX = x
        item.cellY = y
        if (container == CONTAINER_DOCK) { item.spanX = 1; item.spanY = 1 }
        return true
    }

    fun placeFirstFree(item: ItemState, container: Long, screen: Int): Boolean {
        if (item.readOnly) return false
        val cell = findFreeCell(container, screen, item) ?: return false
        return placeDirect(item, container, screen, cell.first, cell.second)
    }

    /** Drop dragged item onto target item: into folder, or merge two apps into a new folder. */
    fun dropOnItem(dragged: ItemState, target: ItemState): Boolean {
        if (dragged.readOnly || target.readOnly || dragged === target) return false
        if (target.isFolder) {
            if (dragged.isFolder) return false
            return placeFirstFree(dragged, target.dbId, target.screen)
        }
        if (dragged.isFolder) return false
        if (dragged.itemCode != target.itemCode) return false
        if (dragged.itemCode != ItemType.APP && dragged.itemCode != ItemType.SHORTCUT) return false
        createFolderAt(target.cellX, target.cellY, target.container, target.screen, listOf(dragged, target))
        return true
    }

    fun createFolderAt(
        x: Int,
        y: Int,
        container: Long,
        screen: Int,
        members: List<ItemState>,
        title: String = DEFAULT_FOLDER_TITLE,
    ): ItemState {
        val folder = ItemState(
            dbId = nextTempId(),
            title = title,
            intent = "",
            itemCode = ItemType.FOLDER,
            iconPackage = null,
            iconResource = null,
            container = container,
            screen = screen,
            cellX = x,
            cellY = y,
            isNew = true,
        )
        ws.items += folder
        members.forEachIndexed { i, m ->
            m.container = folder.dbId
            m.screen = screen
            m.cellX = i % grid.folderColumns
            m.cellY = i / grid.folderColumns
        }
        return folder
    }

    /**
     * 重命名桌面项（文件夹与应用图标均可；widget 只读项拒绝）。
     * 文件夹名确认读库（location_jan.title）；应用图标的显示名是否跟随库 title
     * 待真机确认（可能取 PackageManager label）——最坏情况只是编辑器内改名生效，写库无害。
     */
    fun renameItem(item: ItemState, rawTitle: String): Boolean {
        if (item.readOnly) return false
        val title = rawTitle.trim().take(MAX_TITLE_LEN)
        if (title.isEmpty()) return false
        item.title = title
        return true
    }

    /** Delete item from workspace. Folder children are restored to [restoreScreen]. */
    fun delete(item: ItemState, restoreScreen: Int): Boolean {
        if (item.readOnly) return false
        if (item.isFolder) {
            val children = ws.childrenOf(item)
            item.isDeleted = true
            children.forEach { child ->
                if (!placeFirstFree(child, CONTAINER_DESKTOP, restoreScreen)) {
                    child.isDeleted = true
                }
            }
            return true
        }
        item.isDeleted = true
        return true
    }

    /** Move a folder child back out to the first free desktop cell of the folder's page. */
    fun ejectChild(child: ItemState, screen: Int): Boolean {
        val parent = ws.items.firstOrNull { it.dbId == child.container && it.isFolder } ?: return false
        val free = freeDesktopScreenFor(parent, screen)
        return placeFirstFree(child, CONTAINER_DESKTOP, free)
    }

    private fun freeDesktopScreenFor(folder: ItemState, fallback: Int): Int {
        if (folder.container == CONTAINER_DESKTOP) return folder.screen
        return fallback
    }

    /** 从已安装应用新增一个桌面图标，落当前页首个空位；页满返回 null。 */
    fun addApp(intent: String, title: String, container: Long, screen: Int): ItemState? {
        if (intent.isEmpty() || !intent.contains('/')) return null
        val free = findFreeCell(container, screen) ?: return null
        val item = ItemState(
            dbId = nextTempId(),
            title = title,
            intent = intent,
            itemCode = ItemType.APP,
            iconPackage = intent.substringBefore('/'),
            iconResource = null,
            container = container,
            screen = screen,
            cellX = free.first,
            cellY = free.second,
            isNew = true,
        )
        ws.items += item
        return item
    }

    // ---------- page management ----------

    private fun livePages(): List<ScreenPage> = ws.pages.filter { !it.isDeleted }.sortedBy { it.order }

    /** 追加一页。screenId 取现有最大 id + 1（不与已删除页复用），order 排在最后。 */
    fun addPage(): ScreenPage? {
        val live = livePages()
        val newId = (ws.pages.maxOfOrNull { it.screenId } ?: -1) + 1
        if (live.any { it.screenId == newId }) return null
        val page = ScreenPage(newId, live.size, isNew = true)
        ws.pages += page
        return page
    }

    /**
     * 删除一页：先把该页图标整体迁移到其余页的空位，成功才标记删除。
     * 容量不足、该页有只读项（widget）或只剩一页时拒绝，且不做任何改动。
     */
    fun deletePage(screenId: Int): Boolean {
        val live = livePages()
        val page = live.firstOrNull { it.screenId == screenId } ?: return false
        if (live.size <= 1) return false
        val items = ws.pageItems(screenId)
        if (items.any { it.readOnly }) return false
        val targets = live.filter { it.screenId != screenId }
        if (targets.sumOf { freeCellCount(it.screenId) } < items.size) return false

        val backup = items.map { it to longArrayOf(it.container, it.screen.toLong(), it.cellX.toLong(), it.cellY.toLong()) }
        val ok = items.all { item -> targets.any { t -> placeFirstFree(item, CONTAINER_DESKTOP, t.screenId) } }
        if (!ok) {
            backup.forEach { (item, pos) ->
                item.container = pos[0]
                item.screen = pos[1].toInt()
                item.cellX = pos[2].toInt()
                item.cellY = pos[3].toInt()
            }
            return false
        }
        page.isDeleted = true
        reindexOrders()
        return true
    }

    /** delta = -1 前移 / +1 后移：与相邻页交换 screen_order。 */
    fun movePage(screenId: Int, delta: Int): Boolean {
        val live = livePages()
        val idx = live.indexOfFirst { it.screenId == screenId }
        val target = idx + delta
        if (idx < 0 || target !in live.indices) return false
        val a = live[idx]
        val b = live[target]
        val tmp = a.order
        a.order = b.order
        b.order = tmp
        ws.pages.sortBy { it.order }
        return true
    }

    private fun reindexOrders() {
        livePages().forEachIndexed { i, p -> p.order = i }
        ws.pages.sortBy { it.order }
    }

    private fun freeCellCount(screen: Int): Int {
        val (cols, rows) = gridDims(CONTAINER_DESKTOP)
        val taken = occupants(CONTAINER_DESKTOP, screen, null).flatMap { cellsOf(it) }.toSet().size
        return cols * rows - taken
    }
}
