package com.deskflow.domain.layout

import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.Workspace

data class InsertRow(
    val id: Long,
    val title: String,
    val intent: String,
    val container: Long,
    val screen: Int,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
    val itemType: Int,
    // 双开/快捷方式的图标解析依赖这两列（四级兜底），反向恢复被删行时不能丢
    val iconPackage: String? = null,
    val iconResource: String? = null,
)

sealed class DbOp {
    data class UpdatePosition(val id: Long, val container: Long, val screen: Int, val cellX: Int, val cellY: Int) : DbOp()
    data class UpdateTitle(val id: Long, val title: String) : DbOp()
    data class Insert(val row: InsertRow) : DbOp()
    data class Delete(val id: Long) : DbOp()
    data class InsertScreen(val screenId: Int, val order: Int) : DbOp()
    data class DeleteScreen(val screenId: Int) : DbOp()
    data class UpdateScreenOrder(val screenId: Int, val order: Int) : DbOp()
}

class DiffPlan(
    val ops: List<DbOp>,
    val deletedIds: List<Long>,
    val insertedIds: List<Long>,
    /** 临时 id → 真实行 id（仅包含本次新增的行）。 */
    val idMap: Map<Long, Long> = emptyMap(),
) {
    val moved: Int get() = ops.count { it is DbOp.UpdatePosition }
    val renamed: Int get() = ops.count { it is DbOp.UpdateTitle }
    val screenAdded: Int get() = ops.count { it is DbOp.InsertScreen }
    val screenRemoved: Int get() = ops.count { it is DbOp.DeleteScreen }
    val screenReordered: Int get() = ops.count { it is DbOp.UpdateScreenOrder }
    override fun toString(): String = buildString {
        append("移动 $moved 项 / 新增 ${insertedIds.size} 项 / 删除 ${deletedIds.size} 项")
        if (renamed > 0) append(" / 重命名 $renamed")
        if (screenAdded > 0) append(" / 新增页 $screenAdded")
        if (screenRemoved > 0) append(" / 删除页 $screenRemoved")
        if (screenReordered > 0) append(" / 页序调整 $screenReordered")
    }
}

/**
 * Pure planner: turns an edited [Workspace] plus the freshly read DB snapshot into
 * ordered SQL-level operations. New items carry negative temp ids until [plan] is called
 * with the current max row id; children of new folders are remapped to real ids here.
 */
object DiffCalculator {

    fun plan(ws: Workspace, maxExistingId: Long): DiffPlan {
        val ops = mutableListOf<DbOp>()
        val live = ws.liveItems

        val tempIds = live.filter { it.isNew }.sortedBy { it.dbId }.map { it.dbId }
        val idMap = LinkedHashMap<Long, Long>()
        val base = maxExistingId + 1L
        tempIds.forEachIndexed { i, t -> idMap[t] = base + i }

        val deletedIds = ws.items.filter { it.isDeleted }.map { it.dbId }
        // deleting a folder also deletes its children rows
        val cascadeChildren = ws.items.filter { it.isDeleted && it.isFolder }
            .flatMap { folder -> ws.items.filter { it.container == folder.dbId } }
            .map { it.dbId }
        val allDeleted = (deletedIds + cascadeChildren).distinct()

        val inserted: List<InsertRow> = live.filter { it.isNew }.sortedBy { it.dbId }.map { item ->
            val resolvedContainer = idMap[item.container] ?: item.container
            InsertRow(
                id = idMap.getValue(item.dbId),
                title = item.title,
                intent = item.intent,
                container = resolvedContainer,
                screen = item.screen,
                cellX = item.cellX,
                cellY = item.cellY,
                spanX = item.spanX,
                spanY = item.spanY,
                itemType = item.itemCode,
                iconPackage = item.iconPackage,
                iconResource = item.iconResource,
            )
        }

        // 新页先建，item 才能落到上面；删页放在最后，等图标迁移完
        ws.pages.filter { it.isNew }.forEach { ops += DbOp.InsertScreen(it.screenId, it.order) }
        allDeleted.forEach { ops += DbOp.Delete(it) }
        inserted.forEach { ops += DbOp.Insert(it) }
        live.filter { !it.isNew && it.positionChanged() }.forEach { item ->
            val resolvedContainer = idMap[item.container] ?: item.container
            ops += DbOp.UpdatePosition(item.dbId, resolvedContainer, item.screen, item.cellX, item.cellY)
        }
        live.filter { !it.isNew && it.titleChanged() }.forEach { item ->
            ops += DbOp.UpdateTitle(item.dbId, item.title)
        }
        ws.pages.filter { !it.isNew && !it.isDeleted && it.order != it.originalOrder }
            .forEach { ops += DbOp.UpdateScreenOrder(it.screenId, it.order) }
        ws.pages.filter { it.isDeleted }.forEach { ops += DbOp.DeleteScreen(it.screenId) }
        return DiffPlan(ops, allDeleted, inserted.map { it.id }, idMap)
    }
}

/**
 * 写库成功后调用：把新增项的临时 id 换成真实行 id（连同指向它的 container），
 * 并把当前坐标设为新基线。不做这一步的话，二次应用会重复 INSERT 同一批 id 而失败。
 */
fun commitApplied(ws: Workspace, plan: DiffPlan) {
    ws.items.forEach { item ->
        plan.idMap[item.container]?.let { item.container = it }
    }
    ws.items.removeAll { it.isDeleted }
    ws.items.forEach { item ->
        plan.idMap[item.dbId]?.let { item.dbId = it }
        item.isNew = false
        item.originalContainer = item.container
        item.originalScreen = item.screen
        item.originalX = item.cellX
        item.originalY = item.cellY
        item.originalTitle = item.title
    }
    ws.pages.removeAll { it.isDeleted }
    ws.pages.forEach { page ->
        page.isNew = false
        page.originalOrder = page.order
    }
}
