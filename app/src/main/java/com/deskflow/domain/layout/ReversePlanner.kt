package com.deskflow.domain.layout

import com.deskflow.domain.model.Workspace

/**
 * 由正向 [DiffPlan] 推导反向 plan，用于「撤销上次应用」整库回退。
 *
 * 必须在 [commitApplied] 之前、plan 与 workspace 仍一致时捕获：被删行（含级联子项）的完整
 * 行数据、坐标/标题的 pre-edit 原值、被删页的原 order 只在此刻可得。
 *
 * 反向 ops = 正向 ops 倒序逐个取逆。倒序保证依赖成立：正向「新页先建、删页最后」翻过来
 * 即「删新页在图标回迁之后、重建被删页在图标回迁之前」。
 */
object ReversePlanner {

    fun reverse(ws: Workspace, plan: DiffPlan): DiffPlan {
        val ops = plan.ops.reversed().mapNotNull { inverse(ws, it) }
        // 反向语义：恢复的行 = 正向删掉的；删除的行 = 正向新增的
        return DiffPlan(ops, deletedIds = plan.insertedIds, insertedIds = plan.deletedIds)
    }

    private fun inverse(ws: Workspace, op: DbOp): DbOp? = when (op) {
        is DbOp.UpdatePosition ->
            ws.byId(op.id)?.let {
                DbOp.UpdatePosition(op.id, it.originalContainer, it.originalScreen, it.originalX, it.originalY)
            }
        is DbOp.UpdateTitle ->
            ws.byId(op.id)?.let { DbOp.UpdateTitle(op.id, it.originalTitle) }
        is DbOp.Insert -> DbOp.Delete(op.row.id)
        // 会话内新增又删除的行从未落库，正向 Delete 是空操作，反向不能再 Insert 回来
        is DbOp.Delete ->
            ws.byId(op.id)?.takeIf { !it.isNew }?.let {
                DbOp.Insert(
                    InsertRow(
                        id = it.dbId,
                        title = it.title,
                        intent = it.intent,
                        container = it.container,
                        screen = it.screen,
                        cellX = it.cellX,
                        cellY = it.cellY,
                        spanX = it.spanX,
                        spanY = it.spanY,
                        itemType = it.itemCode,
                        iconPackage = it.iconPackage,
                        iconResource = it.iconResource,
                    ),
                )
            }
        is DbOp.InsertScreen -> DbOp.DeleteScreen(op.screenId)
        is DbOp.DeleteScreen ->
            ws.pages.firstOrNull { it.screenId == op.screenId }?.let { DbOp.InsertScreen(it.screenId, it.originalOrder) }
        is DbOp.UpdateScreenOrder ->
            ws.pages.firstOrNull { it.screenId == op.screenId }?.let { DbOp.UpdateScreenOrder(it.screenId, it.originalOrder) }
    }
}
