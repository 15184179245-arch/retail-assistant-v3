package com.retaildemo.v2.ui.agent.a2ui

import com.google.gson.JsonObject

/**
 * 生成式 UI 的「直达功能」白名单（V7 红线承载点）。
 *
 * agent 只能引用这里的 route id，参数按各自 schema 校验后才拼成真实路由串；
 * 未登记的 route 结构上到不了——包括 settings / recyclebin / onboarding /
 * shelf_write / adjust，以及任何写语义（写只走 pending_actions 票据）。
 *
 * 路由串形状与 MainActivity 的 NavHost 保持一致（字符串路由 + path 参数）。
 */
enum class AgentRoute(val id: String, private val build: (Map<String, String>) -> String?) {
    /** 商品详情：必须有条码，编不出不存在的条码。 */
    PRODUCT("product", { args -> args["barcode"]?.takeIf { it.isNotBlank() }?.let { "product/$it" } }),

    /** 账单详情。 */
    BILL("bill_detail", { args -> args["billId"]?.takeIf { it.isNotBlank() }?.let { "bill_detail/$it" } }),

    /** 无参只读页。 */
    LOW_STOCK("low_stock", { "low_stock" }),
    CUSTOMERS("customers", { "customers" }),
    STOCKTAKE("stocktake", { "stocktake" }),
    MOVEMENTS("movements", { "movements" }),
    PRINT_QUEUE("print_queue", { "print_queue" }),
    TODOS("todos", { "todos" }),
    SEARCH("search", { "search" }),
    ;

    /** 解析成可导航的路由串；不合法（未登记 / 缺参数 / 参数为空）返回 null＝这个动作不许出现。 */
    fun toRoute(args: Map<String, String>): String? = runCatching { build(args) }.getOrNull()

    companion object {
        fun of(id: String?): AgentRoute? = values().firstOrNull { it.id == id }

        /** 报文里的 context：字符串直接用，绑定 {"path": "..."} 由调用方先解引用成字符串再进来。 */
        fun resolve(context: JsonObject?): Pair<AgentRoute?, Map<String, String>> {
            val ctx = context ?: return null to emptyMap()
            val args = ctx.entrySet().mapNotNull { (k, v) ->
                v.takeIf { it.isJsonPrimitive }?.asString?.let { k to it }
            }.toMap()
            return of(ctx.get("route")?.takeIf { it.isJsonPrimitive }?.asString) to args
        }
    }
}

/** 一条被批准的动作：路由串 + 展示用描述。 */
data class AgentNav(val route: String, val label: String)

/** 白名单解析：只有登记过且参数齐全的动作才允许变成一次导航。 */
fun resolveNav(route: AgentRoute?, args: Map<String, String>): AgentNav? {
    val r = route ?: return null
    val path = r.toRoute(args) ?: return null
    return AgentNav(path, "${r.id} ${args.entries.joinToString(" ") { "${it.key}=${it.value}" }}".trim())
}

/**
 * 卡片动作总线（照 NfcEventBus 范式，MainActivity.kt:99-101）。
 * Agent 面板/工作台页都拿不到 NavController，统一把动作发到这里，由 NavHost 收集后导航。
 */
object AgentActionBus {
    val requests = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 4,
    )

    fun nav(nav: AgentNav) = requests.tryEmit(nav.route)
}
