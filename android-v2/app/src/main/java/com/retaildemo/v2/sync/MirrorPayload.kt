package com.retaildemo.v2.sync

import com.retaildemo.v2.data.local.entity.BillDraftEntity
import com.retaildemo.v2.data.local.entity.CustomerEntity
import com.retaildemo.v2.data.local.entity.DataMarkEntity
import com.retaildemo.v2.data.local.entity.ProductSupplementEntity
import com.retaildemo.v2.data.local.entity.ProductViewEntity
import com.retaildemo.v2.data.local.entity.SearchHistoryEntity
import com.retaildemo.v2.data.local.entity.TempProductEntity
import com.retaildemo.v2.data.remote.MirrorPushRequest

/**
 * S13 镜像载荷组装（纯函数，JVM 可测）：Room 实体 → 服务端 snake_case 行。
 * 键集=mirror 服务表契约（见 src/mirror/mirror_server.py），照片字段只传文件名。
 */
object MirrorPayload {

    private fun row(vararg pairs: Pair<String, Any?>): Map<String, Any?> = mapOf(*pairs)

    fun build(
        device: String,
        tempProducts: List<TempProductEntity>,
        dataMarks: List<DataMarkEntity>,
        supplements: List<ProductSupplementEntity>,
        productViews: List<ProductViewEntity>,
        searchHistory: List<SearchHistoryEntity>,
        drafts: List<BillDraftEntity>,
        customers: List<CustomerEntity>,
    ): MirrorPushRequest = MirrorPushRequest(
        device = device,
        tables = mapOf(
            "temp_products" to tempProducts.map {
                row(
                    "temp_code" to it.tempCode, "type" to it.type, "name" to it.name,
                    "spec" to it.spec, "sell_price" to it.sellPrice, "cost_price" to it.costPrice,
                    "note" to it.note, "source_vendor" to it.sourceVendor,
                    "strade_price" to it.stradePrice, "reimburse_status" to it.reimburseStatus,
                    "created_at" to it.createdAt,
                )
            },
            "data_marks" to dataMarks.map {
                row(
                    "id" to it.id.toString(), "sku" to it.sku, "barcode" to it.barcode,
                    "name" to it.name, "mark_type" to it.markType, "note" to it.note,
                    "status" to it.status, "created_at" to it.createdAt,
                )
            },
            "product_supplements" to supplements.map {
                row(
                    "sku" to it.sku, "name" to it.name, "brand" to it.brand, "spec" to it.spec,
                    "note" to it.note, "pending_barcode" to it.pendingBarcode,
                    "updated_at" to it.updatedAt,
                )
            },
            "product_views" to productViews.map {
                row(
                    "barcode" to it.barcode, "name" to it.name, "spec" to it.spec,
                    "sell_price" to it.sellPrice, "viewed_at" to it.viewedAt,
                )
            },
            "search_history" to searchHistory.map {
                row("keyword" to it.keyword, "searched_at" to it.searchedAt)
            },
            "bill_draft" to drafts.map {
                row(
                    "id" to it.id.toString(), "customer_name" to it.customerName,
                    "items" to it.items, "saved_at" to it.savedAt,
                )
            },
            "customers" to customers.map {
                row(
                    "id" to it.id, "name" to it.name, "phone" to it.phone,
                    "credit_limit" to it.creditLimit, "pending_sync" to it.pendingSync,
                )
            },
        ),
    )
}
