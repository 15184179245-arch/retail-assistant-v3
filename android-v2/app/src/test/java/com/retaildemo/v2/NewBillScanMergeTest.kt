package com.retaildemo.v2

import android.content.Intent
import com.retaildemo.v2.data.local.dao.DraftDao
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.ProductRepo
import com.retaildemo.v2.data.repo.TempProductRepo
import com.retaildemo.v2.ui.pages.ScannerActivity
import com.retaildemo.v2.ui.pages.parseScanResult
import com.retaildemo.v2.ui.viewmodel.NewBillViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * U5 扫码筐并入记一笔：入单逻辑回归（自 ScanBasketViewModelTest 移植）。
 *
 * 核心回归：**重复扫同一件数量必须涨**（copy 出新实例保证 StateFlow 发射，
 * 旧扫码筐 `var qty` 就地自增导致 equals 不变、UI 永远卡在 ×1 的教训）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NewBillScanMergeTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun product(barcode: String, name: String, price: Double): ProductEntity = ProductEntity(
        id = 1L,
        barcode = barcode,
        sku = "SKU-$barcode",
        name = name,
        spec = null,
        unit = "件",
        category = null,
        sellPrice = price,
        costPrice = price * 0.6,
        stockQty = 10.0,
        pinyin = null,
        initials = null,
        updatedAt = null,
    )

    private fun viewModel(productRepo: ProductRepo): NewBillViewModel {
        val billRepo = mockk<BillRepo>(relaxed = true)
        every { billRepo.allBills } returns flowOf(emptyList<BillEntity>())
        return NewBillViewModel(
            billRepo = billRepo,
            productRepo = productRepo,
            draftDao = mockk<DraftDao>(relaxed = true),
            tempRepo = mockk<TempProductRepo>(relaxed = true),
        )
    }

    @Test
    fun `重复扫同一条码 数量不累加`() = runTest {
        val repo = mockk<ProductRepo>()
        coEvery { repo.getByBarcode("6901") } returns product("6901", "不锈钢螺丝", 2.0)
        val vm = viewModel(repo)

        vm.onBarcodeScanned("6901")
        assertEquals(1, vm.cart.value.first().qty)

        vm.onBarcodeScanned("6901")
        vm.onBarcodeScanned("6901")

        // ★ 2026-09-20 老板令：重复扫**只提醒、绝不录入** —— 数量恒为 1。
        //   加数量一律到清单里手动改（扫码太灵敏，自动累加会放大误扫）。
        assertEquals(1, vm.cart.value.size)
        assertEquals(1, vm.cart.value.first().qty)
        assertEquals(1, vm.totalQty)
        assertEquals(2.0, vm.totalAmount, 0.001)
    }

    @Test
    fun `批量入单 重复码不累加 不同码各占一行`() = runTest {
        val repo = mockk<ProductRepo>()
        coEvery { repo.getByBarcode("A") } returns product("A", "螺丝", 1.0)
        coEvery { repo.getByBarcode("B") } returns product("B", "螺母", 3.0)
        val vm = viewModel(repo)

        vm.scanBatch(listOf("A", "B", "A", "A", "B"))

        // 重复码只提醒不录入：A / B 各一行、各 1 件
        assertEquals(2, vm.cart.value.size)
        assertEquals(1, vm.cart.value.first { it.barcode == "A" }.qty)
        assertEquals(1, vm.cart.value.first { it.barcode == "B" }.qty)
        assertEquals(2, vm.totalQty)
        assertEquals(1.0 + 3.0, vm.totalAmount, 0.001)
    }

    /**
     * ★ 交接台「确定 · 保存账单」= **一步到底**（老板令 2026-09-26：
     *   「上面取景框 / 中间商品条栏 / 底下一个确定，按下去保存账单就完事了」）。
     *
     * 这一条锁的是**执行顺序** —— 保存必须发生在**入单之后**：
     * `getByBarcode` 是挂起查询，若在外层紧接着调 `saveBill()`，它拿到的是**还没入单的空货筐**，
     * `saveBill` 里 `lines.isEmpty()` 直接 return，账单**静默丢掉**。
     * 症状是「按了确定没反应」：不崩、不报错、toast 还说「已加入」，
     * **只有对账时才发现账单没了** —— 属于本项目最怕的那一类（看得见却不存在）。
     *
     * 断言用**可观察副作用**：`saveBill()` 成功后会清空货筐。货筐空 = 确实落账了。
     */
    @Test
    fun `交接台确定即保存 入完单就地落账`() = runTest {
        val repo = mockk<ProductRepo>()
        coEvery { repo.getByBarcode("A") } returns product("A", "螺丝", 2.0)
        val vm = viewModel(repo)

        vm.scanBatch(listOf("A"), saveAfterScan = true)

        assertTrue("按「确定 · 保存账单」后货筐应已落账清空", vm.cart.value.isEmpty())
    }

    /** 非交接台的旧调用点（查价 / 货位绑定 / 连扫入格…）带上标志才会保存 —— 默认**绝不**动账单。 */
    @Test
    fun `旧调用点不带保存标志 扫完不动账单`() = runTest {
        val repo = mockk<ProductRepo>()
        coEvery { repo.getByBarcode("A") } returns product("A", "螺丝", 2.0)
        val vm = viewModel(repo)

        vm.scanBatch(listOf("A"))

        assertEquals("没有交接台标志时不该自动保存", 1, vm.cart.value.size)
    }

    @Test
    fun `连扫命中行带全字段 sku 单位与成本快照不丢`() = runTest {
        val repo = mockk<ProductRepo>()
        coEvery { repo.getByBarcode("6901") } returns product("6901", "不锈钢螺丝", 2.0)
        val vm = viewModel(repo)

        vm.scanBatch(listOf("6901"))

        val line = vm.cart.value.first()
        assertEquals("SKU-6901", line.sku)
        assertEquals("件", line.unit)
        assertEquals(2.0 * 0.6, line.costPrice, 0.001)
        assertEquals(2.0 * 0.6, line.costPriceSnap!!, 0.001)
    }

    @Test
    fun `已入单的码重复扫 不再查库也不加一`() = runTest {
        val repo = mockk<ProductRepo>()
        coEvery { repo.getByBarcode("A") } returns product("A", "螺丝", 1.0)
        val vm = viewModel(repo)

        vm.onBarcodeScanned("A")
        vm.onBarcodeScanned("A")

        coVerify(exactly = 1) { repo.getByBarcode("A") }
        assertEquals(1, vm.cart.value.first().qty)
        assertTrue(vm.toast.value!!.text.contains("未重复计入"))
    }

    @Test
    fun `库里查不到的码直接忽略 不进任何队列`() = runTest {
        val repo = mockk<ProductRepo>()
        coEvery { repo.getByBarcode("9999") } returns null
        val vm = viewModel(repo)

        vm.onBarcodeScanned("9999")
        vm.onBarcodeScanned("9999")

        // ★ 2026-09-20 老板令：非条码 / 查不到的码**不要录进来，直接忽略**
        assertTrue(vm.cart.value.isEmpty())
        assertTrue(vm.toast.value!!.text.contains("不在商品库里"))
    }

    @Test
    fun `同一句提示连续出现两次 也要能再次发射`() = runTest {
        val repo = mockk<ProductRepo>()
        coEvery { repo.getByBarcode("9999") } returns null
        val vm = viewModel(repo)

        vm.onBarcodeScanned("9999")
        val first = vm.toast.value
        vm.onBarcodeScanned("9999")
        val second = vm.toast.value

        assertTrue(first!!.text.contains("9999"))
        assertTrue(second!!.text.contains("9999"))
        assertTrue("提示必须带自增 id，否则同一句话第二次不会发射", second.id > first.id)
    }

    @Test
    fun `空条码与空白不影响入单`() = runTest {
        val repo = mockk<ProductRepo>()
        val vm = viewModel(repo)

        // 全空输入：直接早退，一次库都不查，也不产生提示
        vm.scanBatch(listOf("", "   "))
        assertTrue(vm.cart.value.isEmpty())
        assertNull(vm.toast.value)

        coVerify(exactly = 0) { repo.getByBarcode(any()) }
    }

    @Test
    fun `文字识别 数字优先精确匹配命中 不走模糊搜索`() = runTest {
        val repo = mockk<ProductRepo>()
        coEvery { repo.getByBarcode("1001") } returns product("1001", "不锈钢螺丝", 2.0)
        val vm = viewModel(repo)

        vm.searchByText("不锈钢螺丝 1001")

        val result = vm.textSearch.value
        assertTrue(result != null)
        assertEquals(1, result!!.candidates.size)
        assertEquals("不锈钢螺丝", result.candidates.first().name)
        coVerify(exactly = 0) { repo.searchLocal(any(), any()) }
    }

    @Test
    fun `文字识别 数字匹配不到 降级整段文字模糊搜索`() = runTest {
        val repo = mockk<ProductRepo>()
        coEvery { repo.searchLocal(any(), any()) } returns listOf(product("X", "膨胀螺丝", 3.0))
        val vm = viewModel(repo)

        vm.searchByText("膨胀螺丝")

        val result = vm.textSearch.value
        assertTrue(result != null)
        assertEquals(1, result!!.candidates.size)
        assertEquals("膨胀螺丝", result.candidates.first().name)
    }

    @Test
    fun `并入已有挂账单 逐行 addItem 全字段透传并清空货筐`() = runTest {
        val repo = mockk<ProductRepo>()
        coEvery { repo.getByBarcode("6901") } returns product("6901", "不锈钢螺丝", 2.0)
        val billRepo = mockk<BillRepo>(relaxed = true)
        every { billRepo.allBills } returns flowOf(emptyList<BillEntity>())
        val vm = NewBillViewModel(
            billRepo = billRepo,
            productRepo = repo,
            draftDao = mockk<DraftDao>(relaxed = true),
            tempRepo = mockk<TempProductRepo>(relaxed = true),
        )

        vm.onBarcodeScanned("6901")
        vm.updateQty(0, 3)
        vm.mergeInto("B123")

        // addItem 逐行调用：条码/sku/数量/价格全字段透传
        coVerify(exactly = 1) {
            billRepo.addItem(eq("B123"), match { item ->
                item.barcode == "6901" && item.sku == "SKU-6901" &&
                    item.qty == 3 && item.price == 2.0
            })
        }
        coVerify(exactly = 1) { billRepo.syncBills() }
        assertTrue(vm.cart.value.isEmpty())
        assertTrue(vm.toast.value!!.text.contains("已并入"))
    }
}

/** 扫码结果解析：连续模式读批量，单次模式兜底单条。 */
class ScanResultParseTest {

    @Test
    fun `连续模式读批量条码`() {
        val data = mockk<Intent>()
        every { data.getStringArrayListExtra(ScannerActivity.EXTRA_BARCODES) } returns arrayListOf("A", "B", "A")
        assertEquals(listOf("A", "B", "A"), parseScanResult(data))
    }

    @Test
    fun `无批量结果时兜底单个条码`() {
        val data = mockk<Intent>()
        every { data.getStringArrayListExtra(ScannerActivity.EXTRA_BARCODES) } returns null
        every { data.getStringExtra(ScannerActivity.EXTRA_BARCODE) } returns "6901"
        assertEquals(listOf("6901"), parseScanResult(data))
    }

    @Test
    fun `无数据返回空列表`() {
        assertTrue(parseScanResult(null).isEmpty())
        val data = mockk<Intent>()
        every { data.getStringArrayListExtra(ScannerActivity.EXTRA_BARCODES) } returns null
        every { data.getStringExtra(ScannerActivity.EXTRA_BARCODE) } returns null
        assertTrue(parseScanResult(data).isEmpty())
    }
}
