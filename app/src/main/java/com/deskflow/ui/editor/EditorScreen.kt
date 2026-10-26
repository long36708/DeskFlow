package com.deskflow.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.deskflow.DOCK_PAGE_ID
import com.deskflow.ui.DeskFlowViewModel
import com.deskflow.ui.Phase
import com.deskflow.ui.arrange.ArrangeDialog
import com.deskflow.ui.arrange.ColdCollectDialog
import com.deskflow.ui.pages.PageManagerDialog
import com.deskflow.data.adapter.vivo.VivoConstants
import com.deskflow.domain.model.ItemState
import com.deskflow.domain.model.MAX_TITLE_LEN
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeskFlowApp(vm: DeskFlowViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    val layoutExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::exportLayout) }
    val layoutImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importLayout) }
    LaunchedEffect(vm.message) {
        val msg = vm.message ?: return@LaunchedEffect
        val hasAction = vm.messageActionLabel != null
        val result = snackbarHostState.showSnackbar(
            msg,
            actionLabel = vm.messageActionLabel,
            duration = if (hasAction) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        vm.message = null
        if (result == SnackbarResult.ActionPerformed) vm.messageAction?.invoke()
    }

    when (vm.phase) {
        Phase.Loading -> Centered("读取桌面布局中…（会复制一次 launcher 数据库）")
        Phase.NoRoot -> Centered("未获得 Root 权限。\n请在 Magisk/KernelSU 中授权 DeskFlow 后点击重试。", retry = vm::load)
        Phase.Error -> Centered("读取失败：${vm.loadError}", retry = vm::load)
        else -> {
            val ws = vm.workspace
            if (ws == null) {
                Centered("无数据")
            } else {
                val rev = vm.revision
                val pages = remember(ws, rev) { vm.visiblePages() }
                val chipIds = remember(pages) { listOf(DOCK_PAGE_ID) + pages.map { it.screenId } }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = { Text("DeskFlow · vivo", fontSize = 18.sp, maxLines = 1) },
                            actions = {
                                TextButton(onClick = { vm.showSearch = !vm.showSearch }) { Text("搜索") }
                                TextButton(onClick = { vm.showPages = true }) { Text("页面") }
                                OverflowMenu(
                                    vm,
                                    onExportLayout = {
                                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                                            .format(java.util.Date())
                                        layoutExportLauncher.launch("deskflow-layout-$stamp.json")
                                    },
                                    onImportLayout = { layoutImportLauncher.launch(arrayOf("application/json", "text/plain")) },
                                )
                                Button(
                                    onClick = vm::requestApply,
                                    enabled = vm.phase == Phase.Ready && vm.hasPendingChanges(),
                                ) { Text(if (vm.phase == Phase.Applying) "应用中…" else "应用") }
                            },
                        )
                    },
                ) { pad ->
                    Column(Modifier.fillMaxSize().padding(pad)) {
                        if (vm.showSearch) SearchPanel(vm)

                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            items(chipIds) { id ->
                                AssistChip(
                                    onClick = {
                                        when {
                                            vm.multiSelectMode -> vm.moveMultiSelectionToPage(id)
                                            vm.selected != null -> vm.moveSelectionToPage(id)
                                            else -> vm.currentScreenId = id
                                        }
                                    },
                                    label = {
                                        Text(
                                            if (id == DOCK_PAGE_ID) "Dock"
                                            else "第${pages.indexOfFirst { it.screenId == id } + 1}页($id)",
                                        )
                                    },
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                        }

                        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp)) {
                            val itemsOnPage = vm.visibleItems()
                            val grid = ws.grid
                            if (vm.isDockPage()) {
                                WorkGrid(
                                    vm, itemsOnPage,
                                    cols = grid.dockCells, rows = 1,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(grid.dockCells.toFloat()).align(Alignment.TopCenter),
                                )
                            } else {
                                WorkGrid(vm, itemsOnPage, cols = grid.columns, rows = grid.rows, modifier = Modifier.fillMaxSize())
                            }
                        }

                        HorizontalDivider()
                        SelectionBar(vm)
                    }
                }

                vm.folderOpen?.let { folder ->
                    FolderDialog(vm, folder, onDismiss = { vm.folderOpen = null })
                }
                vm.renameTarget?.let { item -> RenameDialog(vm, item) }
                vm.confirmDelete?.let { item ->
                    val childCount = if (item.isFolder) vm.workspace?.childrenOf(item)?.size ?: 0 else 0
                    AlertDialog(
                        onDismissRequest = { vm.confirmDelete = null },
                        title = { Text("删除「${item.title}」？") },
                        text = {
                            Text(
                                if (item.isFolder) {
                                    "文件夹内的 $childCount 个图标会散落到桌面的空位上。可用「更多 → 撤销上一步」回退。"
                                } else {
                                    "该图标将从桌面移除（不会卸载应用）。可用「更多 → 撤销上一步」回退。"
                                },
                            )
                        },
                        confirmButton = { Button(onClick = vm::confirmDeleteNow) { Text("删除") } },
                        dismissButton = { TextButton(onClick = { vm.confirmDelete = null }) { Text("取消") } },
                    )
                }
                if (vm.confirmDeleteMulti) {
                    AlertDialog(
                        onDismissRequest = { vm.confirmDeleteMulti = false },
                        title = { Text("删除所选 ${vm.multiSelected.size} 项？") },
                        text = { Text("所选图标将从桌面移除（不会卸载应用）。可用「更多 → 撤销上一步」回退。") },
                        confirmButton = { Button(onClick = vm::confirmDeleteMultiNow) { Text("删除") } },
                        dismissButton = { TextButton(onClick = { vm.confirmDeleteMulti = false }) { Text("取消") } },
                    )
                }
                vm.conflictPrompt?.let { prompt ->
                    AlertDialog(
                        onDismissRequest = { vm.conflictPrompt = null },
                        title = { Text("桌面数据已被外部修改") },
                        text = {
                            Column {
                                Text("自读取以来 launcher.db 被外部（桌面自身）改动：${prompt.changes}。")
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    "「仍要写入」会覆盖以上改动；「重新读取」放弃写入、同步最新布局。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!prompt.isReverse) {
                                    Spacer(Modifier.size(6.dp))
                                    Text(
                                        "注意：「重新读取」会丢弃当前未应用的编辑。",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        confirmButton = { Button(onClick = vm::confirmOverwriteConflict) { Text("仍要写入") } },
                        dismissButton = { TextButton(onClick = vm::reloadInsteadOfConflict) { Text("重新读取") } },
                    )
                }
                if (vm.confirmUndoApply) {
                    AlertDialog(
                        onDismissRequest = { vm.confirmUndoApply = false },
                        title = { Text("撤销上次应用到桌面？") },
                        text = {
                            Text(
                                "按反向操作写回（${vm.lastReversePlan}），恢复到上次应用前的布局。\n" +
                                    "写回前会自动备份并逐条验证。",
                            )
                        },
                        confirmButton = { Button(onClick = vm::undoLastApply) { Text("撤销") } },
                        dismissButton = { TextButton(onClick = { vm.confirmUndoApply = false }) { Text("取消") } },
                    )
                }
                vm.confirmPlan?.let { plan ->
                    if (plan.ops.isEmpty()) {
                        LaunchedEffect(Unit) { vm.message = "没有待应用的改动"; vm.confirmPlan = null }
                    } else {
                        AlertDialog(
                            onDismissRequest = { vm.confirmPlan = null },
                            title = { Text("应用到桌面？") },
                            text = {
                                Text(
                                    "$plan\n\n将写入 ${VivoConstants.LAUNCHER_PACKAGE} 的 launcher.db，" +
                                        "并强制重启桌面。已自动备份，验证失败会自动回滚。",
                                )
                            },
                            confirmButton = { Button(onClick = vm::doApply) { Text("确认应用") } },
                            dismissButton = { TextButton(onClick = { vm.confirmPlan = null }) { Text("取消") } },
                        )
                    }
                }
                vm.arrangePreview?.let { result ->
                    ArrangeDialog(vm, result, onDismiss = { vm.arrangePreview = null })
                }
                if (vm.showPages) PageManagerDialog(vm, onDismiss = { vm.showPages = false })
                if (vm.showBackups) BackupsDialog(vm)
                if (vm.showAppPicker) AppPickerDialog(vm, onDismiss = { vm.showAppPicker = false })
                if (vm.showUsagePermDialog) {
                    AlertDialog(
                        onDismissRequest = { vm.showUsagePermDialog = false },
                        title = { Text("需要「使用情况访问」权限") },
                        text = {
                            Text(
                                "判断冷门应用需要读取系统使用统计。\n" +
                                    "请在接下来的列表里找到 DeskFlow 并允许「使用情况访问」，" +
                                    "然后回到这里重新点「更多 → 冷门收夹」。",
                            )
                        },
                        confirmButton = { Button(onClick = vm::openUsageAccessSettings) { Text("去授权") } },
                        dismissButton = { TextButton(onClick = { vm.showUsagePermDialog = false }) { Text("关闭") } },
                    )
                }
                vm.coldPreview?.let { plan ->
                    ColdCollectDialog(vm, plan, onDismiss = { vm.coldPreview = null })
                }
                vm.importPreview?.let { plan ->
                    LayoutImportDialog(vm, plan, onDismiss = { vm.importPreview = null })
                }
                vm.actionTarget?.let { item ->
                    ItemActionsDialog(vm, item, onDismiss = { vm.actionTarget = null })
                }
            }
        }
    }
}

@Composable
private fun Centered(text: String, retry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, fontSize = 15.sp)
            if (retry != null) OutlinedButton(onClick = retry, modifier = Modifier.padding(top = 12.dp)) { Text("重试") }
        }
    }
}

@Composable
private fun SelectionBar(vm: DeskFlowViewModel) {
    if (vm.multiSelectMode) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已选 ${vm.multiSelected.size} 项 · 点图标加减选，点文件夹收进，点页签整体移动",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { vm.selectAllOnPage() }) { Text("全选本页") }
            OutlinedButton(
                onClick = { vm.requestDelete() },
                enabled = vm.multiSelected.isNotEmpty(),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("删除") }
            TextButton(onClick = { vm.exitMultiSelect() }, modifier = Modifier.padding(start = 8.dp)) { Text("完成") }
        }
        return
    }
    val sel = vm.selected
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (sel == null) {
            Text(
                "点按选中图标 → 点空位移动 / 点其它图标合并成文件夹；长按拖拽移动；拖出左右边缘换页",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.startMultiSelect() }) { Text("多选") }
        } else {
            Text("已选: ${sel.title}", fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (!sel.readOnly) {
                OutlinedButton(onClick = { vm.requestRename(sel) }) { Text("重命名") }
            }
            OutlinedButton(onClick = vm::requestDelete, modifier = Modifier.padding(start = 8.dp)) { Text("删除") }
            TextButton(onClick = vm::cancelSelection, modifier = Modifier.padding(start = 8.dp)) { Text("取消") }
        }
    }
}

@Composable
private fun OverflowMenu(vm: DeskFlowViewModel, onExportLayout: () -> Unit, onImportLayout: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("更多") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("撤销上一步") },
                onClick = { expanded = false; vm.undo() },
                enabled = vm.canUndo(),
            )
            DropdownMenuItem(
                text = { Text("撤销上次应用") },
                onClick = { expanded = false; vm.requestUndoApply() },
                enabled = vm.canUndoLastApply(),
            )
            DropdownMenuItem(
                text = { Text(if (vm.multiSelectMode) "退出批量选择" else "批量选择") },
                onClick = {
                    expanded = false
                    if (vm.multiSelectMode) vm.exitMultiSelect() else vm.startMultiSelect()
                },
            )
            DropdownMenuItem(text = { Text("一键整理") }, onClick = { expanded = false; vm.requestArrange() })
            DropdownMenuItem(text = { Text("冷门收夹（30 天未用）") }, onClick = { expanded = false; vm.requestColdCollect() })
            DropdownMenuItem(
                text = { Text("导出布局方案") },
                onClick = {
                    expanded = false
                    onExportLayout()
                },
            )
            DropdownMenuItem(
                text = { Text("导入布局方案") },
                onClick = {
                    expanded = false
                    onImportLayout()
                },
            )
            DropdownMenuItem(text = { Text("添加应用") }, onClick = { expanded = false; vm.showAppPicker = true })
            DropdownMenuItem(text = { Text("备份 / 恢复") }, onClick = { expanded = false; vm.showBackups = true })
            DropdownMenuItem(text = { Text("重新读取桌面") }, onClick = { expanded = false; vm.load() })
        }
    }
}

@Composable
private fun SearchPanel(vm: DeskFlowViewModel) {
    Column {
        OutlinedTextField(
            value = vm.searchQuery,
            onValueChange = { vm.searchQuery = it },
            placeholder = { Text("搜索应用名 / 包名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )
        val (hits, total) = remember(vm.searchQuery, vm.revision) { vm.searchResults() }
        when {
            vm.searchQuery.isBlank() -> Unit
            hits.isEmpty() -> Text(
                "无匹配",
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column {
                Text(
                    "命中 $total 项" + if (total > hits.size) "（显示前 ${hits.size}）" else "",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    items(hits) { hit ->
                        AssistChip(
                            onClick = { vm.requestItemActions(hit) },
                            label = { Text("${hit.title.take(12)} · ${vm.locationLabel(hit)}", maxLines = 1) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 搜索命中项的快捷操作：打开 / 强制停止 / 应用信息 / 定位。无组件 intent 的项（夹、widget）只有定位。 */
@Composable
private fun ItemActionsDialog(vm: DeskFlowViewModel, item: ItemState, onDismiss: () -> Unit) {
    val launchable = vm.actionTargetLaunchable(item)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bmp = vm.iconFor(item)
                if (bmp != null) {
                    Image(bmp, contentDescription = null, modifier = Modifier.size(28.dp))
                } else {
                    Box(Modifier.size(28.dp).background(Color(0x33999999), RoundedCornerShape(6.dp)))
                }
                Text("  ${item.title.take(14)}", fontSize = 16.sp, maxLines = 1)
            }
        },
        text = {
            Column {
                if (launchable) {
                    Text(
                        item.intent.substringBeforeLast('/'),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.size(2.dp))
                    ActionRow("打开应用") { vm.launchApp(item) }
                    ActionRow("强制停止") { vm.forceStopApp(item) }
                    ActionRow("应用信息") { vm.openAppDetails(item) }
                }
                ActionRow("定位到桌面") {
                    vm.actionTarget = null
                    vm.locate(item)
                }
                if (launchable) {
                    Text(
                        "「强制停止」经 root shell 执行 am force-stop，应用的后台推送会暂停到它下次启动。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun WorkGrid(
    vm: DeskFlowViewModel,
    pageItems: List<ItemState>,
    cols: Int,
    rows: Int,
    modifier: Modifier = Modifier,
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragItem by remember { mutableStateOf<ItemState?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragPageSwitch by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current

    val cellW = if (sizePx.width == 0) 0 else sizePx.width / cols
    val cellH = if (sizePx.height == 0) 0 else sizePx.height / rows

    Box(
        modifier
            .clipToBounds()
            .onGloballyPositioned { gridCoords = it }
            .onSizeChanged { sizePx = it },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(cols, rows) {
                    detectTapGestures { off ->
                        if (cellW > 0 && cellH > 0) {
                            vm.tapCell(
                                (off.x / cellW).toInt().coerceIn(0, cols - 1),
                                (off.y / cellH).toInt().coerceIn(0, rows - 1),
                            )
                        }
                    }
                },
        ) {
            val cw = size.width / cols
            val ch = size.height / rows
            for (i in 1 until cols) drawLine(Color(0x22888888), Offset(cw * i, 0f), Offset(cw * i, size.height), 1f)
            for (i in 1 until rows) drawLine(Color(0x22888888), Offset(0f, ch * i), Offset(size.width, ch * i), 1f)
        }

        // 遍历顺序必须稳定：把被拖项挪到末尾会让它的 pointerInput 节点被销毁重建，
        // 拖拽协程在 onDragStart 当帧就被取消（表现为长按拖拽完全无反应）。提层改用 zIndex。
        pageItems.forEach { item ->
            val dragging = dragItem === item
            val xPx = (item.cellX * cellW + if (dragging) dragOffset.x.roundToInt() else 0)
            val yPx = (item.cellY * cellH + if (dragging) dragOffset.y.roundToInt() else 0)
            val wDp = with(density) { (item.spanX * cellW).toDp() }
            val hDp = with(density) { (item.spanY * cellH).toDp() }

            ItemCell(
                vm = vm,
                item = item,
                dragging = dragging,
                selected = vm.selected === item,
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .offset { IntOffset(xPx, yPx) }
                    .size(wDp, hDp)
                    .pointerInput(item, cellW, cellH, vm.multiSelectMode) {
                        if (cellW == 0 || cellH == 0) return@pointerInput
                        if (vm.multiSelectMode) return@pointerInput // 多选下禁拖：点选语义与拖拽冲突
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragItem = item
                                dragOffset = Offset.Zero
                                dragPageSwitch = null
                            },
                            onDrag = { change, pan ->
                                dragOffset += pan
                                change.consume()
                                if (!vm.isDockPage()) {
                                    val lx = gridCoords?.windowToLocal(change.position)?.x ?: change.position.x
                                    val w = sizePx.width
                                    if (lx < -cellW * 0.5f) {
                                        val t = vm.neighborScreen(-1)
                                        if (t != null && t != vm.currentScreenId) {
                                            vm.currentScreenId = t
                                            dragPageSwitch = t
                                        }
                                    } else if (lx > w + cellW * 1.5f) {
                                        val t = vm.neighborScreen(1)
                                        if (t != null && t != vm.currentScreenId) {
                                            vm.currentScreenId = t
                                            dragPageSwitch = t
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                val switched = dragPageSwitch
                                val movedX = (dragOffset.x / cellW).roundToInt()
                                val movedY = (dragOffset.y / cellH).roundToInt()
                                dragItem = null
                                dragOffset = Offset.Zero
                                if (switched != null) {
                                    vm.dropToPage(item, switched)
                                } else {
                                    vm.dropOnCell(
                                        item,
                                        (item.cellX + movedX).coerceIn(0, cols - 1),
                                        (item.cellY + movedY).coerceIn(0, rows - 1),
                                    )
                                }
                            },
                            onDragCancel = {
                                dragItem = null
                                dragOffset = Offset.Zero
                            },
                        )
                    }
                    .pointerInput(item) {
                        detectTapGestures { vm.tapItem(item) }
                    },
            )
        }
    }
}

@Composable
private fun ItemCell(
    vm: DeskFlowViewModel,
    item: ItemState,
    dragging: Boolean,
    selected: Boolean,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val multiSelected = vm.multiSelectMode && vm.multiSelected.contains(item)
    Box(
        modifier
            .padding(2.dp)
            .background(
                when {
                    dragging -> Color(0x6642A5F5)
                    multiSelected -> Color(0x554CAF50)
                    selected -> Color(0x55FFB300)
                    else -> Color.Transparent
                },
                shape,
            ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (item.isFolder) {
                    FolderFace(vm, item)
                } else {
                    val bmp = vm.iconFor(item)
                    if (bmp != null) {
                        Image(bmp, contentDescription = item.title, modifier = Modifier.fillMaxSize().padding(5.dp))
                    } else {
                        Box(Modifier.size(36.dp).background(Color(0x33999999), shape))
                    }
                    if (vm.isColdItem(item)) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(3.dp).size(6.dp)
                                .background(Color(0x88999999), CircleShape),
                        )
                    }
                }
            }
            Text(
                item.title.substringAfterLast('/').take(20),
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FolderFace(vm: DeskFlowViewModel, folder: ItemState) {
    val ws = vm.workspace ?: return
    val children = ws.childrenOf(folder).take(4)
    Box(
        Modifier.fillMaxSize().padding(4.dp).background(Color(0x22000000), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            children.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.Center) {
                    row.forEach { child ->
                        val bmp = vm.iconFor(child)
                        if (bmp != null) {
                            Image(bmp, contentDescription = null, modifier = Modifier.size(18.dp).padding(1.dp))
                        } else {
                            Box(Modifier.size(18.dp).padding(1.dp).background(Color(0x55999999), RoundedCornerShape(4.dp)))
                        }
                    }
                    repeat(2 - row.size) { Box(Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FolderDialog(vm: DeskFlowViewModel, folder: ItemState, onDismiss: () -> Unit) {
    val ws = vm.workspace ?: return
    val children = ws.childrenOf(folder)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${folder.title}（${children.size}，点图标移回桌面）") },
        text = {
            Column {
                children.chunked(4).forEach { rowItems ->
                    Row {
                        rowItems.forEach { child ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { vm.ejectChild(child) }.padding(6.dp),
                            ) {
                                val bmp = vm.iconFor(child)
                                if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(44.dp))
                                else Box(Modifier.size(44.dp).background(Color(0x33999999), RoundedCornerShape(8.dp)))
                                Text(child.title.take(6), fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = { vm.requestRename(folder) }) { Text("重命名") }
        },
    )
}

@Composable
private fun RenameDialog(vm: DeskFlowViewModel, item: ItemState) {
    var text by remember(item) { mutableStateOf(item.title) }
    AlertDialog(
        onDismissRequest = { vm.renameTarget = null },
        title = { Text(if (item.isFolder) "重命名文件夹" else "重命名图标") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_TITLE_LEN) },
                    placeholder = { Text(if (item.isFolder) "文件夹名称" else "图标名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "最多 $MAX_TITLE_LEN 个字；保存后仍需点「应用」写入桌面。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { Button(onClick = { vm.commitRename(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { vm.renameTarget = null }) { Text("取消") } },
    )
}

@Composable
private fun AppPickerDialog(vm: DeskFlowViewModel, onDismiss: () -> Unit) {
    val entries = remember(vm.appQuery, vm.revision) { vm.filteredAppEntries() }
    val onDesktop = remember(vm.revision) { vm.desktopIntents() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加应用到 ${if (vm.isDockPage()) "Dock" else "当前页"}（${entries.size}）") },
        text = {
            Column {
                OutlinedTextField(
                    value = vm.appQuery,
                    onValueChange = { vm.appQuery = it },
                    placeholder = { Text("按应用名 / 包名过滤") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(6.dp))
                LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                    items(entries) { entry ->
                        val placed = entry.intent in onDesktop
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !placed) { vm.addAppToDesktop(entry) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(vm, entry.intent)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(entry.label.take(20), fontSize = 14.sp, maxLines = 1)
                                Text(entry.pkg, fontSize = 10.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (placed) {
                                Text("已在桌面", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                TextButton(onClick = { vm.addAppToDesktop(entry) }) { Text("添加") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun AppIcon(vm: DeskFlowViewModel, intent: String) {
    var bmp by remember(intent) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(intent) { bmp = vm.appIcon(intent) }
    val icon = bmp
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
    } else {
        Box(Modifier.size(36.dp).background(Color(0x33999999), RoundedCornerShape(8.dp)))
    }
}

@Composable
private fun BackupsDialog(vm: DeskFlowViewModel) {
    val backups = vm.backups()
    AlertDialog(
        onDismissRequest = { vm.showBackups = false },
        title = { Text("备份（保留最近 10 份）") },
        text = {
            Column {
                OutlinedButton(onClick = vm::manualBackup, modifier = Modifier.fillMaxWidth()) { Text("立即备份当前桌面") }
                Spacer(Modifier.size(8.dp))
                if (backups.isEmpty()) Text("暂无备份")
                backups.forEach { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.file.name.substringBefore(".db"), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.restoreBackup(entry.file.name) }) { Text("恢复") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { vm.showBackups = false }) { Text("关闭") } },
    )
}
