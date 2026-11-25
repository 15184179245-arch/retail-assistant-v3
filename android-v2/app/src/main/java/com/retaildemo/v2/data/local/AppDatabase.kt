package com.retaildemo.v2.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.retaildemo.v2.data.local.dao.BillDao
import com.retaildemo.v2.data.local.dao.ClientLogDao
import com.retaildemo.v2.data.local.dao.CustomerDao
import com.retaildemo.v2.data.local.dao.DraftDao
import com.retaildemo.v2.data.local.dao.ProductDao
import com.retaildemo.v2.data.local.dao.ProductViewDao
import com.retaildemo.v2.data.local.dao.SearchHistoryDao
import com.retaildemo.v2.data.local.dao.ShelfDao
import com.retaildemo.v2.data.local.dao.TempProductDao
import com.retaildemo.v2.data.local.dao.PrintJobDao
import com.retaildemo.v2.data.local.entity.BillDraftEntity
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.ClientLogEntity
import com.retaildemo.v2.data.local.entity.CustomerEntity
import com.retaildemo.v2.data.local.entity.DataMarkEntity
import com.retaildemo.v2.data.local.entity.PrintJobEntity
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.local.entity.ProductViewEntity
import com.retaildemo.v2.data.local.entity.ProductSupplementEntity
import com.retaildemo.v2.data.local.entity.SearchHistoryEntity
import com.retaildemo.v2.data.local.entity.ShelfBindingEntity
import com.retaildemo.v2.data.local.entity.ShelfEntity
import com.retaildemo.v2.data.local.entity.ShelfLevelEntity
import com.retaildemo.v2.data.local.entity.ShelfSlotEntity
import com.retaildemo.v2.data.local.entity.ShelfZoneEntity
import com.retaildemo.v2.data.local.entity.TempProductEntity

@Database(
    entities = [ProductEntity::class, BillEntity::class, CustomerEntity::class, SearchHistoryEntity::class, BillDraftEntity::class, TempProductEntity::class, DataMarkEntity::class, ProductSupplementEntity::class, ProductViewEntity::class, PrintJobEntity::class, ShelfEntity::class, ShelfLevelEntity::class, ShelfSlotEntity::class, ShelfBindingEntity::class, ShelfZoneEntity::class, ClientLogEntity::class],
    version = 25,
    // 排雷 ③a（2026-09-10）：开启 schema 导出（schemas/ 目录）——此前 12 个手写
    // Migration 无 schema 历史可机器校验，列定义漂移只会在运行时崩
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun billDao(): BillDao
    abstract fun customerDao(): CustomerDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun draftDao(): DraftDao
    abstract fun tempProductDao(): TempProductDao
    abstract fun dataMarkDao(): com.retaildemo.v2.data.local.dao.DataMarkDao
    abstract fun supplementDao(): com.retaildemo.v2.data.local.dao.SupplementDao
    abstract fun productViewDao(): ProductViewDao
    abstract fun printJobDao(): PrintJobDao
    abstract fun mirrorDao(): com.retaildemo.v2.data.local.dao.MirrorDao
    abstract fun shelfDao(): ShelfDao
    abstract fun clientLogDao(): ClientLogDao

    companion object {
        /** v1→v2：新增 search_history / bill_draft 表 + customers.credit_limit 列。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS search_history (keyword TEXT NOT NULL PRIMARY KEY, searched_at INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS bill_draft (id INTEGER NOT NULL PRIMARY KEY, customer_name TEXT NOT NULL, items TEXT NOT NULL, saved_at INTEGER NOT NULL)",
                )
                db.execSQL("ALTER TABLE customers ADD COLUMN credit_limit REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE products ADD COLUMN category TEXT")
            }
        }

        /** v2→v3（S2）：products 增 sale_status 列（零库存冻结两段式检索）。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN sale_status TEXT")
            }
        }

        /** v3→v4（B2）：bills 增 pay_method / receipt_photo 列（收款方式标注 + 小票拍照）。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN pay_method TEXT")
                db.execSQL("ALTER TABLE bills ADD COLUMN receipt_photo TEXT")
            }
        }

        /** v4→v5（B3）：新增 temp_products 表（自定义/串货无码货临时档案，TMP- 专用号段）。 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS temp_products (" +
                        "temp_code TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, name TEXT NOT NULL, " +
                        "spec TEXT, sell_price REAL NOT NULL, cost_price REAL, note TEXT, " +
                        "source_vendor TEXT, strade_price REAL, voucher_photo TEXT, " +
                        "reimburse_status TEXT NOT NULL, created_by TEXT NOT NULL, created_at TEXT NOT NULL)",
                )
            }
        }

        /** v5→v6（B5）：customers 增 pending_sync（临时客户标记），bills 增 invoice_needed（需开票标记）。 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE customers ADD COLUMN pending_sync INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE bills ADD COLUMN invoice_needed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6→v7（B6）：bills 增 direction（sale|return 方向标记）+ ref_bill_id（退货单关联原单）。 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN direction TEXT NOT NULL DEFAULT 'sale'")
                db.execSQL("ALTER TABLE bills ADD COLUMN ref_bill_id TEXT")
            }
        }

        /** v7→v8（I1）：bills 增 collaborators（协同人逗号名单，可空）——设备绑定+店员归属。 */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN collaborators TEXT")
            }
        }

        /** v8→v9（I4）：新增 data_marks（数据问题标记闭环）+ product_supplements（商品信息本地补充，§11）两表。纯建表，老库无损。 */
        /** v9→v10：新增 product_views 最近浏览表（详情打开即记，最多 20 条）。 */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS product_views (barcode TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, spec TEXT NOT NULL, sell_price REAL NOT NULL, viewed_at INTEGER NOT NULL)",
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS data_marks (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, sku TEXT NOT NULL, barcode TEXT NOT NULL, " +
                        "name TEXT NOT NULL, mark_type TEXT NOT NULL, note TEXT NOT NULL, photo TEXT, " +
                        "status TEXT NOT NULL, created_by TEXT NOT NULL, created_at TEXT NOT NULL, resolved_at TEXT)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS product_supplements (" +
                        "sku TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, brand TEXT, spec TEXT, note TEXT, " +
                        "photo TEXT, pending_barcode TEXT, updated_by TEXT NOT NULL, updated_at TEXT NOT NULL)",
                )
            }
        }

        /** v10→v11（S5）：新增 print_jobs 打印任务队列表（6大打印场景统一队列，防重+失败重试3次）。 */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS print_jobs (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "sku TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "spec TEXT, " +
                        "barcode TEXT, " +
                        "sell_price REAL NOT NULL DEFAULT 0.0, " +
                        "qty INTEGER NOT NULL DEFAULT 1, " +
                        "source_scene TEXT NOT NULL DEFAULT 'detail', " +
                        "status TEXT NOT NULL DEFAULT 'pending', " +
                        "fail_count INTEGER NOT NULL DEFAULT 0, " +
                        "created_by TEXT NOT NULL DEFAULT '', " +
                        "created_at INTEGER NOT NULL DEFAULT 0)",
                )
            }
        }

        /** v11→v12：products 增 brand 列（品牌维度搜索与展示）。 */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN brand TEXT")
            }
        }

        /** v12→v13（B7 打烊闭环）：bills 增回收站两列（跨天挂单独立字段，status 红线不动）。 */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN recycled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bills ADD COLUMN recycled_at TEXT")
            }
        }

        /** v13→v14（M3 人员名册）：bills 增归属人 owner_id 一等字段（改名不断链；不做权限隔离）。 */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN owner_id TEXT")
            }
        }

        /**
         * v14→v15（I20 货架重写）：新增三级货架 4 表 DDL。
         *
         * 抽成常量是为了能被单测直接执行，与 Room 由实体生成的 schema 逐列比对
         * （`ShelfRepoTest.migration DDL matches entity generated schema`）——手写迁移与实体漂移
         * 只会在真机升级时崩，必须在测试里拦下。
         */
        internal val SHELF_DDL_14_15: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS shelves (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "tag_id TEXT NOT NULL, " +
                "note TEXT, " +
                "sort_order INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_shelves_tag ON shelves (tag_id)",

            "CREATE TABLE IF NOT EXISTS shelf_levels (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "shelf_id INTEGER NOT NULL, " +
                "name TEXT NOT NULL, " +
                "tag_id TEXT NOT NULL, " +
                "sort_order INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_shelf_levels_shelf ON shelf_levels (shelf_id)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_shelf_levels_tag ON shelf_levels (tag_id)",

            "CREATE TABLE IF NOT EXISTS shelf_slots (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "level_id INTEGER NOT NULL, " +
                "name TEXT NOT NULL, " +
                "tag_id TEXT NOT NULL, " +
                "sort_order INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_shelf_slots_level ON shelf_slots (level_id)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_shelf_slots_tag ON shelf_slots (tag_id)",

            "CREATE TABLE IF NOT EXISTS shelf_bindings (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "level_id INTEGER NOT NULL, " +
                "slot_id INTEGER, " +
                "product_id INTEGER NOT NULL, " +
                "barcode TEXT, " +
                "name TEXT NOT NULL, " +
                "misplaced INTEGER NOT NULL, " +
                "note TEXT, " +
                "created_by TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_shelf_bindings_level ON shelf_bindings (level_id)",
            "CREATE INDEX IF NOT EXISTS idx_shelf_bindings_slot ON shelf_bindings (slot_id)",
            "CREATE INDEX IF NOT EXISTS idx_shelf_bindings_product ON shelf_bindings (product_id)",
        )

        /**
         * v14→v15（I20 货架重写）：新增三级货架 4 表（shelves / shelf_levels / shelf_slots / shelf_bindings）。
         *
         * 纯建表，老库无损、不动任何既有列。层级靠自增 id 父子外键（名字完全自定义，不可解析），
         * 排序显式 sort_order，tag_id 唯一且与显示名解耦（NFC 写 tagId，改名免重贴）。
         * 存量五级表（后端 inventory 服务）与 product_locations **零改动**（冻结红线，老板已确认是测试数据）。
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SHELF_DDL_14_15.forEach { db.execSQL(it) }
            }
        }

        /**
         * v15→v16（价签参考版式）：print_jobs 增 unit 列（价签「单位」行数据源）。
         * 纯加列，老库无损；老任务 unit 为 null，打印时该行自动不占位。
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE print_jobs ADD COLUMN unit TEXT")
            }
        }

        /**
         * v16→v17（S17 真机实测暴露）：`products.stock_qty` 由 INTEGER 改 REAL——
         * 上游库（管家婆）散装商品库存是小数（实测 191 行：0.4 捆 / 12.5 米），
         * 原 Int 有两处害：① Gson 整包解析 Integer 遇小数抛异常 → 手机永远拉不下商品库；
         * ② Room getInt() 静默截断 0.4→0，64 条商品在 APP 里被显示成「缺货」。
         * SQLite 改不了列类型 → 建新表搬运（小数原样保留，因为老列 INTEGER 亲和性下
         * 12.5 本就以 REAL 存储），再改名；products 无索引/外键，安全。
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `products_new` (" +
                        "`id` INTEGER NOT NULL, `barcode` TEXT, `sku` TEXT, `name` TEXT NOT NULL," +
                        " `spec` TEXT, `unit` TEXT, `category` TEXT, `sell_price` REAL NOT NULL," +
                        " `cost_price` REAL NOT NULL, `stock_qty` REAL NOT NULL, `pinyin` TEXT," +
                        " `initials` TEXT, `updated_at` TEXT, `sale_status` TEXT, `brand` TEXT," +
                        " PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO `products_new` (`id`,`barcode`,`sku`,`name`,`spec`,`unit`," +
                        "`category`,`sell_price`,`cost_price`,`stock_qty`,`pinyin`,`initials`," +
                        "`updated_at`,`sale_status`,`brand`) " +
                        "SELECT `id`,`barcode`,`sku`,`name`,`spec`,`unit`,`category`,`sell_price`," +
                        "`cost_price`,`stock_qty`,`pinyin`,`initials`,`updated_at`,`sale_status`,`brand` " +
                        "FROM `products`"
                )
                db.execSQL("DROP TABLE `products`")
                db.execSQL("ALTER TABLE `products_new` RENAME TO `products`")
            }
        }

        /** v17→v18（S18）：customers 增上游档案锚点列（管家婆只读同步），老库列可空。internal=迁移复演测试可引用。 */
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE customers ADD COLUMN upstream_id TEXT")
                db.execSQL("ALTER TABLE customers ADD COLUMN upstream_code TEXT")
            }
        }

        /**
         * v18→v19（H2 运行日志系统）：新增 client_logs 表——APP 运行日志本地留存 + 批量上报网关。
         * 纯建表老库无损；上传成功即删，容量上限由 AppLogger prune 控制（≤2000 行）。
         */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `client_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, `level` TEXT NOT NULL, `tag` TEXT NOT NULL, " +
                        "`message` TEXT NOT NULL)"
                )
            }
        }

        /** v19→v20（S19 客户选择页优化）：customers 增 pinyin/contacts/category（上游档案检索/展示字段，可空）。 */
        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE customers ADD COLUMN pinyin TEXT")
                db.execSQL("ALTER TABLE customers ADD COLUMN contacts TEXT")
                db.execSQL("ALTER TABLE customers ADD COLUMN category TEXT")
            }
        }

        /**
         * v20→v21（I20 补需求 2026-09-13 老板）：层/格补 `note` 备注列（货架级本就有）。
         *
         * 老板真机实测反馈「没有备注的地方」——货架建好了但没地方写「这层放什么」，
         * 只能靠名字硬记，名字又完全自定义（不可解析），久了必然记混。备注给三级都配上。
         * 纯加可空列，老库无损（存量层/格 note 为 null，UI 上就是没有备注行）。
         */
        internal val SHELF_NOTE_DDL_20_21 = listOf(
            "ALTER TABLE shelf_levels ADD COLUMN note TEXT",
            "ALTER TABLE shelf_slots ADD COLUMN note TEXT",
        )

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SHELF_NOTE_DDL_20_21.forEach { db.execSQL(it) }
            }
        }

        /**
         * v21→v22（I22/I23 2026-09-13 老板令）：
         * ① 新建 `shelf_zones`（分区/楼层文件夹树，可嵌套）——老板「货架要编入分区和楼层，像文件夹一样」；
         * ② 四张货架表（shelves/shelf_levels/shelf_slots/shelf_bindings）补同步元数据三列：
         *    `updated_at`（LWW 依据）/`dirty`（有未上传改动）/`deleted`（软删，防删除在别的设备复活）；
         * ③ `shelves` 补 `zone_id`（所在分区，可空 = 未分类）。
         * 纯建表 + 纯加列，老库无损；回填：让存量行 updated_at 从各表自有的日期列取值
         * （不能用 `System.currentTimeMillis()` 当列默认——SQLite ADD COLUMN 不允许非常量默认，
         * 而且回填成同一个「现在」会让所有存量行都像刚改过，用旧值更诚实）。
         */
        internal val SHELF_SYNC_DDL_21_22 = listOf(
            // ① 分区/楼层（文件夹树）
            """CREATE TABLE IF NOT EXISTS `shelf_zones` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `parent_id` INTEGER,
                `name` TEXT NOT NULL,
                `tag_id` TEXT NOT NULL,
                `note` TEXT,
                `sort_order` INTEGER NOT NULL DEFAULT 0,
                `created_at` INTEGER NOT NULL DEFAULT 0,
                `updated_at` INTEGER NOT NULL DEFAULT 0,
                `dirty` INTEGER NOT NULL DEFAULT 1,
                `deleted` INTEGER NOT NULL DEFAULT 0
            )""",
            "CREATE INDEX IF NOT EXISTS `idx_shelf_zones_parent` ON `shelf_zones` (`parent_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_shelf_zones_tag` ON `shelf_zones` (`tag_id`)",
            // ② shelves 同步列 + zone_id
            // 注意：加列不带 DEFAULT——Room 实体的列定义里没有 SQL 默认值（默认值在 Kotlin 侧），
            // 带 DEFAULT 会让「迁移 DDL vs 实体 schema」逐列比对不过（真机升级即崩）。存量行稍后统一 UPDATE 回填。
            "ALTER TABLE `shelves` ADD COLUMN `zone_id` INTEGER",
            "ALTER TABLE `shelves` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `shelves` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `shelves` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0",
            // ③ levels / slots（补 created_at 列，便于 updated_at 回填）
            "ALTER TABLE `shelf_levels` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `shelf_levels` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `shelf_levels` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `shelf_levels` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `shelf_slots` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `shelf_slots` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `shelf_slots` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `shelf_slots` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0",
            // ④ bindings（有 created_at 列回填）+ 跨设备稳定 uid（I22 同步主键）
            // ⚠️ 顺序铁律（2.12.0 真机崩溃教训，OPPO PGU110 实录）：**先回填 uid、后建唯一索引**——
            // ADD COLUMN 后所有存量行 uid 都是空串，先建索引时两行以上即撞 UNIQUE
            // （SQLiteConstraintException: UNIQUE constraint failed: shelf_bindings.uid）→ 启动即崩循环。
            // 'B' || id 以主键拼值，天然唯一，回填后建索引零碰撞。
            "ALTER TABLE `shelf_bindings` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE `shelf_bindings` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `shelf_bindings` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE `shelf_bindings` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0",
            "UPDATE `shelf_bindings` SET `uid` = 'B' || `id` WHERE `uid` = ''",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_shelf_bindings_uid` ON `shelf_bindings` (`uid`)",
            // 回填 updated_at（从各表自有时间列）
            "UPDATE `shelves` SET `updated_at` = `created_at` WHERE `updated_at` = 0",
            "UPDATE `shelf_levels` SET `updated_at` = `created_at` WHERE `updated_at` = 0",
            "UPDATE `shelf_slots` SET `updated_at` = `created_at` WHERE `updated_at` = 0",
            "UPDATE `shelf_bindings` SET `updated_at` = `created_at` WHERE `updated_at` = 0",
            // 存量行必须上传一次（老数据此前从未同步过；dirty=1 让首次同步把它们带上去）
            "UPDATE `shelves` SET `dirty` = 1",
            "UPDATE `shelf_levels` SET `dirty` = 1",
            "UPDATE `shelf_slots` SET `dirty` = 1",
            "UPDATE `shelf_bindings` SET `dirty` = 1",
        )

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SHELF_SYNC_DDL_21_22.forEach { db.execSQL(it) }
            }
        }

        /**
         * v22→v23（2026-09-19 老板令）：products 增 `model` 列（上游「型号」=出厂货号，
         * 如雷士 DD.00000001）。此前同步链路从未收该字段，店里按货号搜不到任何商品。
         * 纯加可空列老库无损；存量行 model 为 null，镜像全量同步自动补齐
         * （服务端 /api/sync 为 SELECT *，已带 model 下发）。
         */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN model TEXT")
            }
        }

        /**
         * v23→v24（B12 单据两态）：bills 增 `stage` 列（draft=挂账 / ready=待出库），
         * 与 status 正交（红线「绝不往 status 加值」不碰）。存量单一律落默认 draft——
         * 不做批量猜测，靠人逐单补全三件后才标待出库。
         * DDL 提成常量供单测执行（ShelfRepoTest 迁移护栏同款手法）。
         */
        internal const val STAGE_DDL_23_24: String =
            "ALTER TABLE bills ADD COLUMN stage TEXT NOT NULL DEFAULT 'draft'"

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(STAGE_DDL_23_24)
            }
        }

        /**
         * v24→v25（B13 结算回执）：bills 增 `bill_code` / `order_no` 可空列（管家婆上云出库单号与订单号）。
         * 只存不参与业务判断；回执到来时由 APP 侧更新落库。纯加可空列，存量行默认为 null。
         */
        internal val RECEIPT_DDL_24_25: List<String> = listOf(
            "ALTER TABLE bills ADD COLUMN bill_code TEXT",
            "ALTER TABLE bills ADD COLUMN order_no TEXT",
        )

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                RECEIPT_DDL_24_25.forEach { db.execSQL(it) }
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun build(context: android.content.Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "retaildemo_v2.db",
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                        MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
                        MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25,
                    )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            try {
                                db.execSQL("PRAGMA temp_store = MEMORY")
                                db.execSQL("PRAGMA cache_size = -16000")
                            } catch (e: Throwable) {
                                android.util.Log.w("AppDatabase", "Failed to configure sqlite pragmas", e)
                            }
                            seedIfEmpty(db)
                        }
                    })
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { instance = it }
            }

        /** One fictional item seeded on opening an otherwise empty database. */
        private fun seedIfEmpty(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT INTO products (id,barcode,sku,name,spec,model,unit,category," +
                    "sell_price,cost_price,stock_qty,pinyin,initials,updated_at,sale_status,brand) " +
                    "SELECT 1,'','DEMO-101','演示耗材','规格 A','','盒','演示',8.8,0.0,12,'','','','active','' " +
                    "WHERE NOT EXISTS (SELECT 1 FROM products)"
            )
        }
    }
}
