package com.retaildemo.v2.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.ui.components.HoldToReveal
import com.retaildemo.v2.ui.components.NumberField
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.PrimaryActionButton
import com.retaildemo.v2.ui.components.QrOverlay
import com.retaildemo.v2.ui.components.castRaised
import com.retaildemo.v2.ui.components.directTap
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import com.retaildemo.v2.ui.viewmodel.BillDetailViewModel
import com.retaildemo.v2.ui.window.SanxAlertDialog
import com.retaildemo.v2.ui.components.ScanFeedback
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.repo.ReceiptPhotoStore
import com.retaildemo.v2.data.repo.BillDetailUi
import com.retaildemo.v2.data.repo.uniqueProductKeys
import com.retaildemo.v2.ui.pages.bills.FillMissingSkuDialog
import com.retaildemo.v2.ui.pages.newbill.NoCodeDialog

/** 账单详情：编辑行 / 删行 / 继续加货 / 实收减免 / 出二维码 / 对账分享。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillDetailPage(onBack: () -> Unit, onOpenBill: (String) -> Unit = {}, viewModel: BillDetailViewModel = hiltViewModel()) {
    val bill by viewModel.bill.collectAsState()
    val referencedBills by viewModel.referencedBills.collectAsState()
    val customerDebt by viewModel.customerDebt.collectAsState()
    val customer by viewModel.customer.collectAsState()
    val lastVisit by viewModel.lastVisit.collectAsState()
    val movements by viewModel.movements.collectAsState()
    val context = LocalContext.current

    var editIndex by remember { mutableStateOf(-1) }
    var showAdd by remember { mutableStateOf(false) }
    var showPaidDialog by remember { mutableStateOf(false) }
    var qrIndex by remember { mutableStateOf(-1) }
    var showPhotoFull by remember { mutableStateOf(false) }
    var showCollabDialog by remember { mutableStateOf(false) }
    var collabSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showPayMethodDialog by remember { mutableStateOf(false) }
    var showFillSkuDialog by remember { mutableStateOf(false) }
    var tempPayMethod by remember { mutableStateOf<String?>(null) }
    var tempNote by remember { mutableStateOf("") }

    // 继续加货与无码/串货状态
    var showNoCodeDialog by remember { mutableStateOf(false) }
    var addQuery by remember { mutableStateOf("") }
    var addResults by remember { mutableStateOf<List<ProductEntity>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // B4: 临时码已回写正式码的联查缓存（tempCode → 别名），渲染时命中即显示正式码
    val aliasMap = remember { mutableStateMapOf<String, com.retaildemo.v2.data.remote.AliasResolveDto>() }
    LaunchedEffect(bill?.items) {
        (bill?.items ?: emptyList())
            .mapNotNull { it.tempCode }
            .distinct()
            .filter { it !in aliasMap }
            .forEach { code -> viewModel.aliasOf(code)?.let { aliasMap[code] = it } }
    }

    LaunchedEffect(addQuery) {
        val q = addQuery.trim()
        if (q.isBlank()) {
            addResults = emptyList()
            isSearching = false
        } else {
            isSearching = true
            kotlinx.coroutines.delay(200)
            addResults = viewModel.searchProducts(q)
            isSearching = false
        }
    }

    // B2 小票：相册选图 → 存 APP 私有目录 → 回写文件名到 receiptPhoto（相机 FileProvider 后续真机补）
    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val bmp = runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) {
                val name = ReceiptPhotoStore.saveBitmap(context, viewModel.billId, bmp)
                viewModel.setReceiptPhoto(name)
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val barcode = result.data?.getStringExtra(ScannerActivity.EXTRA_BARCODE)
        if (!barcode.isNullOrBlank()) {
            // 语义纠偏：这里发生的是「加货入本单」=商品入列，不是"扫到了"
            ScanFeedback.play(context, ScanFeedback.Sfx.ITEM_ADD)
            viewModel.addByBarcode(barcode.trim(), onMiss = { missed ->
                addQuery = missed
                showNoCodeDialog = true
                android.widget.Toast.makeText(context, "店内未找到条码 $missed，请录入串货/无码货", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
    }

    val currentBill = bill
    Scaffold(
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextMain)
                }
                Column {
                    // 03 稿：标题语义化「单据详情」，副标=客户·时间·状态（替代裸单号）
                    Text("单据详情", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    Text(
                        currentBill?.let { b ->
                            val time = b.createdAt.take(16).dropWhile { it == ' ' }.substringAfter(' ')
                            val stageLabel = when {
                                b.status != "pending" -> "已出库"
                                b.stage == BillEntity.STAGE_READY -> "待出库"
                                else -> "挂账"
                            }
                            "${b.customerName} · $time $stageLabel"
                        } ?: "",
                        fontSize = 11.sp,
                        color = TextWeak,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        bottomBar = {
            // 03 稿底栏：合计墨色（公理 A2：操作面只留橙色一个信号源）+ castRaised「管家婆扫枪录单」
            // B12：出码从主路径摘除——底栏 QR 按钮**只在待出库单**保留（兜底：插件不通时还能扫枪）
            if (currentBill != null && currentBill.items.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Design.CardSurface)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Column {
                        Text("合计 ${currentBill.items.size} 件", fontSize = 10.sp, color = TextWeak)
                        PriceText(
                            price = currentBill.totalAmount,
                            integerSize = 24.sp,
                            decimalSize = 13.sp,
                            symbolSize = 13.sp,
                            color = TextMain,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (currentBill.status == "pending" && currentBill.stage == BillEntity.STAGE_READY) {
                        Box(
                            modifier = Modifier
                                .castRaised(shape = RoundedCornerShape(Design.ButtonRadius.dp), fill = Primary)
                                .directTap(
                                    sfx = ScanFeedback.Sfx.KEY,
                                    enabled = currentBill.items.isNotEmpty(),
                                    onClick = { qrIndex = 0 },
                                )
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.QrCode2, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("扫码出码兜底", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        val b = bill ?: return@Scaffold
        // 03 稿：本单数据就绪后拉取首个未录商品的出入库流水
        LaunchedEffect(b.id, b.items.size) { viewModel.loadMovements() }
        // B12 三件齐全性实时判据：挂账单「补全三件」卡的置灰与提示都吃这个值（字段变即重算）
        val missingForReady by produceState<String?>(
            initialValue = null,
            b.stage, b.items.size, b.payMethod, b.receiptPhoto,
        ) { value = viewModel.missingForReady() }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                // 更多操作展开/清单行多时内容超屏：整页可滚（此前无滚动，展开态商品清单够不着）
                .verticalScroll(rememberScrollState()),
        ) {
            // 03 稿：管家婆录入进度卡（B12：出码退居兜底，仅**待出库单**显示；挂账单主路径是补全三件）
            val recordedCount = b.items.count { it.recorded }
            if (b.status == "pending" && b.stage == BillEntity.STAGE_READY && b.items.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(Design.CardRadius.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.sxElevation(SxElevation.L2),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "管家婆录入进度（兜底）",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMain,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "已录 ",
                                fontSize = 11.sp,
                                color = TextWeak,
                            )
                            Text(
                                "$recordedCount",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (recordedCount > 0) Primary else TextWeak,
                            )
                            Text(
                                " / ${b.items.size} 件",
                                fontSize = 11.sp,
                                color = TextWeak,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(SurfaceSubtle),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(recordedCount.toFloat() / b.items.size)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Primary),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "收银端插件不通时才走这条路：拿手机到收银机前，点商品左侧方块逐条出码，用扫枪扫进管家婆",
                            fontSize = 11.sp,
                            color = TextWeak,
                            lineHeight = 16.sp,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // B15：待出库单漏拍凭证 → 轻横幅提醒 + 就地补拍（不挡出库，打烊前补齐）
            if (b.status == "pending" && b.stage == BillEntity.STAGE_READY && b.receiptPhoto.isNullOrBlank()) {
                Card(
                    shape = RoundedCornerShape(Design.CardRadius.dp),
                    colors = CardDefaults.cardColors(containerColor = WarningLight),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .sxElevation(SxElevation.L2),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            "这张待出库单还没拍凭证",
                            fontSize = 12.sp,
                            color = Warning,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { photoLauncher.launch("image/*") },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text("补拍凭证", fontSize = 11.sp, color = Primary)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // B12：挂账单的「补全三件 + 标待出库」卡（出门硬闸——缺一件标不了）
            if (b.status == "pending" && b.stage == BillEntity.STAGE_DRAFT) {
                MissingForReadyCard(
                    bill = b,
                    missing = missingForReady,
                    onMarkReady = {
                        viewModel.markReady { ok, msg ->
                            android.widget.Toast.makeText(
                                context,
                                when {
                                    ok -> "已标待出库，收银端可领"
                                    else -> msg ?: "暂时无法标待出库"
                                },
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onPickPayMethod = {
                        tempPayMethod = b.payMethod
                        tempNote = b.note
                        showPayMethodDialog = true
                    },
                    onPickPhoto = { photoLauncher.launch("image/*") },
                )
                Spacer(Modifier.height(8.dp))
            }
            // 03 稿：客户卡（姓名 + 电话·信用额度 + 上次拿货 + 累计欠款）
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.sxElevation(SxElevation.L2),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(b.customerName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain)
                        val meta = listOfNotNull(
                            customer?.phone?.takeIf { it.isNotBlank() },
                            customer?.creditLimit?.takeIf { it > 0 }?.let { "信用额度 ¥%.0f".format(it) },
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(
                                meta,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        lastVisit?.let {
                            Text(
                                "上次拿货：$it",
                                fontSize = 11.sp,
                                color = TextWeak,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    if (customerDebt > 0) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("累计欠款", fontSize = 10.sp, color = TextWeak)
                            // 03 稿：欠款为三段式红价（¥ 小 + 整数大 + 小数小），非平铺 Text
                            PriceText(
                                price = customerDebt,
                                color = Danger,
                                integerSize = 16.sp,
                                symbolSize = 11.sp,
                                decimalSize = 12.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 03 稿：次要操作折叠（收款/小票/发票/记账人/实收/加货 收进抽屉，主面留给录单）
            var showOps by remember { mutableStateOf(false) }
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2).clickable { showOps = !showOps },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        "更多操作（收款 · 发票 · 记账人 · 实收 · 加货）",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (showOps) "收起 ▲" else "展开 ▼",
                        fontSize = 12.sp,
                        color = Primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            AnimatedVisibility(visible = showOps) {
                Column {
            // 汇总卡
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.sxElevation(SxElevation.L2),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (b.direction == "return") {
                            Text(
                                "退",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier
                                    .background(Danger, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(b.customerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            if (b.status == "pending") "挂账 (未出库)" else "已出库",
                            color = if (b.status == "pending") Primary else Success,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable {
                                if (b.status == "pending") {
                                    if (!viewModel.canMarkPaid(b)) {
                                        showFillSkuDialog = true
                                    } else {
                                        viewModel.toggleStatus()
                                    }
                                } else {
                                    viewModel.toggleStatus()
                                }
                            },
                        )
                    }
                    if (b.refBillId != null) {
                        Text(
                            "退货关联原单：${b.refBillId}",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable { onOpenBill(b.refBillId) },
                        )
                    }
                    if (referencedBills.isNotEmpty()) {
                        referencedBills.forEach { ref ->
                            Text(
                                "被退记录：${ref.id}（${ref.createdAt.take(10)} ¥%.2f）".format(ref.totalAmount),
                                fontSize = 11.sp,
                                color = Danger,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { onOpenBill(ref.id) },
                            )
                        }
                    }
                    if (!b.billCode.isNullOrBlank()) {
                        Text(
                            "单据编号：${b.billCode}",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (!b.orderNo.isNullOrBlank()) {
                        Text(
                            "订单号：${b.orderNo}",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("合计", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Spacer(Modifier.height(2.dp))
                            PriceText(
                                price = b.totalAmount,
                                integerSize = 26.sp,
                                decimalSize = 14.sp,
                                symbolSize = 14.sp,
                                color = TextMain, // 03 稿/公理 A2：合计条金额改墨色，操作面只留橙色信号
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                        OutlinedButton(onClick = { showPaidDialog = true }) {
                            Text(
                                if (b.paidAmount != null) "实收 ¥%.2f".format(b.paidAmount) else "记实收/减免",
                                color = Primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        OutlinedButton(onClick = { showAdd = true }) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.width(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("继续加货")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            scanLauncher.launch(ScannerActivity.intent(context))
                        }) {
                            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.width(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("扫码加货")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            val text = viewModel.buildShareText()
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "分享账单"))
                        }) {
                            Icon(Icons.Default.Share, "分享账单", tint = Primary)
                        }
                    }
                    if (b.status == "pending") {
                        Spacer(Modifier.height(8.dp))
                        PrimaryActionButton(
                            text = "标记已录入管家婆 (出库)",
                            icon = Icons.Default.Check,
                            containerColor = Success,
                            contentColor = Color.White,
                            sfx = ScanFeedback.Sfx.COMMIT_OK,
                            height = 44.dp,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (!viewModel.canMarkPaid(b)) {
                                    showFillSkuDialog = true
                                } else {
                                    viewModel.toggleStatus()
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // B2 收款方式 + 小票凭据
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.sxElevation(SxElevation.L2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(14.dp),
                ) {
                    val isUnselected = b.payMethod.isNullOrBlank()
                    val payLabel = if (isUnselected) "待选 (出库必选)" else BillEntity.payMethodLabel(b.payMethod)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                tempPayMethod = b.payMethod
                                tempNote = b.note
                                showPayMethodDialog = true
                            },
                    ) {
                        Text("收款：", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text(
                            payLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isUnselected) Danger else TextMain,
                        )
                        if (b.payMethod == BillEntity.PAY_METHOD_POS && b.receiptPhoto.isNullOrBlank()) {
                            Spacer(Modifier.width(4.dp))
                            Text("待传小票", fontSize = 11.sp, color = Danger, fontWeight = FontWeight.Bold)
                        }
                        if (b.note.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text("（${b.note}）", style = MaterialTheme.typography.bodySmall, color = Primary, maxLines = 1)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("✎", fontSize = 12.sp, color = Primary)
                    }
                    if (b.receiptPhoto != null) {
                        val bmp = remember(b.receiptPhoto) {
                            runCatching {
                                BitmapFactory.decodeFile(ReceiptPhotoStore.path(context, b.receiptPhoto))
                            }.getOrNull()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "小票",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(52.dp).height(52.dp)
                                    .clickable { showPhotoFull = true },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        OutlinedButton(onClick = { photoLauncher.launch("image/*") }) { Text("重拍小票") }
                    } else {
                        OutlinedButton(onClick = { photoLauncher.launch("image/*") }) { Text("拍小票") }
                    }
                }
            }

            // B5 需开发票标记（现结制只打标记，不做开票流程）
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).sxElevation(SxElevation.L2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Text("需开发票", style = MaterialTheme.typography.bodyMedium, color = TextMain, modifier = Modifier.weight(1f))
                    Switch(checked = b.invoiceNeeded, onCheckedChange = { viewModel.setInvoiceNeeded(it) })
                }
            }

            // I1 记账人 + 协同人（主记账人=staff，协同可多选）
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).sxElevation(SxElevation.L2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("记账人：${b.staff.ifBlank { "店员" }}", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                        val collab = b.collaborators?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                        Text(
                            if (collab.isNullOrEmpty()) "协同：无" else "协同：${collab.joinToString("、")}",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                    }
                    TextButton(onClick = {
                        collabSelected = (b.collaborators?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()).toSet()
                        showCollabDialog = true
                    }) { Text("编辑协同") }
                }
            }
            } // 折叠区 Column 结束
            } // AnimatedVisibility：更多操作折叠区结束

            if (showPhotoFull && b.receiptPhoto != null) {
                val big = runCatching {
                    BitmapFactory.decodeFile(ReceiptPhotoStore.path(context, b.receiptPhoto))
                }.getOrNull()
                Dialog(
                    onDismissRequest = { showPhotoFull = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    if (big != null) {
                        Image(
                            bitmap = big.asImageBitmap(),
                            contentDescription = "小票大图",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().clickable { showPhotoFull = false },
                        )
                    }
                }
            }

            // I1 协同人多选弹层（候选=本机店员名单）
            if (showCollabDialog) {
                SanxAlertDialog(
                    onDismissRequest = { showCollabDialog = false },
                    title = { Text("选择协同人", style = MaterialTheme.typography.titleSmall) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val candidates = viewModel.staffList
                            if (candidates.isEmpty()) {
                                Text("店员名单为空——请先在「设置 → 店员名单」添加", fontSize = 13.sp, color = TextSecondary)
                            } else {
                                candidates.forEach { s ->
                                    val on = s in collabSelected
                                    TextButton(
                                        onClick = {
                                            collabSelected = if (on) collabSelected - s else collabSelected + s
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(if (on) "✓ $s" else "　$s", color = if (on) Primary else Color.Unspecified)
                                    }
                                }
                            }
                        }
                    },
                    confirmText = "保存",
                    onConfirm = {
                        viewModel.setCollaborators(collabSelected.toList())
                        showCollabDialog = false
                    },
                    dismissText = "取消",
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // B12：出码退居兜底——标题只在待出库单还提「方块出码」，挂账单不再诱导这条路径
                    if (b.status == "pending" && b.stage == BillEntity.STAGE_READY) {
                        "商品清单 · 点左侧方块出码（兜底）"
                    } else {
                        "商品清单"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain,
                    modifier = Modifier.weight(1f),
                )
                Text("共 ${b.items.size} 行", fontSize = 11.sp, color = TextWeak)
            }
            Spacer(Modifier.height(8.dp))

            // 03 稿（3:6 AST）：每行独立卡 rx14 白底 L2，卡距 8（画布 y=369/443/517 → pitch 74 = 66 行高 + 8）；
            // 单据行数少，随整页滚动
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                b.items.forEachIndexed { idx, item ->
                    // 行身份 key = 位置+内容：删除行后相邻行的 key 必然变化，
                    // dismiss 状态强制重建，杜绝「删一行连环清空」的错位复用
                    // （实测教训：key(idx) 或无 key 时，一次滑动可清空两行）。
                    key("${idx}_${item.name}|${item.barcode}|${item.qty}|${item.price}") {
                    val rec = item.recorded
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.deleteItem(idx)
                                true
                            } else false
                        },
                    )
                    // 3:6：每行独立卡（rx14 白底 L2）；卡白底同时遮住左滑删除的红底
                    Card(
                        shape = RoundedCornerShape(Design.CardRadius.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
                    ) {
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Danger),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(end = 16.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "删除",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Design.CardSurface) // 不透明底：遮住滑动删除的红色背景层
                                .clickable { editIndex = idx }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            // 出码方块（B12：仅待出库单保留作兜底；挂账单隐藏，不引导扫码路径）
                            if (b.status == "pending" && b.stage == BillEntity.STAGE_READY) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (rec) SuccessLight else PrimaryLight,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable { qrIndex = idx },
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (rec) Icons.Default.Check else Icons.Default.QrCode2,
                                            if (rec) "已录 ${item.name}" else "出二维码 ${item.name}",
                                            tint = if (rec) Success else Primary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (item.tempCode != null) {
                                        Spacer(Modifier.width(6.dp))
                                        androidx.compose.material3.Surface(
                                            color = if (aliasMap.containsKey(item.tempCode)) SuccessLight else WarningLight,
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                if (aliasMap.containsKey(item.tempCode)) "已回写" else "待补码",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (aliasMap.containsKey(item.tempCode)) Success else Warning,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                if (item.tempCode != null) {
                                    Text(
                                        item.tempCode,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextSecondary,
                                    )
                                    aliasMap[item.tempCode]?.let { alias ->
                                        Text(
                                            "正式码 ${alias.sku}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Success,
                                        )
                                    }
                                } else if (item.barcode.isNotBlank()) {
                                    Text(
                                        item.barcode,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextWeak,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                item.itemNote?.takeIf { it.isNotBlank() }?.let { note ->
                                    Text(
                                        "备注：$note",
                                        fontSize = 11.sp,
                                        color = TextWeak,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                val cost = item.costPriceSnap ?: item.costPrice
                                if (cost > 0.0) {
                                    HoldToReveal(
                                        holdMs = 400,
                                        masked = {
                                            Text("成本 •••", fontSize = 11.sp, color = TextWeak)
                                        },
                                        revealed = {
                                            Text("成本 ¥%.2f".format(cost), fontSize = 11.sp, color = TextSecondary)
                                        },
                                    )
                                } else {
                                    Text("成本 未定价", fontSize = 11.sp, color = TextWeak)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                PriceText(
                                    price = item.subtotal,
                                    integerSize = 15.sp,
                                    decimalSize = 12.sp,
                                    symbolSize = 12.sp,
                                    color = Danger,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (rec) "已录 · ×${item.qty} @¥%.2f".format(item.price) else "×${item.qty} @¥%.2f".format(item.price),
                                    fontSize = 11.sp,
                                    fontWeight = if (rec) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (rec) Success else TextSecondary,
                                )
                            }
                        }
                    }
                    }
                    }
                }
            }
            Spacer(Modifier.height(16.dp).navigationBarsPadding())

            // 03 稿：本商品最近流水（首个未录商品；库存服务 movements 实时查询，失败静默隐藏）
            if (movements.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("本商品最近流水", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                Spacer(Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(Design.CardRadius.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.sxElevation(SxElevation.L2),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        val top = movements.take(3)
                        top.forEachIndexed { mIdx, m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 8.dp),
                            ) {
                                Text(
                                    text = "${m.created_at?.take(16)?.replace('T', ' ') ?: ""} · ${if (m.change_qty < 0) "出库" else "入库"}${if (m.operator.isNullOrBlank()) "" else " · ${m.operator}"}",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = (if (m.change_qty > 0) "+" else "") + "${m.change_qty} 件",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (m.change_qty > 0) Success else Danger,
                                )
                            }
                            if (mIdx < top.lastIndex) {
                                HorizontalDivider(thickness = 0.5.dp, color = Border)
                            }
                        }
                    }
                }
            }
        }
    }

    // 编辑行
    if (editIndex >= 0) {
        val item = bill?.items?.getOrNull(editIndex)
        if (item != null) {
            var qtyText by remember { mutableStateOf(item.qty.toString()) }
            var priceText by remember { mutableStateOf("%.2f".format(item.price)) }
            var unitText by remember { mutableStateOf(item.unit) }
            var noteText by remember { mutableStateOf(item.itemNote ?: "") }
            var skuText by remember { mutableStateOf(item.sku ?: "") }
            SanxAlertDialog(
                onDismissRequest = { editIndex = -1 },
                title = { Text("编辑 — ${item.name}", style = MaterialTheme.typography.titleSmall, maxLines = 2) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = skuText,
                            onValueChange = { skuText = it },
                            label = { Text("商品编号（管家婆编码）") },
                            singleLine = true,
                        )
                        NumberField(
                            value = qtyText,
                            onValueChange = { qtyText = it },
                            label = "数量",
                            decimals = false,
                        )
                        NumberField(
                            value = priceText,
                            onValueChange = { priceText = it },
                            label = "单价",
                        )
                        OutlinedTextField(
                            value = unitText,
                            onValueChange = { unitText = it },
                            label = { Text("单位") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            label = { Text("备注") },
                            singleLine = true,
                        )
                    }
                },
                confirmText = "保存",
                confirmEnabled = (qtyText.toIntOrNull() ?: 0) >= 1 &&
                    (priceText.toDoubleOrNull() ?: -1.0) >= 0,
                onConfirm = {
                    val qty = qtyText.toIntOrNull() ?: 0
                    val price = priceText.toDoubleOrNull() ?: -1.0
                    if (qty >= 1 && price >= 0) {
                        viewModel.updateItem(
                            index = editIndex,
                            qty = qty,
                            price = price,
                            unit = unitText,
                            itemNote = noteText,
                            sku = skuText.trim().ifBlank { null },
                        )
                        editIndex = -1
                    }
                },
                dismissText = "取消",
            )
        }
    }

    // 实收减免
    if (showPaidDialog) {
        var paidText by remember { mutableStateOf(bill?.paidAmount?.let { "%.2f".format(it) } ?: "") }
        val total = bill?.totalAmount ?: 0.0
        SanxAlertDialog(
            onDismissRequest = { showPaidDialog = false },
            title = { Text("实收金额（留空 = 全额）", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    NumberField(
                        value = paidText,
                        onValueChange = { paidText = it },
                        label = "实收",
                    )
                    Spacer(Modifier.height(8.dp))
                    // 抹零快捷键：把零头抹掉（不改变挂账总额，仅记实收）
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { paidText = "%.2f".format(kotlin.math.floor(total)) }) {
                            Text("抹到元 ¥%.0f".format(kotlin.math.floor(total)), fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            paidText = "%.2f".format(kotlin.math.floor(total * 10) / 10)
                        }) {
                            Text("抹到角 ¥%.1f".format(kotlin.math.floor(total * 10) / 10), fontSize = 12.sp)
                        }
                        TextButton(onClick = { paidText = "%.2f".format(total) }) {
                            Text("全额", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmText = "保存",
            onConfirm = {
                viewModel.setPaidAmount(paidText.toDoubleOrNull())
                showPaidDialog = false
            },
            dismissText = "取消",
        )
    }

    // 修改收款方式与备注弹窗
    if (showPayMethodDialog) {
        SanxAlertDialog(
            onDismissRequest = { showPayMethodDialog = false },
            title = { Text("修改收款方式", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf("cash" to "现金", "pos" to "POS", "other" to "备注").forEach { (k, label) ->
                            FilterChip(
                                selected = tempPayMethod == k,
                                onClick = { tempPayMethod = if (tempPayMethod == k) null else k },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PrimaryLight,
                                    selectedLabelColor = Primary,
                                ),
                            )
                        }
                    }
                    if (tempPayMethod == "other") {
                        OutlinedTextField(
                            value = tempNote,
                            onValueChange = { tempNote = it },
                            placeholder = { Text("填写例外收款说明（如：微信转账、客户晚点付等）", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmText = "保存",
            onConfirm = {
                viewModel.updatePayMethod(tempPayMethod, tempNote.trim())
                showPayMethodDialog = false
            },
            dismissText = "取消",
        )
    }

    // 二维码
    if (qrIndex >= 0) {
        bill?.let { b ->
            QrOverlay(
                items = b.items,
                initialIndex = qrIndex,
                onDismiss = { qrIndex = -1 },
                headerLabel = "单据 · ${b.customerName}",
                recordedOf = { idx -> b.items.getOrNull(idx)?.recorded == true },
                onToggleRecorded = if (b.status == BillEntity.STATUS_PENDING) {
                    { idx ->
                        val now = b.items.getOrNull(idx)?.recorded ?: false
                        // 04 稿：最后一件标记后满足校验则自动标记已出库
                        viewModel.setItemRecorded(idx, !now) {
                            qrIndex = -1
                            android.widget.Toast.makeText(context, "已全部录入管家婆，自动标记出库", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else null,
                onMarkPaid = if (b.status == BillEntity.STATUS_PENDING) {
                    {
                        qrIndex = -1
                        if (!viewModel.canMarkPaid(b)) {
                            showFillSkuDialog = true
                        } else {
                            viewModel.toggleStatus()
                        }
                    }
                } else null,
            )
        }
    }

    // 出库前核销拦截确认弹窗（编号补全、强制收款方式、POS强制小票照片）
    if (showFillSkuDialog) {
        bill?.let { b ->
            val missingItems = b.items.mapIndexed { idx, item -> idx to item }.filter { it.second.sku.isBlank() }
            FillMissingSkuDialog(
                billId = b.id,
                currentPayMethod = b.payMethod,
                currentNote = b.note,
                currentReceiptPhoto = b.receiptPhoto,
                missingItems = missingItems,
                onDismiss = { showFillSkuDialog = false },
                onConfirm = { skuMap, payMethod, note, receiptPhoto ->
                    viewModel.fulfillAndMarkPaid(skuMap, payMethod, note, receiptPhoto) { ok ->
                        if (ok) showFillSkuDialog = false
                    }
                },
            )
        }
    }

    // 继续加货弹窗（店内商品搜索 / 扫码 / 串货无码快捷通道）
    if (showAdd) {
        SanxAlertDialog(
            onDismissRequest = {
                showAdd = false
                addQuery = ""
                addResults = emptyList()
            },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("继续加货", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                showAdd = false
                                scanLauncher.launch(ScannerActivity.intent(context))
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("扫码", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                showNoCodeDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("串货/无码", fontSize = 12.sp)
                        }
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 380.dp),
                ) {
                    OutlinedTextField(
                        value = addQuery,
                        onValueChange = { addQuery = it },
                        placeholder = { Text("搜索品名 / 拼音首字母 / 条码", fontSize = 13.sp) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = if (addQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { addQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "清空", modifier = Modifier.size(16.dp))
                                }
                            }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    if (isSearching) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Primary)
                        }
                    } else if (addQuery.isNotBlank() && addResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("未找到店内匹配商品", color = TextSecondary, fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { showNoCodeDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight, contentColor = Primary),
                                ) {
                                    Text("+ 添加为串货或店内无码货", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    } else if (addResults.isNotEmpty()) {
                        // 行 key 唯一化：空条码重名货（如「手套」两条）会让 LazyColumn 撞 key 崩整页
                        val addKeys = remember(addResults) { uniqueProductKeys(addResults) }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            itemsIndexed(addResults, key = { i, _ -> addKeys[i] }) { _, prod ->
                                Card(
                                    shape = RoundedCornerShape(Design.ShapeSm.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sxElevation(SxElevation.L2, shape = RoundedCornerShape(Design.ShapeSm.dp))
                                        .directTap(sfx = ScanFeedback.Sfx.ITEM_ADD) {
                                            viewModel.addProduct(prod)
                                            android.widget.Toast.makeText(context, "已添加：${prod.name}", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(prod.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                prod.spec.orEmpty(),
                                                fontSize = 11.sp,
                                                color = TextSecondary,
                                                maxLines = 1,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        PriceText(price = prod.sellPrice, integerSize = 14.sp, decimalSize = 11.sp, symbolSize = 10.sp, color = Danger)
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.Default.AddCircle, contentDescription = "添加", tint = Primary, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                "输入关键词搜索店内商品，\n或点击右上角扫码 / 登记串货无码货",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                }
            },
            confirmText = "完成",
            onConfirm = {
                showAdd = false
                addQuery = ""
                addResults = emptyList()
            },
        )
    }

    // 串货 / 店内无码货录入弹窗
    if (showNoCodeDialog) {
        NoCodeDialog(
            defaultName = addQuery.trim(),
            onDismiss = { showNoCodeDialog = false },
            onConfirm = { type, name, spec, priceText, qty, costText, vendor, stradePriceText, voucher, sku ->
                val err = viewModel.addNoCodeItem(type, name, spec, priceText, qty, costText, vendor, stradePriceText, voucher, sku)
                if (err != null) {
                    android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // 语义纠偏：无码货录入入单=商品入列
                    ScanFeedback.play(context, ScanFeedback.Sfx.ITEM_ADD)
                    showNoCodeDialog = false
                    showAdd = false
                    addQuery = ""
                    addResults = emptyList()
                    android.widget.Toast.makeText(context, "已加货：$name", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

/**
 * B12「补全三件 + 标待出库」卡（挂账单详情页专用）。
 * 出门硬闸（B15 凭证口径 2026-09-27 老板拍板放宽）：
 * - 串货标注缺时给去串货台账补「在谁家串」的提示（台账页编辑，详情页不内联改档）；
 * - 收款方式缺时给可点的补全按钮（收款弹窗）；
 * - 凭证**可跳过**：没拍也能标待出库，落「待补凭证」清单打烊前补拍——卡片上仍给补拍按钮。
 */
@Composable
private fun MissingForReadyCard(
    bill: BillDetailUi,
    missing: String?,
    onMarkReady: () -> Unit,
    onPickPayMethod: () -> Unit,
    onPickPhoto: () -> Unit,
) {
    val payMissing = bill.payMethod.isNullOrBlank()
    val stradeMissing = missing?.contains("串货标注") == true
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.sxElevation(SxElevation.L2),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "标待出库前 · 补全三件",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "补齐才进全店并同步收银端",
                    fontSize = 10.sp,
                    color = TextWeak,
                    lineHeight = 14.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            MissingRow(
                label = "串货标注",
                done = !stradeMissing,
                doneText = "已标注",
                missingText = "缺「在谁家串」——去串货台账补",
            )
            MissingRow(
                label = "收款方式",
                done = !payMissing,
                doneText = BillEntity.payMethodLabel(bill.payMethod),
                missingText = "未选收款方式",
                actionText = "去补全",
                onAction = onPickPayMethod,
            )
            MissingRow(
                label = "付款凭证",
                done = bill.receiptPhoto != null,
                doneText = "已拍",
                missingText = "可跳过 · 漏拍进待补凭证清单",
                actionText = "拍小票",
                onAction = onPickPhoto,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryActionButton(
                text = "标待出库",
                icon = Icons.Default.Check,
                containerColor = Primary,
                contentColor = Color.White,
                sfx = ScanFeedback.Sfx.COMMIT_OK,
                height = 44.dp,
                enabled = missing == null,
                modifier = Modifier.fillMaxWidth(),
                onClick = onMarkReady,
            )
            if (missing != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    missing,
                    fontSize = 11.sp,
                    color = Danger,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

/** 三件检查行：✓绿勾 + 达成说明，或 ✗红叉 + 缺项说明 +（可选）补全按钮。 */
@Composable
private fun MissingRow(
    label: String,
    done: Boolean,
    doneText: String,
    missingText: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = if (done) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (done) Success else Danger,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
        Spacer(Modifier.width(8.dp))
        Text(
            if (done) doneText else missingText,
            fontSize = 11.sp,
            color = if (done) TextWeak else Danger,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!done && actionText != null && onAction != null) {
            OutlinedButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text(actionText, fontSize = 11.sp, color = Primary)
            }
        }
    }
}
