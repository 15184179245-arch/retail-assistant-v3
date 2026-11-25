package com.retaildemo.v2

import com.retaildemo.v2.data.local.CategoryMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S10 巨桶细分映射：最长命中优先 / 无命中回 null（调用方回上游原类）/
 * 资产与脚本定稿一致（条数 + 长度降序，防手滑改生成文件）。
 */
class CategoryMapTest {

    @Test
    fun `最长命中优先`() {
        // 轴承风扇同时命中 轴承/风扇/轴承风扇，最长的赢
        assertEquals("通风取暖", CategoryMap.refine("轴承风扇"))
        // 硅胶管同时命中 硅胶/胶管，最长的硅胶管赢（橡塑制品，不是胶粘剂）
        assertEquals("橡塑制品", CategoryMap.refine("硅胶管"))
        // 电焊钳：手动工具与焊接都含，裁决归焊接
        assertEquals("焊接", CategoryMap.refine("电焊钳"))
        // 三位单级：开关碎片不许吃，精确词赢
        assertEquals("电气", CategoryMap.refine("三位单级86型"))
    }

    @Test
    fun `行话与86盒系命中`() {
        assertEquals("电气", CategoryMap.refine("86盒白色"))
        assertEquals("电气", CategoryMap.refine("八角盒盖"))
        // 王八盒库内 0 件也要能归（未来上货/改名即生效）
        assertEquals("电气", CategoryMap.refine("王八盒"))
    }

    @Test
    fun `无命中回null上游兜底`() {
        assertNull(CategoryMap.refine("C1（3-20）"))
        assertNull(CategoryMap.refine("安装费"))
        assertNull(CategoryMap.refine(""))
    }

    @Test
    fun `资产与脚本定稿一致`() {
        // rules_merged.json 定稿数；改了资产必须同步改脚本与本断言
        assertEquals(1341, CategoryMap.RULES.size)
        // 长度非递增（引擎不依赖顺序，但保证可读与首个最长语义一致）
        val lens = CategoryMap.RULES.map { it.first.length }
        assertTrue(lens.zipWithNext().all { (a, b) -> a >= b })
        // 无空 keyword（空串会全匹配，红线）
        assertTrue(CategoryMap.RULES.none { it.first.isBlank() })
    }
}
