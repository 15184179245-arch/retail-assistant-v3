package com.retaildemo.v2.ui.pages.newbill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.R
import com.retaildemo.v2.data.repo.CartLine
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.viewmodel.SearchHit

/**
 * 开单页**单容器**货筐清单（TASK-02 从 NewBillPage 平移，几何与文案零改动）：
 * 一个 LazyColumn 装三件事——搜索推荐、货筐行条、末尾添加无码货；空态也在同一容器里。
 *
 * 滚动只这一层（rule 14②③）：页面外层不许再挂 verticalScroll，行条卡内也不套滚动。
 * 所有业务动作以回调上抛给 NewBillPage（组件不持有状态）。
 */
@Composable
fun NewBillCartList(
    query: String,
    searching: Boolean,
    results: List<SearchHit>,
    cart: List<CartLine>,
    editIndex: Int,
    editPrice: String,
    onAddHit: (SearchHit) -> Unit,
    onEditPrice: (Int) -> Unit,
    onIncrease: (Int) -> Unit,
    onDecrease: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onLongPress: (CartLine) -> Unit,
    onAddNoCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        // 搜索命中列表
        if (query.isNotBlank()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索推荐 (${results.size})", style = MaterialTheme.typography.bodySmall)
                    if (searching) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (results.isEmpty() && !searching) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                drawRoundRect(
                                    color = Warning.copy(alpha = 0.55f),
                                    cornerRadius = CornerRadius(14.dp.toPx()),
                                    style = Stroke(
                                        width = 1.5.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                                    ),
                                )
                            }
                            .clickable(onClick = onAddNoCode)
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AddCircle, null, tint = Primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "未在店内搜到？添加为非店内商品/串货：「${query.trim()}」",
                                color = Primary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = Design.FontSm.sp,
                            )
                        }
                    }
                }
            }
            items(results, key = { it.key }) { hit ->
                BillResultRow(hit = hit, onAdd = { onAddHit(hit) })
            }
        }

        // 购物车列表
        if (cart.isNotEmpty()) {
            itemsIndexed(cart, key = { index, line -> "${index}_${line.tempCode ?: line.barcode.ifBlank { line.name }}" }) { index, line ->
                NewBillCartItem(
                    item = line,
                    index = index,
                    // 02 稿 P1：编辑态（docked 键盘驱动行内价格框）
                    editing = index == editIndex,
                    editValue = editPrice,
                    onEdit = { onEditPrice(index) },
                    onIncrease = { onIncrease(index) },
                    onDecrease = { onDecrease(index) },
                    onRemove = { onRemove(it) },
                    onLongClick = { onLongPress(line) },
                )
            }

            // 列表末尾快捷添加非店内无码商品
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceSubtle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAddNoCode)
                        .padding(vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    ) {
                        Icon(Icons.Default.AddCircle, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("+ 添加非店内商品 / 串货", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else if (query.isBlank()) {
            item {
                Spacer(Modifier.height(20.dp))
                EmptyState(
                    illustrationRes = R.drawable.ill_empty_bill,
                    title = "开单货筐为空",
                    subtitle = "点扫码方块连续扫码入单、输入品名/拼音搜索，或添加无码杂件",
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * 开单页搜索推荐行（U2 v2 私有实现）：瓷片锚点 + 名称/规格 + 价格 + 加入按钮。
 * 与查价页 SearchResultCard 同语言；仅本页使用，共享 ProductItemView 未动。
 */
@Composable
private fun BillResultRow(
    hit: SearchHit,
    onAdd: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(Design.ShapeLg.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sxElevation(SxElevation.L2, shape = RoundedCornerShape(Design.ShapeLg.dp))
            .clickable { onAdd() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(11.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PrimaryLight,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = hit.name.firstOrNull()?.toString() ?: "货",
                        color = Primary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                Text(
                    text = hit.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    hit.spec?.takeIf { it.isNotBlank() },
                    hit.unit?.takeIf { it.isNotBlank() }?.let { "库存单位 $it" },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(meta, fontSize = Design.FontXs.sp, color = TextWeak, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            PriceText(price = hit.sellPrice, integerSize = 15.sp, symbolSize = 10.sp, decimalSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = PrimaryLight,
                modifier = Modifier.clickable { onAdd() },
            ) {
                Text(
                    "加入",
                    color = Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                )
            }
        }
    }
}
