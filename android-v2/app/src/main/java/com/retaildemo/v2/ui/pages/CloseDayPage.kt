package com.retaildemo.v2.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.retaildemo.v2.data.local.entity.BillEntity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.BillTime
import com.retaildemo.v2.data.repo.TempProductRepo
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** B7 打烊清单：下班前四项核对，全绿才收工（billing-loop §6）。 */
data class CloseDayCard(
    val key: String,   // pending | strade | custom | return
    val title: String,
    val count: Int,
    val unit: String,
)

@HiltViewModel
class CloseDayViewModel @Inject constructor(
    private val billRepo: BillRepo,
    private val tempRepo: TempProductRepo,
) : ViewModel() {

    private val _cards = MutableStateFlow<List<CloseDayCard>>(emptyList())
    val cards: StateFlow<List<CloseDayCard>> = _cards.asStateFlow()

    private val _recycledCount = MutableStateFlow(0)
    val recycledCount: StateFlow<Int> = _recycledCount.asStateFlow()

    private val _allClear = MutableStateFlow(false)
    val allClear: StateFlow<Boolean> = _allClear.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            // 先把跨天挂单归位回收站（B7 Step3），再统计
            runCatching { billRepo.markCrossDayRecycled() }
            val bills = billRepo.allBills.first()
            val today = BillTime.today()
            val cntPending = bills.count { it.status == "pending" && !it.recycled }
            val cntStrade = runCatching { tempRepo.listStrade("pending").size }.getOrDefault(0)
            val cntCustom = runCatching { tempRepo.listByType("custom").size }.getOrDefault(0)
            val cntReturn = bills.count { it.direction == "return" && it.createdAt.startsWith(today) }
            // B15 待补凭证：已标待出库但没拍凭证的单（打烊前补拍，不挡出门）
            val cntReceipt = bills.count {
                BillEntity.needsReceiptSupplement(it.stage, it.status, it.receiptPhoto) && !it.recycled
            }
            _recycledCount.value = bills.count { it.recycled }
            _cards.value = listOf(
                CloseDayCard("pending", "滞留挂单", cntPending, "单"),
                CloseDayCard("receipt", "待补凭证", cntReceipt, "单"),
                CloseDayCard("strade", "未处理串货", cntStrade, "笔"),
                CloseDayCard("custom", "待补全自定义", cntCustom, "条"),
                CloseDayCard("return", "当日退货", cntReturn, "单"),
            )
            _allClear.value = cntPending == 0 && cntStrade == 0 && cntCustom == 0 && cntReceipt == 0
        }
    }
}

@Composable
fun CloseDayPage(
    onBack: () -> Unit,
    onOpenBills: () -> Unit = {},
    onOpenStrade: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onOpenRecycleBin: () -> Unit = {},
    viewModel: CloseDayViewModel = hiltViewModel(),
) {
    val cards by viewModel.cards.collectAsState()
    val recycledCount by viewModel.recycledCount.collectAsState()
    val allClear by viewModel.allClear.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = { retaildemoTopBar(title = "打烊清单", subtitle = "四项全清才收工", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 未清横幅（36 三态合辑 · 23 对齐）：非全清时给「还差 N 项」+ 进度，全清走下方 ✅ 文案
            if (!allClear && cards.isNotEmpty()) {
                val uncleared = cards.count { it.count > 0 }
                val cleared = cards.size - uncleared
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarningLight, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "今日未清 · 还差 $uncleared 项收工",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Warning,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(Color.White, RoundedCornerShape(3.dp)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(cleared.toFloat() / cards.size.coerceAtLeast(1))
                                .height(6.dp)
                                .background(Primary, RoundedCornerShape(3.dp)),
                        ) {}
                    }
                }
            }
            if (allClear) {
                Text(
                    "今日已清，下班！",
                    color = Success,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            cards.forEach { c ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceSubtle, RoundedCornerShape(12.dp))
                        .clickable {
                            when (c.key) {
                                "pending" -> onOpenBills()
                                // fix-round1 #4：当日退货跳当日带退货标的账单列表（此前漏分支点了没反应）
                                "return" -> onOpenBills()
                                "strade" -> onOpenStrade()
                                "custom" -> onOpenQueue()
                            }
                        }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(c.title, color = TextMain, fontSize = 16.sp)
                    Text(
                        "${c.count} ${c.unit}",
                        color = if (c.count == 0) Success else Danger,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceSubtle, RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenRecycleBin)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("挂单回收站", color = TextMain, fontSize = 16.sp)
                Text(
                    if (recycledCount > 0) "$recycledCount 条待处理" else "空",
                    color = TextSecondary,
                )
            }
        }
    }
}
