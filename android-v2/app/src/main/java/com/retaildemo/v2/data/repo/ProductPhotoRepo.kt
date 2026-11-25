package com.retaildemo.v2.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.ImageUploadRequest
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.sync.ProductPhotoSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 商品照片仓储（I19）：photos/ 待传文件 → 压缩（EXIF 摆正，≤1280px JPEG80）→ dataURL 上传
 * 服务端 product_images，成功即删本地；失败留在本机由 [retryPending] 补传
 * （拍照后一次性 + 详情页打开时 + 6h 周期 Worker 兜底）。
 */
@Singleton
class ProductPhotoRepo @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val api: retaildemoApi,
    private val config: AppConfig,
    private val http: OkHttpClient,
) {
    /** 相机直写文件经 FileProvider 授权 Uri（file_paths.xml 已授权 photos/）。 */
    fun fileUri(file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)

    /** 上传一个待传文件；成功删本地，失败留文件等补传。 */
    suspend fun uploadFile(file: File): Boolean = withContext(Dispatchers.IO) {
        val pid = ProductPhotoStore.productIdOf(file.name) ?: return@withContext false
        val bytes = scaledJpegBytes(file) ?: return@withContext false
        val b64 = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val ok = runCatching {
            val resp = api.uploadProductImage(
                ImageUploadRequest(product_id = pid, image_data = b64, filename = file.name),
            )
            resp.isSuccessful && resp.body()?.success == true
        }.getOrDefault(false)
        if (ok) ProductPhotoStore.delete(file)
        ok
    }

    /** 相册选图 → 另存为待传文件 → 走统一上传（收敛详情页旧 uploadImage 通道）。 */
    suspend fun importFromUri(uri: Uri, productId: Long): Boolean = withContext(Dispatchers.IO) {
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return@withContext false
        val target = ProductPhotoStore.newTargetFile(ctx, productId)
        runCatching { target.writeBytes(bytes) }.isSuccess && uploadFile(target)
    }

    /** 扫 photos/ 逐个补传，返回 (成功数, 仍待传数)；单个失败不中断其余。 */
    suspend fun retryPending(): Pair<Int, Int> {
        val all = ProductPhotoStore.allPending(ctx)
        var ok = 0
        for (f in all) {
            if (uploadFile(f)) ok++
        }
        return ok to (all.size - ok)
    }

    /** UI 取服务端图（dto.url=/images/x.jpg 相对路径；共享 OkHttp 带鉴权头与服务器地址重写）。 */
    suspend fun fetchBitmap(relUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(config.serverUrl.trimEnd('/') + relUrl).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }.getOrNull()
    }

    companion object {
        private const val MAX_W = 1280

        /**
         * UI 取图专用采样解码。2026-09-11 老板实测闪退根因：待传照片=相机原图直存
         * （4000×3000 起，单张解码 ≈48MB），详情页缩略图横排同时全尺寸解 N 张必 OOM。
         * 统一走这里按 maxW 采样，内存可控（384px≈0.6MB/张，1280px≈6MB/张）。
         */
        fun decodeScaled(src: File, maxW: Int): android.graphics.Bitmap? = runCatching {
            if (!src.exists() || src.length() == 0L) return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(src.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
            android.graphics.BitmapFactory.decodeFile(
                src.absolutePath,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()

        /**
         * I27 批量上传消息口径（纯函数，PhotoBatchTest 锁死）：成功的张数和留在本机
         * 等补传的张数必须分开说清——不许用「已上传」口径盖住没传上去的那几张。
         */
        fun batchUploadMessage(ok: Int, failed: Int): String = when {
            failed <= 0 -> if (ok <= 1) "照片已上传 ✓" else "已上传 $ok 张 ✓"
            ok <= 0 -> "$failed 张照片已保存本机，联网后自动补传"
            else -> "已上传 $ok 张；$failed 张已保存本机，联网后自动补传"
        }

        /** 拍照后/详情页打开时立即补传一次（带联网约束；周期 Worker 之外的快路径）。 */
        fun enqueueRetry(ctx: Context) {
            runCatching {
                val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    "product_photo_retry",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<ProductPhotoSyncWorker>()
                        .setConstraints(net)
                        .build(),
                )
            }
        }

        private fun scaledJpegBytes(src: File): ByteArray? = runCatching {
            if (!src.exists() || src.length() == 0L) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_W) sample *= 2
            val bmp = BitmapFactory.decodeFile(
                src.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            val upright = exifRotated(src.absolutePath, bmp)
            val finalBmp = if (upright.width > MAX_W) {
                val ratio = MAX_W.toFloat() / upright.width
                Bitmap.createScaledBitmap(
                    upright, MAX_W,
                    (upright.height * ratio).toInt().coerceAtLeast(1), true,
                )
            } else upright
            val out = ByteArrayOutputStream()
            finalBmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }.getOrNull()

        /** 相机直写文件常带 EXIF 旋转标记，BitmapFactory 不读——按标记摆正（框架 ExifInterface，不引新依赖）。 */
        @Suppress("DEPRECATION")
        private fun exifRotated(path: String, bmp: Bitmap): Bitmap = runCatching {
            val exif = ExifInterface(path)
            val rotation = when (
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation == 0f) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        }.getOrDefault(bmp)
    }
}
