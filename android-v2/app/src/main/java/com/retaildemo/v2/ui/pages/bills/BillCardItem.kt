package com.retaildemo.v2.ui.pages.bills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import com.retaildemo.v2.ui.viewmodel.BillUiModel

/**
 * 01 记账 · 单据列表卡片 —— AST 确定性编译。
 * 设计权威：Ardot 3:128（老张五金·挂账）/ 3:146（李师傅·已录进度）/ 3:168（散客·已出库）/ 3:475（王姐）。
 *
 * 结构与参数（逐值取自节点 AST）：
 * 1. 卡：圆角 14、白底、0.5dp 细边框、L2 双层环境光阴影（Elevation.kt L2 与 3:128 effects 逐层同参）。
 * 2. 客户瓷片 44dp/圆角 12：客户=顶→底渐变 #FFF7F0→#FFF2E8 + 首字 18sp ExtraBold 品牌橙；
 *    散客=灰片弱字（3:1145/3:1146）。
 * 3. 内容列行距 8：客户名 15sp SemiBold + 状态胶囊紧跟其后（行 gap=0；胶囊定高 22、水平内边 9、圆角 20）；
 *    元信息 11sp；挂账且已录>0 时同行追加「已录 N/M」（3:1200，11sp SemiBold 警示棕）。
 * 4. 底行：B12 起「出码录单」按钮从主路径摘除（逐条大二维码退居**待出库详情**兜底），
 *    底行左槽改阶段提示语；右侧三段式红价 ¥11/整18/分12 Bold（PriceText）。
 *
 * 与设计稿的关系（2026-09-17 已双向对齐）：
 * · Warning/Success/TextWeak 深色文字档（fix-round1：浅底上作文字需 ≥4.5:1）——画布实例与 M1 令牌板
 *   已同步回写为此值（真源 design/tokens.json：warning/success/textWeak），画布不再是旧值出处；
 * · 边框/散客灰片与画布差 ≤4/255：代码取全仓令牌（Border #EAECF0 / SurfaceSubtle #F2F4F7），
 *   画布逐卡值留待令牌收口批次统一，勿按卡零改；
 * · PriceText 为共享组件，¥ 符号 Medium vs 画布 Bold 的 1 档字重差不在本卡收口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillCardItem(
    bill: BillUiModel,
    onDelete: () -> Unit,
    onEditItem: (Int) -> Unit,
    onOpenBill: () -> Unit = {},
    onDeleteItem: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isWalkIn = bill.customerName.trim() == "散客"
    val recordedCount = bill.items.count { it.recorded }

    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(
            containerColor = Design.CardSurface,
            contentColor = TextMain,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 118.dp)
            .sxElevation(SxElevation.L2)
            .clickable(onClick = onOpenBill),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 客户瓷片（3:1139 渐变 / 3:1145 散客灰片）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (isWalkIn) {
                            Modifier.background(SurfaceSubtle)
                        } else {
                            Modifier.background(
                                Brush.verticalGradient(
                                    colors = listOf(Design.CustomerTileFrom, Design.CustomerTileTo)
                                )
                            )
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = bill.customerName.take(1),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isWalkIn) TextWeak else Primary,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
            ) {
                // 行 客户（3:129：gap=0，胶囊紧跟客户名；B6「退」标为业务扩展，置于最前）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (bill.direction == "return") {
                        Text(
                            text = "退",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier
                                .background(Danger, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = bill.customerName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (bill.status == BillEntity.STATUS_PAID) {
                        StatusPill(text = "已出库", container = SuccessLight, content = Success)
                    } else if (bill.stage == BillEntity.STAGE_READY) {
                        StatusPill(text = "待出库", container = PrimaryLight, content = Primary)
                    } else {
                        StatusPill(text = "挂账", container = WarningLight, content = Warning)
                    }
                    // B15 待补凭证：ready 且没拍凭证 → 小灰标提醒（打烊前补拍，不挡出门）
                    if (bill.status == BillEntity.STATUS_PENDING &&
                        bill.stage == BillEntity.STAGE_READY &&
                        bill.receiptPhoto.isNullOrBlank()
                    ) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "待补凭证",
                            fontSize = 10.sp,
                            color = TextWeak,
                            lineHeight = 13.sp,
                            modifier = Modifier
                                .background(SurfaceSubtle, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 行 2：件数 · 时间 · 自定义标记；李师傅稿（3:1200）已录进度与元信息同行，间距 10
                val timeStr = if (bill.createdAt.length >= 16) bill.createdAt.substring(11, 16) else bill.createdAt
                val customCount = bill.items.count { it.barcode.startsWith("custom_") || it.barcode.startsWith("TMP-") }
                val customSuffix = if (customCount > 0) " · 含自定义商品 $customCount 项" else ""
                val metaText = "${bill.items.size} 件商品 · $timeStr$customSuffix"
                if (bill.status == BillEntity.STATUS_PENDING && recordedCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = metaText,
                            fontSize = 11.sp,
                            color = TextWeak,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "已录 $recordedCount/${bill.items.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Warning,
                        )
                    }
                } else {
                    Text(
                        text = metaText,
                        fontSize = 11.sp,
                        color = TextWeak,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 行 3：阶段提示（B12 起「出码录单」从主路径摘除，二维码退居待出库详情兜底）+ 右对齐红价
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val stageHint = when {
                        bill.status == BillEntity.STATUS_PAID -> "已出库"
                        bill.stage == BillEntity.STAGE_READY -> "待出库 · 收银端领单（详情页可出码兜底）"
                        else -> "挂账中 · 点开详情补全三件后标待出库"
                    }
                    Text(
                        text = stageHint,
                        fontSize = 11.sp,
                        fontWeight = if (bill.status == BillEntity.STATUS_PENDING && bill.stage == BillEntity.STAGE_READY) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        color = when {
                            bill.status == BillEntity.STATUS_PAID -> Success
                            bill.stage == BillEntity.STAGE_READY -> Primary
                            else -> TextWeak
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    PriceText(
                        price = bill.totalAmount,
                        color = Danger,
                        integerSize = 18.sp,
                        symbolSize = 11.sp,
                        decimalSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** 状态胶囊（3:131）：定高 22、圆角 20、水平内边 9、11sp SemiBold，垂直居中。 */
@Composable
private fun StatusPill(
    text: String,
    container: Color,
    content: Color,
) {
    Surface(
        shape = RoundedCornerShape(Design.PillRadius.dp),
        color = container,
        modifier = Modifier.height(22.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = content,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
