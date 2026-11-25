package com.retaildemo.v2.print

/**
 * CPCL 位图（EG 指令）编码——纯函数，JVM 可测。
 *
 * 语法（Zebra CPCL 口径，国产面单机兼容）：`EG {每行字节} {行数} {x} {y} {hex数据}`，
 * 8 像素/字节、MSB=最左点、数据为 ASCII 十六进制（2 字符/字节）；`!` 页头必须先行。
 *
 * 这是 NiimBot/快麦等标签 App 的行业做法：排版在手机端完成、整图下发——位图是唯一事实，
 * 设备字库号/字宽/行高差异（BTP-P39 PLUS 实证 TEXT 路径中文乱码+版式漂移压码）从此免疫。
 * 依据：iprtapp CPCL 参考「图片(EG/CG)」+ CSDN 小程序 CPCL 位图实践 + Zebra 官方 EG 文档。
 */
object CpclImageCodec {

    private val HEX = "0123456789ABCDEF"

    /**
     * 生成一张 EG 位图表单。[isBlack](x,y) 返回该点是否打印黑；
     * 宽度必须是 8 的倍数（8 像素打包 1 字节）。
     */
    fun egForm(widthDots: Int, heightDots: Int, copies: Int = 1, isBlack: (x: Int, y: Int) -> Boolean): String {
        require(widthDots > 0 && widthDots % 8 == 0 && heightDots > 0) {
            "EG 位图宽度必须是 8 的倍数且高宽为正：${widthDots}x$heightDots"
        }
        val bytesPerRow = widthDots / 8
        val hex = CharArray(bytesPerRow * heightDots * 2)
        var hi = 0
        for (y in 0 until heightDots) {
            var acc = 0
            var bit = 7
            for (x in 0 until widthDots) {
                if (isBlack(x, y)) acc = acc or (1 shl bit)
                if (bit == 0) {
                    hex[hi++] = HEX[acc ushr 4 and 0xF]
                    hex[hi++] = HEX[acc and 0xF]
                    acc = 0
                    bit = 7
                } else {
                    bit--
                }
            }
        }
        return buildString {
            append("! 0 200 200 ").append(heightDots).append(' ').append(copies).append('\n')
            append("PAGE-WIDTH ").append(widthDots).append('\n')
            append("EG ").append(bytesPerRow).append(' ').append(heightDots).append(" 0 0 ")
            append(hex)
            append("\nFORM\nPRINT\n")
        }
    }
}
