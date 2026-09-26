package com.deskflow.domain.model

const val CONTAINER_DOCK = -101L
const val CONTAINER_DESKTOP = -100L

/** 桌面文件夹标题显示两行后截断，写库前统一限长。 */
const val MAX_TITLE_LEN = 12

object ItemType {
    const val FOLDER = 10
    const val APP = 30
    const val SHORTCUT = 31
}

data class GridConfig(
    val columns: Int = 4,
    val rows: Int = 7,
    val dockCells: Int = 5,
    val folderColumns: Int = 3,
    val folderRows: Int = 3,
) {
    /**
     * 收夹切块用的标称容量（[folderColumns] × [folderRows] = 9）。
     * 这不是硬上限：vivo 夹内是 3 列多行，实测 10 个渲染成 4 行，超容量也放得下。
     */
    val folderCapacity: Int get() = folderColumns * folderRows
}

class ItemState(
    var dbId: Long,
    var title: String,
    val intent: String,
    val itemCode: Int,
    val iconPackage: String?,
    val iconResource: String?,
    var container: Long,
    var screen: Int,
    var cellX: Int,
    var cellY: Int,
    var spanX: Int = 1,
    var spanY: Int = 1,
    val readOnly: Boolean = false,
    var isNew: Boolean = false,
    var isDeleted: Boolean = false,
) {
    val isFolder get() = itemCode == ItemType.FOLDER
    var originalContainer: Long = container
    var originalScreen: Int = screen
    var originalX: Int = cellX
    var originalY: Int = cellY
    var originalTitle: String = title

    fun positionChanged(): Boolean =
        !isNew && !isDeleted &&
            (container != originalContainer || screen != originalScreen || cellX != originalX || cellY != originalY)

    fun titleChanged(): Boolean = !isNew && !isDeleted && title != originalTitle

    fun componentPackage(): String? = intent.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }

    /** 深拷贝，用于撤销快照。 */
    fun copyState(): ItemState = ItemState(
        dbId = dbId,
        title = title,
        intent = intent,
        itemCode = itemCode,
        iconPackage = iconPackage,
        iconResource = iconResource,
        container = container,
        screen = screen,
        cellX = cellX,
        cellY = cellY,
        spanX = spanX,
        spanY = spanY,
        readOnly = readOnly,
        isNew = isNew,
        isDeleted = isDeleted,
    ).also {
        it.originalContainer = originalContainer
        it.originalScreen = originalScreen
        it.originalX = originalX
        it.originalY = originalY
        it.originalTitle = originalTitle
    }
}

class ScreenPage(
    val screenId: Int,
    var order: Int,
    var isNew: Boolean = false,
    var isDeleted: Boolean = false,
    var originalOrder: Int = order,
) {
    fun copyState(): ScreenPage = ScreenPage(screenId, order, isNew, isDeleted, originalOrder)
}

class Workspace(
    val grid: GridConfig,
    val pages: MutableList<ScreenPage>,
    val items: MutableList<ItemState>,
) {
    val liveItems: List<ItemState> get() = items.filter { !it.isDeleted }

    fun pageItems(screenId: Int): List<ItemState> =
        liveItems.filter { it.container == CONTAINER_DESKTOP && it.screen == screenId }

    fun dockItems(): List<ItemState> = liveItems.filter { it.container == CONTAINER_DOCK }

    fun childrenOf(folder: ItemState): List<ItemState> =
        liveItems.filter { it.container == folder.dbId }

    fun byId(id: Long): ItemState? = items.firstOrNull { it.dbId == id }
}
