package com.retaildemo.v2

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.data.repo.TempCode
import com.retaildemo.v2.data.repo.TempFormValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** B3 数据层纯逻辑：临时码号段、串货表单校验、老账单 JSON 兼容 tempCode。 */
class TempProductTest {

    private val gson = Gson()

    @Test
    fun `临时码格式与号段隔离`() {
        val code = TempCode.build("custom", "20260904", 1)
        assertEquals("TMP-20260904-c001", code)
        assertEquals("TMP-20260904-s007", TempCode.build("strade", "20260904", 7))
        // 序号补零到 3 位，永不与管家婆纯数字 code 冲突（TMP- 前缀）
        assertTrue(code.startsWith("TMP-"))
    }

    @Test
    fun `同日序号递增 跨日重置`() {
        val a = TempCode.build("custom", "20260904", 1)
        val b = TempCode.build("custom", "20260904", 2)
        val c = TempCode.build("custom", "20260905", 1)
        assertTrue(a < b)
        assertEquals("TMP-20260904-c001", a)
        assertEquals("TMP-20260905-c001", c)
    }

    @Test
    fun `自定义通道 只需名称与售价`() {
        assertNull(TempFormValidator.validate("custom", "螺丝", "", "5", "", "", null))
        assertNotNull(TempFormValidator.validate("custom", "", "", "5", "", "", null))
        assertNotNull(TempFormValidator.validate("custom", "螺丝", "", "", "", "", null))
    }

    @Test
    fun `串货通道 参数五必填 编号不必填`() {
        // 规则（2026-09-10 老板反馈，回归 billing-loop §2 原口径，废止 09-07「随手记」放松）：
        // 串货重在参数完整——规格型号/售价/在谁家串/串货价/凭证五项必填；商品编号最不重要，出库前补齐
        assertNull(TempFormValidator.validate("strade", "闸阀", "DN25", "30", "甲店", "22", "v_1.jpg"))
        // 缺规格型号
        assertNotNull(TempFormValidator.validate("strade", "闸阀", "", "30", "甲店", "22", "v_1.jpg"))
        // 缺售价 / 售价非数字 / 负数
        assertNotNull(TempFormValidator.validate("strade", "闸阀", "DN25", "", "甲店", "22", "v_1.jpg"))
        assertNotNull(TempFormValidator.validate("strade", "闸阀", "DN25", "abc", "甲店", "22", "v_1.jpg"))
        assertNotNull(TempFormValidator.validate("strade", "闸阀", "DN25", "-1", "甲店", "22", "v_1.jpg"))
        // 缺串货来源 / 缺串货价 / 串货价非数字
        assertNotNull(TempFormValidator.validate("strade", "闸阀", "DN25", "30", "", "22", "v_1.jpg"))
        assertNotNull(TempFormValidator.validate("strade", "闸阀", "DN25", "30", "甲店", "", "v_1.jpg"))
        assertNotNull(TempFormValidator.validate("strade", "闸阀", "DN25", "30", "甲店", "abc", "v_1.jpg"))
        // 缺凭证照片
        assertNotNull(TempFormValidator.validate("strade", "闸阀", "DN25", "30", "甲店", "22", null))
        // 名称仍然必填
        assertNotNull(TempFormValidator.validate("strade", "", "DN25", "30", "甲店", "22", "v.jpg"))
    }

    @Test
    fun `老账单 JSON 无 tempCode 反序列化不崩`() {
        val legacy = """[{"barcode":"","sku":"","name":"老自定义","spec":"自定义","unit":"件","qty":1,"price":5.0,"costPrice":0.0}]"""
        val back: List<BillItem> = gson.fromJson(legacy, object : TypeToken<List<BillItem>>() {}.type)
        assertEquals(1, back.size)
        assertNull(back[0].tempCode)
        assertNull(back[0].costPriceSnap)
    }

    private fun assertTrue(cond: Boolean) = org.junit.Assert.assertTrue(cond)
}
