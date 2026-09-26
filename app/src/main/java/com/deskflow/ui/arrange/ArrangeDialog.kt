package com.deskflow.ui.arrange

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskflow.domain.classifier.ArrangeResult
import com.deskflow.domain.classifier.FOLDER_MIN_ITEMS
import com.deskflow.ui.DeskFlowViewModel

@Composable
fun ArrangeDialog(vm: DeskFlowViewModel, result: ArrangeResult, onDismiss: () -> Unit) {
    val capacity = vm.workspace?.grid?.folderCapacity ?: 9
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按分类收拢散放图标") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "待归类 ${result.looseCount} 项（首页之外的散放应用）· 收进 ${result.placedCount} 项 · 其中 ${result.movedCount} 项移位",
                    fontSize = 13.sp,
                )
                Text(
                    "将新建 ${result.newFolderCount} 个文件夹（同类满 $FOLDER_MIN_ITEMS 个收夹，每夹 $capacity 个，装不下的续编号如「影音 2」；尾组不足 $FOLDER_MIN_ITEMS 个时并回上一夹）",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    "${result.lockedCount} 项保持原位不动（首页全部 + 既有文件夹 + widget + 未达收夹阈值或未分类的散放项）",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (result.newScreens.isNotEmpty()) {
                    Text(
                        "非首页的空格不够，将新增 ${result.newScreens.size} 页承接新文件夹",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    "首页永不重排：不增删、不移动、也不接收新图标。Dock 项与文件夹内的应用同样不受影响；新文件夹只落在非首页的空位或本次腾空的格子。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.size(12.dp))
                Text("散放项分类概览", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (result.groups.isEmpty()) {
                    Text("没有可归类的散放应用，桌面不会变动。", fontSize = 12.sp)
                }
                result.groups.forEach { (cat, items) ->
                    val folders = result.folderCountFor(cat)
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(cat.label, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            "${items.size} 项" + if (folders > 0) " → $folders 夹" else " → 原地不动",
                            fontSize = 12.sp,
                        )
                    }
                }

                Spacer(Modifier.size(12.dp))
                Text("整理后的分页", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                result.pages.forEachIndexed { i, page ->
                    val locked = page.placements.count { !it.isNewFolder }
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text("第${i + 1}页" + if (locked > 0) "*" else "", fontSize = 12.sp, modifier = Modifier.width(56.dp))
                        Text(page.summary(), fontSize = 12.sp)
                    }
                }
                Text("* 含保持原位的图标", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { vm.applyArrange(); onDismiss() }) { Text("采用此排布") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
