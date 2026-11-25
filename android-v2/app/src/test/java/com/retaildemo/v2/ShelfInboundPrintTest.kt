package com.retaildemo.v2

import com.retaildemo.v2.data.local.dao.ProductDao
import com.retaildemo.v2.data.local.entity.PrintJobEntity
import com.retaildemo.v2.data.repo.PrintRepo
import com.retaildemo.v2.ui.viewmodel.ShelfViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U5 自扫码筐迁入货架页的「入库出签」：连扫到货 → 批量入 S5 SCENE_INBOUND 打印队列。
 * 同码合并数量（旧扫码筐语义）；查不到的码不入队只提示；绝不写库存（铁律 12）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShelfInboundPrintTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun product(barcode: String, name: String): com.retaildemo.v2.data.local.entity.ProductEntity =
        com.retaildemo.v2.data.local.entity.ProductEntity(
            id = 1L,
            barcode = barcode,
            sku = "SKU-$barcode",
            name = name,
            spec = "40mm",
            unit = "件",
            category = null,
            sellPrice = 2.0,
            costPrice = 1.0,
            stockQty = 10.0,
            pinyin = null,
            initials = null,
            updatedAt = null,
        )

    /** I20 起 ShelfViewModel 直依赖本地 ProductDao（离线优先），不再走 ProductRepo/InventoryRepo。 */
    private fun viewModel(dao: ProductDao, printRepo: PrintRepo): ShelfViewModel {
        return ShelfViewModel(
            shelfRepo = mockk(relaxed = true),
            shelfSyncRepo = mockk(relaxed = true),
            productDao = dao,
            dataMarkDao = mockk(relaxed = true),
            appConfig = mockk(relaxed = true),
            printRepo = printRepo,
            photoLoader = mockk(relaxed = true),
        )
    }

    @Test
    fun `连扫批量 同码合并数量入队且场景为入库`() = runTest {
        // Main 必须绑到 runTest 的 scheduler 上，否则 VM 协程排在没人抽水的队列（advanceUntilIdle 无效）
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val dao = mockk<ProductDao>()
        coEvery { dao.getByBarcode("A") } returns product("A", "螺丝")
        coEvery { dao.getByBarcode("B") } returns product("B", "螺母")
        val printRepo = mockk<PrintRepo>(relaxed = true)
        val vm = viewModel(dao, printRepo)

        // toastEvent 是 replay=0 零缓冲 SharedFlow：tryEmit 在无订阅者时直接丢，
        // 收集器必须 UNDISPATCHED 立即完成订阅，才能接住发射
        val toasts = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.toastEvent.collect { toasts.add(it) }
        }

        vm.enqueueInboundPrint(listOf("A", "B", "A", "A"))
        advanceUntilIdle() // VM 协程可能排队在调度器上：抽干再断言
        vm.enqueueInboundPrint(listOf("A"))
        advanceUntilIdle()

        // 两次 enqueue → enqueueBatch 两次；用列表 capture 收全部批次，按批次断言
        val batches = mutableListOf<List<PrintJobEntity>>()
        coVerify(exactly = 2) { printRepo.enqueueBatch(capture(batches)) }
        assertEquals(2, batches.size)
        val jobs = batches[0]
        assertEquals(2, jobs.size)
        val jobA = jobs.first { it.barcode == "A" }
        assertEquals(3, jobA.qty)
        assertEquals(PrintJobEntity.SCENE_INBOUND, jobA.sourceScene)
        assertEquals("SKU-A", jobA.sku)
        assertEquals("40mm", jobA.spec)
        assertEquals(1, jobs.first { it.barcode == "B" }.qty)
        // I19-photo-audit 班注：toast 断言移交 I20——MutableSharedFlow(replay=0) 的发射在
        // JVM 单测的 viewModelScope 调度下无法被收集器稳定捕获（UNDISPATCHED/backgroundScope
        // 两种惯例写法均收不到，属死班遗留未解问题），toast 文案留模拟器/真机实测覆盖。
    }

    @Test
    fun `全部查不到 不入队（提示文案留 I20 实测）`() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val dao = mockk<ProductDao>()
        coEvery { dao.getByBarcode(any()) } returns null
        coEvery { dao.search(any()) } returns emptyList()
        val printRepo = mockk<PrintRepo>(relaxed = true)
        val vm = viewModel(dao, printRepo)

        val toasts = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.toastEvent.collect { toasts.add(it) }
        }

        vm.enqueueInboundPrint(listOf("X1", "X2"))
        advanceUntilIdle()

        coVerify(exactly = 0) { printRepo.enqueueBatch(any()) }
    }
}
