package com.deskflow.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskflow.domain.layout.LayoutImportPlan
import com.deskflow.ui.DeskFlowViewModel

@Composable
fun LayoutImportDialog(vm: DeskFlowViewModel, plan: LayoutImportPlan, onDismiss: () -> Unit) {
    val s = plan.stats
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("套用布局方案") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "档案 ${s.archivePages} 页 · ${s.archiveIcons} 项图标 · ${s.foldersTotal} 个夹",
                    fontSize = 13.sp,
                )
                Text(
                    "匹配 ${s.matchedIcons} 项（widget 原位保持 ${s.widgetKept} 项）、" +
                        "沿用 ${s.foldersReused} 夹 / 新建 ${s.foldersCreated} 夹、" +
                        "${s.parkedItems} 项挪到备用位；档案里有 ${s.skippedArchive} 项当前桌面找不到，将跳过。",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (s.newPages > 0) {
                    Text("将新建 ${s.newPages} 页承接档案布局。", fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }
                if (s.gridMismatch) {
                    Text(
                        "注意：档案网格与当前桌面不同（或含越界格位），相关项会退到备用位。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    "未匹配的现有文件夹与其成员原地不动；套用后仍需点「应用」写入桌面，可用「更多 → 撤销上一步」回退。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { vm.applyLayoutImport(); onDismiss() }) { Text("套用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
