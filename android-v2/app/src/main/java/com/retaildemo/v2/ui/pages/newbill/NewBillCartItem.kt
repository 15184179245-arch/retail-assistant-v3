package com.retaildemo.v2.ui.pages.newbill

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.components.HoldToReveal
import com.retaildemo.v2.ui.components.InlineStepper
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import androidx.compose.ui.text.style.TextDecoration
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.data.repo.CartLine

/**
 * 购物车清单单项（02 稿 3:4 两行布局）：
 * 行1 = 品名（+待补码徽章） | 红色小计；行2 = 规格·条码（+长按透出毛利）；
 * 行3 = 步进器 | 单价（编辑态=行内价格框，由 docked 九宫格键盘驱动，见 NewBillPage）。
 * 保留：点选改价、步进器、右滑删除、长按问 AI。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NewBillCartItem(
    item: CartLine,
    index: Int,
    onEdit: (Int) -> Unit,
    onIncrease: (Int) -> Unit,
    onDecrease: (Int) -> Unit,
    onRemove: (Int) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    editing: Boolean = false,
    editValue: String = "",
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRemove(index)
                true
            } else false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Design.CardRadius.dp))
                    .background(Danger),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "删除",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
        },
        modifier = modifier,
    ) {
        Card(
            // 02 稿：radius 14 + L2；编辑态整卡 1.5dp 橙描边（对齐 02_newbill 画布选中卡语言）
            shape = RoundedCornerShape(Design.CardRadius.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .sxElevation(if (editing) SxElevation.L2 else SxElevation.L1, shape = RoundedCornerShape(Design.CardRadius.dp))
                .then(
                    if (editing) {
                        Modifier.border(2.dp, Primary, RoundedCornerShape(Design.CardRadius.dp))
                    } else {
                        Modifier.border(0.5.dp, Border, RoundedCornerShape(Design.CardRadius.dp))
                    },
                )
                .combinedClickable(
                    onClick = { onEdit(index) },
                    onLongClick = onLongClick,
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                // 间距 7 由 02 稿锚点反推：11+19+7+14+7+30+11 = 99dp（卡高真值）；8dp 会撑到 103
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                // 行1：品名 | 小计（红）
                // ⚠️ 行高显式钉死（19/14/30 = 画布行盒），实测不钉时行盒被下游组件链撑到 ~44/35/30，
                //     卡高恒 136dp（证据：PROBE-A 探针 + spacing(0) 对照，2026-09-19）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(19.dp),
                ) {
                    if (item.isCustom) {
                        Surface(
                            shape = RoundedCornerShape(Design.PillRadius.dp),
                            color = WarningLight,
                        ) {
                            Text(
                                text = "待补码",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Warning,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = item.name,
                        fontSize = Design.FontLg.sp,
                        // ⚠️ 行高必须显式给：`Text(fontSize = …)` 只覆盖字号、**不覆盖继承的行高**，
                        // 行盒会按上层 style（bodyLarge 15sp/lineHeight 22sp，见 Theme.kt:78）撑到 22dp。
                        // 整卡高 113.9dp vs 稿 99.0dp（Δ+9dp）的主因。同一坑见 SearchPage.kt:1128。
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    PriceText(
                        price = item.subtotal,
                        integerSize = Design.FontXl.sp,
                        symbolSize = Design.FontXs.sp,
                        decimalSize = Design.FontXs.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // 行2：规格 · 条码 + 毛利（Hold 透出，敏感默认掩码）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(14.dp),
                ) {
                    val meta = listOfNotNull(
                        item.spec?.takeIf { it.isNotBlank() },
                        item.barcode.takeIf { it.isNotBlank() && !item.isCustom },
                    ).joinToString(" · ")
                    Text(
                        text = meta.ifBlank { "无规格无码" },
                        fontSize = Design.FontXs.sp,
                        // ⚠️ 同上：11sp 的字默认坐 22dp 行盒，单这一行就多撑 8dp。
                        // 14.sp 是照抄 SearchPage.kt:1128 的既有处方（字号 +3）。
                        lineHeight = 14.sp,
                        color = TextWeak,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 售价为 0（未定价）时跳过毛利显示，避免除零溢出
                    if (item.costPrice > 0.0 && item.price > 0.0) {
                        val margin = (((item.price - item.costPrice) / item.price) * 100).toInt()
                        Spacer(Modifier.width(8.dp))
                        HoldToReveal(
                            holdMs = 400,
                            masked = {
                                Text(
                                    text = "毛利 ••%",
                                    fontSize = Design.FontXs.sp,
                                    // 行盒与行2 主文本一致（14sp）：缺省会继承 22sp 把行2 撑高 8dp
                                    lineHeight = 14.sp,
                                    color = TextWeak,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                            revealed = {
                                Text(
                                    text = "毛利 $margin%",
                                    fontSize = Design.FontXs.sp,
                                    lineHeight = 14.sp,
                                    color = if (margin >= 20) com.retaildemo.v2.ui.theme.Success else Danger,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                        )
                    }
                }

                // 行3：对齐 02 稿 一体式胶囊步进器 [ — 4 + ] | 单价（未编辑=原价划线/小字，编辑态=白底橙框输入框）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp),
                ) {
                    // 一体式浅灰胶囊步进器（对齐 02 稿 3:4 原图）
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SurfaceSubtle,
                        // 02 稿胶囊实测 29.8dp ≈ 30（32dp 会把卡撑到 101）
                        modifier = Modifier.height(30.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable(enabled = item.qty > 1) { onDecrease(index) },
                            ) {
                                Text(
                                    text = "−",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.qty > 1) TextSecondary else TextWeak,
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.widthIn(min = 28.dp),
                            ) {
                                Text(
                                    text = "${item.qty}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMain,
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable { onIncrease(index) },
                            ) {
                                Text(
                                    text = "+",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Primary,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    if (editing) {
                        // 02 稿选中态：白底 + 1.5dp Primary 橙边框输入框 + 光标
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White,
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Primary),
                            modifier = Modifier
                                // 02 稿编辑态输入框与步进器胶囊同高同轴（实测均 1024..1083px = 30dp）
                                .height(30.dp)
                                .clickable { onEdit(index) },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp),
                            ) {
                                Text(
                                    text = editValue.ifBlank { "0" },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMain,
                                )
                                Spacer(Modifier.width(3.dp))
                                Box(
                                    modifier = Modifier
                                        .width(1.5.dp)
                                        .height(16.dp)
                                        .background(Primary),
                                )
                            }
                        }
                    } else {
                        // 02 稿未编辑态：显示单价 / 单位（点按触发九宫格改价）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onEdit(index) },
                        ) {
                            Text(
                                text = "¥%.2f${if (item.unit.isNotBlank()) " / " + item.unit else ""}".format(item.price),
                                fontSize = 13.sp,
                                color = TextWeak,
                            )
                        }
                    }
                }
            }
        }
    }
}
