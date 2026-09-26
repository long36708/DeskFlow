package com.deskflow.domain.layout

import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ScreenPage
import com.deskflow.domain.model.Workspace

/** 快照式撤销栈：每次编辑前 push，undo 时整体回滚 items/pages。 */
class WorkspaceHistory(private val ws: Workspace, private val limit: Int = 20) {

    private val stack = ArrayDeque<Pair<List<ItemState>, List<ScreenPage>>>()

    val canUndo: Boolean get() = stack.isNotEmpty()

    fun push() {
        stack.addLast(ws.items.map { it.copyState() } to ws.pages.map { it.copyState() })
        while (stack.size > limit) stack.removeFirst()
    }

    fun undo(): Boolean {
        val snap = stack.removeLastOrNull() ?: return false
        ws.items.clear()
        ws.items.addAll(snap.first)
        ws.pages.clear()
        ws.pages.addAll(snap.second)
        return true
    }

    fun clear() = stack.clear()
}
