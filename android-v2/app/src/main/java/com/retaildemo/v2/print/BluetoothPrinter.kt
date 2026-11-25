package com.retaildemo.v2.print

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class PairedPrinter(val name: String, val address: String, val likelyPrinter: Boolean = false)

/**
 * 蓝牙 SPP/BLE 双通道标签打印（SPP UUID + GBK 编码；BLE 为兜底通道）。
 * 每次打印建立短连接，超时保护；打印失败抛异常由上层提示。
 * BTP-P39 PLUS 等新北洋便携机（双模/LE）走 BLE 兜底仍可打印。
 */
object BluetoothPrinter {

    private const val TAG = "BluetoothPrinter"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val BLE_MTU_REQUEST = 247
    private const val BLE_TIMEOUT_MS = 15000L

    /** 疑似标签打印机：SDP 声明了 SPP 服务，或名字含 print / btp（新北洋 BTP 系列）。只作排序提示，不用于过滤。 */
    fun looksLikePrinter(name: String?, uuids: List<String>): Boolean =
        uuids.any { it.equals(SPP_UUID.toString(), true) } ||
            name?.contains("print", true) == true ||
            name?.contains("btp", true) == true

    /** 疑似打印机排前，其余按名字跟后；一个不丢。 */
    fun sortPrintersFirst(devices: List<PairedPrinter>): List<PairedPrinter> =
        devices.sortedWith(compareByDescending<PairedPrinter> { it.likelyPrinter }.thenBy { it.name })

    /**
     * 全部系统已配对设备（2026-09-09 修复：旧版按名字/SPP 过滤，导致已连接但名字不带
     * print、SDP 缓存为空的打印机从列表里消失）。
     * 疑似打印机排前，一个不丢；连不上时由用户自行甄别换设备。
     */
    fun pairedPrinters(context: Context): List<PairedPrinter> {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptyList()
        val adapter = bm.adapter ?: return emptyList()
        val bonded = try {
            adapter.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        return sortPrintersFirst(
            bonded.map { device ->
                val uuids = try {
                    device.uuids?.map { it.toString() }.orEmpty()
                } catch (_: SecurityException) {
                    emptyList()
                }
                PairedPrinter(
                    name = device.name ?: "未命名设备",
                    address = device.address,
                    likelyPrinter = looksLikePrinter(device.name, uuids),
                )
            },
        )
    }

    /** 通道计划：LE 只走 BLE；双模（如 BTP-P39 PLUS）SPP 失败转 BLE；经典/未知只 SPP。 */
    fun transportPlan(deviceType: Int): List<String> = when (deviceType) {
        BluetoothDevice.DEVICE_TYPE_LE -> listOf("ble")
        BluetoothDevice.DEVICE_TYPE_DUAL -> listOf("spp", "ble")
        else -> listOf("spp")
    }

    /** SPP 失败阶段标记：[dataWritten]=false 表示还没写入任何字节（连接期失败），换通道重发无害。 */
    internal class SppPhaseException(
        val dataWritten: Boolean,
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    /**
     * 排雷 ③b（2026-09-10）：只有 SPP 尚未写入任何数据（连接阶段失败）才允许 BLE 兜底重发。
     * 旧实现 SPP 写到一半失败也整单转 BLE 重发 → 打出重复/残缺标签。
     */
    fun shouldFallbackToBle(sppError: IllegalStateException): Boolean =
        sppError is SppPhaseException && !sppError.dataWritten

    /** MTU 分块：每块 mtu-3 字节（GATT 协议头 3 字节），保底 20。 */
    fun chunkPayload(data: ByteArray, mtu: Int, minChunk: Int = 20): List<ByteArray> {
        val size = maxOf(mtu - 3, minChunk)
        if (data.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>((data.size + size - 1) / size)
        var i = 0
        while (i < data.size) {
            val end = minOf(i + size, data.size)
            out.add(data.copyOfRange(i, end))
            i = end
        }
        return out
    }

    /** BLE 可写特征选择策略：优先带应答写（PROPERTY_WRITE），次选免应答；notify-only 不选。 */
    fun pickWriteCharacteristic(candidates: List<Pair<String, Int>>): String? {
        candidates.firstOrNull { it.second and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 }
            ?.let { return it.first }
        return candidates.firstOrNull {
            it.second and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        }?.first
    }

    /** 发送 TSPL（GBK 编码）。双模机 SPP 失败自动转 BLE 兜底。 */
    suspend fun printTspl(context: Context, address: String, tspl: String): Unit =
        withContext(Dispatchers.IO) {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: throw IllegalStateException("设备不支持蓝牙")
            val adapter = bm.adapter ?: throw IllegalStateException("蓝牙不可用")
            val device = adapter.getRemoteDevice(address)
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {
            }
            val payload = tspl.toByteArray(charset("GBK"))
            val deviceType = try {
                device.type
            } catch (_: SecurityException) {
                BluetoothDevice.DEVICE_TYPE_UNKNOWN
            }
            var sppError: IllegalStateException? = null
            for (channel in transportPlan(deviceType)) {
                try {
                    if (channel == "spp") printSpp(device, payload) else printBle(context, device, payload)
                    return@withContext
                } catch (e: IllegalStateException) {
                    if (channel == "spp" && shouldFallbackToBle(e)) {
                        sppError = e
                    } else {
                        throw e
                    }
                }
            }
            throw sppError ?: IllegalStateException("打印失败：无可用蓝牙通道")
        }

    private suspend fun printSpp(device: BluetoothDevice, payload: ByteArray) {
        val socket = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: SecurityException) {
            throw IllegalStateException("没有蓝牙权限", e)
        }
        try {
            try {
                socket.connect()
            } catch (e: Exception) {
                throw SppPhaseException(dataWritten = false, "SPP 连接失败：${e.message}", e)
            }
            try {
                socket.outputStream.write(payload)
                socket.outputStream.flush()
                // 热敏打印需要一点时间吐纸
                Thread.sleep(250)
            } catch (e: Exception) {
                // 已开始写入（可能部分出纸）——上游不得再整单重发
                throw SppPhaseException(dataWritten = true, "打印失败：${e.message}", e)
            }
        } catch (e: SppPhaseException) {
            Log.w(TAG, "spp print failed", e)
            throw e
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /** BLE GATT 兜底：连接→发现服务→谈 MTU→找可写特征→分块逐笔写。 */
    private suspend fun printBle(context: Context, device: BluetoothDevice, payload: ByteArray) {
        withTimeout(BLE_TIMEOUT_MS) {
            val session = BleSession()
            val gatt = try {
                device.connectGatt(context, false, session, BluetoothDevice.TRANSPORT_LE)
                    ?: throw IllegalStateException("BLE 发起连接失败")
            } catch (e: SecurityException) {
                throw IllegalStateException("没有蓝牙权限", e)
            }
            try {
                session.awaitReady()
                val characteristic = writableCharacteristic(gatt)
                    ?: throw IllegalStateException("BLE 打印机无可写通道")
                for (chunk in chunkPayload(payload, session.negotiatedMtu)) {
                    session.write(gatt, characteristic, chunk)
                }
                // 热敏打印需要一点时间吐纸
                Thread.sleep(250)
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("BLE 打印超时（${BLE_TIMEOUT_MS / 1000} 秒），请确认打印机已开机", e)
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("BLE 打印失败：${e.message}", e)
            } finally {
                try {
                    gatt.disconnect()
                } catch (_: Exception) {
                }
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun writableCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val candidates = gatt.services.orEmpty().flatMap { service ->
            service.characteristics.map { Pair(it.uuid.toString(), it.properties) }
        }
        val picked = pickWriteCharacteristic(candidates) ?: return null
        val uuid = UUID.fromString(picked)
        return gatt.services.asSequence()
            .flatMap { it.characteristics.asSequence() }
            .firstOrNull { it.uuid == uuid }
    }

    /**
     * 一次 BLE 打印连接的回调会话：连接/发现/MTU 走 connectGate，写应答走逐笔 writeGate
     *（CompletableDeferred 单次消费，每笔写换新）。
     */
    private class BleSession : BluetoothGattCallback() {
        private val connectGate = CompletableDeferred<BluetoothGatt>()
        @Volatile
        private var writeGate: CompletableDeferred<Int>? = null
        var negotiatedMtu: Int = 23
            private set

        suspend fun awaitReady(): BluetoothGatt = connectGate.await()

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                !connectGate.isCompleted -> connectGate.completeExceptionally(
                    IllegalStateException("BLE 连接失败(status=$status)"),
                )
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> connectGate.completeExceptionally(
                    IllegalStateException("BLE 服务发现失败(status=$status)"),
                )
                !g.requestMtu(BLE_MTU_REQUEST) -> connectGate.complete(g) // 不支持协商就按默认 MTU
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            connectGate.complete(g)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeGate?.complete(status)
        }

        suspend fun write(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, chunk: ByteArray) {
            val gate = CompletableDeferred<Int>()
            writeGate = gate
            val accepted: Any = try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(characteristic, chunk, characteristic.writeType)
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = chunk
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
            } catch (e: Exception) {
                writeGate = null
                throw IllegalStateException("BLE 写入异常：${e.message}", e)
            }
            val submitted = if (android.os.Build.VERSION.SDK_INT >= 33) {
                accepted == BluetoothGatt.GATT_SUCCESS
            } else {
                accepted == true
            }
            if (!submitted) {
                writeGate = null
                throw IllegalStateException("BLE 写入被拒")
            }
            val result = gate.await()
            writeGate = null
            if (result != BluetoothGatt.GATT_SUCCESS) {
                throw IllegalStateException("BLE 写入失败(status=$result)")
            }
        }
    }

    /** 蓝牙是否可用。 */
    fun isBluetoothReady(context: Context): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return bm.adapter?.isEnabled == true
    }
}
