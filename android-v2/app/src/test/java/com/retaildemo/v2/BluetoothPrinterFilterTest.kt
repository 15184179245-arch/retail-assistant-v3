package com.retaildemo.v2

import com.retaildemo.v2.print.BluetoothPrinter.looksLikePrinter
import com.retaildemo.v2.print.BluetoothPrinter.sortPrintersFirst
import com.retaildemo.v2.print.PairedPrinter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现 2026-09-09 现场问题：打印机蓝牙已在系统层配对/连接，
 * 但 APP 打印机管理列表里不出现。
 *
 * 旧实现把「已配对」过滤成「名字含 print 或 SDP 缓存里有 SPP」：
 * 汉印/芝柯类机器名字（如 HM-A300）不含 print、SDP 缓存又常为空，
 * 结果被过滤掉。新契约：全量保留、疑似排前。
 */
class BluetoothPrinterFilterTest {

    private val sppUuid = "00001101-0000-1000-8000-00805F9B34FB"

    @Test
    fun `疑似打印机判定_SPP声明与print名字`() {
        assertTrue(looksLikePrinter("随便什么名", listOf(sppUuid)))
        assertTrue(looksLikePrinter("Xprinter-58", emptyList()))
        assertTrue(looksLikePrinter("Bluetooth Printer", emptyList()))
        assertTrue(looksLikePrinter(null, listOf("00001101-0000-1000-8000-00805f9b34fb".uppercase())))
    }

    @Test
    fun `新北洋BTP系列按名字即识别为疑似打印机`() {
        // 老板新购的 BTP-P39 PLUS：名字既不含 print 也常无 SDP 缓存
        assertTrue(looksLikePrinter("BTP-P39 PLUS", emptyList()))
        assertTrue(looksLikePrinter("btp-p39", emptyList()))
    }

    @Test
    fun `名字不含print且uuids为空不算疑似_但不能因此消失`() {
        // 老板现场那台：汉印 HM-A300，SDP 缓存为空
        assertFalse(looksLikePrinter("HM-A300", emptyList()))
        // 旧逻辑在这里直接把它过滤没了——新逻辑必须仍然返回它
        val list = sortPrintersFirst(listOf(PairedPrinter("HM-A300", "02:00:00:00:00:01")))
        assertEquals(1, list.size)
        assertEquals("HM-A300", list[0].name)
        assertFalse(list[0].likelyPrinter)
    }

    @Test
    fun `列表一个不丢_疑似排前其余按名字`() {
        val sorted = sortPrintersFirst(
            listOf(
                PairedPrinter("MI 13", "02:00:00:00:00:02"), // 店员的手机
                PairedPrinter("HM-A300", "02:00:00:00:00:03"), // 名字不带 print 的标签机
                PairedPrinter("Xprinter-58", "02:00:00:00:00:04", likelyPrinter = true),
                PairedPrinter("Barcode Scanner", "02:00:00:00:00:05"),
            ),
        )
        assertEquals(listOf("Xprinter-58", "Barcode Scanner", "HM-A300", "MI 13"), sorted.map { it.name })
    }

    @Test
    fun `全空列表不崩`() {
        assertEquals(0, sortPrintersFirst(emptyList()).size)
    }
}
