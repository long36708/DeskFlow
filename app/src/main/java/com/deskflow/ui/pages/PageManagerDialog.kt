package com.deskflow.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskflow.ui.DeskFlowViewModel

@Composable
fun PageManagerDialog(vm: DeskFlowViewModel, onDismiss: () -> Unit) {
    val rev = vm.revision
    val pages = vm.visiblePages()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("页面管理（${pages.size} 页）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "删除页时，页上图标会先迁到其它页的空位；页上有 widget 或空间不足时会拒绝删除。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = vm::addPage, modifier = Modifier.fillMaxWidth()) { Text("+ 新增一页") }
                Spacer(Modifier.size(8.dp))
                pages.forEachIndexed { i, page ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "第${i + 1}页 · ${vm.pageItemCount(page.screenId)} 项",
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.movePage(page.screenId, -1) }, enabled = i > 0) { Text("上移") }
                        TextButton(onClick = { vm.movePage(page.screenId, 1) }, enabled = i < pages.size - 1) { Text("下移") }
                        TextButton(onClick = { vm.deletePage(page.screenId) }, enabled = pages.size > 1) { Text("删除") }
                    }
                }
                Spacer(Modifier.size(4.dp))
                Text("rev $rev", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("完成") } },
    )
}
