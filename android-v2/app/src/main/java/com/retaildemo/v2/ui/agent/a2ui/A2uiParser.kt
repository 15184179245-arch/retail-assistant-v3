package com.retaildemo.v2.ui.agent.a2ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * V7 spike：A2UI v0.9.1 报文的极简解析器（纯 Kotlin + Gson，无 Compose 依赖，可 JVM 单测）。
 *
 * 只做四件事，刻意不复用任何外部渲染库：
 *  1) JSONL 逐行解报文  2) 扁平邻接表建树  3) JSON Pointer 取值  4) 组件白名单与越权剥离
 * 第 4 件是本项目自己加的闸门——官方与社区实现都没有（详见 V7 汇报）。
 */

data class A2uiNode(
    val id: String,
    val type: String,
    val props: JsonObject,
    val childIds: List<String>,
)

class A2uiSurface(
    val root: A2uiNode?,
    val nodes: Map<String, A2uiNode>,
    val dataModel: JsonObject,
    /** catalog 外组件：整块不渲染，只留痕。 */
    val rejected: List<String>,
    /** 报文里自带的样式数值：被剥离，渲染器只认令牌。 */
    val stripped: List<String>,
    /** children 指到了不存在的 id。 */
    val dangling: List<String>,
    /** 解不动的行：少一块卡片比画错一块更难查，不许静默丢。 */
    val malformed: List<String>,
)

object A2ui {

    /** 本 spike 的 catalog：4 个协议标准件 + 3 个自写胖组件。 */
    val CATALOG = setOf("Card", "Column", "Row", "Text", "StatGrid", "Table", "NavCard")

    /** 报文里一出现就剥掉的样式键——外观只许由 design tokens 决定。 */
    val BANNED_STYLE_KEYS = setOf(
        "color", "background", "fontSize", "lineHeight", "fontFamily",
        "padding", "margin", "borderRadius", "width", "height", "opacity",
    )

    fun parse(jsonl: String): A2uiSurface {
        val nodes = LinkedHashMap<String, A2uiNode>()
        val data = JsonObject()
        val rejected = mutableListOf<String>()
        val stripped = mutableListOf<String>()
        val malformed = mutableListOf<String>()

        jsonl.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, line ->
            val msg = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
            if (msg == null) {
                malformed += "第 ${i + 1} 行解不动：${line.take(48)}"
                return@forEachIndexed
            }
            when {
                msg.has("createSurface") -> Unit // spike 只有一个 surface，建表即写数据
                msg.has("updateDataModel") -> {
                    val m = msg.getAsJsonObject("updateDataModel")
                    bindPath(data, m.get("path")?.asString ?: "/", m.get("value"))
                }
                msg.has("updateComponents") -> {
                    val arr = msg.getAsJsonObject("updateComponents").getAsJsonArray("components")
                    arr?.forEach { el ->
                        val c = runCatching { el.asJsonObject }.getOrNull() ?: return@forEach
                        val id = c.get("id")?.asString ?: return@forEach
                        val type = c.get("component")?.asString ?: return@forEach
                        if (type !in CATALOG) {
                            rejected += "$type (#$id)"
                            return@forEach
                        }
                        val props = JsonObject()
                        c.entrySet().forEach { (k, v) ->
                            if (k in BANNED_STYLE_KEYS) stripped += "#$id $type → $k=${v}"
                            else props.add(k, v)
                        }
                        val kids = mutableListOf<String>()
                        props.get("child")?.takeIf { it.isJsonPrimitive }?.let { kids += it.asString }
                        props.getAsJsonArray("children")?.forEach { kids += it.asString }
                        nodes[id] = A2uiNode(id, type, props, kids)
                    }
                }
            }
        }

        // 悬空引用：children 指到被拒/不存在的 id，剔掉并留痕
        val dangling = mutableListOf<String>()
        nodes.values.forEach { n ->
            n.childIds.filter { it !in nodes }.forEach { dangling += "${n.type} (#${n.id}) → 缺失子节点 #$it" }
        }
        val cleaned = nodes.mapValues { (id, n) ->
            n.copy(childIds = n.childIds.filter { it in nodes })
        }
        val rootId = cleaned["root"]?.id
            ?: cleaned.entries.firstOrNull { (_, n) ->
                cleaned.values.none { it.childIds.contains(n.id) }
            }?.key
        return A2uiSurface(rootId?.let { cleaned[it] }, cleaned, data, rejected, stripped, dangling, malformed)
    }

    /** 把 "/a/b" = value 写进数据模型。 */
    private fun bindPath(root: JsonObject, path: String, value: JsonElement?) {
        if (value == null) return
        val segs = path.trimStart('/').split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return
        var cur = root
        for (s in segs.dropLast(1)) {
            val next = cur.get(s)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { cur.add(s, it) }
            cur = next
        }
        cur.add(segs.last(), value)
    }

    /** 从给定对象按 "a/b" 取值。 */
    fun pointerData(obj: JsonObject?, path: String): JsonElement? {
        var cur: JsonElement? = obj ?: return null
        for (s in path.trimStart('/').split('/').filter { it.isNotEmpty() }) {
            cur = (cur as? JsonObject)?.get(s) ?: return null
        }
        return cur
    }

    /** 文本/数值绑定统一入口：props[key] 是绑定就解引用，否则当字面量。 */
    fun resolve(props: JsonObject, key: String, row: JsonObject?, data: JsonObject): JsonElement? {
        val el = props.get(key) ?: return null
        if (el.isJsonObject && el.asJsonObject.has("path")) {
            val p = el.asJsonObject.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            return if (p.startsWith("/")) pointerData(data, p) else pointerData(row, p)
        }
        return el
    }
}
