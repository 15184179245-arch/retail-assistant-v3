package com.retaildemo.v2.print

import com.retaildemo.v2.util.qrContentOf

/**
 * 标签模板引擎 v3（2026-09-11 参考管家婆价签版式重建 + 文字防溢出机制）。
 *
 * 指令集双语言：TSPL（标签机主流：芯烨/佳博/汉印/TSC/新北洋…默认）与
 * CPCL（便携面单机常见语言，Zebra 系语法）——按打印机配置切换。
 * ESC/POS 不做：价签是不干胶，小票走管家婆收银（APP 边界）。
 * 字体：TSPL 用 TSS24.BF2（简体中文，GB2312 字库）；CPCL 用设备侧字库（GBK 编码直发）。
 *
 * 版式引擎（与 src/print/print_server.py `_price_tag_ops` 同参数同几何，双路一致）：
 * - [planPriceTagOps] 纯函数按 纸宽×纸高 产出几何指令（TEXT/LINE/QR/BARCODE），不做任何语言特定编码；
 * - 版式=参考图：外框内左侧「【标签】值」五行（商品编号/商品名称/规格/零售价/单位）+ 右侧码图；
 *   名称/规格/编号/单位按列宽换行，装不下末行省略号；价格优先完整（长价自动缩号）；
 *   幅面实在装不下时从尾部让行（单位 → 规格 → 编号），名称与价格永远保留；
 * - 全部文字走 [LabelTextFit] 防溢出三层防线（换行 / 末行省略 / 缩号），任何元素不越纸边界；
 * - TSPL/CPCL 双渲染器吃同一份 plan，几何零漂移。
 */
object LabelTemplates {

    const val CMD_TSPL = "tspl"
    const val CMD_CPCL = "cpcl"
    const val DOTS_PER_MM = 8 // 203dpi ≈ 8 dot/mm

    /**
     * CPCL 中文字体号（设备侧 TSS24 中文字库，24×24 点阵、ASCII 半宽 12dot——与 TSPL 字宽度量同口径）。
     * 2026-09-12 真机排障（新北洋 BTP-P39 PLUS）：旧代码发 `TEXT 4 0`（西文字体）导致中文
     * 乱码/缺字而二维码正常。社区约定（iprtapp CPCL 参考：`TEXT 24 0 x y "中文"`，中文走 GBK）
     * 以 24 号为 TSS24；个别固件以 55 号为中文字库——测试打印附带「字体号对照条」
     * （[cpclFontProbeTag]，4/24/55 同句中文），真机一看即定，改这一个常量即可换装。
     */
    const val CPCL_CJK_FONT = 24

    internal const val LINE_H = 24 // TSS24.BF2 @1x 单行高（dot）；位图渲染器同口径行高
    private const val BORDER_INSET = 4 // 外框内缩（dot），防骑纸边
    /** 偏移容错上限（mm）：超出即视为误配置（0.5mm 步进 × 最多 ±2mm 的物理意义以内）。 */
    const val OFFSET_MAX_MM = 10.0 // 2026-10-22 老板令「不再做限制」：2.0 → 4.0 → 10.0，且不再按纸边夹紧
    /**
     * 边缘死区（dot）：任何元素都不允许进入的纸内圈——打印偏移/走纸偏差/纸在胶辊上
     * 横向错位时，死区内的内容会被切掉，所以宁可让位也不贴边（手机边缘防误触同款思路）。
     */
    private const val DEAD_ZONE_X = 3 // 水平 ≈0.4mm
    private const val DEAD_ZONE_Y = 2 // 纵向 ≈0.25mm
    /**
     * 向外偏移上限（dot）：补偿量本来就该是「把跑偏的内容拉回纸内」；
     * 允许的最大外推 = 当前边距的一半，保证外框退到死区边上就不再退（永不贴边、永不出纸）。
     */
    /** 向外（朝纸边）最大平移：与 UI 上限（±2mm）对齐——
     *  打印头/走纸偏差是双向的，出不出纸由死区夹紧兜底，这里不再额外收紧。 */
    private const val OFFSET_OUT_MAX = 16
    /** 名称/价格加粗：TSPL/CPCL 无真粗体，用横向 2 倍拉伸实现醒目（占宽/行高同步放大才不溢出）。 */
    private const val BOLD_XMUL = 2

    data class PriceTagItem(
        val name: String,
        val price: Double,
        val spec: String = "",
        val sku: String = "",
        val barcode: String = "",
        /** 基本单位（如 个/米/箱）。 */
        val unit: String = "",
    )

    /**
     * 价签模板配置（打印机管理页可调，持久化）。
     * codeStyle: "qr"=二维码（内容=管家婆原始 SKU 带点，如 192.0.2.10，扫码枪直录管家婆）/ "barcode"=传统条形码（内容=条码）。
     * priceScale: 价格字号上限 2~4——按参考版式恒为 1 倍，仅大纸加价号时用得上。
     */
    data class PriceTagTemplate(
        val storeName: String = "",
        val showSpec: Boolean = true,
        val showSku: Boolean = true,
        val codeStyle: String = "qr",
        val priceScale: Int = 3,
        val footer: String = "",
        /** 打印偏移容错 X（mm，右为正）：整版内容整体平移，补齐打印头/走纸偏差。 */
        val offsetXMm: Double = 0.0,
        /** 打印偏移容错 Y（mm，下为正）：整版内容整体平移，补齐打印头/走纸偏差。 */
        val offsetYMm: Double = 0.0,
    )

    /** 纸规：宽×高 mm。竖版=高>宽；横版=宽≥高。 */
    data class LabelSize(val widthMm: Int, val heightMm: Int) {
        override fun toString(): String = "${widthMm}×${heightMm}"
    }

    /** 市售常用不干胶纸规（打印机管理页可选）。 */
    val PRESET_SIZES: List<LabelSize> = listOf(
        LabelSize(30, 20),
        LabelSize(30, 40),
        LabelSize(40, 20),
        LabelSize(40, 30),
        LabelSize(50, 30),
        LabelSize(60, 40),
        LabelSize(70, 50),
        LabelSize(80, 50),
    )

    /** 老配置只存纸宽时的高度默认（40 宽→30 高 与历史 40×30 货架口径一致）。 */
    fun heightFor(widthMm: Int): Int =
        mapOf(30 to 40, 40 to 30, 50 to 30, 60 to 40, 70 to 50, 80 to 50)[widthMm] ?: 40

    // ==================== 版式辅助（与 src/print/print_server.py 同口径） ====================

    /** QR 模块数估算表：(数据字符数上限, 模块数)。ECC=M 字母数字容量**上界**，宁多勿少。 */
    private val QR_CAPACITY = listOf(20 to 21, 38 to 25, 61 to 29, 90 to 33, 122 to 37, 154 to 41)

    /** 估 QR 模块数：边长 = 模块数 × 码格（TSPL 的 QRCODE 第 4 参就是码格）。 */
    internal fun qrModules(data: String): Int {
        val n = data.length
        for ((cap, mod) in QR_CAPACITY) if (n <= cap) return mod
        return QR_CAPACITY.last().second
    }

    private val NAME_BRACKET = Regex("[（(]")
    private val NAME_PACK_TAIL =
        Regex("[-—/]\\s*\\d+\\s*(枚|只|个|台|件|包|卷|筒|箱|米|对|套|支|把|片)\\s*$")

    /** 名称里嵌着的规格/型号（保守：必须有数字；型号还要求夹在中文之间，
     *  避免误伤「LED」「PP」这类纯字母品类词）。 */
    private val NAME_SPEC = Regex(
        "φ\\s*\\d+(\\.\\d+)?(\\s*[*×xX]\\s*\\d+(\\.\\d+)?)*" +
            "|(\\d+(\\.\\d+)?\\s*[A-Za-z]{0,3}\\s*[*×xX]\\s*)+\\d+(\\.\\d+)?\\s*[A-Za-z]{0,3}" +
            "|\\d+(\\.\\d+)?\\s*(W|V|A|mA|kg|g|Hz|K)(?![A-Za-z0-9])" +
            "|\\d+(\\.\\d+)?\\s*(mm|cm|寸|米|匹)" +
            "|(?<=[\\u4e00-\\u9fff])[A-Za-z]{1,5}-?\\d{2,}[A-Za-z]*",
    )

    /**
     * 整理商品名（价签/展示专用，**绝不回写数据库**——铁律 12）。
     * 返回 (整理后的名, 摘出来的规格型号串)——摘出来的部分不丢，补到规格行去。
     */
    internal fun shortName(name: String, maxDots: Int = 0): Pair<String, String> {
        val raw = name.trim()
        if (raw.isEmpty()) return "" to ""
        var s = raw.split("/")[0]
        s = NAME_BRACKET.split(s)[0]
        val hits = NAME_SPEC.findAll(s).map { it.value.trim() }.toList()
        if (hits.isNotEmpty()) s = NAME_SPEC.replace(s, "")
        s = s.trim(' ', '-', '—', '/', '#', '、', ',', '·')
        s = NAME_PACK_TAIL.replace(s, "").trim(' ', '-', '—', '/', '#', '、', ',', '·')
        if (maxDots > 0 && dispW(s) > maxDots) {
            val segs = s.split(Regex("[\\s　]+")).filter { it.isNotEmpty() }
            if (segs.size > 1) {
                // 取「中文最多」的那段（比取第一个安全：「LED 直发光平板灯」的第一段是 LED）
                val best = segs.maxByOrNull { x -> x.count { it in '\u4e00'..'\u9fff' } * 10000 + x.length }
                if (best != null && dispW(best) < dispW(s)) s = best
            }
        }
        return (s.ifEmpty { raw }) to hits.distinct().joinToString("、")
    }

    // ==================== 文字度量（委托 LabelTextFit，保留历史入口） ====================

    /** TSS24.BF2 @1x 显示宽：CJK/全角=24dot，ASCII=12dot。 */
    internal fun dispW(s: String): Int = LabelTextFit.textWidth(s)

    /** 指定字号下的显示宽（24dot 字宽按比例缩放；16dot 字约为 2/3 宽）。 */
    internal fun dispW(s: String, font: Int): Int =
        if (font == LINE_H) dispW(s) else (dispW(s) * font + LINE_H - 1) / LINE_H

    /** 按显示宽贪婪断行，最多 maxLines 行；放不下时末行省略号收尾。 */
    internal fun fitLines(text: String, maxDots: Int, maxLines: Int): List<String> =
        LabelTextFit.fitLines(text, maxDots, maxLines)

    /** 在 [minimum, preferred] 里取显示后不超 maxDots 的最大字号倍率。 */
    internal fun fitScale(text: String, maxDots: Int, preferred: Int, minimum: Int = 1): Int =
        LabelTextFit.fitScale(text, maxDots, preferred, minimum)

    /** 价格文本：Y 前缀（全 ASCII，真机字库零风险；全角￥待 F3 真机验证后再启）。 */
    internal fun priceText(price: Double): String = "%.2f".format(price)

    // ==================== 版式计划（几何中立法，渲染前无语言痕迹） ====================

    internal sealed class Op {
        data class Txt(
            val x: Int, val y: Int, val mul: Int, val text: String, val xmul: Int = mul,
            /** 设备字库高度（dot）：24 = TSS24.BF2（默认/全纸规），16 = TSS16.BF2（小横版正文再小一号）。 */
            val font: Int = LINE_H,
            /** 真粗体（位图渲染器用，2026-09-12 老板要求名称加粗）：不影响占宽度量与几何，
             *  TSPL 设备字库路径忽略（无真粗体，维持现状）；几何双路一致不破。 */
            val bold: Boolean = false,
        ) : Op()
        data class Ln(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : Op()
        data class Qr(val x: Int, val y: Int, val cell: Int, val data: String) : Op()
        data class Bar(val x: Int, val y: Int, val h: Int, val narrow: Int, val data: String) : Op()
    }

    /** CODE128 符号宽估算：约 11 模块/字符 + 起止校验，取能塞进列宽的最大窄条宽（1~3dot）。 */
    internal fun barNarrow(data: String, colDots: Int): Int {
        val modules = 11 * (data.take(20).length + 2)
        return when {
            modules * 3 <= colDots -> 3
            modules * 2 <= colDots -> 2
            else -> 1
        }
    }

    // ==================== 价签版式（参考图：左五行 + 右码图 + 外框） ====================

    /** 一行规格：行高由内容定；z=让位优先级（大者先让）；[xmul]=值横向拉伸（加粗行）；[protected]=永不让位。 */
    // ==================== 价签版式（参考图：左五行 + 右码图 + 外框） ====================

    /** 一行规格：行高由内容定；z=让位优先级（大者先让）；[xmul]=值横向拉伸（加粗行）；[protected]=永不让位。 */
    private class RowSpec(
        val label: String,
        val value: String,
        val maxLinesBudget: Int,
        var preferred: Int,
        val minSize: Int,
        val z: Int,
        var xmul: Int = 1,
        val protected: Boolean = false,
    ) {
        var size = 1
        var lateral = 1
        var valueLines: List<String> = emptyList()
        var height = LINE_H

        /** 定稿标签（渲染复用，避免二次计算漂移）。 */
        var labFinal: String = ""

        /** 窄纸兜底：价格独占整行（标签一行 + 值整宽一行），绝不截断数字。 */
        var standalone = false
    }

    /** 行标签的最终渲染文本（含【】）；与占宽计算共用，避免「量宽/打宽」不一致。 */
    internal fun labelText(label: String, value: String, colW: Int, xmul: Int = 1, maxSize: Int = BOLD_XMUL): String =
        "【${labelFor(label, value, colW, xmul, maxSize)}】"

    /**
     * 标签自适应（可读性优先，2026-09-11 实物验证后定稿）：
     * ① 值在 2 行内完整放下 → 取**最长**标签（最紧凑好看）；
     * ② 不行就放宽到 3 行、4 行（仍取最长标签——先保标签可读）；
     * ③ 再放不下 → 取 2 字标签（保底：值至少显示一段，避开实物上【称】【格】这种残标签）。
     * 代价明确：窄纸上标签让位给值，但**永不留单字残标签**。
     */
    internal fun labelFor(label: String, value: String, colW: Int, xmul: Int = 1, maxSize: Int = BOLD_XMUL): String {
        val clean = LabelTextFit.sanitize(value)
        if (clean.isEmpty()) return label
        val cand = mutableListOf(label)
        for (i in 1 until label.length) cand += label.substring(i)
        cand += label.takeLast(1)
        for (lineBudget in intArrayOf(2, 3, 4)) {
            for (lab in cand) {
                if (lab.length < 2) continue
                val wv = colW - dispW("【$lab】")
                if (wv <= 0) continue
                for (size in maxSize.coerceAtLeast(1) downTo 1) {
                    if (LabelTextFit.fitsFully(clean, wv, lineBudget, size * xmul)) return lab
                }
            }
        }
        for (lab in cand) {
            if (lab.length <= 2) return lab
        }
        return cand.last()
    }

    /**
     * 该行放得下的行高（与渲染同口径）：值宽 = 列宽 − 标签渲染宽（含【】）。
     * 价格：字号=整版 scale（宽度按最短标签估），放不下则独占整行；其余：换行→收口→缩号。
     */
    private fun rowHeight(label: String, value: String, colW: Int, maxLines: Int, preferred: Int, minSize: Int, scale: Int, xmul: Int): Int {
        if (label.isEmpty() || value.isEmpty() || colW <= 0) return -1
        val labelW = dispW(labelText(label, value, colW, xmul))
        val wv = colW - labelW
        val lineCap = maxLines.coerceAtLeast(1)
        if (label == "零售价") {
            // 价格：字号=整版 scale；值宽 = 列宽 − 真实标签宽（与渲染同口径）——量高=渲染高
            val wvPrice = (colW - labelW).coerceAtLeast(1)
            val psize = scale.coerceAtLeast(1)
            if (LabelTextFit.fitsFully(value, wvPrice, 1, psize)) return LINE_H * psize * scale + 2
            return 2 * LINE_H * scale + 2
        }
        if (wv > 0) {
            for (size in preferred.coerceAtLeast(minSize) downTo minSize) {
                val g = size * xmul
                if (LabelTextFit.fitsFully(value, wv, lineCap, g)) {
                    return LabelTextFit.fitLines(value, wv, lineCap, g).size.coerceAtLeast(1) * size * LINE_H * scale + 2
                }
            }
        }
        return LabelTextFit.fitLines(value, wv.coerceAtLeast(1), lineCap, minSize * xmul).size.coerceAtLeast(1) *
            minSize * LINE_H * scale + 2
    }

    /**
     * 价签版式计划：按纸宽×高产出绝对坐标几何指令，全部元素保证在纸边界内。
     * 参考管家婆价签：外框 + 左侧【标签】值五行（名称/零售价/规格/编号/单位）+ 右侧码图。
     */
    internal fun planPriceTagOps(
        item: PriceTagItem,
        template: PriceTagTemplate,
        widthMm: Int,
        heightMm: Int,
    ): List<Op> {
        val w = widthMm * DOTS_PER_MM
        val h = heightMm * DOTS_PER_MM
        val margin = if (widthMm <= 32) 8 else 10
        // 2026-10-22 老板令：小横版文字再向内缩进约 1.2mm（左右上下都留白），
        // 打印机走纸/横向偏移再吃掉 1~2mm 也不会切到字。
        val landscape0 = w >= h
        val compact0 = landscape0 && widthMm >= 40 && heightMm <= 20
        val inset0 = if (compact0) 10 else 0
        val innerX = BORDER_INSET + margin + inset0
        val innerW = w - 2 * innerX
        val innerY = BORDER_INSET + margin + inset0
        val innerH = h - 2 * innerY
        val code = if (template.codeStyle == "qr") {
            qrContentOf(item.sku, item.barcode)
        } else {
            item.barcode.ifBlank { item.sku.replace(".", "") }
        }

        val landscape = w >= h
        /**
         * 小横版（40×20 一类：宽 ≥40mm、高只有 20mm）。
         * 纵向只有 160dot，装不下 40×30 那套「码格 5 + 底部抬升 24」：
         * 5 格码 = 105dot 占掉 65% 纸高，加上上下净空后文字只能挤在 20mm 窄列里折三行。
         * 这里按紧凑口径重算（码格 4 / 净空按窄纸收 / 不抬版 / 名称限 2 行），
         * 换出 41dot 的宽度与 24dot 的高度给名称和价格。其他纸规零变化。
         */
        val compact = landscape && widthMm >= 40 && heightMm <= 20
        val hasHead = template.storeName.isNotBlank()
        val hasFoot = template.footer.isNotBlank()
        /**
         * 小横版（20mm 高）只够「编号 / 名称 / 规格 / 价格 / 码」这几样刚需：
         * 店名与页脚各占 24dot，一起开就把规格整行挤掉（老板 2026-10-20「规格型号呢」）。
         * 取舍：**店名、页脚在小横版上让位**，空间全给商品信息（价格 > 名称 > 规格 > 编号）。
         * 其他纸规照旧。
         */
        val showHead = hasHead && !compact
        val showFoot = hasFoot && !compact
        /**
         * 小横版的底部基准线：纸内边距（不走 qpad 的 19dot 净空——qpad 是右侧净空，
         * 拿它当纵向基准会把行预算算少 5dot，规格正好卡在预算外）。码图底沿与价格基线共用。
         */
        // 2026-10-22 老板实拍：40×20 上整版仍偏下 1.0mm（价格/码底被标签缝切）。
        // 底部安全边距由 14dot(1.75mm) 加到 22dot(2.75mm)，整版底沿抬 1mm；
        // 行间 +2dot 的呼吸缝在小横版上收紧为 0（行高仍 24dot），腾出的高度正好给这条边距。
        val compactBottom = if (compact) 10 else 0 // 10-22：底部留 3mm（给大字号名称腾一行）
        val compactBase = h - BORDER_INSET - margin - compactBottom - (if (showFoot) LINE_H else 0)

        // ── 行集：order=渲染顺序，z=让位优先级（大者先让），maxLines=行数预算 ──
        class Row(
            val order: Int, val key: String, val value: String, val z: Int,
            val maxLines: Int, val prot: Boolean, val xmul: Int, val font: Int = LINE_H,
        )
        val rows = mutableListOf<Row>()
        fun addRow(order: Int, key: String, value: String, z: Int, maxLines: Int,
                   isProtected: Boolean = false, xmul: Int = 1, font: Int = LINE_H) {
            val v = LabelTextFit.sanitize(value)
            if (v.isEmpty()) return
            rows += Row(order, key, v, z, maxLines, isProtected, xmul, font)
        }

        // 小横版上「编号 vs 规格」打架时**规格优先**：规格型号是认件的唯一依据，
        // 编号内容本来就在二维码里（扫一下就有）。所以小横版把编号的让位优先级提到最高（z=4）。
        // 阅读顺序也随纸型调整：品名在最上（吃整幅宽度，不再被窄列挤成两行）→ 规格 → 编号 → 价格。
        // 2026-10-22 老板令「整体字体再缩小一个字号」：小横版正文（品名/规格/编号）改用 TSS16.BF2，
        // 比 TSS24 小一号（2mm 字），同样宽度能多放 1/3 的字、纵向每行省 8dot——正好解决规格被省略号截断。
        // 2026-10-22 二轮实拍：正文再小一号（16dot）看起来太小 → 正文回到 24dot（3mm），
        // 改由**价格降一档**腾空间（价格 2×16=32dot，4mm，仍比正文大一档）。
        val bodyFont = LINE_H
        val priceFont = if (compact) 16 else LINE_H
        val skuZ = if (compact) 4 else 2
        val skuOrder = if (compact) 3 else 1
        val nameOrder = if (compact) 1 else 2
        val specOrder = if (compact) 2 else 3
        addRow(skuOrder, "sku", if (template.showSku) item.sku else "", skuZ, 1, font = bodyFont)  // 编号：常规纸规最后让位，小横版最先让位
        // 名称专项：正常名原样打印、零加工；只有「2 行预算都装不下」的超长名才摘取型号/规格
        val (nameShown, nameSpec) = if (dispW(item.name) > innerW * 2) {
            shortName(item.name, innerW * 2)
        } else {
            LabelTextFit.sanitize(item.name) to ""
        }
        val specShown = item.spec.trim().ifEmpty { nameSpec }
        // 小横版名称行数：20mm 纸最多两行（店名/页脚已让位），常规纸规 3 行
        val nameLines = if (compact) 2 else 3
        // 2026-10-22：小横版**名称能用大字就用大字**（4mm / 32dot，撑满二维码上方整幅宽度）；
        // 只有 32dot 一行放不下时才退回 24dot（3mm、最多两行）——老板「名称可以大字号」。
        val nameFont = if (compact && dispW(nameShown) * 32 <= innerW * LINE_H) 32 else bodyFont
        addRow(nameOrder, "name", nameShown, 0, if (nameFont == 32) 1 else nameLines, true, font = nameFont)
        // 小横版规格：只给 1 行（2 行会把自己撑出预算），且**不带「规格:」前缀**——
        // 20mm 纸上这 60dot 能多放 2 个字，型号本身一看就知道是规格
        val specText = if (template.showSpec && specShown.isNotEmpty()) {
            if (compact) specShown else "规格:$specShown"
        } else {
            ""
        }
        addRow(specOrder, "spec", specText, 3, if (compact) 1 else 2, font = bodyFont)
        addRow(4, "price", priceText(item.price), 1, 1, true, font = priceFont)
        rows.sortBy { it.order }
        val unitText = LabelTextFit.sanitize(item.unit)

        val reserved = (if (showHead) LINE_H else 0) + (if (showFoot) LINE_H else 0)
        // 横版整体上提（2026-09-12 老板 40×30 实拍两轮「偏下」）：QR 与价格锚点同步抬升，
        // 版式相对关系不变；竖版（30×40）不动。安全上限=24dot：再大 qrTop<名称行底(72)，
        // 名称（7 汉字 168dot > 窄列 162）会被挤折行。机器走纸起点若仍偏晚（整图打低 2~3mm），
        // 靠纸校准（长按走纸键）+ 打印偏移 Y（图内余量 1.5mm）收尾——图内上方就这么多。
        // 小横版不抬：20mm 纸抬 24dot（3mm）会把码顶到纸中、下方留一条空带。
        val bottomLift = if (landscape && !compact) 24 else 0

        // ── 码图占位：码格**按纸型钉死**（不随商品浮动，否则码尺寸/价格位置一张一个样）；
        //    静默边与偏移余量按纸宽收紧，小纸上省下的宽度全给价格 ──
        // ★ 折中（老板：「能不能折中」）：窄纸（<40mm）把**价格压到纸底、横贯全幅**，
        // 二维码上移让出这一行——价格吃大字，二维码保住 4 模块静默边 + 完整偏移余量
        val priceFullWidth = widthMm < 40
        val maxPriceScale = if (widthMm < 30) 1 else if (widthMm >= 60) 3 else 2
        val qrMod = qrModules(code)
        var qrDots = 0
        var qrCell = 0
        var barH = 0
        var qpad = 0
        var colW: Int
        var rowAreaH: Int
        if (template.codeStyle != "qr") {
            // 小横版：条码压到 28dot（3.5mm）——20mm 高的签要留出「品名 + 规格 + 价格」，
            // 条码再高就只能砍规格（老板 2026-10-20 明确要规格）
            barH = maxOf(20, minOf(if (compact) 28 else 50, innerH / 4))
            colW = innerW
            rowAreaH = innerH - reserved - barH
        } else if (code.isEmpty()) {
            colW = innerW
            rowAreaH = innerH - reserved
        } else {
            // 码格按纸型钉死：小横版 4（0.5mm/模块，优于 30mm 纸的 0.375），40mm 起 5，窄纸 3
            // 2026-10-22 二轮：正文回到 24dot 后窄列放不下规格 → 小横版码格 4→3，
            // 让出 21dot 给文字列（码 7.9mm 仍是 0.375mm/模块，与 30mm 纸同口径，现场可扫）。
            qrCell = if (compact) 3 else if (widthMm >= 40) 5 else 3
            qrDots = qrMod * qrCell
            // 净空：小横版/窄纸按 2 模块静默边 + 8dot 偏移余量（4 模块+16dot 会把码挤成小方块，
            // 20mm 纸上尤其明显）；常规纸规维持 4 模块 + 完整偏移余量
            val quietMods = if (compact || widthMm < 40) 2 else 4
            val outSlack = if (compact || widthMm < 40) 8 else OFFSET_OUT_MAX
            qpad = maxOf(BORDER_INSET + margin + (if (compact) 10 else 0), quietMods * qrCell + DEAD_ZONE_X + outSlack)
            if (landscape) {
                colW = maxOf(w - qpad - qrDots - innerX, 40)
                rowAreaH = if (priceFullWidth) {
                    // 价格压底独占一行：预算 = 上方行 + 价格
                    val upper = h - innerY - maxPriceScale * LINE_H - 6 - innerY - reserved
                    maxOf(upper + maxPriceScale * LINE_H, LINE_H)
                } else {
                    // 小横版的可用高度按**纸内边距**算，不用 qpad：qpad 是右侧净空（19dot），
                    // 比内边距多 5dot——40×20 上正好把「规格」这一行卡在预算外（128 > 127，
                    // 差 1dot 就整行让位）。码/价都用 compactBase 作底基准，行预算必须同源。
                    val rowBase = if (compact) h - BORDER_INSET - margin else h - qpad
                    maxOf(rowBase - innerY - reserved, LINE_H)
                }
            } else {
                colW = innerW
                rowAreaH = innerH - reserved - qrDots - 6
            }
        }

        fun rowH(r: Row, sc: Int): Int {
            // 小横版的品名排第一行、恒在「码上方净空区」→ 它的折行宽度是整幅（与渲染同源）。
            // 若仍按窄列估，品名会被多算一行、白掉一个编号行（老板 2026-10-20「规格型号呢」同源问题）。
            val w = if (compact && r.key == "name") innerW else colW
            val pad = if (compact) 0 else 2
            // 字号越小字越窄：预算按 24/font 放大后再交给 24dot 口径的 fitLines
            val budget = w * LINE_H / r.font
            return maxOf(LabelTextFit.fitLines(r.value, budget, r.maxLines, sc * r.xmul).size, 1) * r.font * sc + pad
        }

        // ── 阶段一：1× 装不下的非保护行整行让位（宁缺勿残），再按 z 让到高度够 ──
        //    文本行（名称/规格）恒保留走折行+省略号；单行编码行（编号）放不下才让位
        val kept = rows.filter {
            it.prot || it.maxLines > 1 || LabelTextFit.fitsFully(it.value, innerW, it.maxLines, 1)
        }.toMutableList()
        while (kept.isNotEmpty() &&
            kept.sumOf { rowH(it, if (it.key == "price") maxPriceScale else 1) } > rowAreaH
        ) {
            val drop = kept.sortedByDescending { it.z }.firstOrNull { !it.prot } ?: break
            kept.remove(drop)
        }

        // 二维码上沿：**它以上的行可用整幅宽度**（那段净空区是留给长名称/规格的）
        var qrTop = if (template.codeStyle == "qr" && qrCell > 0 && landscape && code.isNotEmpty()) {
            if (compact) {
                compactBase - qrDots
            } else {
                h - qpad - qrDots - (if (hasFoot) LINE_H else 0) - bottomLift
            }
        } else {
            h
        }
        qrTop = maxOf(qrTop, innerY)

        val nameRow = kept.firstOrNull { it.key == "name" }
        val priceRow = kept.firstOrNull { it.key == "price" }
        val unitW = dispW(unitText)

        // ── 阶段三：价格字号**按纸型钉死**（30mm 起 2×、60mm 起 3×），放不下才降档 ──
        val priceAvail = if (priceFullWidth) innerW else colW
        val priceW = if (priceRow != null) dispW(priceRow.value) else 0
        var priceScale = maxPriceScale
        if (priceRow != null) {
            // 单位先让位（2026-09-12 老板 40×30 实拍「价格空间够用怎么这么小」）：降档判据
            // 不含单位——「225.00」2×+单位超窄列就整档掉到 1×，价格小到没存在感；
            // 单位放不下就不画（渲染处 ux+unitW 守卫兜住）。长价真放不下才降档。
            while (priceScale > 1 && priceW * priceScale + 4 > priceAvail) priceScale -= 1
        }

        // ── 阶段四（2026-09-12 老板拍板废除自动放大）：名称恒定 1×，醒目只靠真粗体（位图路径）。
        // 「稳可切割片」实拍教训：短名触发 2× 后（240dot）越过 qrTop 线被窄列折成两行巨块，
        // 还把价格挤出标签——名称放大与横版二维码布局天然冲突，砍掉。
        val nameScale = 1

        fun rowScale(r: Row): Int =
            if (r === priceRow) priceScale else if (r === nameRow) nameScale else 1

        val totalH = kept.sumOf { rowH(it, rowScale(it)) }
        // 行距上限 6dot：余量留给「二维码上方净空区」，让更多行能吃整幅宽度
        val gap = if (compact) 0
        else if (kept.isNotEmpty()) minOf(maxOf(rowAreaH - totalH, 0) / (kept.size + 1), 6) else 0

        // ── 行渲染（无字段名、无外框：白底裸版式）──
        val ops = mutableListOf<Op>()
        var rowTop = innerY
        if (showHead) {
            val head = fitLines(template.storeName, innerW, 1).firstOrNull()
            if (!head.isNullOrEmpty()) ops += Op.Txt(innerX + (innerW - dispW(head)) / 2, rowTop, 1, head)
            rowTop += LINE_H
        }

        // ★ 价格行**底对齐**：钉在二维码下沿那条水平线上，一排价签价格落在同一条线
        // 条码样式没有二维码，基准线改取**条码上沿**——原先按 qpad(=0) 算，价格会压在条码上
        val priceH = if (priceRow != null) priceScale * (priceRow?.font ?: LINE_H) else 0
        val priceBase = when {
            // 条码样式：价格压在条码上沿，留 2dot 呼吸缝（单位小字恰好落在缝里）
            template.codeStyle != "qr" -> h - BORDER_INSET - margin - barH - 2
            compact -> compactBase
            priceFullWidth -> h - innerY
            else -> h - qpad
        }
        val priceY = if (priceRow != null) maxOf(priceBase - priceH - bottomLift, innerY) else 0

        for (r in kept) {
            if (r === priceRow) continue  // 价格单独在下面画（底对齐）
            val sc = rowScale(r)
            val f = r.font
            val nFull = maxOf(LabelTextFit.fitLines(r.value, innerW * LINE_H / f, r.maxLines, sc).size, 1)
            val avail = if (rowTop + nFull * f * sc + 2 <= qrTop) innerW else colW
            val lines = LabelTextFit.fitLines(r.value, avail * LINE_H / f, r.maxLines, sc * r.xmul)
                .ifEmpty { listOf(r.value) }
            var y = rowTop
            for (line in lines) {
                ops += Op.Txt(innerX, y, sc, line, sc * r.xmul, bold = r === nameRow, font = f)
                y += f * sc
            }
            rowTop += maxOf(lines.size, 1) * f * sc + (if (compact) 0 else 2) + gap
        }

        if (priceRow != null) {
            val sc = priceScale
            val pf = priceRow.font
            val lines = LabelTextFit.fitLines(priceRow.value, priceAvail * LINE_H / pf, 1, sc)
                .ifEmpty { listOf(priceRow.value) }
            val y = maxOf(priceY, rowTop)  // 兜底：空间不够时不与上方压字
            for (line in lines) ops += Op.Txt(innerX, y, sc, line, sc, font = pf)
            if (unitText.isNotEmpty()) {
                // 单位跟价格同排：1× 小字、底对齐，紧贴价格右侧
                val ux = innerX + dispW(lines[0]) * sc + 4
                val uy = y + (sc - 1) * pf
                val uw = dispW(unitText, pf)
                if (ux + uw <= innerX + priceAvail) ops += Op.Txt(ux, uy, 1, unitText, 1, font = pf)
            }
        }

        // ── 码图放置：横版右下角 / 竖版底部居中；条码风格底置 ──
        if (template.codeStyle == "qr") {
            if (code.isNotEmpty() && qrCell > 0) {
                val qrX = if (landscape) w - qpad - qrDots else innerX + (innerW - qrDots) / 2
                var qrY = if (compact) {
                    compactBase - qrDots
                } else {
                    (if (priceFullWidth) h - qpad - qrDots - priceH - 6
                     else h - qpad - qrDots) - (if (hasFoot) LINE_H else 0) - bottomLift
                }
                // 上移后**上方也要留足静默边**，否则码的包围盒会顶到纸边
                qrY = maxOf(qrY, innerY + 4 * qrCell)
                ops += Op.Qr(qrX, qrY, qrCell, code)
            }
        } else if (code.isNotEmpty()) {
            val yBar = h - BORDER_INSET - margin - barH
            ops += Op.Bar(innerX, yBar, barH, barNarrow(code, innerW), code.take(20))
        }

        // ── 页脚（可选，一行，放得下才画；小横版整行让位给商品信息，见 showFoot）──
        if (showFoot) {
            val foot = fitLines(template.footer, innerW, 1).firstOrNull()
            if (!foot.isNullOrEmpty()) {
                ops += Op.Txt(innerX + (innerW - dispW(foot)) / 2, h - BORDER_INSET - margin - LINE_H, 1, foot)
            }
        }

        return applyOffset(ops, template.offsetXMm, template.offsetYMm, w, h)
    }

    /**
     * 打印偏移容错：把整版元素整体平移 (dxMm, dyMm)，并把平移量**夹紧**到安全区。
     *
     * 打印机机械偏差（打印头起始位/走纸/纸在胶辊上的横向位置）表现为整版偏移，
     * 统一平移即可对齐；夹紧保证「无论偏移量多大，外框与文字都不越出纸面」。
     * 无偏移（0,0）时返回原列表，几何零变化（老任务、老配置完全兼容）。
     */
    internal fun applyOffset(ops: List<Op>, dxMm: Double, dyMm: Double, widthDots: Int, heightDots: Int): List<Op> {
        if (dxMm == 0.0 && dyMm == 0.0) return ops
        // 2026-10-22 老板实拍：打印机走纸起点比标签低 2~3mm，内容必须整体上移才能对齐；
        // 原先「按纸边死区夹紧」会把上移量吃掉（最多只能挪 1.5mm），现场调不动。
        // 现在**只按 OFFSET_MAX_MM 限幅，不再按纸边夹紧**：允许整版越过逻辑纸边（负坐标），
        // 由打印机在物理标签上裁掉——这正是补偿机械偏移需要的效果。
        val rawDx = Math.round(dxMm.coerceIn(-OFFSET_MAX_MM, OFFSET_MAX_MM) * DOTS_PER_MM).toInt()
        val rawDy = Math.round(dyMm.coerceIn(-OFFSET_MAX_MM, OFFSET_MAX_MM) * DOTS_PER_MM).toInt()
        if (rawDx == 0 && rawDy == 0) return ops
        return ops.map { shift(it, rawDx, rawDy) }
    }

    /** 元素包围盒 (left, right, top, bottom)（dot）。 */
    private fun boxOf(op: Op): List<Int> = when (op) {
        is Op.Txt -> listOf(op.x, op.x + dispW(op.text, op.font) * op.xmul, op.y, op.y + op.font * maxOf(op.mul, op.xmul))
        is Op.Ln -> listOf(minOf(op.x1, op.x2), maxOf(op.x1, op.x2), minOf(op.y1, op.y2), maxOf(op.y1, op.y2))
        // 边长按真实模块数（不再硬估 30）；外扩 2 模块静默边（与布局口径一致）
        is Op.Qr -> {
            val side = qrModules(op.data) * op.cell
            val quiet = 2 * op.cell
            listOf(op.x - quiet, op.x + side + quiet, op.y - quiet, op.y + side + quiet)
        }
        is Op.Bar -> listOf(op.x, op.x + 200, op.y, op.y + op.h)
    }

    private fun shift(op: Op, dx: Int, dy: Int): Op = when (op) {
        is Op.Txt -> Op.Txt(op.x + dx, op.y + dy, op.mul, op.text, op.xmul, op.font, op.bold)
        is Op.Ln -> Op.Ln(op.x1 + dx, op.y1 + dy, op.x2 + dx, op.y2 + dy)
        is Op.Qr -> Op.Qr(op.x + dx, op.y + dy, op.cell, op.data)
        is Op.Bar -> Op.Bar(op.x + dx, op.y + dy, op.h, op.narrow, op.data)
    }

    /**
     * 贪心装行：在 [heightBudget] 内放得下就返回保留下来的行（行高按 [scale] 定稿），
     * 装不下按 z（大到小）让位；名称/价格 protected 永不让位，保命行也放不下返回 null。
     */
    private fun fitRows(
        rows: List<RowSpec>,
        colW: Int,
        heightBudget: Int,
        scale: Int,
        labelOf: (RowSpec, Int, Int) -> String,
    ): MutableList<RowSpec>? {
        if (heightBudget < LINE_H) return null
        val kept = rows.toMutableList()
        fun measure(): Int {
            var sum = 0
            for (r in kept) {
                val hh = rowHeight(r.label, r.value, colW, r.maxLinesBudget, r.preferred, r.minSize, scale, r.xmul)
                if (hh < 0) return Int.MAX_VALUE
                r.height = hh
                sum += hh
            }
            return sum
        }
        var sum = measure()
        while (sum > heightBudget) {
            val dropAt = kept.indexOfLast { !it.protected }
            if (dropAt < 0) return null
            kept.removeAt(dropAt)
            sum = measure()
        }
        return kept
    }

    // ==================== 渲染器（同一 plan → 两种指令语言） ====================

    private fun renderTspl(
        ops: List<Op>,
        widthMm: Int,
        heightMm: Int,
        copies: Int,
        density: Int,
    ): String = buildString {
        append("SIZE $widthMm mm,$heightMm mm\nGAP 2 mm,0 mm\nDENSITY $density\nCLS\n")
        for (op in ops) {
            when (op) {
                // 第 5/6 个参数=横/纵倍率：横向拉伸即加粗（名称/价格 xmul>mul）
                is Op.Txt -> append(
                    "TEXT ${op.x},${op.y},\"TSS${op.font}.BF2\",0,${op.xmul},${op.mul},\"${tsplEscape(op.text)}\"\n",
                )
                is Op.Ln -> append("LINE ${op.x1},${op.y1},${op.x2},${op.y2}\n")
                is Op.Qr -> append("QRCODE ${op.x},${op.y},M,${op.cell},A,0,\"${tsplEscape(op.data)}\"\n")
                is Op.Bar -> append(
                    "BARCODE ${op.x},${op.y},\"128\",${op.h},1,0,${op.narrow},${op.narrow},\"${tsplEscape(op.data)}\"\n",
                )
            }
        }
        append("PRINT $copies\n")
    }

    private fun renderCpcl(ops: List<Op>, widthMm: Int, heightMm: Int, copies: Int): String = buildString {
        append("! 0 200 200 ${heightMm * DOTS_PER_MM} $copies\n")
        append("PAGE-WIDTH ${widthMm * DOTS_PER_MM}\n")
        var lastMul = -1
        var lastXmul = -1
        for (op in ops) {
            when (op) {
                is Op.Txt -> {
                    if (op.mul != lastMul || op.xmul != lastXmul) {
                        append("SETMAG ${op.xmul} ${op.mul}\n")
                        lastMul = op.mul
                        lastXmul = op.xmul
                    }
                    append("TEXT $CPCL_CJK_FONT 0 ${op.x} ${op.y} ${cpclLine(op.text)}\n")
                }
                is Op.Ln -> append("LINE ${op.x1} ${op.y1} ${op.x2} ${op.y2} 2\n")
                is Op.Qr -> append("B QR ${op.x} ${op.y} M 2 U ${op.cell}\nMA,${cpclLine(op.data)}\nENDQR\n")
                is Op.Bar -> append("BARCODE 128 1 1 ${op.h} ${op.x} ${op.y}\n${cpclLine(op.data)}\n")
            }
        }
        if (lastMul != 1 || lastXmul != 1) append("SETMAG 1 1\n")
        append("FORM\nPRINT\n")
    }

    // ==================== 价签 ====================

    /** 价签 TSPL：paperHeight<=0 时按纸宽取默认高度（兼容老配置）。 */
    fun priceTag(
        items: List<PriceTagItem>,
        copies: Int = 1,
        template: PriceTagTemplate = PriceTagTemplate(),
        paperWidth: Int = 30,
        density: Int = 8,
        paperHeight: Int = 0,
    ): String {
        val hMm = if (paperHeight > 0) paperHeight else heightFor(paperWidth)
        return items.joinToString("") {
            renderTspl(planPriceTagOps(it, template, paperWidth, hMm), paperWidth, hMm, copies, density)
        }
    }

    /** 价签 CPCL（与 priceTag 同一 plan，几何零漂移）。 */
    fun priceTagCpcl(
        items: List<PriceTagItem>,
        copies: Int = 1,
        template: PriceTagTemplate = PriceTagTemplate(),
        paperWidth: Int = 30,
        paperHeight: Int = 0,
    ): String {
        val hMm = if (paperHeight > 0) paperHeight else heightFor(paperWidth)
        return items.joinToString("") {
            renderCpcl(planPriceTagOps(it, template, paperWidth, hMm), paperWidth, hMm, copies)
        }
    }

    /** 指令集分发：按打印机配置的 commandSet 出对应语言的价签指令。 */
    fun priceTagFor(
        commandSet: String,
        items: List<PriceTagItem>,
        copies: Int = 1,
        template: PriceTagTemplate = PriceTagTemplate(),
        paperWidth: Int = 30,
        density: Int = 8,
        paperHeight: Int = 0,
    ): String =
        if (commandSet.equals(CMD_CPCL, true)) {
            priceTagCpcl(items, copies, template, paperWidth, paperHeight)
        } else {
            priceTag(items, copies, template, paperWidth, density, paperHeight)
        }

    /**
     * CPCL 中文**字体号对照条**（测试打印附带，真机排障专用，2026-09-12 BTP-P39 PLUS）：
     * 4（西文对照）/ [CPCL_CJK_FONT]（默认中文字库）/ 55（部分固件的中文字库号）各打一行
     * 同句「中文+ASCII」，行首 ASCII 编号永远可读——打印一次即可看出这台机器的
     * 中文字体号，定谳后只改 [CPCL_CJK_FONT]。首行文字从 x=8（1mm）起打，兼作左切边参照。
     */
    fun cpclFontProbeTag(paperWidth: Int = 40, paperHeight: Int = 30, copies: Int = 1): String {
        val w = paperWidth * DOTS_PER_MM
        val h = paperHeight * DOTS_PER_MM
        return buildString {
            append("! 0 200 200 $h $copies\n")
            append("PAGE-WIDTH $w\n")
            append("SETMAG 1 1\n")
            var y = 6
            for (font in intArrayOf(4, CPCL_CJK_FONT, 55)) {
                append("TEXT $font 0 8 $y $font:示例零售 ABC123\n")
                y += LINE_H + 4
            }
            append("TEXT 4 0 8 $y PROBE ${paperWidth}x$paperHeight\n")
            append("FORM\nPRINT\n")
        }
    }

    /** 登记打印机时按名字自动选指令集：新北洋 BTP-P* 便携面单机=CPCL，其余默认 TSPL。 */
    fun defaultCommandSetFor(printerName: String): String =
        if (Regex("(?i)\\bbtp[-_ ]?p\\d").containsMatchIn(printerName)) CMD_CPCL else CMD_TSPL

    // ==================== 货架 / 货位 / 条码标签（顺带适配纸高） ====================

    /** 货架标签（横排）：分区小字 / 排名大字居中（fitScale 按纸宽放大）/ 层位。 */
    fun shelfLabel(
        zoneName: String,
        rackName: String,
        floorTag: String,
        copies: Int = 1,
        paperWidth: Int = 40,
        density: Int = 8,
        paperHeight: Int = 0,
    ): String {
        val hMm = if (paperHeight > 0) paperHeight else heightFor(paperWidth)
        val w = paperWidth * DOTS_PER_MM
        val h = hMm * DOTS_PER_MM
        val m = 10
        val cw = w - 2 * m
        val mul = fitScale(rackName, cw, 3, 2)
        val rackY = 28
        val ops = listOf(
            Op.Txt(m, 6, 1, fitLines(zoneName, cw, 1).firstOrNull() ?: ""),
            Op.Txt(m + (cw - dispW(rackName) * mul) / 2, rackY, mul, rackName),
            Op.Txt(m, (rackY + LINE_H * mul + 6).coerceAtMost(h - 30), 1, fitLines(floorTag, cw, 1).firstOrNull() ?: ""),
        )
        return renderTspl(ops, paperWidth, hMm, copies, density)
    }

    fun shelfLabelCpcl(
        zoneName: String,
        rackName: String,
        floorTag: String,
        copies: Int = 1,
        paperWidth: Int = 40,
        paperHeight: Int = 0,
    ): String {
        val hMm = if (paperHeight > 0) paperHeight else heightFor(paperWidth)
        val w = paperWidth * DOTS_PER_MM
        val h = hMm * DOTS_PER_MM
        val cw = w - 20
        val mul = fitScale(rackName, cw, 3, 2)
        val ops = listOf(
            Op.Txt(10, 6, 1, fitLines(zoneName, cw, 1).firstOrNull() ?: ""),
            Op.Txt(10 + (cw - dispW(rackName) * mul) / 2, 28, mul, rackName),
            Op.Txt(10, (28 + LINE_H * mul + 6).coerceAtMost(h - 30), 1, fitLines(floorTag, cw, 1).firstOrNull() ?: ""),
        )
        return renderCpcl(ops, paperWidth, hMm, copies)
    }

    fun shelfLabelFor(
        commandSet: String,
        zoneName: String,
        rackName: String,
        floorTag: String,
        copies: Int = 1,
        paperWidth: Int = 40,
        density: Int = 8,
        paperHeight: Int = 0,
    ): String =
        if (commandSet.equals(CMD_CPCL, true)) {
            shelfLabelCpcl(zoneName, rackName, floorTag, copies, paperWidth, paperHeight)
        } else {
            shelfLabel(zoneName, rackName, floorTag, copies, paperWidth, density, paperHeight)
        }

    /** 条码标签 25×15mm：条码图 + 品名（固定小纸规，不参与纸规自适应；品名走防溢出机制）。 */
    fun barcodeLabel(barcode: String, name: String, copies: Int = 1): String {
        val cw = 25 * DOTS_PER_MM - 16
        val lines = LabelTextFit.fitLines(name, cw, 2)
        return buildString {
            append("SIZE 25 mm,15 mm\nGAP 1 mm,0 mm\nCLS\n")
            append("BARCODE 8,5,\"128\",0,1,0,30,8,\"${tsplEscape(LabelTextFit.sanitize(barcode))}\"\n")
            var y = 40
            for (l in lines) {
                append("TEXT 8,$y,\"TSS24.BF2\",0,1,1,\"${tsplEscape(l)}\"\n")
                y += LINE_H
            }
            append("PRINT $copies\n")
        }
    }

    fun barcodeLabelCpcl(barcode: String, name: String, copies: Int = 1): String {
        val cw = 25 * DOTS_PER_MM - 16
        val lines = LabelTextFit.fitLines(name, cw, 2)
        return buildString {
            append("! 0 200 200 120 $copies\n")
            append("PAGE-WIDTH 200\n")
            append("BARCODE 128 1 1 30 8 5\n${cpclLine(LabelTextFit.sanitize(barcode))}\n")
            var y = 40
            for (l in lines) {
                append("TEXT $CPCL_CJK_FONT 0 8 $y ${cpclLine(l)}\n")
                y += LINE_H
            }
            append("FORM\nPRINT\n")
        }
    }

    fun barcodeLabelFor(commandSet: String, barcode: String, name: String, copies: Int = 1): String =
        if (commandSet.equals(CMD_CPCL, true)) {
            barcodeLabelCpcl(barcode, name, copies)
        } else {
            barcodeLabel(barcode, name, copies)
        }

    /** 货位标签：货位号大字居中（fitScale）+ 条码图底置。 */
    fun cellLabel(
        cellCode: String,
        copies: Int = 1,
        paperWidth: Int = 40,
        density: Int = 8,
        paperHeight: Int = 0,
    ): String {
        val hMm = when {
            paperHeight > 0 -> paperHeight
            paperWidth >= 60 -> 40
            else -> 20
        }
        val w = paperWidth * DOTS_PER_MM
        val h = hMm * DOTS_PER_MM
        val cw = w - 16
        val mul = fitScale(cellCode, cw, 3, 2)
        val barY = (8 + LINE_H * mul + 4).coerceAtMost(h - 40)
        val bh = (h - 6 - barY).coerceIn(20, 40)
        val ops = listOf(
            Op.Txt(8 + (cw - dispW(cellCode) * mul) / 2, 8, mul, cellCode),
            Op.Bar(8, barY, bh, barNarrow(cellCode, cw), cellCode.take(20)),
        )
        return renderTspl(ops, paperWidth, hMm, copies, density)
    }

    fun cellLabelCpcl(cellCode: String, copies: Int = 1, paperWidth: Int = 40, paperHeight: Int = 0): String {
        val hMm = when {
            paperHeight > 0 -> paperHeight
            paperWidth >= 60 -> 40
            else -> 20
        }
        val w = paperWidth * DOTS_PER_MM
        val h = hMm * DOTS_PER_MM
        val cw = w - 16
        val mul = fitScale(cellCode, cw, 3, 2)
        val barY = (8 + LINE_H * mul + 4).coerceAtMost(h - 40)
        val bh = (h - 6 - barY).coerceIn(20, 40)
        val ops = listOf(
            Op.Txt(8 + (cw - dispW(cellCode) * mul) / 2, 8, mul, cellCode),
            Op.Bar(8, barY, bh, barNarrow(cellCode, cw), cellCode.take(20)),
        )
        return renderCpcl(ops, paperWidth, hMm, copies)
    }

    fun cellLabelFor(
        commandSet: String,
        cellCode: String,
        copies: Int = 1,
        paperWidth: Int = 40,
        density: Int = 8,
        paperHeight: Int = 0,
    ): String =
        if (commandSet.equals(CMD_CPCL, true)) {
            cellLabelCpcl(cellCode, copies, paperWidth, paperHeight)
        } else {
            cellLabel(cellCode, copies, paperWidth, density, paperHeight)
        }

    /** TSPL 特殊字符转义（双引号、反斜杠）。 */
    private fun tsplEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    // ==================== I20 三级货架位置标签（自定义名字 + 可扫定位码） ====================

    /**
     * 位置标签（货架 / 层 / 格通用）。
     *
     * 版式：**自定义名字大字居中**（人一眼认位置）+ 上级路径小字（可选）+ **稳定定位码条码**
     * （扫描枪可扫，与 NFC 碰贴互为兜底——PDA/WMS 的行业标准做法）。
     *
     * ⚠️ 条码里编码的是 **tagId 派生载荷（`SX:xxxx`），绝不是名字**：
     * 名字随时可改，改一次名若靠名字定位就得全店重贴（预研 §4.4）。
     *
     * @param name 自定义显示名（大字）
     * @param code 可扫定位载荷（`SX:<tagId>`）
     * @param sub  上级路径小字（如「A架 · 1层」），可空
     */
    fun locationLabel(
        name: String,
        code: String,
        sub: String = "",
        copies: Int = 1,
        paperWidth: Int = 40,
        density: Int = 8,
        paperHeight: Int = 0,
    ): String {
        val hMm = when {
            paperHeight > 0 -> paperHeight
            paperWidth >= 60 -> 40
            else -> 20
        }
        val w = paperWidth * DOTS_PER_MM
        val cw = w - 16
        return renderTspl(planLocationOps(name, code, sub, hMm * DOTS_PER_MM, cw), paperWidth, hMm, copies, density)
    }

    fun locationLabelCpcl(
        name: String,
        code: String,
        sub: String = "",
        copies: Int = 1,
        paperWidth: Int = 40,
        paperHeight: Int = 0,
    ): String {
        val hMm = when {
            paperHeight > 0 -> paperHeight
            paperWidth >= 60 -> 40
            else -> 20
        }
        val w = paperWidth * DOTS_PER_MM
        val cw = w - 16
        return renderCpcl(planLocationOps(name, code, sub, hMm * DOTS_PER_MM, cw), paperWidth, hMm, copies)
    }

    fun locationLabelFor(
        commandSet: String,
        name: String,
        code: String,
        sub: String = "",
        copies: Int = 1,
        paperWidth: Int = 40,
        density: Int = 8,
        paperHeight: Int = 0,
    ): String =
        if (commandSet.equals(CMD_CPCL, true)) {
            locationLabelCpcl(name, code, sub, copies, paperWidth, paperHeight)
        } else {
            locationLabel(name, code, sub, copies, paperWidth, density, paperHeight)
        }

    /**
     * 位置标签排版。核心取舍：**条码区优先保底**（扫不出来这个标签就是废纸），
     * 名字区按剩余空间自适应字号，小纸规（20mm 高）下自动省略上级路径行。
     */
    internal fun planLocationOps(
        name: String,
        code: String,
        sub: String,
        heightDots: Int,
        contentWidth: Int,
    ): List<Op> {
        val ops = mutableListOf<Op>()
        val displayName = name.ifBlank { code }
        val nameMul = fitScale(displayName, contentWidth, 3, 1)

        // 条码区先占位：底部留 6 + 条码高（20~40）
        val barH = (heightDots / 5).coerceIn(18, 40)
        val barY = (heightDots - 6 - barH).coerceAtLeast(8)

        var y = 6
        val nameLines = LabelTextFit.fitLines(displayName, contentWidth, if (heightDots >= 240) 2 else 1, nameMul)
        for (l in nameLines) {
            ops += Op.Txt(8 + ((contentWidth - dispW(l) * nameMul).coerceAtLeast(0)) / 2, y, nameMul, l)
            y += LINE_H * nameMul
        }
        y += 2

        // 上级路径：只有在条码上方还剩得下一行时才画
        if (sub.isNotBlank() && y + 20 <= barY) {
            ops += Op.Txt(8, y, 1, LabelTextFit.fitLines(sub, contentWidth, 1).firstOrNull() ?: "")
        }

        if (code.isNotBlank()) {
            ops += Op.Bar(8, barY, barH, barNarrow(code, contentWidth), code.take(20))
        }
        return ops
    }

    /** CPCL 数据行清洗：指令行内不允许换行，连续换行符折叠为单个空格。 */
    private fun cpclLine(s: String): String =
        s.replace(Regex("[\r\n]+"), " ")
}
