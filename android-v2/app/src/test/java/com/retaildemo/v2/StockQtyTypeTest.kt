package com.retaildemo.v2

import com.google.gson.Gson
import com.retaildemo.v2.data.remote.ApiEnvelope
import com.retaildemo.v2.data.remote.ProductDto
import com.retaildemo.v2.util.formatQty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S17 真机实测暴露的阻塞缺陷回归：上游/服务端库存是 REAL（管家婆散装商品 0.4 捆、12.5 米）。
 *
 * 原实现 `stock_qty: Int` 两处害：
 * ① Gson 解析整包时遇小数抛 NumberFormatException → 手机永远拉不下商品库
 *    （真机现场：点同步 → 服务端作业成功 → 手机回拉 200 但本地库零变化、水位未落）；
 * ② Room getInt() 静默截断 0.4→0 → 64 条商品在 APP 里被显示成「缺货」假信号。
 * 修复：DTO/Entity 全链 Double + Room 16→17 迁移 + formatQty 显示。
 */
class StockQtyTypeTest {

    private val gson = Gson()

    @Test
    fun `小数库存整包解析不再抛异常（原崩溃现场）`() {
        // 与 Retrofit 生产路径同型：ApiEnvelope<List<ProductDto>> 的精确泛型
        val type = object : com.google.gson.reflect.TypeToken<ApiEnvelope<List<ProductDto>>>() {}.type
        val env: ApiEnvelope<List<ProductDto>> = gson.fromJson(
            """{"success":true,"data":[
                {"id":1,"name":"整数货","stock_qty":4},
                {"id":2,"name":"散装货","stock_qty":12.5},
                {"id":3,"name":"极小量","stock_qty":0.4}
            ],"server_time_ms":123}""",
            type,
        )
        assertTrue(env.success)
        val parsed = env.data!!
        assertEquals(3, parsed.size)
        assertEquals(4.0, parsed[0].stock_qty, 1e-9)
        assertEquals(12.5, parsed[1].stock_qty, 1e-9)
        assertEquals(0.4, parsed[2].stock_qty, 1e-9)
        assertEquals(123L, env.serverTimeMs)
    }

    @Test
    fun `销量小数原样保留不被取整`() {
        val dto = gson.fromJson("""{"id":9,"name":"热缩管","stock_qty":12.5}""", ProductDto::class.java)
        assertEquals(12.5, dto.stock_qty, 1e-9)
    }

    @Test
    fun `显示格式化 整数去小数点 小数原样`() {
        assertEquals("4", formatQty(4.0))
        assertEquals("0", formatQty(0.0))
        assertEquals("12649", formatQty(12649.0))
        assertEquals("12.5", formatQty(12.5))
        assertEquals("0.79", formatQty(0.79))
        assertEquals("0.4", formatQty(0.4))
    }
}
