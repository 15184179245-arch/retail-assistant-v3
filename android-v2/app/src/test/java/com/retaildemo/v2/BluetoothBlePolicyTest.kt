package com.retaildemo.v2

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import com.retaildemo.v2.print.BluetoothPrinter.chunkPayload
import com.retaildemo.v2.print.BluetoothPrinter.pickWriteCharacteristic
import com.retaildemo.v2.print.BluetoothPrinter.transportPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BTP-P39 PLUS 适配：SPP/BLE 双通道决策与 GATT 写策略（纯函数，JVM 可测）。 */
class BluetoothBlePolicyTest {

    @Test
    fun `通道计划_LE只BLE_双模SPP转BLE_经典只SPP`() {
        assertEquals(listOf("ble"), transportPlan(BluetoothDevice.DEVICE_TYPE_LE))
        assertEquals(listOf("spp", "ble"), transportPlan(BluetoothDevice.DEVICE_TYPE_DUAL))
        assertEquals(listOf("spp"), transportPlan(BluetoothDevice.DEVICE_TYPE_CLASSIC))
        assertEquals(listOf("spp"), transportPlan(BluetoothDevice.DEVICE_TYPE_UNKNOWN))
    }

    @Test
    fun `分块_默认MTU23按20字节_协商MTU247按244字节`() {
        val data = ByteArray(100) { it.toByte() }
        val small = chunkPayload(data, 23)
        assertEquals(5, small.size)
        assertTrue(small.take(4).all { it.size == 20 })
        assertEquals(20, small[4].size)
        // 首尾内容不重不漏
        assertEquals(0, small[0][0].toInt())
        assertEquals(99, small[4][19].toInt())

        val big = chunkPayload(ByteArray(500), 247)
        assertEquals(244, big[0].size)
        assertEquals(3, big.size)
        assertEquals(500 - 244 * 2, big[2].size)
    }

    @Test
    fun `分块_空载荷返回空列表`() {
        assertEquals(0, chunkPayload(ByteArray(0), 247).size)
    }

    @Test
    fun `特征选择_优先带应答写_次选免应答_notify不选`() {
        val notifyOnly = Pair("0000ffe1-0000-1000-8000-00805f9b34fb", BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        val writeNoResp = Pair(
            "0000ffe2-0000-1000-8000-00805f9b34fb",
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        )
        val write = Pair("0000ffe3-0000-1000-8000-00805f9b34fb", BluetoothGattCharacteristic.PROPERTY_WRITE)

        assertEquals("0000ffe3-0000-1000-8000-00805f9b34fb", pickWriteCharacteristic(listOf(notifyOnly, write)))
        assertEquals("0000ffe2-0000-1000-8000-00805f9b34fb", pickWriteCharacteristic(listOf(notifyOnly, writeNoResp)))
        assertNull("只有 notify 特征时选不出可写通道", pickWriteCharacteristic(listOf(notifyOnly)))
        assertNull(pickWriteCharacteristic(emptyList()))
    }
}
