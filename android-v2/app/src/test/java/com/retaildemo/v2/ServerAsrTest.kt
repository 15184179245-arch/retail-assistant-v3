package com.retaildemo.v2

import com.retaildemo.v2.util.ServerAsr
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I17 ServerAsr 纯函数单测：PCM16 小端序列化 + WAV 44 字节包头
 * （与 src/asr/asr_server.py wrap_pcm_to_wav 同格式，服务端 _read_wav 可解析）。
 */
class ServerAsrTest {

    @Test
    fun `pcm16转小端字节`() {
        val out = ServerAsr.pcmToBytes(shortArrayOf(1, -2, 1000, -32768, 32767))
        assertEquals(10, out.size)
        assertEquals(1, out[0].toInt() and 0xFF)          // 1 低字节
        assertEquals(0, out[1].toInt() and 0xFF)          // 1 高字节
        assertEquals(0xFE, out[2].toInt() and 0xFF)       // -2 低
        assertEquals(0xFF, out[3].toInt() and 0xFF)       // -2 高
        assertEquals(0xE8, out[4].toInt() and 0xFF)       // 1000=0x03E8 低
        assertEquals(0x03, out[5].toInt() and 0xFF)       // 高
        assertEquals(0x00, out[6].toInt() and 0xFF)       // -32768=0x8000 低
        assertEquals(0x80, out[7].toInt() and 0xFF)       // 高
        assertEquals(0xFF, out[8].toInt() and 0xFF)       // 32767=0x7FFF 低
        assertEquals(0x7F, out[9].toInt() and 0xFF)       // 高
    }

    @Test
    fun `wav头44字节RIFF结构`() {
        val pcm = byteArrayOf(1, 2, 3, 4) // 2 个 int16 采样
        val wav = ServerAsr.wrapWav(pcm)
        assertEquals(48, wav.size) // 44 头 + 4 数据
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        // RIFF chunk size = 36 + 数据长
        assertEquals(36 + 4, wav.readIntLE(4))
        // fmt：PCM=1 / 单声道 / 16000Hz / 字节率 32000 / 块对齐 2 / 16bit
        assertEquals(16, wav.readIntLE(16))
        assertEquals(1, wav.readShortLE(20))
        assertEquals(1, wav.readShortLE(22))
        assertEquals(16000, wav.readIntLE(24))
        assertEquals(32000, wav.readIntLE(28))
        assertEquals(2, wav.readShortLE(32))
        assertEquals(16, wav.readShortLE(34))
        assertEquals(4, wav.readIntLE(40)) // data chunk size
    }

    @Test
    fun `vad阈值随噪声自适应且有下限`() {
        // 安静环境（噪声底~80）：阈值=3倍噪声=240 → 取下限 350，防环境噪声常触发
        assertEquals(350, ServerAsr.vadThreshold(80.0))
        // 嘈杂店内（噪声底~300）：阈值=900，轻声也不会全漏
        assertEquals(900, ServerAsr.vadThreshold(300.0))
        // 极吵（噪声底~1000）：阈值=3000
        assertEquals(3000, ServerAsr.vadThreshold(1000.0))
    }

    @Test
    fun `电平换算阈值处为一半且夹紧0到100`() {
        assertEquals(0, ServerAsr.levelPercent(0.0, 500))
        assertEquals(50, ServerAsr.levelPercent(500.0, 500))   // 阈值处=50
        assertEquals(100, ServerAsr.levelPercent(1500.0, 500)) // 上限夹紧
        assertEquals(100, ServerAsr.levelPercent(9999.0, 500))
    }

    private fun ByteArray.readIntLE(off: Int): Int =
        (this[off].toInt() and 0xFF) or
            ((this[off + 1].toInt() and 0xFF) shl 8) or
            ((this[off + 2].toInt() and 0xFF) shl 16) or
            ((this[off + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.readShortLE(off: Int): Int =
        (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)
}
