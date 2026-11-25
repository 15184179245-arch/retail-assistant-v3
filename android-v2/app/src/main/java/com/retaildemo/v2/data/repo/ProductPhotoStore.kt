package com.retaildemo.v2.data.repo

import android.content.Context
import java.io.File

/**
 * 商品照片本地存储（I19）。待传队列的「唯一事实」= photos/ 私有目录下的文件本身，
 * 文件名 `<productId>_<时间戳>.jpg` 自带归属（拍照时手头就有服务端商品 id），
 * 顺序/主图/正式 url 以服务端 product_images 表为准——APP 端零 Room 改动、零迁移。
 * 契约同 ReceiptPhotoStore：外部私有目录免存储权限，只记文件名绝不存绝对路径。
 */
object ProductPhotoStore {
    private const val DIR = "photos"

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), DIR).also { it.mkdirs() }

    fun fileNameFor(productId: Long, ts: Long = System.currentTimeMillis()): String =
        "${productId}_$ts.jpg"

    /** 从文件名解析商品 id（不符合本目录契约的脏文件返回 null，补传时跳过）。 */
    fun productIdOf(fileName: String): Long? =
        fileName.removeSuffix(".jpg").substringBefore('_').toLongOrNull()

    /** 拍照目标文件（内置相机 CameraX 直写，见 CameraCaptureActivity）。 */
    fun newTargetFile(ctx: Context, productId: Long): File =
        File(dir(ctx), fileNameFor(productId))

    /**
     * I27 连拍：连拍会话里第 [seq] 张的文件名（基名加 `_seq` 段）。
     * `<pid>_<ts>_<seq>.jpg` 依旧满足 [productIdOf] 的解析契约，待传队列零改动兼容。
     */
    fun burstTargetFile(base: File, seq: Int): File =
        File(base.parentFile, "${base.nameWithoutExtension}_$seq.jpg")

    /** 某商品的本地待传照片，按文件名时间戳倒序=新在前。 */
    fun pendingOf(ctx: Context, productId: Long): List<File> =
        pendingIn(dir(ctx), productId)

    /** 全部待传（补传 Worker 用）。 */
    fun allPending(ctx: Context): List<File> =
        allPendingIn(dir(ctx))

    fun pendingIn(dir: File, productId: Long): List<File> =
        dir.listFiles { f -> f.isFile && productIdOf(f.name) == productId }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun allPendingIn(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && productIdOf(f.name) != null }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun delete(file: File) {
        runCatching { file.takeIf { it.exists() }?.delete() }
    }
}
