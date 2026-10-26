package com.deskflow.domain.layout

import com.deskflow.domain.model.Workspace

/**
 * 比较两次库快照（load 时 vs apply 时现读），摘要编辑期间桌面的外部改动。
 * 返回空列表表示无外部变化，可以安全写回。
 */
object WorkspaceDiff {

    fun summarize(before: Workspace, after: Workspace): List<String> = buildList {
        val old = before.items.associateBy { it.dbId }
        val new = after.items.associateBy { it.dbId }
        var moved = 0
        var respanned = 0
        var retitled = 0
        new.forEach { (id, item) ->
            val prev = old[id] ?: return@forEach
            if (item.container != prev.container || item.screen != prev.screen ||
                item.cellX != prev.cellX || item.cellY != prev.cellY
            ) {
                moved++
            }
            if (item.spanX != prev.spanX || item.spanY != prev.spanY) respanned++
            if (item.title != prev.title) retitled++
        }
        val added = new.count { it.key !in old }
        val removed = old.count { it.key !in new }
        val oldPages = before.pages.associate { it.screenId to it.order }
        val newPages = after.pages.associate { it.screenId to it.order }
        val pageAdded = newPages.count { it.key !in oldPages }
        val pageRemoved = oldPages.count { it.key !in newPages }
        val pageReordered = newPages.count { oldPages[it.key] != null && oldPages[it.key] != it.value }

        if (moved > 0) add("位置变动 $moved 项")
        if (respanned > 0) add("尺寸调整 $respanned 项")
        if (added > 0) add("新增 $added 项")
        if (removed > 0) add("移除 $removed 项")
        if (retitled > 0) add("改名 $retitled 项")
        if (pageAdded + pageRemoved + pageReordered > 0) {
            add("页面变化（增 $pageAdded / 删 $pageRemoved / 调序 $pageReordered）")
        }
    }
}
