package com.retaildemo.v2.data.repo

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * 小票照片本地存储（B2）。存 APP 外部私有目录 receipt/，无需存储权限（API19+）。
 * 契约：BillEntity.receiptPhoto 只存**文件名**（相对私有目录），绝不存绝对路径——换机/备份后绝对路径失效。
 */
object ReceiptPhotoStore {
    private const val DIR = "receipt"

    fun dir(ctx: Context): File = dirFor(ctx, DIR)

    /** I4 通用目录（supplement/ 等）：同契约（只存文件名），B2/B3 原调用保持不动。 */
    fun dirFor(ctx: Context, dir: String): File =
        File(ctx.getExternalFilesDir(null), dir).also { it.mkdirs() }

    fun newFileName(billId: String): String = "${billId}_${System.currentTimeMillis()}.jpg"

    /** 相机 TakePicture 目标文件（预建空文件，provider 授权后拍照直写此文件）。 */
    fun newTargetFile(ctx: Context, billId: String): File =
        File(dir(ctx), newFileName(billId))

    /** 相册/位图另存为 JPEG，返回文件名（写入 BillEntity.receiptPhoto）。 */
    fun saveBitmap(ctx: Context, billId: String, bmp: Bitmap): String =
        saveBitmapTo(ctx, DIR, billId, bmp)

    /** I4 通用另存（目录自选，返回文件名只存库不存绝对路径）。 */
    fun saveBitmapTo(ctx: Context, dir: String, prefix: String, bmp: Bitmap): String {
        val f = File(dirFor(ctx, dir), "${prefix}_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return f.name
    }

    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    /** I4 通用取文件（supplement/ 等目录）。 */
    fun fileIn(ctx: Context, dir: String, name: String): File = File(dirFor(ctx, dir), name)

    fun path(ctx: Context, name: String): String = file(ctx, name).absolutePath

    /** 删除照片文件（置 receiptPhoto=null 时调用）；文件不存在静默忽略。 */
    fun delete(ctx: Context, name: String?) {
        if (name.isNullOrBlank()) return
        runCatching { file(ctx, name).takeIf { it.exists() }?.delete() }
    }
}
