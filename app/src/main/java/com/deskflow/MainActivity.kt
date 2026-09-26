package com.deskflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deskflow.ui.DeskFlowViewModel
import com.deskflow.ui.editor.DeskFlowApp
import com.topjohnwu.superuser.Shell

const val DOCK_PAGE_ID = -101

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shell.getShell() // 预热 root shell（异步触发授权弹窗）
        setContent {
            MaterialTheme {
                val vm: DeskFlowViewModel = viewModel()
                DeskFlowApp(vm)
            }
        }
    }
}
