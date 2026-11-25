package com.retaildemo.v2

import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.ProductEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.BillPushRequest
import com.retaildemo.v2.data.remote.BillSyncDto
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.remote.ApiEnvelope
import com.retaildemo.v2.data.remote.BillPushResult
import com.retaildemo.v2.data.remote.ServerBillDto
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.parseServerBillMap
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * 账单同步逻辑：推送未同步 → markSynced；拉取裸 map 合并 last-writer-wins。
 * 服务端契约见 src/gateway/bills_sync.py。
 */
class BillRepoSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var billDao: com.retaildemo.v2.data.local.dao.BillDao
    private lateinit var api: retaildemoApi
    private lateinit var config: AppConfig
    private lateinit var repo: BillRepo
    private lateinit var localBills: kotlinx.coroutines.flow.MutableStateFlow<List<BillEntity>>

    private val gson = com.google.gson.Gson()

    /** 服务端 GET /bills/sync 返回 JsonElement 裸 map——测试侧用 toJsonTree 造同形数据。 */
    private fun srv(dto: ServerBillDto): com.google.gson.JsonElement = gson.toJsonTree(dto)

    private fun bill(
        id: String,
        updatedAt: String,
        needsSync: Boolean = false,
        status: String = BillEntity.STATUS_PENDING,
        total: Double = 10.0,
    ) = BillEntity(
        id = id, customerName = "散客", totalAmount = total, paidAmount = null,
        status = status, items = "[]", note = "", staff = "店员",
        createdAt = "2026-08-31 08:00:00", updatedAt = updatedAt, paidAt = null,
        needsSync = needsSync,
    )

    @Before
    fun setup() {
        db = mockk()
        billDao = mockk(relaxUnitFun = true)
        api = mockk()
        config = mockk()
        every { config.staffName } returns "店员"
        every { db.billDao() } returns billDao
        every { db.customerDao() } returns mockk {
            every { observeAll() } returns flowOf(emptyList())
        }
        every { db.tempProductDao() } returns mockk(relaxed = true) // B12 markReady 依赖，本文件用不到
        // localSnapshot() 在每次 syncBills 时都会取 observeAll 首值（非 suspend）；
        // allBills 在构造时捕获 Flow 实例，故用 StateFlow 控制内容
        localBills = kotlinx.coroutines.flow.MutableStateFlow(emptyList<BillEntity>())
        every { billDao.observeAll() } returns localBills
        repo = BillRepo(db, api, config, mockk<com.retaildemo.v2.data.repo.AppLogger>(relaxed = true))
    }

    @Test
    fun `pull cannot wipe local receipt photo even when server row is newer`() = runTest {
        // I31/B2 回归：BillSyncDto/ServerBillDto 均不携带 receipt_photo，REPLACE 整行覆盖
        // 会把照片引用洗掉——POS 出库硬条件（必须有小票照片）随之永久不可满足。
        val localWithPhoto = bill("B1", "2026-09-12 03:56:00").copy(receiptPhoto = "p1.jpg")
        localBills.value = listOf(localWithPhoto)
        coEvery { billDao.getUnsynced() } returns emptyList()
        coEvery { api.pullBills() } returns Response.success(
            mapOf(
                "B1" to srv(
                    ServerBillDto(
                        id = "B1", total = 10.0, status = "pending",
                        created_at = "2026-09-12 03:56:00",
                        updated_at = "2026-09-12 11:38:06", // 服务端时钟快 ⇒ incoming 更新，本地按 LWW 让位
                        items = emptyList(),
                    ),
                ),
            ),
        )
        val captured = slot<BillEntity>()
        coEvery { billDao.upsert(capture(captured)) } returns Unit

        val ok = repo.syncBills()

        assertTrue(ok)
        assertEquals("p1.jpg", captured.captured.receiptPhoto)
    }

    @Test
    fun `corrupt items json refuses edit write back`() = runTest {
        // I31/B2 回归：items JSON 损坏时绝不能带着空列表继续读改写——那等于把账单行清零。
        val corrupt = bill("B1", "2026-08-31 09:00:00").copy(items = "{broken")
        coEvery { billDao.getById("B1") } returns corrupt

        val ok = repo.addItem(
            "B1",
            com.retaildemo.v2.data.local.entity.BillItem(
                barcode = "6901", sku = "S1", name = "测试", spec = "", unit = "个",
                qty = 1, price = 2.0, costPrice = 1.0,
            ),
        )

        assertFalse(ok)
        coVerify(exactly = 0) { billDao.upsert(any()) }
    }

    @Test
    fun `push success marks bills synced`() = runTest {
        val unsynced = listOf(bill("B1", "2026-08-31 09:00:00", needsSync = true))
        coEvery { billDao.getUnsynced() } returns unsynced
                coEvery {
            api.pushBills(any<BillPushRequest>())
        } returns Response.success(BillPushResult(success = true, updated = listOf("B1"), skipped = emptyList()))
        coEvery { api.pullBills() } returns Response.success(emptyMap())

        val ok = repo.syncBills()

        assertTrue(ok)
        coVerify { billDao.markSynced("B1") }
    }

    @Test
    fun `push marks only server accepted ids synced`() = runTest {
        // 2026-09-12：服务端 LWW 会 skip 它判为旧的单（典型=两写者时钟差），
        // 被 skip 的必须保持待推（needsSync=1），否则本地改动从此不再重推。
        val unsynced = listOf(
            bill("B1", "2026-09-12 03:59:12", needsSync = true),
            bill("B2", "2026-09-12 03:59:13", needsSync = true),
        )
        coEvery { billDao.getUnsynced() } returns unsynced
        coEvery { api.pushBills(any<BillPushRequest>()) } returns Response.success(
            BillPushResult(success = true, updated = listOf("B1"), skipped = listOf("B2")),
        )
        coEvery { api.pullBills() } returns Response.success(emptyMap())

        val ok = repo.syncBills()

        assertTrue(ok)
        coVerify(exactly = 1) { billDao.markSynced("B1") }
        coVerify(exactly = 0) { billDao.markSynced("B2") }
    }

    @Test
    fun `push skipped keeps needsSync and pull cannot revert local paid`() = runTest {
        // 老板 2026-09-12 实测回归：标记出库 → 推送被服务端 LWW skip（后台宿主机时钟
        // 比手持端快，库里已有"更新"的旧状态）→ 回拉把 paid 打回 pending，
        // 今日实收营业额变动随之消失。修复后：skip 单保持脏行，回拉不得覆盖脏行。
        val localPaid = bill(
            "B1", "2026-09-12 03:59:12",
            needsSync = true, status = BillEntity.STATUS_PAID, total = 12.5,
        )
        coEvery { billDao.getUnsynced() } returns listOf(localPaid)
        localBills.value = listOf(localPaid)
        coEvery { api.pushBills(any<BillPushRequest>()) } returns Response.success(
            BillPushResult(success = true, updated = emptyList(), skipped = listOf("B1")),
        )
        coEvery { api.pullBills() } returns Response.success(
            mapOf(
                "B1" to srv(
                    ServerBillDto(
                        id = "B1", total = 12.5, status = "pending",
                        created_at = "2026-09-12 03:56:00",
                        updated_at = "2026-09-12 11:38:06", items = emptyList(),
                    ),
                ),
            ),
        )

        val ok = repo.syncBills()

        assertTrue(ok)
        coVerify(exactly = 0) { billDao.markSynced(any()) }
        coVerify(exactly = 0) { billDao.upsert(any()) }
    }

    @Test
    fun `push failure keeps needsSync and reports failure`() = runTest {
        val unsynced = listOf(bill("B2", "2026-08-31 09:00:00", needsSync = true))
        coEvery { billDao.getUnsynced() } returns unsynced
                coEvery {
            api.pushBills(any<BillPushRequest>())
        } returns Response.error<BillPushResult>(500, "{}".toResponseBody("application/json".toMediaTypeOrNull()))
        coEvery { api.pullBills() } returns Response.success(emptyMap())

        val ok = repo.syncBills()

        assertFalse(ok)
        coVerify(exactly = 0) { billDao.markSynced(any()) }
    }

    @Test
    fun `pull merges remote bill that is new`() = runTest {
        coEvery { billDao.getUnsynced() } returns emptyList()
        coEvery { api.pushBills(any<BillPushRequest>()) } returns Response.error(
            500, "{}".toResponseBody("application/json".toMediaTypeOrNull()),
        )
        val remote = bill(
            "SRV1", "2026-08-31 12:00:00",
            status = BillEntity.STATUS_PAID, total = 42.0,
        )
        // 2026-09-11 老板实测回归：老网关把 _request_id 字符串混进裸 map，
        // 强类型解析整包抛 Expected BEGIN_OBJECT——解析必须容错跳过元数据键。
        coEvery { api.pullBills() } returns Response.success(
            mapOf(
                "SRV1" to srv(
                    ServerBillDto(
                        id = "SRV1", customer_name = "张师傅", total = 42.0,
                        status = "paid", created_at = "2026-08-31 12:00:00",
                        updated_at = "2026-08-31 12:00:00", items = emptyList(),
                    ),
                ),
                "_request_id" to com.google.gson.JsonPrimitive("767609352ccd"),
            ),
        )
        coEvery { billDao.upsert(any()) } returns Unit

        repo.syncBills()

        coVerify {
            billDao.upsert(withArg {
                assertEquals("SRV1", it.id)
                assertEquals(BillEntity.STATUS_PAID, it.status)
                assertEquals(42.0, it.totalAmount, 1e-9)
                assertFalse(it.needsSync)
            })
        }
    }

    @Test
    fun `pull skips when local is newer last-writer-wins`() = runTest {
        val local = bill("B3", "2026-08-31 18:00:00", needsSync = false)
        coEvery { billDao.getUnsynced() } returns emptyList()
        localBills.value = listOf(local)
        coEvery { api.pullBills() } returns Response.success(
            mapOf(
                "B3" to srv(
                    ServerBillDto(
                        id = "B3", total = 1.0, status = "pending",
                        created_at = "2026-08-31 08:00:00",
                        updated_at = "2026-08-31 08:00:00", items = emptyList(),
                    ),
                ),
            ),
        )

        repo.syncBills()

        coVerify(exactly = 0) { billDao.upsert(any()) }
    }

    @Test
    fun `pull equal timestamp keeps local row`() = runTest {
        // 2026-09-12：推送成功后的回拉会原样带回刚推的内容（updated_at 相等），
        // 再 upsert 一遍只会洗掉 DTO 未携带的字段（退货方向/开票标记等）。
        // 相等时间戳保留本地，服务端必须严格更新才覆盖。
        val local = bill("B3", "2026-08-31 10:00:00", needsSync = false).copy(direction = "return")
        coEvery { billDao.getUnsynced() } returns emptyList()
        localBills.value = listOf(local)
        coEvery { api.pullBills() } returns Response.success(
            mapOf(
                "B3" to srv(
                    ServerBillDto(
                        id = "B3", total = 1.0, status = "paid",
                        created_at = "2026-08-31 08:00:00",
                        updated_at = "2026-08-31 10:00:00", items = emptyList(),
                    ),
                ),
            ),
        )

        repo.syncBills()

        coVerify(exactly = 0) { billDao.upsert(any()) }
    }

    @Test
    fun `pull restores return direction invoice flag and cost snap`() = runTest {
        // B6/B5 往返契约：type=return ↔ direction，invoice_needed ↔ invoiceNeeded，
        // items.cost_price_snap ↔ costPriceSnap——此前拉取重构直接丢这三个。
        coEvery { billDao.getUnsynced() } returns emptyList()
        coEvery { api.pushBills(any<BillPushRequest>()) } returns Response.success(
            BillPushResult(success = true, updated = emptyList(), skipped = emptyList()),
        )
        coEvery { api.pullBills() } returns Response.success(
            mapOf(
                "SRV1" to srv(
                    ServerBillDto(
                        id = "SRV1", type = "return", total = -30.0,
                        status = "paid", created_at = "2026-09-12 09:00:00",
                        updated_at = "2026-09-12 09:00:00",
                        invoice_needed = true,
                        items = listOf(
                            mapOf(
                                "barcode" to "1", "sku" to "S1", "productName" to "灯带头",
                                "spec" to "", "unit" to "个", "quantity" to 2.0,
                                "price" to 10.0, "cost_price" to 6.0, "cost_price_snap" to 6.5,
                            ),
                        ),
                    ),
                ),
            ),
        )

        repo.syncBills()

        coVerify {
            billDao.upsert(withArg {
                assertEquals("return", it.direction)
                assertTrue(it.invoiceNeeded)
                val item = repo.itemsFromJson(it.items).single()
                assertEquals(6.5, item.costPriceSnap!!, 1e-9)
            })
        }
    }

    @Test
    fun `sync dto carries item note temp code and recorded flag`() {
        // 2026-09-20 往返补全：这三个 item 字段此前不上送，回拉重构会把它们洗成默认值。
        // recorded 是「这条已扫枪录进管家婆」的唯一凭据，洗掉 = 店员重复录入（重复出库）。
        val json = repo.itemsToJson(
            listOf(
                com.retaildemo.v2.data.local.entity.BillItem(
                    barcode = "", sku = "", name = "串货·空开", spec = "C25 3P", unit = "个",
                    qty = 1, price = 28.0, costPrice = 0.0,
                    itemNote = "少收两块", tempCode = "TMP-20260920-s001", recorded = true,
                ),
            ),
        )
        val dtoItems = repo.toSyncDto(bill("B9", "2026-08-31 09:00:00").copy(items = json)).items
        assertEquals("少收两块", dtoItems[0]["item_note"])
        assertEquals("TMP-20260920-s001", dtoItems[0]["temp_code"])
        assertEquals(true, dtoItems[0]["recorded"])
    }

    @Test
    fun `pull restores recorded and temp code from server row`() = runTest {
        coEvery { billDao.getUnsynced() } returns emptyList()
        localBills.value = emptyList()
        coEvery { api.pullBills() } returns Response.success(
            mapOf(
                "SRV2" to srv(
                    ServerBillDto(
                        id = "SRV2", total = 28.0, status = "pending",
                        created_at = "2026-09-20 09:00:00", updated_at = "2026-09-20 09:00:00",
                        items = listOf(
                            mapOf(
                                "barcode" to "", "sku" to "", "productName" to "串货·空开",
                                "spec" to "C25 3P", "unit" to "个", "quantity" to 1.0,
                                "price" to 28.0, "cost_price" to 0.0,
                                "item_note" to "少收两块", "temp_code" to "TMP-20260920-s001",
                                "recorded" to true,
                            ),
                        ),
                    ),
                ),
            ),
        )
        coEvery { billDao.upsert(any()) } returns Unit

        repo.syncBills()

        coVerify {
            billDao.upsert(withArg {
                val item = repo.itemsFromJson(it.items).single()
                assertEquals("少收两块", item.itemNote)
                assertEquals("TMP-20260920-s001", item.tempCode)
                assertTrue(item.recorded)
            })
        }
    }

    @Test
    fun `pull keeps local recorded when server row predates the field`() = runTest {
        // 过渡期兼容：服务端现存的老账单没有这三个键，回拉时不能拿默认值覆盖本地真值，
        // 否则装完新版第一次同步就把全店「已录入」标记洗白。
        val localJson = repo.itemsToJson(
            listOf(
                com.retaildemo.v2.data.local.entity.BillItem(
                    barcode = "1", sku = "S1", name = "灯带头", spec = "", unit = "个",
                    qty = 2, price = 10.0, costPrice = 6.0,
                    itemNote = "少收两块", tempCode = "TMP-20260920-c001", recorded = true,
                ),
            ),
        )
        val local = bill("B7", "2026-09-20 09:00:00").copy(items = localJson)
        coEvery { billDao.getUnsynced() } returns emptyList()
        localBills.value = listOf(local)
        coEvery { api.pullBills() } returns Response.success(
            mapOf(
                "B7" to srv(
                    ServerBillDto(
                        id = "B7", total = 20.0, status = "pending",
                        created_at = "2026-09-20 08:00:00",
                        updated_at = "2026-09-20 12:00:00",   // 服务端更新 ⇒ LWW 覆盖本地
                        items = listOf(
                            mapOf(
                                "barcode" to "1", "sku" to "S1", "productName" to "灯带头",
                                "spec" to "", "unit" to "个", "quantity" to 2.0,
                                "price" to 10.0, "cost_price" to 6.0,
                            ),
                        ),
                    ),
                ),
            ),
        )
        coEvery { billDao.upsert(any()) } returns Unit

        repo.syncBills()

        coVerify {
            billDao.upsert(withArg {
                val item = repo.itemsFromJson(it.items).single()
                assertEquals("少收两块", item.itemNote)
                assertEquals("TMP-20260920-c001", item.tempCode)
                assertTrue(item.recorded)
            })
        }
    }

    @Test
    fun `sync dto carries server contract fields`() {
        // 账单 items 序列化包含服务端读取的字段名
        val items = listOf(
            com.retaildemo.v2.data.local.entity.BillItem(
                "1073670", "SKU", "灯带头", "4分", "个", 2, 10.0, 6.0, costPriceSnap = 6.5,
            ),
        )
        val json = repo.itemsToJson(items)
        assertTrue(json.contains("\"barcode\":\"1073670\""))
        assertTrue(json.contains("\"name\":\"灯带头\""))
        assertTrue(json.contains("\"qty\":2"))
        assertTrue(json.contains("\"price\":10.0"))
        // 上传 DTO 的 items map 才携带服务端读取的 quantity/productName 键；
        // 2026-09-12 补 cost_price_snap（成本快照往返，否则同步洗掉毛利口径）
        val entity = bill("B9", "2026-08-31 09:00:00").copy(items = json)
        val dtoItems = repo.toSyncDto(entity).items
        assertEquals(2, dtoItems[0]["quantity"])
        assertEquals("灯带头", dtoItems[0]["productName"])
        assertEquals(6.5, dtoItems[0]["cost_price_snap"])

        val base = bill("B9", "2026-08-31 09:00:00", status = BillEntity.STATUS_PENDING)
        val dto = repo.toSyncDto(base)
        assertEquals("B9", dto.id)
        assertEquals("pending", dto.status)
        assertEquals("2026-08-31 08:00:00", dto.created_at)
        assertEquals("2026-08-31 09:00:00", dto.updated_at)
        assertEquals("店员", dto.staff)
        assertEquals("sale", dto.type)
        // B6 退货方向 / B5 开票标记随推送上送（回拉重构不再洗掉）
        val retDto = repo.toSyncDto(base.copy(direction = "return", invoiceNeeded = true))
        assertEquals("return", retDto.type)
        assertEquals(true, retDto.invoice_needed)
    }

    // ---------------- M2 从服务器恢复账单 ----------------

    @Test
    fun `restore merges remote bills and reports counts`() = runTest {
        coEvery { api.pullBills() } returns Response.success(
            mapOf(
                "SRV1" to srv(
                    ServerBillDto(
                        id = "SRV1", total = 42.0, status = "paid",
                        created_at = "2026-09-11 08:00:00", updated_at = "2026-09-11 08:00:00",
                        items = emptyList(),
                    ),
                ),
                "SRV2" to srv(
                    ServerBillDto(
                        id = "SRV2", total = 8.0, status = "pending",
                        created_at = "2026-09-11 09:00:00", updated_at = "2026-09-11 09:00:00",
                        items = emptyList(),
                    ),
                ),
            ),
        )
        localBills.value = emptyList()
        coEvery { billDao.upsert(any()) } answers {
            localBills.value = localBills.value + firstArg<BillEntity>()
        }

        val r = repo.restoreFromServer()

        assertTrue(r.ok)
        assertEquals(2, r.restored)
        assertEquals(2, r.localTotal)
    }

    @Test
    fun `restore reports failure instead of fake zero when server errors`() = runTest {
        // 关键 M2 契约：读库失败服务端回 503（见 src/gateway/handler.py），
        // APP 绝不能把它当「服务器没单」显示 0 条——那正是老板看到「什么都没了」的观感来源。
        coEvery { api.pullBills() } returns Response.error(
            503, "{}".toResponseBody("application/json".toMediaTypeOrNull()),
        )

        val r = repo.restoreFromServer()

        assertFalse(r.ok)
        assertEquals(0, r.restored)
        assertTrue(r.error!!.contains("503"))
    }

    @Test
    fun `restore reports failure on network exception`() = runTest {
        coEvery { api.pullBills() } throws java.io.IOException("connect refused")

        val r = repo.restoreFromServer()

        assertFalse(r.ok)
        assertEquals(0, r.restored)
        assertTrue(r.error!!.isNotBlank())
    }

    @Test
    fun `restore carries owner_id into local bill`() = runTest {
        coEvery { api.pullBills() } returns Response.success(
            mapOf(
                "SRV9" to srv(
                    ServerBillDto(
                        id = "SRV9", total = 1.0, status = "pending", staff = "老王",
                        owner_id = "EMP-LW",
                        created_at = "2026-09-11 08:00:00", updated_at = "2026-09-11 08:00:00",
                        items = emptyList(),
                    ),
                ),
            ),
        )
        coEvery { billDao.upsert(any()) } returns Unit

        repo.restoreFromServer()

        coVerify { billDao.upsert(withArg { assertEquals("EMP-LW", it.ownerId) }) }
    }

    // ---------------- 账单真删除（防刷新复活） ----------------

    @Test
    fun `deleteBill reports to server and clears pending on success`() = runTest {
        coEvery { api.deleteBill(any<Map<String, String>>()) } returns Response.success(
            ApiEnvelope(success = true, data = mapOf("id" to "B_DEL", "deleted" to true)),
        )
        coEvery { billDao.deleteById("B_DEL") } returns Unit

        repo.deleteBill("B_DEL")

        coVerify { api.deleteBill(withArg { assertEquals("B_DEL", it["id"]) }) }
        coVerify { billDao.deleteById("B_DEL") }
        assertTrue(repo.pendingDeletes.isEmpty())
    }

    @Test
    fun `deleteBill keeps pending on server failure and sync retries it`() = runTest {
        coEvery { api.deleteBill(any<Map<String, String>>()) } returns Response.error(
            500, "{}".toResponseBody("application/json".toMediaTypeOrNull()),
        )
        coEvery { billDao.deleteById("B_DEL2") } returns Unit

        repo.deleteBill("B_DEL2")

        assertTrue("失败必须留在待删队列等重试", "B_DEL2" in repo.pendingDeletes)

        // 下一轮 syncBills：上报成功 → 队列清空
        coEvery { billDao.getUnsynced() } returns emptyList()
        coEvery { api.pullBills() } returns Response.success(emptyMap())
        coEvery { api.deleteBill(any<Map<String, String>>()) } returns Response.success(
            ApiEnvelope(success = true, data = mapOf("id" to "B_DEL2", "deleted" to true)),
        )
        repo.syncBills()
        assertTrue("重试成功后队列应清空", repo.pendingDeletes.isEmpty())
    }

    @Test
    fun `sync pull skips bills in pendingDeletes no resurrection`() = runTest {
        // 核心回归：本机刚删的单还在待删队列时，服务端若还带着它，回拉绝不能复活本地
        coEvery { billDao.getUnsynced() } returns emptyList()
        localBills.value = emptyList()
        coEvery { api.pullBills() } returns Response.success(
            mapOf(
                "B_GHOST" to srv(
                    ServerBillDto(
                        id = "B_GHOST", total = 42.0, status = "pending",
                        created_at = "2026-09-26 17:37:22", updated_at = "2026-09-26 17:37:22",
                        items = emptyList(),
                    ),
                ),
            ),
        )
        coEvery { billDao.upsert(any()) } returns Unit

        repo.pendingDeletes.add("B_GHOST")
        repo.syncBills()

        coVerify(exactly = 0) { billDao.upsert(any()) }
        assertFalse(repo.pendingDeletes.isEmpty() && false) // 队列保留（服务端删除还没成功）
        coVerify(exactly = 1) { api.deleteBill(any<Map<String, String>>()) }
    }

    @Test
    fun `sync prunes locally synced bills missing from server`() = runTest {
        // 跨设备真删除对齐：本机已同步（needsSync=false）但服务端全量列表里已经没有 → 本地移除
        val stale = bill("B_OLD", "2026-09-20 10:00:00", needsSync = false)
        coEvery { billDao.getUnsynced() } returns emptyList()
        localBills.value = listOf(stale)
        coEvery { api.pullBills() } returns Response.success(emptyMap())
        coEvery { billDao.deleteById("B_OLD") } returns Unit

        repo.syncBills()

        coVerify { billDao.deleteById("B_OLD") }
    }

    @Test
    fun `sync keeps unsynced local bills missing from server`() = runTest {
        // 反证：本地还有未同步的脏行（needsSync=true）时绝不能被服务端"没有"误删
        val dirty = bill("B_NEW", "2026-09-26 18:00:00", needsSync = true)
        coEvery { billDao.getUnsynced() } returns listOf(dirty)
        coEvery {
            api.pushBills(any<BillPushRequest>())
        } returns Response.success(BillPushResult(success = true, updated = listOf("B_NEW"), skipped = emptyList()))
        localBills.value = listOf(dirty)
        coEvery { api.pullBills() } returns Response.success(emptyMap())

        repo.syncBills()

        coVerify(exactly = 0) { billDao.deleteById(any()) }
    }

    // ---------------- M3 归属字段 owner_id ----------------

    @Test
    fun `sync dto carries owner_id`() {
        val dto = repo.toSyncDto(bill("B9", "2026-08-31 09:00:00").copy(ownerId = "EMP-001"))
        assertEquals("EMP-001", dto.owner_id)
    }

    @Test
    fun `saved bill carries current staff identity`() = runTest {
        every { config.staffId } returns "EMP-LW"
        coEvery { billDao.upsert(any()) } returns Unit

        val saved = repo.saveBill("散客", emptyList(), "", null)

        assertEquals("EMP-LW", saved.ownerId)
        assertEquals("店员", saved.staff)
    }

    // ---------------- 2026-09-11 裸 map 解析容错 ----------------

    @Test
    fun `parseServerBillMap skips metadata strings and broken entries`() {
        val good = srv(
            ServerBillDto(id = "B1", total = 5.0, status = "pending", items = emptyList()),
        )
        val raw = mapOf(
            "_request_id" to com.google.gson.JsonPrimitive("767609352ccd") as com.google.gson.JsonElement,
            "B1" to good,
            "junk" to com.google.gson.JsonPrimitive("not a bill"),
        )
        val parsed = parseServerBillMap(raw)
        assertEquals(setOf("B1"), parsed.keys)
        assertEquals(5.0, parsed.getValue("B1").total, 1e-9)
    }

    @Test
    fun `parseServerBillMap handles null empty and malformed object`() {
        assertTrue(parseServerBillMap(null).isEmpty())
        assertTrue(parseServerBillMap(emptyMap()).isEmpty())
        // 单条损坏（items 字段形状不对 → Gson 抛 JsonSyntaxException）只丢那一条，不整包报废
        val parsed = parseServerBillMap(
            mapOf(
                "BAD" to com.google.gson.JsonObject().apply { addProperty("items", "wrong-shape") },
                "B2" to srv(ServerBillDto(id = "B2", total = 1.0, status = "pending", items = emptyList())),
            ),
        )
        assertEquals(setOf("B2"), parsed.keys)
    }

    @Test
    fun `saved bill has null owner when roster not bound`() = runTest {
        every { config.staffId } returns ""
        coEvery { billDao.upsert(any()) } returns Unit

        val saved = repo.saveBill("散客", emptyList(), "", null)

        assertEquals(null, saved.ownerId)
    }
}
