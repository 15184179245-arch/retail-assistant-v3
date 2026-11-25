package com.retaildemo.v2.ui.pages.bills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.viewmodel.TodayStatsUi

/**
 * 今日概览卡（01 稿 3:2）：白卡 L3（一屏唯一浮起卡）。
 * 顶行：「今日合计」+「N 单」灰胶囊。
 * 底行：大红价 +「挂账 x · 已出库 y」。
 */
@Composable
fun TodayStatsSection(
    stats: TodayStatsUi,
    modifier: Modifier = Modifier,
) {
    // 01 稿 3:2 绝对几何：358x72 rx=14 白卡 L3（一屏唯一浮起卡）
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Design.CardSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .sxElevation(SxElevation.L3),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 顶行：「今日合计」+「N 单」灰胶囊（44x26 rx=13）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "今日合计",
                    color = TextWeak,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = SurfaceSubtle,
                    modifier = Modifier
                        .height(26.dp)
                        .padding(0.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${stats.count + stats.todayPendingCount} 单",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            // 底行：大红价 +「挂账 x · 已出库 y」
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                PriceText(
                    price = stats.totalAmount,
                    color = Danger,
                    integerSize = 22.sp,
                    symbolSize = 13.sp,
                    decimalSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "挂账 ${stats.todayPendingCount} · 已出库 ${stats.count}",
                    color = TextWeak,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}
