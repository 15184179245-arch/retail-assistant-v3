package com.retaildemo.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retaildemo.v2.data.remote.PendingAction

/**
 * I16 确认卡：写操作票据的店员二次确认（红线承载点）。
 * 显示 op + 摘要 + 参数明细，确认后才调 /api/agent/confirm 执行。
 */
@Composable
fun AgentConfirmCard(action: PendingAction, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("写操作待确认", style = MaterialTheme.typography.labelLarge)
            Text(action.summary, style = MaterialTheme.typography.bodyMedium)
            if (action.args.isNotEmpty()) {
                Text(
                    action.args.entries.joinToString("，") { "${it.key}=${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onConfirm) { Text("确认执行") }
                OutlinedButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

/** 回执/系统提示气泡（已执行/已取消/离线提示）。 */
@Composable
fun AgentSystemBubble(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}
