package com.retaildemo.v2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.DangerLight
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight

/**
 * 库存状态 4 态胶囊徽章（带指示微圆点）。
 * 状态分明：充足(绿) / 紧张(橙) / 缺货(红) / 冻结(灰)。
 */
@Composable
fun StockBadge(
    qty: Double,
    unit: String = "",
    saleStatus: String = "active",
    minStock: Int = 5,
    modifier: Modifier = Modifier,
) {
    val isFrozen = saleStatus == "frozen"
    val isOutOfStock = qty <= 0
    val isLowStock = !isOutOfStock && qty <= minStock
    val shown = com.retaildemo.v2.util.formatQty(qty)

    val (bgColor, dotColor, textColor, text) = when {
        isFrozen -> Quad(SurfaceSubtle, TextWeak, TextSecondary, "呆滞冻结")
        isOutOfStock -> Quad(DangerLight, Danger, Danger, "缺货")
        isLowStock -> Quad(WarningLight, Warning, Warning, "仅剩 $shown$unit")
        else -> Quad(SuccessLight, Success, Success, "库存 $shown$unit")
    }

    Row(
        modifier = modifier
            .background(color = bgColor, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = dotColor, shape = CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 13.sp,
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
