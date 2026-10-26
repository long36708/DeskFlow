package com.deskflow.ui.arrange

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskflow.data.usage.UsageStatsProvider
import com.deskflow.domain.classifier.ColdCollectPlan
import com.deskflow.ui.DeskFlowViewModel

@Composable
fun ColdCollectDialog(vm: DeskFlowViewModel, plan: ColdCollectPlan, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("冷门收夹") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "发现 ${plan.coldCount} 项近 ${UsageStatsProvider.WINDOW_DAYS} 天未启动的散放应用（含首页），" +
                        "将收进 ${plan.slots.size} 个文件夹、共收纳 ${plan.collectedCount} 项。",
                    fontSize = 13.sp,
                )
                Text(
                    "widget、既有文件夹与 Dock 项不受影响；采用后仍需点「应用」写入桌面，可用「更多 → 撤销上一步」回退。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.size(12.dp))
                Text("文件夹与成员", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                plan.slots.forEach { slot ->
                    val spot = when {
                        slot.screenId < 0 -> "新页"
                        else -> {
                            val idx = vm.visiblePages().indexOfFirst { it.screenId == slot.screenId }
                            if (idx >= 0) "第${idx + 1}页" else "第${slot.screenId}页"
                        }
                    }
                    Text("「${slot.title}」×${slot.members.size}（$spot）", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        slot.members.take(10).joinToString("、") { it.title.take(10) } +
                            if (slot.members.size > 10) " 等 ${slot.members.size} 项" else "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.applyColdCollect(); onDismiss() }) { Text("收进文件夹") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
