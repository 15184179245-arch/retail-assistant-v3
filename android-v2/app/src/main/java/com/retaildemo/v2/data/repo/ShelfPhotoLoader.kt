package com.retaildemo.v2.data.repo

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 货架商品卡片配图（2026-09-13 老板补需求：「商品图片上叠加半透明蒙版显示商品信息」）。
 *
 * 取图顺序（**离线优先、网络只做增强**）：
 * 1. 本机待传照片（`photos/<productId>_<ts>.jpg`，店员刚拍的）→ 有就用，零网络；
 * 2. 服务端 `product_images` 主图（[ProductRepo.imagesOfOrNull]）→ [ProductPhotoRepo.fetchBitmap] 解码；
 * 3. 都没有 → 返回 null，由 UI 画「灰底 + 商品名首字」占位。
 *
 * 两条纪律：
 * - **结果缓存**：一个商品的图只查一次（含"确认没图"的负结果），避免 LazyColumn 每次组合都打网络。
 *   只在进程内存里缓存（重启即丢），因为服务端图片可能被替换。
 * - **失败静默**：断网/服务端 500 一律按"没图"处理，绝不影响货架的绑定与找货
 *   —— 货架主链路（位置/绑定/反查）仍是 0 网络，配图纯属锦上添花。
 */
@Singleton
class ShelfPhotoLoader @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val productRepo: ProductRepo,
    private val photoRepo: ProductPhotoRepo,
) {
    /** productId → 主图相对 url；value == null 表示"确定没有图"（负缓存，同样只查一次）。 */
    private val urlCache = ConcurrentHashMap<Long, String?>()
    private val urlMutex = Mutex()

    /**
     * 取主图位图。`maxW` 默认 320px（卡片实际显示约 110dp，采样解码防 OOM——
     * 详情页曾因同时全尺寸解 N 张 4000×3000 原图闪退，见 ProductPhotoRepo.decodeScaled 注释）。
     */
    suspend fun bitmapOf(productId: Long, maxW: Int = 320): Bitmap? = withContext(Dispatchers.IO) {
        // 1) 本机待传照片（店员刚拍的，最贴近实物）
        val local = ProductPhotoStore.pendingOf(ctx, productId).firstOrNull()
        if (local != null) {
            ProductPhotoRepo.decodeScaled(local, maxW)?.let { return@withContext it }
        }
        // 2) 服务端主图
        val url = photoUrlOf(productId) ?: return@withContext null
        photoRepo.fetchBitmap(url)
    }

    /** 服务端主图相对路径；null = 无图或查不到（已缓存，重复调用不再打网络）。 */
    suspend fun photoUrlOf(productId: Long): String? {
        urlCache[productId]?.let { return it }
        if (urlCache.containsKey(productId)) return null
        return urlMutex.withLock {
            if (urlCache.containsKey(productId)) return@withLock urlCache[productId]
            val list = productRepo.imagesOfOrNull(productId)
            // is_primary 优先；服务端没标主图时取第一张（详情页同口径）
            val url = list
                ?.sortedByDescending { it.is_primary }
                ?.firstOrNull()
                ?.url
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            urlCache[productId] = url
            url
        }
    }
}
