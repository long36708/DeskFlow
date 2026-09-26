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
    val total = result.groups.sumOf { it.second.size } + result.pinned.size
    val capacity = vm.workspace?.grid?.folderCapacity ?: 9
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按分类整理桌面") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "可重排 $total 项 · 已安排 ${result.placedCount} 项 · 其中 ${result.movedCount} 项移位",
                    fontSize = 13.sp,
                )
                Text(
                    "将新建 ${result.newFolderCount} 个文件夹（同类满 $FOLDER_MIN_ITEMS 个收夹，每夹 $capacity 个，装不下的续编号如「影音 2」）",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (result.pinned.isNotEmpty() || result.dissolvedFolders.isNotEmpty()) {
                    Text(
                        "钉选到首页 ${result.pinned.size} 项" +
                            if (result.dissolvedFolders.isNotEmpty()) "；解散 ${result.dissolvedFolders.size} 个被抽空的文件夹" else "",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (result.newScreens.isNotEmpty()) {
                    Text(
                        "格子不够，将新增 ${result.newScreens.size} 页承接余下的图标",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    "widget 与未钉选的 Dock 项不参与重排，桌面上已有的文件夹整体移动（被钉选项抽空后剩不到 2 项时解散）；未分类图标不收夹，排在各类之后。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.size(12.dp))
                Text("分类概览", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                result.groups.forEach { (cat, items) ->
                    val folders = result.folderCountFor(cat)
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(cat.label, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            "${items.size} 项" + if (folders > 0) " → $folders 夹" else "",
                            fontSize = 12.sp,
                        )
                    }
                }

                Spacer(Modifier.size(12.dp))
                Text("分页结果", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                result.pages.forEachIndexed { i, page ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text("第${i + 1}页", fontSize = 12.sp, modifier = Modifier.width(56.dp))
                        Text(page.summary(), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.applyArrange(); onDismiss() }) { Text("采用此排布") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
