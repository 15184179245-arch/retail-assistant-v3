package com.retaildemo.v2.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 价签位图渲染器（CPCL EG 路径，2026-09-12）：版式计划几何 → 单色 Bitmap（1px=1dot，203dpi≈8px/mm）。
 * 文字用系统字库按行高 [LINE_H]dot 绘制（与 TSPL TSS24 行高同口径，所见即所得）；
 * 二维码/条码走 zxing——与屏显出码同引擎，M 级纠错与 TSPL QRCODE 一致。
 * 位图是唯一事实：设备字库号/字宽/行高差异（BTP-P39 PLUS 实证会漂移压码）从此无关。
 */
internal object TagBitmapRenderer {

    /** 灰度归白阈值：抗锯齿灰边（≥128）不打出点，热敏出点干净。 */
    internal const val BLACK_THRESHOLD = 128

    /** 渲染为白底黑内容的位图。调用方用后自行 [Bitmap.recycle]。 */
    fun render(ops: List<LabelTemplates.Op>, widthDots: Int, heightDots: Int): Bitmap {
        val bmp = Bitmap.createBitmap(widthDots.coerceAtLeast(8), heightDots.coerceAtLeast(8), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        for (op in ops) {
            when (op) {
                is LabelTemplates.Op.Txt -> {
                    paint.textSize = (op.font * op.mul).coerceAtLeast(1).toFloat()
                    // 横向拉伸=加粗语义（xmul>mul 时按比例拉宽；本版式两者恒等，留通用）
                    paint.textScaleX = if (op.mul == 0) 1f else op.xmul.toFloat() / op.mul
                    // 位图路径真粗体（名称行）：不占额外宽度；TSPL 设备字库路径无此能力维持原样
                    paint.isFakeBoldText = op.bold
                    val baseline = op.y - paint.fontMetrics.top
                    canvas.drawText(op.text, op.x.toFloat(), baseline, paint)
                    paint.textScaleX = 1f
                    paint.isFakeBoldText = false
                }
                is LabelTemplates.Op.Ln -> {
                    paint.strokeWidth = 2f
                    canvas.drawLine(op.x1.toFloat(), op.y1.toFloat(), op.x2.toFloat(), op.y2.toFloat(), paint)
                }
                is LabelTemplates.Op.Qr -> {
                    if (op.data.isEmpty() || op.cell <= 0) continue
                    val side = LabelTemplates.qrModules(op.data) * op.cell
                    // 脏数据容错（铁律12）：码图编不出就整块跳过（签上仍有名称/价格），绝不崩打印
                    val matrix = try {
                        QRCodeWriter().encode(
                            op.data,
                            BarcodeFormat.QR_CODE,
                            side,
                            side,
                            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
                        )
                    } catch (e: Exception) {
                        continue
                    }
                    for (my in 0 until matrix.height) {
                        for (mx in 0 until matrix.width) {
                            if (matrix.get(mx, my)) {
                                // X 必须加 op.x 偏移（v2.8.3 曾漏加致二维码画到标签左缘压字——真机 40×30 实拍定谳）
                                canvas.drawRect(
                                    (op.x + mx).toFloat(), (op.y + my).toFloat(),
                                    (op.x + mx + 1).toFloat(), (op.y + my + 1).toFloat(), paint,
                                )
                            }
                        }
                    }
                }
                is LabelTemplates.Op.Bar -> {
                    // 铁律12 脏条码容错：Code128 只收可打印 ASCII，非法字符剔除、编不出整块跳过
                    val clean = op.data.filter { it.code in 32..126 }
                    if (clean.isEmpty()) continue
                    val bars = try {
                        Code128Writer().encode(clean)
                    } catch (e: Exception) {
                        continue
                    }
                    for (i in bars.indices) {
                        if (bars[i]) {
                            canvas.drawRect(
                                (op.x + i * op.narrow).toFloat(), op.y.toFloat(),
                                (op.x + (i + 1) * op.narrow).toFloat(), (op.y + op.h).toFloat(), paint,
                            )
                        }
                    }
                }
            }
        }
        return bmp
    }
}
