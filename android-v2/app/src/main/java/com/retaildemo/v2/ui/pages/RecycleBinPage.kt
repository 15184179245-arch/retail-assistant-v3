package com.retaildemo.v2.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.R
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.theme.Danger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** B7 挂单回收站：跨天挂单不删除，一键恢复成当天挂单（恢复后重新参与打烊核对）。 */
@HiltViewModel
class RecycleBinViewModel @Inject constructor(
    private val billRepo: BillRepo,
) : ViewModel() {

    val recycledBills: StateFlow<List<BillEntity>> =
        billRepo.allBills.map { list -> list.filter { it.recycled } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(billId: String) = viewModelScope.launch { billRepo.restore(billId) }

    /** 36 稿（24 对齐）：回收站卡补件数行——复用 repo 的结构化解析，不手工拼 JSON。 */
    fun itemsOf(bill: BillEntity) = billRepo.itemsOf(bill)
}

@Composable
fun RecycleBinPage(
    onBack: () -> Unit,
    viewModel: RecycleBinViewModel = hiltViewModel(),
) {
    val bills by viewModel.recycledBills.collectAsState()

    Scaffold(
        topBar = { retaildemoTopBar(title = "挂单回收站", subtitle = "跨天挂单自动归档 · 一键恢复", onBack = onBack) },
    ) { padding ->
        if (bills.isEmpty()) {
            // fix-round1 #3：空态是「正常，只是还没有」，统一走 EmptyState 组件（弱色，非错误红）
            EmptyState(
                illustrationRes = R.drawable.ill_empty_bill,
                title = "回收站是空的",
                subtitle = "跨天未出库的挂单会自动出现在这里",
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(bills.size, key = { bills[it].id }) { i ->
                val b = bills[i]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(b.customerName.ifBlank { "散客" }, fontWeight = FontWeight.Bold)
                        // fix-round1 #5：金额统一两位小数+千分位（原 "¥" + Double 直拼会出 ¥342.5）
                        Text("¥" + java.text.DecimalFormat("#,##0.00").format(b.paidAmount ?: b.totalAmount))
                    }
                    val lineCount = remember(b.id, b.updatedAt) {
                        runCatching { viewModel.itemsOf(b) }.getOrDefault(emptyList())
                    }
                    val customCount = lineCount.count { it.barcode.startsWith("custom_") || it.barcode.startsWith("TMP-") }
                    Text(
                        buildString {
                            append("${lineCount.size} 件商品")
                            if (customCount > 0) append(" · 含自定义 $customCount 项")
                        },
                        fontSize = 12.sp,
                    )
                    Text("临时挂账于 " + b.createdAt, fontSize = 12.sp)
                    Button(
                        onClick = { viewModel.restore(b.id) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text("恢复成当天挂单")
                    }
                }
            }
        }
    }
}
