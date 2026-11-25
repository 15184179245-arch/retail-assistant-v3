package com.retaildemo.v2

import android.content.Context
import android.graphics.Bitmap
import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.local.dao.BillDao
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.CartLine
import com.retaildemo.v2.data.repo.ReceiptPhotoStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 收款方式 (现金/POS/备注例外) + 小票照片：saveBill 落 payMethod、updateReceiptPhoto 回写、私有目录存图。 */
@RunWith(RobolectricTestRunner::class)
class BillPayMethodTest {

    private fun repoWith(dao: BillDao): BillRepo {
        val db = mockk<AppDatabase>()
        val api = mockk<retaildemoApi>()
        val config = mockk<AppConfig>()
        val customerDao = mockk<com.retaildemo.v2.data.local.dao.CustomerDao>(relaxed = true)
        every { db.billDao() } returns dao
        every { db.customerDao() } returns customerDao
        every { db.tempProductDao() } returns mockk(relaxed = true) // B12 markReady 依赖，本文件用不到
        every { dao.observeAll() } returns flowOf(emptyList())
        every { config.staffName } returns "店员"
        // M3：BillRepo.saveBill 会读 staffId 落 owner_id；空串=未绑名册 → owner_id null
        every { config.staffId } returns ""
        return BillRepo(db, api, config, mockk<com.retaildemo.v2.data.repo.AppLogger>(relaxed = true))
    }

    private fun line() = CartLine("1", "S1", "闸阀", "6分", "个", 1, 20.0, 12.5, costPriceSnap = 12.5)

    @Test
    fun `saveBill 落库带 payMethod 且 receiptPhoto 初始 null`() = runTest {
        val dao = mockk<BillDao>(relaxUnitFun = true)
        val cap = slot<BillEntity>()
        coEvery { dao.upsert(capture(cap)) } returns Unit
        val repo = repoWith(dao)

        val bill = repo.saveBill("散客", listOf(line()), "", null, payMethod = "pos")
        assertEquals("pos", bill.payMethod)
        assertNull(bill.receiptPhoto)
        assertTrue(bill.needsSync)
        assertEquals("pos", cap.captured.payMethod)
    }

    @Test
    fun `saveBill 支持备注选项与例外说明 note`() = runTest {
        val dao = mockk<BillDao>(relaxUnitFun = true)
        val cap = slot<BillEntity>()
        coEvery { dao.upsert(capture(cap)) } returns Unit
        val repo = repoWith(dao)

        val bill = repo.saveBill("张老板", listOf(line()), "微信私转老板", null, payMethod = "other")
        assertEquals("other", bill.payMethod)
        assertEquals("微信私转老板", bill.note)
        assertTrue(bill.needsSync)
        assertEquals("other", cap.captured.payMethod)
        assertEquals("微信私转老板", cap.captured.note)
    }

    @Test
    fun `updatePayMethodAndNote 更新收款方式与备注并置 needsSync`() = runTest {
        val dao = mockk<BillDao>()
        val base = BillEntity(
            id = "B2", customerName = "李工", totalAmount = 50.0, paidAmount = null,
            status = BillEntity.STATUS_PENDING, items = "[]", note = "", staff = "店员",
            createdAt = "2026-09-04 08:00:00", updatedAt = "2026-09-04 08:00:00",
            paidAt = null, needsSync = false, payMethod = "cash",
        )
        coEvery { dao.getById("B2") } returns base
        val cap = slot<BillEntity>()
        coEvery { dao.upsert(capture(cap)) } returns Unit
        val repo = repoWith(dao)

        assertTrue(repo.updatePayMethodAndNote("B2", "other", "客户下周结清"))
        assertEquals("other", cap.captured.payMethod)
        assertEquals("客户下周结清", cap.captured.note)
        assertTrue(cap.captured.needsSync)
    }

    @Test
    fun `payMethodLabel 标签映射测试（现金、POS、备注及历史兼容）`() {
        assertEquals("现金", BillEntity.payMethodLabel(BillEntity.PAY_METHOD_CASH))
        assertEquals("POS", BillEntity.payMethodLabel(BillEntity.PAY_METHOD_POS))
        assertEquals("备注", BillEntity.payMethodLabel(BillEntity.PAY_METHOD_OTHER))
        assertEquals("微信", BillEntity.payMethodLabel("wechat"))
        assertEquals("支付宝", BillEntity.payMethodLabel("alipay"))
        assertEquals("现金", BillEntity.payMethodLabel(null))
        assertEquals("现金", BillEntity.payMethodLabel(""))
        assertEquals("其他方式", BillEntity.payMethodLabel("其他方式"))
    }

    @Test
    fun `updateReceiptPhoto 回写文件名并置 needsSync；null 删除`() = runTest {
        val dao = mockk<BillDao>()
        val base = BillEntity(
            id = "B1", customerName = "散客", totalAmount = 20.0, paidAmount = null,
            status = BillEntity.STATUS_PENDING, items = "[]", note = "", staff = "店员",
            createdAt = "2026-09-04 08:00:00", updatedAt = "2026-09-04 08:00:00",
            paidAt = null, needsSync = false, payMethod = "cash",
        )
        coEvery { dao.getById("B1") } returns base
        val cap = slot<BillEntity>()
        coEvery { dao.upsert(capture(cap)) } returns Unit
        val repo = repoWith(dao)

        assertTrue(repo.updateReceiptPhoto("B1", "B1_123.jpg"))
        assertEquals("B1_123.jpg", cap.captured.receiptPhoto)
        assertTrue(cap.captured.needsSync)

        repo.updateReceiptPhoto("B1", null)
        assertNull(cap.captured.receiptPhoto)
    }

    @Test
    fun `ReceiptPhotoStore saveBitmap 写入私有 receipt 目录并可解码删除`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "sx_receipt_" + System.nanoTime())
        val ctx = mockk<Context>()
        every { ctx.getExternalFilesDir(null) } returns tmp

        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val name = ReceiptPhotoStore.saveBitmap(ctx, "B9", bmp)
        assertTrue(name.startsWith("B9_") && name.endsWith(".jpg"))
        val f = File(tmp, "receipt/$name")
        assertTrue(f.exists())
        assertTrue(File(ReceiptPhotoStore.path(ctx, name)).exists())

        ReceiptPhotoStore.delete(ctx, name)
        assertFalse(f.exists())
        tmp.deleteRecursively()
    }

    @Test
    fun `toggleStatus 遇到缺失 sku 的商品项时拦截直接出库并返回 false`() = runTest {
        val dao = mockk<BillDao>()
        // 含有 sku 为空的无码商品项
        val itemsJson = """[{"barcode":"","sku":"","name":"非店内水龙头","spec":"","unit":"件","qty":1,"price":35.0,"subtotal":35.0,"costPrice":0.0}]"""
        val bill = BillEntity(
            id = "B3", customerName = "散客", totalAmount = 35.0, paidAmount = null,
            status = BillEntity.STATUS_PENDING, items = itemsJson, note = "", staff = "店员",
            createdAt = "2026-09-07 08:00:00", updatedAt = "2026-09-07 08:00:00",
            paidAt = null, needsSync = false, payMethod = "cash",
        )
        coEvery { dao.getById("B3") } returns bill
        val repo = repoWith(dao)

        assertTrue(repo.hasMissingSku(bill))
        val toggled = repo.toggleStatus("B3")
        assertFalse("有缺失 sku 的账单必须被拦截，不可直接标记出库", toggled)
    }

    @Test
    fun `fillSkusAndMarkPaid 补齐所有缺失编号后成功标记已出库`() = runTest {
        val dao = mockk<BillDao>()
        val itemsJson = """[{"barcode":"","sku":"","name":"定制角阀","spec":"","unit":"个","qty":2,"price":15.0,"subtotal":30.0,"costPrice":0.0}]"""
        val bill = BillEntity(
            id = "B4", customerName = "散客", totalAmount = 30.0, paidAmount = null,
            status = BillEntity.STATUS_PENDING, items = itemsJson, note = "", staff = "店员",
            createdAt = "2026-09-07 08:00:00", updatedAt = "2026-09-07 08:00:00",
            paidAt = null, needsSync = false, payMethod = "cash",
        )
        coEvery { dao.getById("B4") } returns bill
        val cap = slot<BillEntity>()
        coEvery { dao.upsert(capture(cap)) } returns Unit
        val repo = repoWith(dao)

        val ok = repo.fillSkusAndMarkPaid("B4", mapOf(0 to "GJP-JF-001"))
        assertTrue("补全编号后出库成功", ok)
        assertEquals(BillEntity.STATUS_PAID, cap.captured.status)
        assertTrue(cap.captured.needsSync)
        val savedItems = repo.itemsOf(cap.captured)
        assertEquals("GJP-JF-001", savedItems[0].sku)
        assertEquals("GJP-JF-001", savedItems[0].barcode)
    }

    @Test
    fun `canMarkPaid 与 toggleStatus 严密防御：未选收款方式禁止出库`() = runTest {
        val dao = mockk<BillDao>()
        val itemsJson = """[{"barcode":"6901","sku":"SKU01","name":"正品水管","spec":"DN20","unit":"米","qty":5,"price":8.0,"subtotal":40.0,"costPrice":5.0}]"""
        // 开单时未选择收款方式 (payMethod = null)
        val bill = BillEntity(
            id = "B5", customerName = "张工", totalAmount = 40.0, paidAmount = null,
            status = BillEntity.STATUS_PENDING, items = itemsJson, note = "", staff = "店员",
            createdAt = "2026-09-07 08:00:00", updatedAt = "2026-09-07 08:00:00",
            paidAt = null, needsSync = false, payMethod = null,
        )
        coEvery { dao.getById("B5") } returns bill
        val repo = repoWith(dao)

        assertFalse("未选收款方式的账单不能出库", repo.canMarkPaid(bill))
        val toggled = repo.toggleStatus("B5")
        assertFalse("toggleStatus 必须拦截未选收款方式的账单出库", toggled)
    }

    @Test
    fun `canMarkPaid 与 toggleStatus 严密防御：POS收款未上传小票禁止出库`() = runTest {
        val dao = mockk<BillDao>()
        val itemsJson = """[{"barcode":"6901","sku":"SKU01","name":"正品水管","spec":"DN20","unit":"米","qty":5,"price":8.0,"subtotal":40.0,"costPrice":5.0}]"""
        // POS 收款但未传小票照片 (receiptPhoto = null)
        val bill = BillEntity(
            id = "B6", customerName = "王工", totalAmount = 40.0, paidAmount = null,
            status = BillEntity.STATUS_PENDING, items = itemsJson, note = "", staff = "店员",
            createdAt = "2026-09-07 08:00:00", updatedAt = "2026-09-07 08:00:00",
            paidAt = null, needsSync = false, payMethod = BillEntity.PAY_METHOD_POS, receiptPhoto = null,
        )
        coEvery { dao.getById("B6") } returns bill
        val repo = repoWith(dao)

        assertFalse("POS收款未上传小票禁止出库", repo.canMarkPaid(bill))
        val toggled = repo.toggleStatus("B6")
        assertFalse("toggleStatus 必须拦截无小票照片的POS出库", toggled)

        // 上传小票照片后允许出库
        val billWithPhoto = bill.copy(receiptPhoto = "B6_pos.jpg")
        assertTrue("POS收款且有小票照片允许出库", repo.canMarkPaid(billWithPhoto))
    }

    @Test
    fun `fulfillAndMarkPaid 原子校验：一次性补齐编号、收款方式与POS小票后出库`() = runTest {
        val dao = mockk<BillDao>()
        val itemsJson = """[{"barcode":"","sku":"","name":"串货管件","spec":"","unit":"件","qty":1,"price":50.0,"subtotal":50.0,"costPrice":0.0}]"""
        // 串货挂单：无 sku，无收款方式
        val bill = BillEntity(
            id = "B7", customerName = "赵总", totalAmount = 50.0, paidAmount = null,
            status = BillEntity.STATUS_PENDING, items = itemsJson, note = "", staff = "店员",
            createdAt = "2026-09-07 08:00:00", updatedAt = "2026-09-07 08:00:00",
            paidAt = null, needsSync = false, payMethod = null,
        )
        coEvery { dao.getById("B7") } returns bill
        val cap = slot<BillEntity>()
        coEvery { dao.upsert(capture(cap)) } returns Unit
        val repo = repoWith(dao)

        // 仅补编号不选收款方式 -> 失败
        val r1 = repo.fulfillAndMarkPaid("B7", skuMap = mapOf(0 to "SKU-999"), payMethod = null)
        assertFalse("未指定收款方式不可出库", r1)

        // 选了POS但没有小票 -> 失败
        val r2 = repo.fulfillAndMarkPaid("B7", skuMap = mapOf(0 to "SKU-999"), payMethod = "pos", receiptPhoto = null)
        assertFalse("POS收款没有小票不可出库", r2)

        // 选了POS并提供小票且补齐编号 -> 成功
        val r3 = repo.fulfillAndMarkPaid("B7", skuMap = mapOf(0 to "SKU-999"), payMethod = "pos", receiptPhoto = "receipt_b7.jpg")
        assertTrue("全要素补齐后出库成功", r3)
        assertEquals(BillEntity.STATUS_PAID, cap.captured.status)
        assertEquals("pos", cap.captured.payMethod)
        assertEquals("receipt_b7.jpg", cap.captured.receiptPhoto)
        assertEquals("SKU-999", repo.itemsOf(cap.captured)[0].sku)
    }

    @Test
    fun `TempFormValidator 串货参数五必填；店内商品仍须有效售价`() {
        // 串货：只填商品名不再放行（2026-09-10 老板口径，规格型号/售价/来源/串货价/凭证必填）
        val errStradeNameOnly = com.retaildemo.v2.data.repo.TempFormValidator.validate(
            type = "strade",
            name = "外采特殊接头",
            spec = "",
            sellPriceText = "",
            sourceVendor = "",
            stradePriceText = "",
            voucherPhoto = null,
        )
        assertEquals("串货须填：规格型号", errStradeNameOnly)

        // 串货：五项参数齐备才通过（编号不必填）
        val okStradeFull = com.retaildemo.v2.data.repo.TempFormValidator.validate(
            type = "strade",
            name = "外采特殊接头",
            spec = "M20",
            sellPriceText = "30",
            sourceVendor = "甲店",
            stradePriceText = "22",
            voucherPhoto = "v_1.jpg",
        )
        assertNull("串货参数齐全通过", okStradeFull)

        // 串货：品名为空不通过
        val errStradeBlankName = com.retaildemo.v2.data.repo.TempFormValidator.validate(
            type = "strade",
            name = "   ",
            spec = "",
            sellPriceText = "",
            sourceVendor = "",
            stradePriceText = "",
            voucherPhoto = null,
        )
        assertEquals("请填写商品名", errStradeBlankName)

        // 店内商品：售价必填非负
        val errCustomEmptyPrice = com.retaildemo.v2.data.repo.TempFormValidator.validate(
            type = "custom",
            name = "店内杂件螺栓",
            spec = "",
            sellPriceText = "",
            sourceVendor = "",
            stradePriceText = "",
            voucherPhoto = null,
        )
        assertEquals("店内商品请填写有效售价", errCustomEmptyPrice)

        val errCustomValid = com.retaildemo.v2.data.repo.TempFormValidator.validate(
            type = "custom",
            name = "店内杂件螺栓",
            spec = "",
            sellPriceText = "5.5",
            sourceVendor = "",
            stradePriceText = "",
            voucherPhoto = null,
        )
        assertNull("店内商品填写真实售价通过", errCustomValid)
    }
}
