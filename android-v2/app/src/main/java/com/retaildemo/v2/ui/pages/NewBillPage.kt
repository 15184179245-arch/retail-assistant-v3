package com.retaildemo.v2.ui.pages

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.data.remote.ChatContext
import com.retaildemo.v2.ui.components.KnowledgeFloatSheet
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.pages.newbill.NewBillCartList
import com.retaildemo.v2.ui.pages.newbill.NewBillPayRow
import com.retaildemo.v2.ui.pages.newbill.NewBillCartTotalRow
import com.retaildemo.v2.ui.pages.newbill.NewBillCustomerPickerDialog
import com.retaildemo.v2.ui.pages.newbill.NewBillDraftBanner
import com.retaildemo.v2.ui.pages.newbill.NewBillMergeIntoBillDialog
import com.retaildemo.v2.ui.pages.newbill.NewBillPriceEditKeypad
import com.retaildemo.v2.ui.pages.newbill.NewBillScanBanner
import com.retaildemo.v2.ui.pages.newbill.NewBillSearchScanRow
import com.retaildemo.v2.ui.pages.newbill.NewBillSettleBar
import com.retaildemo.v2.ui.pages.newbill.NewBillTopBar
import com.retaildemo.v2.ui.pages.newbill.NoCodeDialog
import com.retaildemo.v2.ui.pages.newbill.TextCandidateDialog
import com.retaildemo.v2.ui.pages.newbill.applyPadKey
import com.retaildemo.v2.ui.viewmodel.NewBillViewModel

/**
 * 快速开单记账页（U2 v2 视觉，契约=DESIGN.md v6）。
 * 与查价页同一设计语系：无色块页头（返回+标题）、客户卡含收款无边框胶囊、橙描边搜索框+独立扫码方块、
 * 货品行瓷片锚点+胶囊步进器、底部合计栏大字金额+橙色主按钮。
 * 1. 软键盘防遮挡 (imePadding)。2. 扫码一键加入购物车。3. 保存即挂账，空闲时调码录入管家婆。
 * 业务逻辑（草稿/收款/发票/毛利显示/滑动删除/待补码）零改动。
 *
 * ## 2026-09-22 TASK-02 巨石解耦
 * 原 1,123 行单文件按组件拆到 `ui/pages/newbill/`，本页只留**编排**（状态收集 + 意图分发）：
 *  · [NewBillTopBar] / [NewBillScanBanner] / [NewBillDraftBanner] / [NewBillSearchScanRow] /
 *    [NewBillCustomerPickerDialog] —— 页头与快捷动作（客户选择、连扫、搜索、识文字）
 *  · [NewBillCartList] —— 单容器货筐清单（滚动只这一层）
 *  · [NewBillCartTotalRow] / [NewBillPriceEditKeypad] / [NewBillSettleBar] —— 键盘与结算
 *  · [NewBillMergeIntoBillDialog] —— U5 并入挂账单
 * 视觉与几何逐值平移，未做任何"顺手改一下"的重排。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewBillPage(
    onBack: () -> Unit,
    onBillSaved: (String) -> Unit = { onBack() },
    viewModel: NewBillViewModel = hiltViewModel(),
) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.results.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val context = LocalContext.current
    val cart by viewModel.cart.collectAsState()
    val draftInfo by viewModel.draftInfo.collectAsState()
    val customerName by viewModel.customerName.collectAsState()
    val customers by viewModel.customers.collectAsState(initial = emptyList())
    /** S19：选客户弹窗的「最近记账」快捷行（弹窗打开时刷新一次）。 */
    val recentCustomerNames by viewModel.recentCustomerNames.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val savedId by viewModel.saved.collectAsState()
    val textSearch by viewModel.textSearch.collectAsState()
    val pendingBills by viewModel.pendingBills.collectAsState(initial = emptyList())
    // B15 一步出库：收款方式 + 顺手拍凭证（保存面板双按钮用）
    val payMethod by viewModel.payMethod.collectAsState()
    val receiptPhoto by viewModel.receiptPhoto.collectAsState()

    // B15 顺手拍凭证：相册选图 → 存私有目录 → 只存文件名，保存时随单落库
    val receiptPhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val bmp = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            if (bmp != null) {
                viewModel.setReceiptPhoto(
                    com.retaildemo.v2.data.repo.ReceiptPhotoStore.saveBitmap(
                        context, "newbill", bmp,
                    ),
                )
                android.widget.Toast.makeText(context, "凭证已添加", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(context, "图片读取失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    var editIndex by remember { mutableStateOf(-1) }
    /** docked 键盘编辑中的单价文本（02 稿：行内价格框 + 贴底键盘，替代旧改价弹窗）。 */
    var editPrice by remember { mutableStateOf("") }
    var showCustomDialog by remember { mutableStateOf(false) }
    var showCustomerDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var sheetProduct by remember { mutableStateOf<ChatContext?>(null) }

    // 退出自动存草稿（编辑态先由下面的 BackHandler 接管）
    BackHandler(enabled = editIndex < 0 && cart.isNotEmpty()) {
        viewModel.saveDraft()
        onBack()
    }
    // 编辑态：返回=取消编辑（不落到存草稿）
    BackHandler(enabled = editIndex >= 0) { editIndex = -1 }

    // 扫码直录（连续模式：巴枪范式，扫完点「完成」批量回传；单件也走这条路）
    // handover=true → 扫码页取景框下方挂流式交接清单（扫码交接台）
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val codes = if (result.resultCode == Activity.RESULT_OK) {
            parseScanResult(result.data)
        } else {
            emptyList()
        }
        // 交接台底栏按的是「确定 · 保存账单」：入完单**紧接着就把账单存了**，
        // 不再让店员回本页再点一次「保存挂账」（老板令 2026-09-26，一屏到底）。
        // 非交接台的旧调用点（查价 / 货位绑定 / 连扫入格…）不带这个标志，行为一字不变。
        val saveNow = result.data
            ?.getBooleanExtra(ScannerActivity.EXTRA_SAVE_BILL, false) == true
        if (codes.isNotEmpty()) {
            ScanFeedback.success(context)
            viewModel.scanBatch(codes, saveAfterScan = saveNow)
        } else {
            // 零反馈是硬伤：没扫到也要嘀一声不一样的声，别让店员以为卡住了。
            ScanFeedback.error(context)
        }
    }

    // 文字识别（手写码/手写商品名/印刷标签）：回传识别文字 → 本地搜索候选。
    val textScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(ScannerActivity.EXTRA_TEXT)
        } else {
            null
        }
        if (!text.isNullOrBlank()) {
            viewModel.searchByText(text.trim())
            // 语义：输入捕获成功（扫码/语音/文本命中同一语义角色）
            ScanFeedback.play(context, ScanFeedback.Sfx.SCAN_OK)
        } else {
            // 语义纠偏：扫了个空=「空结果」而不是「扫码失败」——前者是"没有"，后者是"坏了"
            ScanFeedback.play(context, ScanFeedback.Sfx.EMPTY)
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it.text, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    LaunchedEffect(savedId) {
        val id = savedId
        if (id != null) {
            viewModel.consumeSaved()
            onBillSaved(id)
        }
    }

    Scaffold(
        topBar = {
            NewBillTopBar(
                customerName = customerName,
                onBack = onBack,
                onPickCustomer = { showCustomerDialog = true },
            )
        },
        bottomBar = {
            // 02 稿 P1：编辑态贴底九宫格键盘（替代旧改价弹窗）；完成=回写单价，取消=放弃
            if (editIndex in cart.indices) {
                NewBillPriceEditKeypad(
                    name = cart[editIndex].name,
                    cost = cart[editIndex].costPriceSnap ?: cart[editIndex].costPrice,
                    text = editPrice,
                    onCancel = { editIndex = -1 },
                    onKey = { editPrice = applyPadKey(editPrice, it) },
                    onDone = {
                        editPrice.toDoubleOrNull()?.let { viewModel.updatePrice(editIndex, it) }
                        editIndex = -1
                    },
                )
            } else {
                // B15 收款行：出库按钮的口径来源——店员收钱时顺手选收款方式（+可拍凭证）
                if (cart.isNotEmpty()) {
                    NewBillPayRow(
                        payMethod = payMethod,
                        onPayChange = { viewModel.setPayMethod(it) },
                        receiptPhoto = receiptPhoto,
                        onPickPhoto = { receiptPhotoLauncher.launch("image/*") },
                    )
                }
                NewBillSettleBar(
                    totalQty = viewModel.totalQty,
                    totalAmount = viewModel.totalAmount,
                    canMerge = cart.isNotEmpty() && pendingBills.isNotEmpty(),
                    canReady = cart.isNotEmpty() && payMethod != null,
                    onMerge = { showMergeDialog = true },
                    onSave = { viewModel.saveBill(readyAfterSave = false) },
                    onSaveReady = {
                        viewModel.saveBill(
                            readyAfterSave = true,
                            onResult = { ok, missing ->
                                val msg = when {
                                    ok && missing == null -> "已标待出库，收银端可领"
                                    !ok && missing != null -> "$missing——已保存为挂账"
                                    else -> "已保存为挂账"
                                }
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            },
                        )
                    },
                )
            }
        },
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            if (cart.isNotEmpty()) {
                NewBillScanBanner(
                    totalQty = viewModel.totalQty,
                    onScan = {
                        scanLauncher.launch(
                            ScannerActivity.intent(
                                context,
                                continuous = true,
                                handover = true,
                                // 画布 M 帧「单据条-客户」：交接台顶上看得到客户名
                                customer = viewModel.customerName.value,
                            ),
                        )
                    },
                )
                NewBillCartTotalRow(
                    totalQty = viewModel.totalQty,
                    kinds = cart.size,
                    totalAmount = viewModel.totalAmount,
                )
                Spacer(Modifier.height(4.dp))
            }

            // 草稿恢复横幅
            draftInfo?.let { d ->
                NewBillDraftBanner(
                    draft = d,
                    onRestore = { viewModel.restoreDraft() },
                    onDiscard = { viewModel.discardDraft() },
                )
                Spacer(Modifier.height(8.dp))
            }

            // 搜索与扫码录单条：未改价且在搜或购物车为空时展示
            if (editIndex !in cart.indices) {
                NewBillSearchScanRow(
                    query = query,
                    onQueryChange = { viewModel.onQueryChange(it) },
                    onScan = {
                        scanLauncher.launch(
                            ScannerActivity.intent(
                                context,
                                continuous = true,
                                handover = true,
                                // 画布 M 帧「单据条-客户」：交接台顶上看得到客户名
                                customer = viewModel.customerName.value,
                            ),
                        )
                    },
                    onTextScan = {
                        textScanLauncher.launch(ScannerActivity.intent(context, mode = ScannerActivity.MODE_TEXT))
                    },
                )
            }

            Spacer(Modifier.height(4.dp))

            NewBillCartList(
                query = query,
                searching = searching,
                results = results,
                cart = cart,
                editIndex = editIndex,
                editPrice = editPrice,
                onAddHit = { viewModel.addToCart(it) },
                onEditPrice = { index ->
                    if (editIndex != index) {
                        editIndex = index
                        cart.getOrNull(index)?.let { editPrice = "%.2f".format(it.price) }
                    }
                },
                onIncrease = { index -> cart.getOrNull(index)?.let { viewModel.updateQty(index, it.qty + 1) } },
                onDecrease = { index ->
                    val line = cart.getOrNull(index)
                    if (line != null && line.qty > 1) {
                        viewModel.updateQty(index, line.qty - 1)
                    } else if (line != null) {
                        if (editIndex == index) editIndex = -1
                        viewModel.removeAt(index)
                    }
                },
                onRemove = { index ->
                    if (editIndex == index) editIndex = -1
                    viewModel.removeAt(index)
                },
                onLongPress = { line ->
                    sheetProduct = ChatContext(sku = line.sku, name = line.name, spec = line.spec)
                },
                onAddNoCode = { showCustomDialog = true },
            )
        }
    }

    // 自定义/串货弹窗
    if (showCustomDialog) {
        NoCodeDialog(
            defaultName = query.trim(),
            onDismiss = { showCustomDialog = false },
            onConfirm = { type, name, spec, priceText, qty, costText, vendor, stradePriceText, voucher, sku ->
                // 串货五项必填（2026-09-10 老板口径）：校验不过弹窗不关，防丢已填参数
                val err = viewModel.addNoCodeItem(type, name, spec, priceText, qty, costText, vendor, stradePriceText, voucher, sku)
                if (err == null) showCustomDialog = false
            },
        )
    }

    // U5：文字识别结果 → 候选商品点选入单
    textSearch?.let { search ->
        TextCandidateDialog(
            result = search,
            onPick = { product ->
                viewModel.addProduct(product)
                viewModel.clearTextSearch()
                // 语义纠偏：候选点选入单=商品入列
                ScanFeedback.play(context, ScanFeedback.Sfx.ITEM_ADD)
            },
            onDismiss = { viewModel.clearTextSearch() },
        )
    }

    // U5：并入已有挂账单选择弹窗（原扫码筐「追加并入」能力）
    if (showMergeDialog) {
        NewBillMergeIntoBillDialog(
            totalQty = viewModel.totalQty,
            pendingBills = pendingBills,
            onPick = { billId ->
                showMergeDialog = false
                viewModel.mergeInto(billId)
                ScanFeedback.success(context)
            },
            onDismiss = { showMergeDialog = false },
        )
    }

    // I6 知识浮窗
    sheetProduct?.let { ctx ->
        KnowledgeFloatSheet(
            product = ctx,
            onDismiss = { sheetProduct = null },
        )
    }

    // 客户选择弹窗（S19 重做：搜索姓名/拼音缩写/电话 + 最近记账快捷 + 散客置顶 + 搜不到就地新建）
    if (showCustomerDialog) {
        NewBillCustomerPickerDialog(
            customers = customers,
            recentNames = recentCustomerNames,
            onPick = { viewModel.setCustomer(it) },
            onCreateAndPick = { viewModel.pickCustomer(it) },
            onLoadRecent = { viewModel.loadRecentCustomers() },
            onDismiss = { showCustomerDialog = false },
        )
    }
}
