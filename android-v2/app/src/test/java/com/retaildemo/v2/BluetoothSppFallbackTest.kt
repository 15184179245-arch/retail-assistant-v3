package com.retaildemo.v2

import com.retaildemo.v2.print.BluetoothPrinter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排雷 ③b（2026-09-10）：SPP 失败分阶段——只有连接期失败（尚未写入任何字节）
 * 才允许 BLE 兜底重发；已开始写入后失败再整单重发会打出重复/残缺标签。
 */
class BluetoothSppFallbackTest {

    @Test
    fun `连接期失败允许BLE兜底`() {
        val e = BluetoothPrinter.SppPhaseException(false, "SPP 连接失败")
        assertTrue(BluetoothPrinter.shouldFallbackToBle(e))
    }

    @Test
    fun `已写入后失败禁止换通道重发`() {
        val e = BluetoothPrinter.SppPhaseException(true, "打印失败：写入中断")
        assertFalse(BluetoothPrinter.shouldFallbackToBle(e))
    }

    @Test
    fun `普通异常不兜底`() {
        assertFalse(BluetoothPrinter.shouldFallbackToBle(IllegalStateException("没有蓝牙权限")))
    }
}
