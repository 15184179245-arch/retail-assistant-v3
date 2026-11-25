package com.retaildemo.v2

import com.retaildemo.v2.ui.pages.newbill.applyPadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 记一笔页解耦契约（TASK-02，2026-09-22）。
 *
 * 两件事一起锁：
 *  1. **九宫格键值语义**（[applyPadKey] 纯函数）——键盘是全页被点得最多的表面，
 *     小数位封顶、前导零、00 键、+1/+10 这些口径一旦漂了，屏上看不出来，
 *     只有店员按出一单价 12.5→"12.500" 或 0.05→"5" 才知道出事。
 *  2. **巨石拆分的结构不变量**——1,123 行的 NewBillPage 拆成
 *     NewBillHeader / NewBillCartList / NewBillKeypadSection 三个组件文件；
 *     「滚动只一层」是 rule 14② 的硬约束，源码扫描是唯一能在 CI 上拦住它的手段
 *     （编译期和单测都看不见 Compose 的嵌套滚动）。
 */
class NewBillDecouplingTest {

    // ---------------- 1. 九宫格键盘纯函数 ----------------

    @Test
    fun `数字键追加大数位封顶两位`() {
        assertEquals("1", applyPadKey("", "1"))
        assertEquals("12", applyPadKey("1", "2"))
        assertEquals("12.55", applyPadKey("12.5", "5"))
        assertEquals("小数第 3 位必须被丢弃", "12.55", applyPadKey("12.55", "5"))
    }

    @Test
    fun `前导零不累积`() {
        assertEquals("5", applyPadKey("0", "5"))
        assertEquals("5", applyPadKey("0.00", "5"))
        assertEquals("0", applyPadKey("", "0"))
    }

    @Test
    fun `小数点唯一且空串补成零点`() {
        assertEquals("0.", applyPadKey("", "."))
        assertEquals("12.", applyPadKey("12", "."))
        assertEquals("已有小数点则忽略", "12.5", applyPadKey("12.5", "."))
    }

    @Test
    fun `退格清空与整百键`() {
        assertEquals("12.", applyPadKey("12.5", "BKSP"))
        assertEquals("", applyPadKey("12.5", "CLEAR"))
        assertEquals("1200", applyPadKey("12", "00"))
        assertEquals("小数位不足才允许 00", "12.5", applyPadKey("12.5", "00"))
        assertEquals("0 串里按 00 仍是 0", "0", applyPadKey("0", "00"))
    }

    @Test
    fun `加价键按元进位`() {
        assertEquals("1", applyPadKey("", "+1"))
        assertEquals("13.50", applyPadKey("12.5", "+1"))
        assertEquals("22.50", applyPadKey("12.5", "+10"))
        assertEquals("整数结果不带尾巴", "13", applyPadKey("12", "+1"))
        assertEquals("未知键一律不改文本", "12.5", applyPadKey("12.5", "DONE"))
    }

    // ---------------- 2. 结构不变量（源码扫描）----------------

    private val pagesDir: File by lazy {
        val candidates = listOf(
            File("src/main/java/com/retaildemo/v2/ui/pages"),
            File("app/src/main/java/com/retaildemo/v2/ui/pages"),
            File("android-v2/app/src/main/java/com/retaildemo/v2/ui/pages"),
        )
        candidates.firstOrNull { it.resolve("NewBillPage.kt").isFile }
            ?: error("找不到 ui/pages 源码目录，尝试过：$candidates")
    }

    private fun src(path: String): String = pagesDir.resolve(path).let {
        assertTrue("组件文件缺失：$path（解耦被回退了？）", it.isFile)
        it.readText()
    }

    /** 注释里出现 `verticalScroll` 是**说明**不是**代码**（本类的文档就写了"为什么删掉它"），判据只看代码体。 */
    private fun codeOf(path: String): String =
        src(path).replace(Regex("/\\*\\*?.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")

    @Test
    fun `记一笔巨石已拆成三个组件文件`() {
        val header = src("newbill/NewBillHeader.kt")
        val cartList = src("newbill/NewBillCartList.kt")
        val keypad = src("newbill/NewBillKeypadSection.kt")
        assertTrue("页头组件须含客户选择弹窗", "NewBillCustomerPickerDialog" in header)
        assertTrue("货筐组件须为单容器 LazyColumn", "LazyColumn" in cartList)
        assertTrue("键盘组件须含九宫格与结算栏", "NewBillPriceEditKeypad" in keypad && "NewBillSettleBar" in keypad)
        // 巨石页只留编排：不再自己画键盘/行条
        val page = src("NewBillPage.kt")
        assertFalse("NewBillPage 不得再自带改价键盘实现", "fun PriceEditDocked" in page)
        assertFalse("NewBillPage 不得再自带客户弹窗实现", "fun CustomerPickRow" in page)
        assertTrue("NewBillPage 应瘦身为编排层（<450 行）", page.lines().size < 450)
    }

    @Test
    fun `全页滚动容器只一层`() {
        val page = codeOf("NewBillPage.kt")
        val header = codeOf("newbill/NewBillHeader.kt")
        val cartList = codeOf("newbill/NewBillCartList.kt")
        val keypad = codeOf("newbill/NewBillKeypadSection.kt")
        // rule 14②：弹窗/未受限容器内不得二次挂 verticalScroll（SanxAlertDialog 的 text 槽自带滚动）
        assertFalse("记一笔页不得挂 verticalScroll", ".verticalScroll" in page)
        assertFalse("页头族不得挂 verticalScroll（弹窗滚动由 SanxAlertDialog 唯一负责）", ".verticalScroll" in header)
        assertFalse("货筐清单不得二次挂 verticalScroll", ".verticalScroll" in cartList)
        assertFalse("键盘与结算区不得挂 verticalScroll", ".verticalScroll" in keypad)
        assertEquals("货筐清单必须恰好一个滚动容器", 1, "LazyColumn\\(".toRegex().findAll(cartList).count())
    }

    @Test
    fun `扫码交接台三态真源不在Activity里复制一份`() {
        val activity = src("ScannerActivity.kt")
        assertTrue("取景框几何截断必须仍在分析器上生效", "insideViewfinder" in activity)
        assertTrue("回执三态须引用 ScanReceiptSpec 真源", "ScanReceiptSpec" in activity)
        assertTrue("取景框下方须挂流式交接清单", "HandoverLiveList" in activity)
        assertFalse("三态模型不得再退回 Activity 内部类", "private sealed interface Receipt" in activity)
    }
}
