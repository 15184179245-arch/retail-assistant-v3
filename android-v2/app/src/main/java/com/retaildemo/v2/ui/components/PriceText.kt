package com.retaildemo.v2.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.TextWeak
import java.util.Locale

/**
 * 零售级价格排版组件（¥ + 粗体大整数 + 小数位分级展示）。
 * 满足五金店员在复杂光线下一眼看清售价的需求。
 *
 * 美学公理 A5（tokens v7 数字律）：全部数字启用等宽数字特性 `tnum`——
 * 比例字体的「1」比「8」窄，价格在列表里竖排时小数点会左右跳动，账目看起来"歪"。
 * 等宽后整数位、小数点、小数位在任意数字下都垂直对齐，这是金融/零售排版的基本体面。
 */
@Composable
fun PriceText(
    price: Double,
    modifier: Modifier = Modifier,
    color: Color = Danger,
    integerSize: TextUnit = 20.sp,
    symbolSize: TextUnit = 13.sp,
    decimalSize: TextUnit = 13.sp,
    fontSize: TextUnit? = null,
    fontWeight: FontWeight = FontWeight.Bold,
    showUnpricedText: Boolean = false,
) {
    val actualInt = fontSize ?: integerSize
    val actualSymbol = fontSize?.let { (it.value * 0.72f).sp } ?: symbolSize
    val actualDecimal = fontSize?.let { (it.value * 0.72f).sp } ?: decimalSize
    val tabular = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")

    if (price <= 0.0 && showUnpricedText) {
        Text(
            text = "未定价",
            color = TextWeak,
            fontSize = actualDecimal,
            fontWeight = FontWeight.Medium,
            modifier = modifier,
        )
        return
    }

    // 01/03 稿价格均带千分位（¥1,284.50 / ¥2,480.00）：%,.2f 走 Locale 分组符
    val formatted = String.format(Locale.CHINA, "%,.2f", price)
    val dotIndex = formatted.indexOf('.')
    val integerPart = if (dotIndex != -1) formatted.substring(0, dotIndex) else formatted
    val decimalPart = if (dotIndex != -1) formatted.substring(dotIndex) else ""

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = "¥",
            color = color,
            fontSize = actualSymbol,
            fontWeight = fontWeight,
            lineHeight = actualSymbol,
        )
        Text(
            text = integerPart,
            color = color,
            fontSize = actualInt,
            fontWeight = fontWeight,
            lineHeight = actualInt,
            style = tabular,
        )
        Text(
            text = decimalPart,
            color = color,
            fontSize = actualDecimal,
            fontWeight = fontWeight,
            lineHeight = actualDecimal,
            style = tabular,
        )
    }
}
