package com.retaildemo.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * F7 种子库契约回归：检入的 retaildemo_init.db 必须 = scripts/build_asset_db.py 的唯一产物。
 *
 * JVM 单测无 SQLite 驱动（android.jar 为 stub），契约载体用脚本产出的
 * retaildemo_init.manifest.json（确定性、无时间戳）：本测试断言 manifest 与代码常量一致，
 * 并用资产文件 sha256 复核「文件与 manifest 同步」——资产被手改/脚本改了没重跑/实体改了
 * 没对齐，任何一处漂移都会在门禁炸出来（照 CategoryMapTest「资产与脚本定稿一致」先例）。
 *
 * 常量出处：
 * - IDENTITY_HASH / DB_VERSION：kapt 生成 AppDatabase_Impl 的 identityHash 与 @Database(version)
 * - EXPECTED_TABLES / 各表列集：AppDatabase @Database entities（与 seedIfEmpty 列清单同源）
 */
class AssetDbContractTest {

    private val dir: File by lazy {
        val candidates = listOf(
            File("src/main/assets/databases"),
            File("app/src/main/assets/databases"),
            File("android-v2/app/src/main/assets/databases"),
        )
        candidates.firstOrNull { it.resolve("retaildemo_init.db").isFile }
            ?: error("找不到资产目录，尝试过：$candidates")
    }

    private val manifest: Map<String, String> by lazy {
        // 提取 manifest 顶层标量字段（生成器 sort_keys + indent=1，格式确定）
        val text = dir.resolve("retaildemo_init.manifest.json").readText()
        val out = mutableMapOf<String, String>()
        for (key in listOf("asset", "sha256", "bytes", "user_version", "identity_hash", "products")) {
            val m = Regex("\"$key\":\\s*(\"[^\"]*\"|[^,\n}]+)").find(text)
                ?: error("manifest 缺字段 $key")
            out[key] = m.groupValues[1].trim().removeSurrounding("\"")
        }
        out
    }

    /** 从 manifest 文本提取指定表的列清单（保序）。 */
    private fun columnsOf(table: String): List<String> {
        val text = dir.resolve("retaildemo_init.manifest.json").readText()
        val m = Regex("\"$table\":\\s*\\[([^]]*?)]").find(text) ?: error("manifest 缺表 $table")
        return Regex("\"([^\"]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `manifest契约常量_版本与身份哈希`() {
        assertEquals("12", manifest["user_version"])
        // = AppDatabase_Impl 里 room_master_table 写入的 v12 identityHash（createFromAsset 校验依据）
        assertEquals("56b573de4f925b6c7cd0da16fd115d50", manifest["identity_hash"])
        assertEquals("retaildemo_init.db", manifest["asset"])
    }

    @Test
    fun `商品行数下限_全量种子不缩水`() {
        // 2026-09-09 上游全集 12631；只增不减是预期，缩水必须是有意识的拍板（改这里留痕）
        assertTrue("products=${manifest["products"]} 低于下限", (manifest["products"] ?: "0").toInt() >= 12631)
    }

    @Test
    fun `表集合_Room十表加room_master_table不多不少`() {
        val text = dir.resolve("retaildemo_init.manifest.json").readText()
        val tables = Regex("\"([a-z_]+)\":\\s*\\[").findAll(text).map { it.groupValues[1] }
            .filterNot { it in setOf("asset", "sha256") }.toList()
        assertEquals(
            listOf(
                "bill_draft", "bills", "customers", "data_marks", "print_jobs",
                "product_supplements", "product_views", "products", "room_master_table",
                "search_history", "temp_products",
            ),
            tables.sorted(),
        )
    }

    @Test
    fun `products列集与实体及seedIfEmpty清单一致`() {
        // 顺序即 DDL 顺序；seedIfEmpty 的 INSERT...SELECT 按同序引用
        // 2026-09-19：+model（上游「型号」=出厂货号，v23 迁移加列）
        assertEquals(
            listOf(
                "id", "barcode", "sku", "name", "spec", "model", "unit", "category",
                "sell_price", "cost_price", "stock_qty",
                "pinyin", "initials", "updated_at", "sale_status", "brand",
            ),
            columnsOf("products"),
        )
    }

    @Test
    fun `bills与print_jobs列集锚定`() {
        assertEquals(
            listOf(
                "id", "customer_name", "total_amount", "paid_amount", "status", "items",
                "note", "staff", "created_at", "updated_at", "paid_at", "needs_sync",
                "pay_method", "receipt_photo", "invoice_needed", "direction", "ref_bill_id",
                "collaborators",
            ),
            columnsOf("bills"),
        )
        assertEquals(
            listOf(
                "id", "sku", "name", "spec", "barcode", "sell_price", "qty",
                "source_scene", "status", "fail_count", "created_by", "created_at",
            ),
            columnsOf("print_jobs"),
        )
    }

    @Test
    fun `资产文件与manifest哈希同步`() {
        val dbFile = dir.resolve("retaildemo_init.db")
        val sha = MessageDigest.getInstance("SHA-256").digest(dbFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(sha, manifest["sha256"])
        assertEquals(dbFile.length(), manifest["bytes"]?.toLong())
    }
}
