package com.retaildemo.v2.ui.agent.a2ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.directTap
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.SxElevation

/**
 * V7 spike：A2UI 报文 → Compose 的最小渲染层。
 *
 * 铁律：本文件**不得出现任何字面量字号/颜色/圆角**——样式一律取 MaterialTheme.typography
 * （自带 lineHeight，避开本项目最高频的"只设 fontSize 漏 lineHeight"事故）与 Design 令牌。
 * 报文里带来的样式键已在 A2ui.parse 剥离，渲染层无从读到。
 */

private const val LOW_STOCK_THRESHOLD = 5.0

// 几何常量＝design/A2UI-CATALOG.md（画布 M14 板 77:2）的规格，不许在报文里自带
private val ROW_HEIGHT = 48.dp        // 公理 1：整行可点 → 触达下限 48dp
private val HEADER_HEIGHT = 28.dp     // 表头 11/15 + 上下 6
private const val MAX_ROWS = 5        // 一屏一任务：超出必须给 NavCard「看全部」

/** 协议标准件 + 自写胖组件的统一入口。 */
@Composable
fun A2uiNodeView(
    node: A2uiNode,
    surface: A2uiSurface,
    row: JsonObject? = null,
    onAction: (AgentNav?) -> Unit,
) {
    val data = surface.dataModel
    val props = node.props
    when (node.type) {
        "Card" -> Surface(
            shape = RoundedCornerShape(Design.CardRadius.dp),
            color = Design.CardSurface,
            border = BorderStroke(0.5.dp, Design.CardStroke),
            modifier = Modifier
                .fillMaxWidth()
                .sxElevation(SxElevation.L2),
        ) {
            Column(
                modifier = Modifier.padding(Design.SpaceMd.dp),
                verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
            ) {
                Children(node, surface, row, onAction)
            }
        }

        "Column" -> Column(verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
            Children(node, surface, row, onAction)
        }

        "Row" -> Row(horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
            Children(node, surface, row, onAction)
        }

        "Text" -> Text(
            text = props.str("text", row, data) ?: "",
            style = typographyOf(props.str("variant", row, data)),
            color = Design.TextSecondary,
            // 默认锁单行：文案变长不许把卡片撑高（实测标题换行 → 卡片多一行 +22.6dp）
            maxLines = props.get("maxLines")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1,
            overflow = TextOverflow.Ellipsis,
        )

        "StatGrid" -> StatGrid(props.arr("items") ?: JsonArray(), data, row)

        "Table" -> A2uiTable(props, data, onAction)

        "NavCard" -> Row(horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
            (props.arr("actions") ?: JsonArray()).forEach { el ->
                val a = el.asJsonObject
                OutlinedButton(
                    onClick = { onAction(navFor(a.get("event"), null)) },
                    modifier = Modifier.height(ROW_HEIGHT),
                ) {
                    Text(a.get("label")?.asString ?: "", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // 白名单外的类型在 parse 阶段就被拦下，走不到这里
    }
}

@Composable
private fun Children(
    node: A2uiNode,
    surface: A2uiSurface,
    row: JsonObject?,
    onAction: (AgentNav?) -> Unit,
) {
    node.childIds.mapNotNull { surface.nodes[it] }.forEach {
        A2uiNodeView(it, surface, row, onAction)
    }
}

/** variant → 排版档位：只映射 M3 style，绝不落具体字号（lineHeight 随 style 一起来）。 */
@Composable
private fun typographyOf(variant: String?) = when (variant) {
    "h1" -> MaterialTheme.typography.headlineSmall
    "h2", "h3" -> MaterialTheme.typography.titleLarge
    "h4", "h5" -> MaterialTheme.typography.titleMedium
    "caption" -> MaterialTheme.typography.bodySmall
    "label" -> MaterialTheme.typography.labelMedium
    else -> MaterialTheme.typography.bodyMedium
}

@Composable
private fun StatGrid(items: JsonArray, data: JsonObject, row: JsonObject?) {
    Row(horizontalArrangement = Arrangement.spacedBy(Design.SpaceMd.dp)) {
        items.forEach { el ->
            val it = el.asJsonObject
            val tone = it.get("tone")?.asString
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Design.SpaceXs.dp),
            ) {
                Text(
                    text = A2ui.resolve(it, "value", row, data).plain(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = when (tone) {
                        "danger" -> Design.Danger
                        "warning" -> Design.Warning
                        else -> Design.TextMain
                    },
                )
                Text(
                    text = it.get("label")?.asString ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Design.TextWeak,
                )
            }
        }
    }
}

/** 胖组件①：任意列数 × 任意行数的数据表格（列可对齐/格式化/整行可点）。 */
@Composable
private fun A2uiTable(props: JsonObject, data: JsonObject, onAction: (AgentNav?) -> Unit) {
    val cols = props.arr("columns") ?: JsonArray()
    // 行数上限：超出部分不画（一屏一任务），由报文里的 NavCard「看全部」承接
    val rows = (props.resolve("rows", null, data)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray())
        .toList().take(MAX_ROWS)
    val rowAction = props.get("rowAction")?.asJsonObject?.get("event")

    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT),
        ) {
            cols.forEach { c ->
                val col = c.asJsonObject
                Text(
                    text = col.get("label")?.asString ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Design.TextWeak,
                    textAlign = alignOf(col.get("align")?.asString),
                    maxLines = 1,
                    modifier = Modifier.weight(col.get("weight")?.asFloat ?: 1f),
                )
            }
        }
        HorizontalDivider(color = Design.CardStroke)
        rows.forEach { r ->
            val rowObj = r.asJsonObject
            Row(
                horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .directTap(sfx = ScanFeedback.Sfx.KEY) { onAction(navFor(rowAction, rowObj)) },
            ) {
                cols.forEach { c ->
                    val col = c.asJsonObject
                    val raw = rowObj.get(col.get("key")?.asString ?: "")
                    Text(
                        text = formatCell(raw, col.get("format")?.asString),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = alignOf(col.get("align")?.asString),
                        color = cellColor(raw, col.get("tone")?.asString),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(col.get("weight")?.asFloat ?: 1f),
                    )
                }
            }
        }
    }
}

private fun cellColor(raw: JsonElement?, tone: String?): Color {
    if (tone != "stock") return Design.TextMain
    val qty = raw?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }
    return when {
        qty == null -> Design.TextWeak
        qty <= 0.0 -> Design.Danger
        qty < LOW_STOCK_THRESHOLD -> Design.Warning // 与后端 get_low_stock(threshold=5) 同口径
        else -> Design.TextMain
    }
}

private fun alignOf(align: String?) = when (align) {
    "end" -> TextAlign.End
    "center" -> TextAlign.Center
    else -> TextAlign.Start
}

/** 数字格一律挂 tnum（美学公理 A5：等宽数字，小数点不许左右跳动）。 */
private fun formatCell(raw: JsonElement?, format: String?): AnnotatedString {
    if (raw == null || raw.isJsonNull) return buildAnnotatedString { append("—") }
    val num = runCatching { raw.asDouble }.getOrNull()
    val s = when {
        // 未定价不是「0 元」：显示层区分，数据本身一个字节都不动（铁律 12）
        num != null && format == "money" && num == 0.0 -> "—"
        num != null && format == "money" -> "¥%,.2f".format(num)
        num != null && format == "qty" -> "%,.2f".format(num)
        else -> runCatching { raw.asString }.getOrDefault(raw.toString())
    }
    return buildAnnotatedString {
        if (num == null || format == null) append(s)
        else withStyle(SpanStyle(fontFeatureSettings = "tnum")) { append(s) }
    }
}

/**
 * 报文 event → 白名单动作；认不出返回 null（这个动作不许出现在界面上）。
 * 只接受 name=="navigate"——confirm/exec 等写语义因此结构上无法被卡片触发（写只走票据）。
 */
private fun navFor(event: JsonElement?, row: JsonObject?): AgentNav? {
    val ev = event?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    if (ev.get("name")?.takeIf { it.isJsonPrimitive }?.asString != "navigate") return null
    val ctx = ev.get("context")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val args = ctx.entrySet().mapNotNull { (k, v) ->
        val plain = if (v.isJsonObject && v.asJsonObject.has("path")) {
            A2ui.pointerData(row, v.asJsonObject.get("path").asString)
                ?.takeIf { it.isJsonPrimitive }?.asString
        } else {
            v.takeIf { it.isJsonPrimitive }?.asString
        }
        plain?.let { k to it }
    }.toMap()
    return resolveNav(AgentRoute.resolve(ctx).first, args)
}

// ---------- Gson 取值小工具 ----------

private fun JsonObject.str(key: String, row: JsonObject?, data: JsonObject): String? =
    resolve(key, row, data)?.let { if (it.isJsonPrimitive) it.asString else it.toString() }

private fun JsonObject.arr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

fun JsonObject.resolve(key: String, row: JsonObject?, data: JsonObject): JsonElement? =
    A2ui.resolve(this, key, row, data)

private fun JsonElement?.plain(): String = when {
    this == null || isJsonNull -> "—"
    isJsonPrimitive -> asString
    else -> toString()
}
