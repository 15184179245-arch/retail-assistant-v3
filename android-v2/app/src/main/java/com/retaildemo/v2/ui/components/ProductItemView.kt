package com.retaildemo.v2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.components.directTap
import com.retaildemo.v2.ui.components.toolSurface
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextDisabled
import com.retaildemo.v2.ui.theme.TextSecondary

/**
 * 全局统一标准商品卡片（查价/开单搜索/无售价列表/库存盘点统一复用）。
 * 保证信息分层：第一眼看品名与价格，第二眼看规格货位与库存状态。
 */
@Composable
fun ProductItemView(
    name: String,
    sellPrice: Double,
    modifier: Modifier = Modifier,
    stockQty: Double? = null,
    spec: String? = null,
    model: String? = null,
    sku: String? = null,
    barcode: String? = null,
    unit: String? = null,
    location: String? = null,
    category: String? = null,
    saleStatus: String? = "active",
    highlightKeyword: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .sxElevation(SxElevation.L2)
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.directTap(sfx = ScanFeedback.Sfx.KEY, onClick = onClick)
                } else Modifier,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                // 第一行：商品名称（支持命中词高亮）
                Text(
                    text = buildHighlightedText(name, highlightKeyword),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // 第二行：规格型号与条码/SKU（五金型号规格通常包含品牌，高亮突出提高辨识效率）
                val hasSpec = !spec.isNullOrBlank()
                // 上游「型号」=出厂货号（雷士 DD.00000001），有值才占位，夹在规格与条码之间
                val hasModel = !model.isNullOrBlank()
                val codeStr = barcode?.takeIf { it.isNotBlank() } ?: sku?.takeIf { it.isNotBlank() }?.let { "SKU:$it" }
                if (hasSpec || hasModel || !codeStr.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (hasSpec) {
                            val isSpecMatched = highlightKeyword?.isNotBlank() == true && spec!!.contains(highlightKeyword.trim(), ignoreCase = true)
                            Text(
                                text = buildHighlightedText(spec!!, highlightKeyword),
                                color = if (isSpecMatched) Primary else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isSpecMatched) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (hasModel) {
                            if (hasSpec) {
                                Text("  |  ", color = TextDisabled, fontSize = 11.sp)
                            }
                            val isModelMatched = highlightKeyword?.isNotBlank() == true &&
                                model!!.replace(".", "").contains(highlightKeyword.trim().replace(".", ""), ignoreCase = true)
                            Text(
                                text = buildHighlightedText("型号 " + model!!, highlightKeyword),
                                color = if (isModelMatched) Primary else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isModelMatched) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!codeStr.isNullOrBlank()) {
                            if (hasSpec || hasModel) {
                                Text("  |  ", color = TextDisabled, fontSize = 11.sp)
                            }
                            Text(
                                text = buildHighlightedText(codeStr, highlightKeyword),
                                color = TextSecondary.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // 第三行：货位小胶囊 + 4态库存徽章 + 分类微标签
                if (!location.isNullOrBlank() || stockQty != null || !category.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (!category.isNullOrBlank()) {
                            Text(
                                text = category,
                                color = TextSecondary,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .background(SurfaceSubtle, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                        if (!location.isNullOrBlank()) {
                            Row(
                                modifier = Modifier
                                    .background(PrimaryLight, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(11.dp),
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text = location,
                                    color = Primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        if (stockQty != null) {
                            StockBadge(
                                qty = stockQty,
                                unit = unit.orEmpty(),
                                saleStatus = saleStatus ?: "active",
                            )
                        }
                    }
                }
            }

            // 右侧区：价格与可选操作按钮
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                PriceText(
                    price = sellPrice,
                    integerSize = 18.sp,
                    symbolSize = 12.sp,
                    decimalSize = 12.sp,
                    showUnpricedText = true,
                )
                if (trailingContent != null) {
                    Spacer(Modifier.height(6.dp))
                    trailingContent()
                }
            }
        }
    }
}

/** ProductEntity 实体的便捷重载 */
@Composable
fun ProductItemView(
    product: com.retaildemo.v2.data.local.entity.ProductEntity,
    modifier: Modifier = Modifier,
    highlightKeyword: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ProductItemView(
        name = product.name,
        sellPrice = product.sellPrice,
        modifier = modifier,
        stockQty = product.stockQty,
        spec = product.spec,
        model = product.model,
        sku = product.sku,
        barcode = product.barcode,
        unit = product.unit,
        category = product.category,
        saleStatus = product.saleStatus,
        highlightKeyword = highlightKeyword,
        onClick = onClick,
        trailingContent = trailingContent,
    )
}

/** CellProductDto 实体的便捷重载（货架与理货专页复用） */
@Composable
fun ProductItemView(
    cellProduct: com.retaildemo.v2.data.remote.CellProductDto,
    modifier: Modifier = Modifier,
    highlightKeyword: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ProductItemView(
        name = cellProduct.name,
        sellPrice = cellProduct.sell_price,
        modifier = modifier,
        stockQty = cellProduct.stock_qty,
        spec = cellProduct.spec,
        sku = cellProduct.sku,
        barcode = cellProduct.barcode,
        unit = cellProduct.unit,
        category = cellProduct.category,
        highlightKeyword = highlightKeyword,
        onClick = onClick,
        trailingContent = trailingContent,
    )
}

/** 搜索命中词高亮助手：高亮品名与规格型号中的关键词，大幅提升店员扫视匹配效率 */
fun buildHighlightedText(
    text: String,
    keyword: String?,
    highlightColor: Color = Primary,
): androidx.compose.ui.text.AnnotatedString {
    val cleanKw = keyword?.trim()
    if (cleanKw.isNullOrEmpty() || !text.contains(cleanKw, ignoreCase = true)) {
        return androidx.compose.ui.text.buildAnnotatedString { append(text) }
    }
    return androidx.compose.ui.text.buildAnnotatedString {
        var startIndex = 0
        val lowerText = text.lowercase()
        val lowerKw = cleanKw.lowercase()
        while (startIndex < text.length) {
            val idx = lowerText.indexOf(lowerKw, startIndex)
            if (idx == -1) {
                append(text.substring(startIndex))
                break
            }
            if (idx > startIndex) {
                append(text.substring(startIndex, idx))
            }
            pushStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = highlightColor,
                    fontWeight = FontWeight.Bold,
                ),
            )
            append(text.substring(idx, idx + lowerKw.length))
            pop()
            startIndex = idx + lowerKw.length
        }
    }
}
