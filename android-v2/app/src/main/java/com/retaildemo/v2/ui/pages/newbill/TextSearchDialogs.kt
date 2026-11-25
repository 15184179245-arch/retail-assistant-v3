package com.retaildemo.v2.ui.pages.newbill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.directTap
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.viewmodel.TextSearchResult
import com.retaildemo.v2.ui.window.SanxAlertDialog

/**
 * 文字识别候选弹窗（自 `UnmatchedDialogs.kt` 拆出）。
 *
 * ★ 2026-09-20 老板令：**未识别条码区整条退役**（「非条码 SKU 不要录进来，忽略掉」）——
 *   同文件里的 `UnmatchedRow` / `ManualEntryDialog` / `BindProductDialog` 一并删除，
 *   只剩「文字识别候选」这一条链路（手写码 / 无条码货仍有它的用处）。
 *   副码绑定能力本身没丢：查价页 `SearchViewModel` 与串货台账 `StradeLedgerViewModel` 仍在用。
 */

/** 文字识别候选：识别文字 → 本地模糊搜索出的候选商品，店员点选入单。 */
@Composable
fun TextCandidateDialog(
    result: TextSearchResult,
    onPick: (ProductEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    SanxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("识别「${result.query}」", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            if (result.candidates.isEmpty()) {
                // 未识别条码区退役后，兜底出口改成页内既有的「添加非店内商品 / 串货」入口
                Text(
                    "没搜到对应商品。可换个关键词，或用页内「添加非店内商品 / 串货」手动录入。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    items(result.candidates, key = { it.id }) { p ->
                        CandidateCard(
                            product = p,
                            onClick = { onPick(p) },
                        )
                    }
                }
            }
        },
        dismissText = "取消",
    )
}

@Composable
private fun CandidateCard(product: ProductEntity, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(Design.ShapeSm.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sxElevation(SxElevation.L2, shape = RoundedCornerShape(Design.ShapeSm.dp))
            .directTap(sfx = ScanFeedback.Sfx.KEY, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    product.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    product.barcode ?: product.sku ?: "",
                    fontSize = 11.sp,
                    color = TextWeak,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PriceText(
                price = product.sellPrice,
                fontSize = 14.sp,
                color = Danger,
            )
        }
    }
}
