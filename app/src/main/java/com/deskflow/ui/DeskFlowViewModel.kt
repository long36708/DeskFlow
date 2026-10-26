package com.deskflow.ui

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.drawable.Drawable
import com.deskflow.data.backup.BackupEntry
import com.deskflow.data.backup.BackupManager
import com.deskflow.data.classifier.CategoryMappingLoader
import com.deskflow.data.db.ApplyOutcome
import com.deskflow.data.db.LauncherDbAccess
import com.deskflow.data.db.SafeWriter
import com.deskflow.data.db.VivoWorkspaceHolder
import com.deskflow.domain.classifier.ArrangeResult
import com.deskflow.domain.classifier.AutoArranger
import com.deskflow.domain.classifier.CategoryRules
import com.deskflow.domain.classifier.DefaultRules
import com.deskflow.domain.layout.DiffCalculator
import com.deskflow.domain.layout.DiffPlan
import com.deskflow.domain.layout.ReversePlanner
import com.deskflow.domain.layout.WorkspaceEditor
import com.deskflow.domain.layout.WorkspaceHistory
import com.deskflow.domain.layout.commitApplied
import com.deskflow.domain.model.CONTAINER_DESKTOP
import com.deskflow.domain.model.CONTAINER_DOCK
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.ScreenPage
import com.deskflow.domain.model.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { Loading, Ready, Applying, NoRoot, Error }

/** 应用选择列表的一行：intent 即 vivo 桌面行的 `pkg/activity` 组件格式。 */
data class AppEntry(val intent: String, val label: String, val pkg: String)

/** 写回前发现桌面被外部改动的提示；isReverse 标记被拦下的是正向应用还是撤销上次应用。 */
data class ConflictPrompt(val changes: String, val isReverse: Boolean)

const val DOCK_PAGE_ID = -101

private const val ICON_PX = 128

class DeskFlowViewModel(app: Application) : AndroidViewModel(app) {

    private val safeWriter = SafeWriter(app)
    private val pm = app.packageManager

    var phase by mutableStateOf(Phase.Loading)
        private set
    var revision by mutableIntStateOf(0)
    var currentScreenId by mutableIntStateOf(0)
    var selected by mutableStateOf<ItemState?>(null)
    var folderOpen by mutableStateOf<ItemState?>(null)
    var confirmPlan by mutableStateOf<DiffPlan?>(null)
    var confirmDelete by mutableStateOf<ItemState?>(null)
    var renameTarget by mutableStateOf<ItemState?>(null)
    var showBackups by mutableStateOf(false)
    var showAppPicker by mutableStateOf(false)
    var appQuery by mutableStateOf("")
    var arrangePreview by mutableStateOf<ArrangeResult?>(null)
    var showPages by mutableStateOf(false)
    var showSearch by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var loadError by mutableStateOf<String?>(null)

    // ---------- 批量多选 ----------
    var multiSelectMode by mutableStateOf(false)
        private set
    val multiSelected = mutableStateListOf<ItemState>()
    var confirmDeleteMulti by mutableStateOf(false)
    var conflictPrompt by mutableStateOf<ConflictPrompt?>(null)
    var confirmUndoApply by mutableStateOf(false)
    var lastReversePlan by mutableStateOf<DiffPlan?>(null)
        private set

    /** 必须用快照 map：图标由 IO 线程异步写入，网格在组合期直接观察逐项刷新；
     *  普通 map 写完后网格不会重组（网格子树不读 revision），首次打开图标一直是灰块。 */
    private val iconCache = mutableStateMapOf<Long, ImageBitmap?>()

    var workspace by mutableStateOf<Workspace?>(null)
        private set
    private var editor: WorkspaceEditor? = null
    private var history: WorkspaceHistory? = null
    private var maxId = 0L
    private var rules: CategoryRules = DefaultRules.rules

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            phase = Phase.Loading
            rules = DefaultRules.rules.merge(
                withContext(Dispatchers.IO) { CategoryMappingLoader.load(getApplication()) },
            )
            if (!safeWriter.rootAvailable()) {
                phase = Phase.NoRoot
                return@launch
            }
            val result = safeWriter.load()
            result.onFailure {
                phase = Phase.Error
                loadError = it.message
            }.onSuccess { holder ->
                workspace = holder.workspace
                editor = WorkspaceEditor(holder.workspace)
                history = WorkspaceHistory(holder.workspace)
                maxId = holder.maxId
                currentScreenId = holder.workspace.pages.firstOrNull()?.screenId ?: DOCK_PAGE_ID
                selected = null
                multiSelectMode = false
                multiSelected.clear()
                conflictPrompt = null
                confirmUndoApply = false
                phase = Phase.Ready
                preloadIcons(holder.workspace)
            }
        }
    }

    private fun preloadIcons(ws: Workspace) {
        viewModelScope.launch(Dispatchers.IO) {
            ws.liveItems.forEach { item ->
                if (!iconCache.containsKey(item.dbId)) {
                    iconCache[item.dbId] = runCatching { loadIcon(item) }.getOrNull()
                }
            }
            revision++
        }
    }

    /**
     * 图标解析链：
     * 1. intent 里的组件 → getActivityIcon
     * 2. intent 的包名 → getApplicationIcon
     * 3. iconPackage + iconResource 列 → Resources.getDrawable（双开 / 快捷方式 intent 为空时兜底）
     * 4. componentPackage 或 iconPackage → getApplicationIcon
     */
    private fun loadIcon(item: ItemState): ImageBitmap? {
        if (item.isFolder) return null
        val comp = item.intent.takeIf { it.contains('/') }
        if (comp != null) {
            val idx = comp.lastIndexOf('/')
            val pkg = comp.substring(0, idx)
            val cls = comp.substring(idx + 1)
            if (pkg.isNotEmpty() && cls.isNotEmpty()) {
                iconOf { pm.getActivityIcon(ComponentName(pkg, cls)) }?.let { return it }
            }
            if (pkg.isNotEmpty()) iconOf { pm.getApplicationIcon(pkg) }?.let { return it }
        }
        resolveFromResourceColumns(item)?.let { return it }
        val fallbackPkg = item.componentPackage()?.takeIf { it.isNotEmpty() } ?: item.iconPackage
        if (!fallbackPkg.isNullOrEmpty()) iconOf { pm.getApplicationIcon(fallbackPkg) }?.let { return it }
        return null
    }

    private fun iconOf(block: () -> Drawable): ImageBitmap? =
        runCatching { block().toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()

    private fun resolveFromResourceColumns(item: ItemState): ImageBitmap? {
        val pkg = item.iconPackage?.takeIf { it.isNotEmpty() } ?: return null
        val raw = item.iconResource?.takeIf { it.isNotEmpty() } ?: return null
        val res = runCatching { pm.getResourcesForApplication(pkg) }.getOrNull() ?: return null
        val name = raw.substringAfterLast(':').substringAfterLast('/').substringBefore('.')
        if (name.isEmpty()) return null
        val id = res.getIdentifier(name, "drawable", pkg).takeIf { it != 0 }
            ?: res.getIdentifier(name, "mipmap", pkg).takeIf { it != 0 }
            ?: return null
        return iconOf { res.getDrawable(id, null) }
    }

    fun iconFor(item: ItemState): ImageBitmap? = iconCache[item.dbId]

    /** 新增行写库后 id 从临时值变成真实值，缓存要跟着换 key，否则图标会变灰块。 */
    private fun remapIconCache(idMap: Map<Long, Long>) {
        idMap.forEach { (temp, real) ->
            iconCache.remove(temp)?.let { iconCache[real] = it }
        }
    }

    // ---------- editing operations ----------

    fun isDockPage(): Boolean = currentScreenId == DOCK_PAGE_ID

    fun visibleItems(): List<ItemState> {
        val ws = workspace ?: return emptyList()
        return if (isDockPage()) ws.dockItems() else ws.pageItems(currentScreenId)
    }

    fun tapItem(item: ItemState) {
        if (multiSelectMode) {
            when {
                item.readOnly -> Unit
                // 多选模式下点文件夹 = 把已选项整体收进去；文件夹本身不可加选
                item.isFolder -> if (multiSelected.isNotEmpty()) moveMultiSelectionIntoFolder(item)
                else -> if (!multiSelected.remove(item)) multiSelected.add(item)
            }
            return
        }
        val sel = selected
        if (sel == null) {
            if (item.isFolder && !item.readOnly) folderOpen = item else selected = item
            revision++
            return
        }
        if (sel === item) {
            selected = null
            revision++
            return
        }
        edit { ed ->
            val ok = ed.dropOnItem(sel, item)
            if (ok) selected = null else selected = item
            ok
        }
    }

    fun tapCell(x: Int, y: Int) {
        val sel = selected ?: return
        val (container, screen) = containerForPage()
        edit { ed ->
            val ok = ed.placeAt(sel, container, screen, x, y)
            if (ok) selected = null
            ok
        }
    }

    fun dropOnCell(item: ItemState, x: Int, y: Int) {
        val (container, screen) = containerForPage()
        val occupant = occupantsFor(container, screen).firstOrNull { o ->
            o !== item && o.cellX <= x && x < o.cellX + o.spanX && o.cellY <= y && y < o.cellY + o.spanY
        }
        edit { ed ->
            if (occupant == null) {
                ed.placeAt(item, container, screen, x, y)
            } else if (ed.dropOnItem(item, occupant)) {
                true
            } else {
                ed.placeAt(item, container, screen, occupant.cellX, occupant.cellY)
            }
        }
        selected = null
    }

    fun moveSelectionToPage(screenId: Int) {
        val sel = selected ?: return
        val (container, screen) = containerPage(screenId)
        edit { ed ->
            val ok = ed.placeFirstFree(sel, container, screen)
            if (ok) selected = null
            ok
        }
        currentScreenId = screenId
    }

    fun confirmDeleteNow() {
        val sel = confirmDelete ?: return
        confirmDelete = null
        // 不能回落到已标记删除的页上，否则子项会散落到一张即将消失的页
        val restore = visiblePages().firstOrNull()?.screenId ?: 0
        edit { ed ->
            val ok = ed.delete(sel, restore)
            if (ok) selected = null
            ok
        }
    }

    fun ejectChild(child: ItemState) {
        edit { ed -> ed.ejectChild(child, currentScreenId) }
    }

    /** 打开重命名输入框；只有文件夹的标题存在库里，图标名改了不生效所以不开放。 */
    fun requestRename(folder: ItemState) {
        if (!folder.isFolder) return
        renameTarget = folder
    }

    fun commitRename(raw: String) {
        val folder = renameTarget ?: return
        renameTarget = null
        var ok = false
        edit { ed ->
            ok = ed.renameFolder(folder, raw)
            ok
        }
        if (!ok) message = "名称不能为空"
    }

    fun cancelSelection() {
        selected = null
        revision++
    }

    // ---------- 批量多选 ----------

    fun startMultiSelect(seed: ItemState? = null) {
        selected = null
        folderOpen = null
        confirmDelete = null
        arrangePreview = null
        multiSelectMode = true
        multiSelected.clear()
        seed?.takeIf { !it.readOnly }?.let { multiSelected.add(it) }
        revision++
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        multiSelected.clear()
        confirmDeleteMulti = false
        revision++
    }

    fun selectAllOnPage() {
        visibleItems().forEach { item ->
            // 文件夹与点选语义一致：不可加选（点它=整批收进）
            if (!item.readOnly && !item.isFolder && !multiSelected.contains(item)) multiSelected.add(item)
        }
    }

    /** 整批移到某页/Dock 的空位；放不下的留在原位并计入提示。一次 edit = 一次撤销。 */
    fun moveMultiSelectionToPage(screenId: Int) {
        val batch = multiSelected.toList()
        if (batch.isEmpty()) return
        val (container, screen) = containerPage(screenId)
        val movedItems = mutableListOf<ItemState>()
        edit { ed ->
            batch.forEach { item ->
                if (ed.placeFirstFree(item, container, screen)) movedItems += item
            }
            movedItems.isNotEmpty()
        }
        multiSelected.removeAll(movedItems)
        currentScreenId = screenId
        message = batchResultMessage("已移动 ${movedItems.size} 项", batch.size, movedItems.size)
        if (multiSelected.isEmpty()) exitMultiSelect()
    }

    /** 整批收进已有文件夹；已在夹内的、文件夹本身（不可嵌套）跳过。 */
    fun moveMultiSelectionIntoFolder(folder: ItemState) {
        if (!folder.isFolder || folder.readOnly) return
        val batch = multiSelected.filter { it !== folder && !it.isFolder && it.container != folder.dbId }
        if (batch.isEmpty()) return
        val movedItems = mutableListOf<ItemState>()
        edit { ed ->
            batch.forEach { item ->
                if (ed.placeFirstFree(item, folder.dbId, folder.screen)) movedItems += item
            }
            movedItems.isNotEmpty()
        }
        multiSelected.removeAll(movedItems)
        message = batchResultMessage("已收进 ${movedItems.size} 项到「${folder.title}」", batch.size, movedItems.size)
        if (multiSelected.isEmpty()) exitMultiSelect()
    }

    private fun batchResultMessage(head: String, total: Int, moved: Int): String = buildString {
        append(head)
        val skipped = total - moved
        if (skipped > 0) append("，$skipped 项放不下留在原位")
        append("，点「应用」写入桌面")
    }

    /** 删除需二次确认：先弹窗，确认后走 [confirmDeleteNow]。 */
    fun requestDelete() {
        if (multiSelectMode) {
            if (multiSelected.isNotEmpty()) confirmDeleteMulti = true
            return
        }
        confirmDelete = selected ?: return
    }

    fun confirmDeleteMultiNow() {
        if (!confirmDeleteMulti) return
        confirmDeleteMulti = false
        val batch = multiSelected.toList()
        // 不能回落到已标记删除的页上，否则子项会散落到一张即将消失的页
        val restore = visiblePages().firstOrNull()?.screenId ?: 0
        var deleted = 0
        edit { ed ->
            batch.forEach { item ->
                if (ed.delete(item, restore)) deleted++
            }
            deleted > 0
        }
        exitMultiSelect()
        message = "已删除 $deleted 项（可用「更多 → 撤销上一步」回退），点「应用」写入桌面"
    }

    // ---------- app picker（添加应用到桌面） ----------

    private var appEntriesCache: List<AppEntry>? = null
    private val appIconCache = mutableMapOf<String, ImageBitmap?>()

    fun appEntries(): List<AppEntry> {
        appEntriesCache?.let { return it }
        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        val entries = pm.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { ri ->
                val info = ri.activityInfo ?: return@mapNotNull null
                AppEntry(intent = "${info.packageName}/${info.name}", label = ri.loadLabel(pm).toString(), pkg = info.packageName)
            }
            .distinctBy { it.intent }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
        appEntriesCache = entries
        return entries
    }

    /** 已在桌面上的 intent；选择列表据此置灰。 */
    fun desktopIntents(): Set<String> =
        workspace?.liveItems?.map { it.intent }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun filteredAppEntries(): List<AppEntry> {
        val q = appQuery.trim().lowercase()
        val all = appEntries()
        if (q.isEmpty()) return all
        return all.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
    }

    suspend fun appIcon(intent: String): ImageBitmap? = withContext(Dispatchers.IO) {
        appIconCache.getOrPut(intent) {
            val idx = intent.lastIndexOf('/')
            if (idx <= 0) null
            else runCatching {
                pm.getActivityIcon(ComponentName(intent.substring(0, idx), intent.substring(idx + 1)))
                    .toBitmap(ICON_PX, ICON_PX).asImageBitmap()
            }.getOrNull()
        }
    }

    fun addAppToDesktop(entry: AppEntry) {
        if (desktopIntents().contains(entry.intent)) {
            message = "「${entry.label}」已在桌面上"
            return
        }
        val (container, screen) = containerForPage()
        var added = false
        edit { ed ->
            added = ed.addApp(entry.intent, entry.label, container, screen) != null
            added
        }
        if (!added) {
            message = if (isDockPage()) "Dock 已满" else "该页已满：切到其它页再添加，或先在「页面」里新增一页"
            return
        }
        showAppPicker = false
        val pageLabel = if (isDockPage()) "Dock"
        else "第${visiblePages().indexOfFirst { it.screenId == currentScreenId } + 1}页"
        message = "已添加「${entry.label}」到${pageLabel}，点『应用』写入桌面"
    }

    fun undo() {
        val h = history ?: return
        if (!h.undo()) {
            message = "没有可撤销的操作"
            return
        }
        selected = null
        folderOpen = null
        confirmDelete = null
        renameTarget = null
        arrangePreview = null
        multiSelectMode = false
        multiSelected.clear()
        confirmDeleteMulti = false
        if (currentScreenId != DOCK_PAGE_ID && visiblePages().none { it.screenId == currentScreenId }) {
            currentScreenId = visiblePages().firstOrNull()?.screenId ?: DOCK_PAGE_ID
        }
        revision++
        message = "已撤销上一步"
    }

    fun canUndo(): Boolean = history?.canUndo ?: false

    /** 所有改动 workspace 的操作统一入口：先压快照，失败则回滚。 */
    private fun edit(commit: (WorkspaceEditor) -> Boolean) {
        val ed = editor ?: return
        val h = history ?: return
        h.push()
        if (commit(ed)) revision++ else h.undo()
    }

    fun hasPendingChanges(): Boolean {
        val ws = workspace ?: return false
        return ws.items.any { it.isNew || it.isDeleted || it.positionChanged() || it.titleChanged() } ||
            ws.pages.any { it.isNew || it.isDeleted || it.order != it.originalOrder }
    }

    fun neighborScreen(dir: Int): Int? {
        val ws = workspace ?: return null
        val chips = listOf(DOCK_PAGE_ID) + ws.pages.map { it.screenId }
        if (currentScreenId == DOCK_PAGE_ID || dir == 0) return null
        val idx = chips.indexOf(currentScreenId)
        if (idx < 0) return null
        val target = idx + dir
        return chips.getOrNull(target)
    }

    fun dropToPage(item: ItemState, screenId: Int) {
        val (container, screen) = containerPage(screenId)
        edit { ed -> ed.placeFirstFree(item, container, screen) }
        selected = null
    }

    private fun containerForPage(): Pair<Long, Int> =
        if (isDockPage()) CONTAINER_DOCK to 0 else CONTAINER_DESKTOP to currentScreenId

    private fun containerPage(screenId: Int): Pair<Long, Int> =
        if (screenId == DOCK_PAGE_ID) CONTAINER_DOCK to 0 else CONTAINER_DESKTOP to screenId

    private fun occupantsFor(container: Long, screen: Int): List<ItemState> {
        val ws = workspace ?: return emptyList()
        return ws.liveItems.filter {
            it.container == container && (container != CONTAINER_DESKTOP || it.screen == screen)
        }
    }

    // ---------- auto arrange (Phase 2) ----------

    fun requestArrange() {
        val ws = workspace ?: return
        selected = null
        arrangePreview = AutoArranger.arrange(ws, rules)
    }

    /** 把预览的排布落到内存 workspace，真正写库仍需走「应用」。 */
    fun applyArrange() {
        val ws = workspace ?: return
        val result = arrangePreview ?: return
        edit { ed ->
            AutoArranger.apply(ws, result, ed)
            true
        }
        arrangePreview = null
        selected = null
        message = buildString {
            append("已收拢 ${result.placedCount} 项进 ${result.newFolderCount} 个文件夹")
            if (result.newScreens.isNotEmpty()) append("；新增 ${result.newScreens.size} 页")
            append("，${result.lockedCount} 项原位不动")
            append("，点『应用』写入桌面")
        }
    }

    // ---------- page management ----------

    fun visiblePages(): List<ScreenPage> =
        workspace?.pages?.filter { !it.isDeleted }?.sortedBy { it.order } ?: emptyList()

    fun pageItemCount(screenId: Int): Int = workspace?.pageItems(screenId)?.size ?: 0

    fun addPage() {
        var created: ScreenPage? = null
        edit { ed ->
            val page = ed.addPage()
            created = page
            page != null
        }
        val page = created
        if (page == null) {
            message = "新增页失败：无法分配页面 id"
            return
        }
        currentScreenId = page.screenId
        selected = null
        message = "已新增第 ${visiblePages().size} 页，点『应用』写入桌面"
    }

    fun movePage(screenId: Int, delta: Int) {
        edit { ed -> ed.movePage(screenId, delta) }
    }

    fun deletePage(screenId: Int) {
        var ok = false
        edit { ed ->
            ok = ed.deletePage(screenId)
            ok
        }
        if (!ok) {
            message = "无法删除该页：页上有 widget，或其余页空间不足容纳其图标"
            return
        }
        if (currentScreenId == screenId) {
            currentScreenId = visiblePages().firstOrNull()?.screenId ?: DOCK_PAGE_ID
        }
        selected = null
        message = "已删除该页（图标已迁到其它页），点『应用』写入桌面"
    }

    // ---------- search ----------

    fun searchResults(): List<ItemState> {
        val ws = workspace ?: return emptyList()
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return ws.liveItems
            .filter { it.title.lowercase().contains(q) || it.intent.lowercase().contains(q) }
            .take(50)
    }

    fun locationLabel(item: ItemState): String = when {
        item.container == CONTAINER_DOCK -> "Dock"
        item.container == CONTAINER_DESKTOP -> "第${visiblePages().indexOfFirst { it.screenId == item.screen } + 1}页"
        item.container >= 0 -> "文件夹 ${workspace?.byId(item.container)?.title ?: ""}".trim()
        else -> "其它"
    }

    /** 跳到该项所在页并选中；文件夹内的项则直接打开文件夹。 */
    fun locate(item: ItemState) {
        searchQuery = ""
        showSearch = false
        when {
            item.container == CONTAINER_DOCK -> currentScreenId = DOCK_PAGE_ID
            item.container == CONTAINER_DESKTOP -> currentScreenId = item.screen
            item.container > 0 -> {
                folderOpen = workspace?.byId(item.container)
                revision++
                return
            }
            else -> return
        }
        selected = item
        revision++
    }

    // ---------- apply / backup ----------

    fun requestApply() {
        val ws = workspace ?: return
        confirmPlan = DiffCalculator.plan(ws, maxId)
    }

    fun doApply() {
        confirmPlan = null
        runForwardApply(ignoreExternalChanges = false)
    }

    private fun runForwardApply(ignoreExternalChanges: Boolean) {
        val ws = workspace ?: return
        viewModelScope.launch {
            phase = Phase.Applying
            when (val outcome = safeWriter.apply(ws, ignoreExternalChanges)) {
                is ApplyOutcome.Success -> {
                    // 反向 plan 必须从实际执行的 plan 在 commitApplied 之前派生：被删行数据/坐标原值
                    // 此刻才可得；且 SafeWriter 可能用 fresh.maxId 重算过 Insert id，预计算的会错位
                    val reverse = if (outcome.plan.ops.isEmpty()) null else ReversePlanner.reverse(ws, outcome.plan)
                    maxId = maxOf(maxId, outcome.plan.insertedIds.maxOrNull() ?: 0L)
                    // 图标缓存按 dbId 存，新增行的 id 会变，先迁移再提交
                    remapIconCache(outcome.plan.idMap)
                    // 写库成功后必须把内存状态推进到新基线，否则二次应用会重复 INSERT
                    commitApplied(ws, outcome.plan)
                    history?.clear()
                    lastReversePlan = reverse
                    phase = Phase.Ready
                    message = "已应用（${outcome.plan}），备份 ${outcome.backupName}"
                }
                is ApplyOutcome.Conflict -> {
                    phase = Phase.Ready
                    conflictPrompt = ConflictPrompt(outcome.changes, isReverse = false)
                }
                is ApplyOutcome.Failed -> {
                    phase = Phase.Ready
                    message = "应用失败：${outcome.error.message}"
                }
                is ApplyOutcome.RolledBack -> {
                    // DB 已回到备份状态，内存 workspace 与之不一致，重新读取对齐
                    message = "验证不一致，已自动回滚到 ${outcome.backupName}"
                    lastReversePlan = null
                    load()
                }
            }
            revision++
        }
    }

    // ---------- 撤销上次应用 ----------

    /** 上次应用的改动还停留在库里的当前状态、且编辑器没有新的未写改动时才能反向回退。 */
    fun canUndoLastApply(): Boolean = lastReversePlan != null && !hasPendingChanges()

    fun requestUndoApply() {
        if (canUndoLastApply()) confirmUndoApply = true
    }

    fun undoLastApply() {
        if (lastReversePlan == null) return
        confirmUndoApply = false
        runReverseApply(ignoreExternalChanges = false)
    }

    private fun runReverseApply(ignoreExternalChanges: Boolean) {
        val plan = lastReversePlan ?: return
        viewModelScope.launch {
            phase = Phase.Applying
            when (val outcome = safeWriter.applyPlan(plan, ignoreExternalChanges)) {
                is ApplyOutcome.Success -> {
                    lastReversePlan = null
                    phase = Phase.Ready
                    message = "已撤销上次应用（备份 ${outcome.backupName}）"
                    load()
                }
                is ApplyOutcome.Conflict -> {
                    phase = Phase.Ready
                    conflictPrompt = ConflictPrompt(outcome.changes, isReverse = true)
                }
                is ApplyOutcome.Failed -> {
                    phase = Phase.Ready
                    message = "撤销失败：${outcome.error.message}"
                }
                is ApplyOutcome.RolledBack -> {
                    message = "撤销验证不一致，已自动回滚到 ${outcome.backupName}"
                    lastReversePlan = null
                    load()
                }
            }
            revision++
        }
    }

    fun confirmOverwriteConflict() {
        val prompt = conflictPrompt ?: return
        conflictPrompt = null
        if (prompt.isReverse) runReverseApply(ignoreExternalChanges = true)
        else runForwardApply(ignoreExternalChanges = true)
    }

    fun reloadInsteadOfConflict() {
        conflictPrompt = null
        load()
    }

    fun manualBackup() {
        viewModelScope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    val access = LauncherDbAccess(getApplication())
                    val work = access.pullWork()
                    val f = BackupManager(getApplication()).backup(work)
                    "已备份到 ${f.name}"
                }.getOrElse { "备份失败：${it.message}" }
            }
        }
    }

    fun backups(): List<BackupEntry> = safeWriter.backups()

    fun restoreBackup(name: String) {
        viewModelScope.launch {
            phase = Phase.Applying
            val result = safeWriter.restoreBackup(name)
            showBackups = false
            message = result.fold({ "已从 $name 恢复" }, { "恢复失败：${it.message}" })
            if (result.isSuccess) {
                // 库被整体替换，反向 plan 指向的状态已不存在
                lastReversePlan = null
                load()
            } else phase = Phase.Ready
        }
    }
}
