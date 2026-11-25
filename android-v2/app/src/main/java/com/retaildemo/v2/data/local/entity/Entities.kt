package com.retaildemo.v2.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 商品（来源：网关 /api/sync 全量 + /api/search 补充）。
 * 列名对齐 wujin.db 蛇形风格：sell_price/cost_price，无 price 字段。
 */
@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: Long,
    val barcode: String?,
    val sku: String?,
    val name: String,
    val spec: String?,
    /** 上游「型号」（出厂货号 DD.00000001）；老库迁移后为 null，镜像同步自动补齐。 */
    val model: String? = null,
    val unit: String?,
    val category: String?,
    @ColumnInfo(name = "sell_price") val sellPrice: Double,
    @ColumnInfo(name = "cost_price") val costPrice: Double,
    /** S17：库存 REAL——上游散装商品有小数（0.4 捆 / 12.5 米），Int 会截断成 0=假缺货。 */
    @ColumnInfo(name = "stock_qty") val stockQty: Double,
    val pinyin: String?,
    val initials: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: String?,
    /** S2 销售状态：active/frozen/clearable；null（老数据/未同步到）兜底视为在售。 */
    @ColumnInfo(name = "sale_status") val saleStatus: String? = null,
    val brand: String? = null,
)

/** 单条账单商品，序列化为 JSON 存入 BillEntity.items。 */
data class BillItem(
    val barcode: String,
    val sku: String,
    val name: String,
    val spec: String,
    val unit: String,
    val qty: Int,
    val price: Double,
    val costPrice: Double,
    /** 记账当时的成本价快照；null=无成本来源(如自定义商品)。不随后续商品调价变化。 */
    val costPriceSnap: Double? = null,
    /** 项级备注（灵活记账：如"少收两块"），可空，老 JSON 无此字段为 null。 */
    val itemNote: String? = null,
    /** B3 临时码（TMP- 前缀）：无码货（自定义/串货）记账时挂上，sku 留空；事后 B4 回写正式码。 */
    val tempCode: String? = null,
    /** 03 稿：管家婆逐条录入状态——店员扫枪录入后手动置位；JSON 列存老数据缺省 false，无需 Room 迁移。 */
    val recorded: Boolean = false,
) {
    val subtotal: Double get() = price * qty
}

/**
 * 账单。status 只有 pending(挂账/待录入管家婆) / paid(已出库) 两个业务值；
 * 离线同步状态用独立的 needsSync 布尔，禁止与 status 混用（旧工程教训，见 AGENTS.md）。
 * createdAt/updatedAt 对齐服务端 bills_sync 契约："yyyy-MM-dd HH:mm:ss"。
 */
@Entity(tableName = "bills")
data class BillEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "customer_name") val customerName: String,
    @ColumnInfo(name = "total_amount") val totalAmount: Double,
    @ColumnInfo(name = "paid_amount") val paidAmount: Double?,
    val status: String,
    val items: String,
    val note: String,
    val staff: String,
    /**
     * M3 归属人（人员名册里的员工 id）。
     * 归属的**一等字段**：`staff` 是显示名，改名/同名会断链，ownerId 不会。
     * 用途=业绩统计与责任追溯；拍板「全员管理员身份」下**不做访问隔离**。空=老单或未绑定名册。
     */
    @ColumnInfo(name = "owner_id") val ownerId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "paid_at") val paidAt: String?,
    @ColumnInfo(name = "needs_sync") val needsSync: Boolean,
    /** 收款方式：cash(现金) / pos(POS) / other(备注例外)，店内现结制；null=未标注。 */
    @ColumnInfo(name = "pay_method") val payMethod: String? = null,
    /** B2 小票照片文件名（存 APP 私有目录 receipt/，只存名不存绝对路径）；null=未拍。 */
    @ColumnInfo(name = "receipt_photo") val receiptPhoto: String? = null,
    /** B5 需开发票标记（现结制下只做标记，不做开票流程）。 */
    @ColumnInfo(name = "invoice_needed") val invoiceNeeded: Boolean = false,
    /** B6 方向标记：sale=正单 / return=退货负数单。**绝不往 status 加值**（红线 4），退货是方向不是状态。 */
    @ColumnInfo(name = "direction") val direction: String = "sale",
    /** B6 退货单关联的原单 id（正单为 null）。双向跳转追溯用。 */
    @ColumnInfo(name = "ref_bill_id") val refBillId: String? = null,
    /** I1 协同人（逗号分隔名单，可空）：一单多人协作（一人接待一人找货）；主记账人=staff。 */
    @ColumnInfo(name = "collaborators") val collaborators: String? = null,
    /** B7 跨天挂单进回收站（独立字段，**绝不往 status 加值**——红线 4）；恢复=false。 */
    @ColumnInfo(name = "recycled") val recycled: Boolean = false,
    /** B7 进回收站时间（恢复时清空）。 */
    @ColumnInfo(name = "recycled_at") val recycledAt: String? = null,
    /**
     * B12 单据阶段（与 status 正交）：draft=挂账（个人单，不进全店、不同步插件）/
     * ready=待出库（三件齐，进全店+同步收银端插件）。
     * **与 status 正交**：status 仍是 pending(挂账未出库)/paid(已出库)，stage 描述「出门三件齐没齐」。
     * 为什么不开进 status：源码既定红线「绝不往 status 加值」，且 stage 需要独立回退。
     * 存量单迁移后一律 draft（不做批量猜测）。
     */
    @ColumnInfo(name = "stage", defaultValue = "draft") val stage: String = STAGE_DRAFT,
    /** B13 单据编号（管家婆上云出库单号，如 C-XSCK-18118-260726...）；null=未回写或老单。只存不参与业务判断。 */
    @ColumnInfo(name = "bill_code") val billCode: String? = null,
    @ColumnInfo(name = "order_no") val orderNo: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_PAID = "paid"
        fun normalizeStatus(raw: String?): String =
            if (raw == STATUS_PAID) STATUS_PAID else STATUS_PENDING

        const val STAGE_DRAFT = "draft"
        const val STAGE_READY = "ready"

        /** B12 stage 归一化：任何脏值（老 JSON 缺键/null/乱值）都折叠回 draft。 */
        fun normalizeStage(raw: String?): String =
            if (raw == STAGE_READY) STAGE_READY else STAGE_DRAFT

        /**
         * B15 待补凭证：已标待出库（ready）但没拍凭证的单。
         * 凭证口径 2026-09-27 老板拍板放宽——不再硬拦出库，改为漏拍清单可跳过+打烊前补拍。
         */
        fun needsReceiptSupplement(stage: String, status: String, receiptPhoto: String?): Boolean =
            stage == STAGE_READY && status == STATUS_PENDING && receiptPhoto.isNullOrBlank()

        const val PAY_METHOD_CASH = "cash"
        const val PAY_METHOD_POS = "pos"
        const val PAY_METHOD_OTHER = "other"

        fun payMethodLabel(method: String?): String = when (method) {
            null, "" -> "现金" // 历史老单未填收款方式，默认现金
            PAY_METHOD_CASH -> "现金"
            PAY_METHOD_POS -> "POS"
            PAY_METHOD_OTHER, "note" -> "备注"
            "wechat" -> "微信" // 兼容历史数据
            "alipay" -> "支付宝" // 兼容历史数据
            else -> method.orEmpty() // 其他自定义方式原样展示
        }
    }
}

/** 客户档案：本地新建（pendingSync=true）+ 管家婆只读同步（S18，upstreamId 非空）。 */
@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    @ColumnInfo(name = "credit_limit") val creditLimit: Double = 0.0, // 0 = 不限
    @ColumnInfo(name = "created_at") val createdAt: String,
    /** B5 临时客户标记：true=本地新建尚未在管家婆建档（待关联）；false=已关联/同步。 */
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean = true,
    /** S18 上游档案锚点：管家婆客户 id（雪花，TEXT 存）与编号；null=纯本地客户。 */
    @ColumnInfo(name = "upstream_id") val upstreamId: String? = null,
    @ColumnInfo(name = "upstream_code") val upstreamCode: String? = null,
    /** S19 档案检索/展示字段（上游 pinYinCode 供拼音缩写搜索，同查价页范式）。 */
    @ColumnInfo(name = "pinyin") val pinyin: String? = null,
    @ColumnInfo(name = "contacts") val contacts: String? = null,
    @ColumnInfo(name = "category") val category: String? = null, // 零售客户/往来单位
)

/** 搜索历史（查价页）：去重置顶，最多留 30 条。 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val keyword: String,
    @ColumnInfo(name = "searched_at") val searchedAt: Long,
)

/** 最近浏览（商品详情打开即记录，最多留 20 条）。 */
@Entity(tableName = "product_views")
data class ProductViewEntity(
    @PrimaryKey val barcode: String,
    val name: String,
    val spec: String,
    @ColumnInfo(name = "sell_price") val sellPrice: Double,
    @ColumnInfo(name = "viewed_at") val viewedAt: Long,
)

/** 开单草稿（记一笔退出自动暂存，仅一条最新草稿）。 */
@Entity(tableName = "bill_draft")
data class BillDraftEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "customer_name") val customerName: String,
    val items: String, // JSON List<CartLine>
    @ColumnInfo(name = "saved_at") val savedAt: Long,
)

/**
 * 无码货临时档案（B3）：自定义/串货两通道记账时生成，tempCode 为 TMP- 前缀专用号段
 * （永不与管家婆 code 冲突）。串货比自定义多四字段；凭证照片复用 ReceiptPhotoStore 只存文件名。
 * 回写正式码（转成正常 SKU）在 B4 台账，本表回写后置 reimburseStatus=done 或移出待补队列。
 */
@Entity(tableName = "temp_products")
data class TempProductEntity(
    @PrimaryKey @ColumnInfo(name = "temp_code") val tempCode: String,
    val type: String,                        // custom | strade
    val name: String,
    val spec: String?,
    @ColumnInfo(name = "sell_price") val sellPrice: Double,
    @ColumnInfo(name = "cost_price") val costPrice: Double?,  // 自定义可空
    val note: String?,
    @ColumnInfo(name = "source_vendor") val sourceVendor: String? = null,   // 串货：在谁家串
    @ColumnInfo(name = "strade_price") val stradePrice: Double? = null,     // 串货：进价（垫钱额）
    @ColumnInfo(name = "voucher_photo") val voucherPhoto: String? = null,   // 串货：凭证照片文件名
    @ColumnInfo(name = "reimburse_status") val reimburseStatus: String = "none", // none|pending|done
    @ColumnInfo(name = "created_by") val createdBy: String,   // 垫钱人，默认 staffName（I1 前="unknown"）
    @ColumnInfo(name = "created_at") val createdAt: String,
)

/**
 * 数据问题标记（I4）：店员顺手标（无售价/价格不准/账实不符/待绑条码），专员在管家婆后台调，
 * 下次同步回 APP 后人工点「已解决」关闭（绝不自动消失）。
 * APP 只写本表，绝不直接改账改价（红线）。
 */
@Entity(tableName = "data_marks")
data class DataMarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sku: String,
    val barcode: String,
    val name: String,
    @ColumnInfo(name = "mark_type") val markType: String = TYPE_PRICE_WRONG, // no_price|price_wrong|stock_mismatch|barcode_supplement
    val note: String = "",
    val photo: String? = null,                               // 私有目录文件名（复用 ReceiptPhotoStore，只存名）
    val status: String = STATUS_OPEN,                        // open|exported|resolved
    @ColumnInfo(name = "created_by") val createdBy: String = "",
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "resolved_at") val resolvedAt: String? = null,
) {
    companion object {
        const val TYPE_NO_PRICE = "no_price"                 // 无售价
        const val TYPE_PRICE_WRONG = "price_wrong"           // 价格不准
        const val TYPE_STOCK_MISMATCH = "stock_mismatch"     // 账实不符
        const val TYPE_BARCODE_SUPPLEMENT = "barcode_supplement" // 待绑条码（§11 编辑信息自动产生，进 S3 前只记录）
        const val STATUS_OPEN = "open"
        const val STATUS_EXPORTED = "exported"
        const val STATUS_RESOLVED = "resolved"
        fun typeLabel(t: String): String = when (t) {
            TYPE_NO_PRICE -> "无售价"
            TYPE_STOCK_MISMATCH -> "账实不符"
            TYPE_BARCODE_SUPPLEMENT -> "待绑条码"
            else -> "价格不准"
        }
    }
}

/**
 * 商品信息本地补充（I4 §11）：详情页「编辑信息」入口落盘，独立表 keyed by sku——
 * 商品同步整表 upsert 从不碰本表，本地补充天然 preserve（同步器不覆盖）。
 * 价格永不在此入口出现（红线 3）；条码只记 pendingBarcode（待 S3 绑定），绝不裸改主条码列。
 */
@Entity(tableName = "product_supplements")
data class ProductSupplementEntity(
    @PrimaryKey val sku: String,
    val name: String = "",
    val brand: String? = null,                               // 本地品牌（上游 preserve 字段，本地为准）
    val spec: String? = null,                                // 本地规格型号（同上）
    val note: String? = null,                                // 本地备注
    val photo: String? = null,                               // 本地照片文件名（supplement/ 目录，只存名）
    @ColumnInfo(name = "pending_barcode") val pendingBarcode: String? = null, // 待绑新码（S3 落地前只记录+导出）
    @ColumnInfo(name = "updated_by") val updatedBy: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

/**
 * 打印任务队列实体（S5 打印场景矩阵 + 打印队列）。
 * 覆盖 6 大打印场景（详情单打/查价多选/改判补打/货架标签/来货即打/巡检补打）。
 * status 只有 pending / done / failed 三态，失败最多重试 3 次。
 */
@Entity(tableName = "print_jobs")
data class PrintJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sku: String,
    val name: String,
    val spec: String? = null,
    val barcode: String? = null,
    @ColumnInfo(name = "sell_price") val sellPrice: Double = 0.0,
    /** 基本单位（价签参考版式「单位」行；入队时从商品带出，空则不打该行）。 */
    val unit: String? = null,
    val qty: Int = 1,
    @ColumnInfo(name = "source_scene") val sourceScene: String = SCENE_DETAIL,
    val status: String = STATUS_PENDING,
    @ColumnInfo(name = "fail_count") val failCount: Int = 0,
    @ColumnInfo(name = "created_by") val createdBy: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SCENE_DETAIL = "detail"
        const val SCENE_MULTI = "multi"
        const val SCENE_REBIND = "rebind"
        const val SCENE_SHELF = "shelf"
        const val SCENE_INBOUND = "inbound"
        const val SCENE_STOCKTAKE = "stocktake"

        const val STATUS_PENDING = "pending"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"

        fun sceneLabel(scene: String): String = when (scene) {
            SCENE_DETAIL -> "详情单打"
            SCENE_MULTI -> "查价多选"
            SCENE_REBIND -> "换码补打"
            SCENE_SHELF -> "货位标签"
            SCENE_INBOUND -> "入库即打"
            SCENE_STOCKTAKE -> "巡检补打"
            else -> "手动打印"
        }
    }
}

// =====================================================================================
// I20 三级货架（货架 → 层 → 格）· 本地 Room · 名字完全自定义
//
// 为什么是 id 父子关系而不是码字符串（预研 §4.1 最高优先约束）：
//   老板拍板「货架号/层号/格号完全自定义」→ 名字里没有任何可解析的结构，`A-3-2` 式
//   层级推导彻底作废。故层级只能靠自增 `id` 父子外键表达，`name` 仅用于显示。
//   四条硬规则：① 内部引用一律用 id 绝不用名字；② 排序用显式 sort_order（自定义名无法比较）；
//   ③ 同一父下名字唯一（由 ShelfRepo 查重+合并兜底）；④ NFC 写稳定 tag_id，永不写名字。
//   ⇒ 任何货架相关代码禁止出现码格式正则。
//
// 位置与数量解耦（铁律 12）：本组表只记「商品在哪个格」，绝不记数量、绝不改库存。
// =====================================================================================

/** 货架（第一级）。name 完全自定义，tagId 是创建即生成、永不随改名变更的稳定标识。 */
@Entity(
    tableName = "shelves",
    indices = [Index(value = ["tag_id"], unique = true, name = "idx_shelves_tag")],
)
data class ShelfEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 显示名，完全自定义（如「A架」「水管架」「门口那排」）。内部引用**绝不**用它。 */
    val name: String,
    /** 稳定标签 id：NFC 载荷 `SX:<tagId>` 的内核；改名不重贴。同步时作跨设备对象主键。 */
    @ColumnInfo(name = "tag_id") val tagId: String,
    val note: String? = null,
    /** I23 所在分区（文件夹树节点 id）；null = 未分类（顶层直接可见）。 */
    @ColumnInfo(name = "zone_id") val zoneId: Long? = null,
    /** 显式排序位（同层按此升序）；自定义名无法比较大小，必须显式存。 */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    // ---- I22 多终端同步元数据（本地写路径置脏；同步按 tagId 对位） ----
    /** 本地最后修改时间（毫秒），LWW 依据；创建即=createdAt。 */
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    /** 1 = 有未上传的本地改动。 */
    val dirty: Boolean = true,
    /** 1 = 已删除（软删；同步后各端可见删除，防复活）。 */
    val deleted: Boolean = false,
)

/**
 * I23 分区 / 楼层（文件夹树节点，可嵌套）。
 *
 * 老板 2026-09-13 令：「货架也要编入分区和楼层，要想文件夹类似功能来分类整理货架」。
 * `parentId` 为空 = 顶层（楼层，如「一楼」）；非空 = 其下的分区（如「五金区」「水管区」）。
 * 深度不设硬限制（文件夹语义），实际用法两层足够；名字完全自定义、tagId 同样稳定。
 */
@Entity(
    tableName = "shelf_zones",
    indices = [
        Index(value = ["parent_id"], name = "idx_shelf_zones_parent"),
        Index(value = ["tag_id"], unique = true, name = "idx_shelf_zones_tag"),
    ],
)
data class ShelfZoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** null = 顶层（楼层）。 */
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    val name: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
    val note: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

/** 层（第二级），挂在货架下。 */
@Entity(
    tableName = "shelf_levels",
    indices = [
        Index(value = ["shelf_id"], name = "idx_shelf_levels_shelf"),
        Index(value = ["tag_id"], unique = true, name = "idx_shelf_levels_tag"),
    ],
)
data class ShelfLevelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "shelf_id") val shelfId: Long,
    val name: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
    /** 备注（2026-09-13 老板补需求）：「放螺钉」「靠墙那头」之类给人看的提示，不参与任何逻辑。 */
    val note: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    // ---- I22 同步元数据（同 shelves） ----
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

/** 格（第三级），挂在层下。允许某一层只有格没有……反之亦然；「只绑到层」见 ShelfBindingEntity.slotId。 */
@Entity(
    tableName = "shelf_slots",
    indices = [
        Index(value = ["level_id"], name = "idx_shelf_slots_level"),
        Index(value = ["tag_id"], unique = true, name = "idx_shelf_slots_tag"),
    ],
)
data class ShelfSlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "level_id") val levelId: Long,
    val name: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
    /** 备注（2026-09-13 老板补需求）：给人看的提示，不参与任何逻辑。 */
    val note: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    // ---- I22 同步元数据（同 shelves） ----
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

/**
 * 商品 × 位置 绑定（**多对多**）。
 *
 * 一品多货位（老板 2026-09-11 明确「做好」）：同一 productId 可有多条记录指向不同格/层；
 * 一格多品同理多条记录指向同一 slotId。解绑某一处**只删这一条**，商品在别处的绑定不受影响
 * （`ShelfRepo.locationsOfProduct` 负责一次列出全部位置）。
 *
 * 「只绑到层」= `slotId` 为 null：levelId 必填、slotId 可空，语义为「东西在这一层，具体哪格没细究」。
 *
 * 位置与数量解耦：本表**不存数量**，数量永远看管家婆（铁律 12）。
 */
@Entity(
    tableName = "shelf_bindings",
    indices = [
        Index(value = ["level_id"], name = "idx_shelf_bindings_level"),
        Index(value = ["slot_id"], name = "idx_shelf_bindings_slot"),
        Index(value = ["product_id"], name = "idx_shelf_bindings_product"),
        Index(value = ["uid"], unique = true, name = "idx_shelf_bindings_uid"),
    ],
)
data class ShelfBindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * 跨设备稳定标识（I22 同步主键）：创建时生成的 UUID。
     * 货架/层/格用 tagId（NFC 贴里就是它），绑定没有贴纸，另发一个——同步对位全靠它。
     */
    val uid: String = java.util.UUID.randomUUID().toString(),
    @ColumnInfo(name = "level_id") val levelId: Long,
    /** null = 只绑到层（未细究到格）。 */
    @ColumnInfo(name = "slot_id") val slotId: Long? = null,
    @ColumnInfo(name = "product_id") val productId: Long,
    /** 条码快照（可能为 null = 无码商品）。仅用于显示/反查兜底，不参与商品主数据。 */
    val barcode: String? = null,
    /** 商品名快照，供商品库未同步时仍能显示。 */
    val name: String = "",
    /** 巡架发现错位：商品不在它该在的位置（标记，不失联；铁律 12 只标记不改数）。 */
    val misplaced: Boolean = false,
    val note: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    // ---- I22 同步元数据（同 shelves） ----
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

/**
 * H2（2026-09-11 老板令）APP 运行日志：网络失败/同步失败/启动事件等本地留存，
 * LogUploadWorker 批量上报网关（/api/logs/ingest），上传成功即删——日志是遥测不是业务数据。
 * 本地表有容量上限（AppLogger prune ≤2000 行），离线积压也不撑爆存储。
 */
@Entity(tableName = "client_logs")
data class ClientLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 客户端 epoch ms；服务端另有落盘时间封存字段。 */
    val ts: Long = 0L,
    /** DEBUG/INFO/WARN/ERROR（LogPipeline.normalizeLevel 归一）。 */
    val level: String = "INFO",
    val tag: String = "",
    val message: String = "",
)
