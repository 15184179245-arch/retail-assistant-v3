package com.retaildemo.v2.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.ui.components.HoldToReveal
import com.retaildemo.v2.ui.components.MaskedPriceText
import com.retaildemo.v2.ui.components.NumberField
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.PrimaryActionButton
import com.retaildemo.v2.ui.components.QrOverlay
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.TopBarStyle
import com.retaildemo.v2.ui.components.generateQrBitmap
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Cyan
import com.retaildemo.v2.ui.theme.CyanDark
import com.retaildemo.v2.ui.theme.CyanLight
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import com.retaildemo.v2.ui.viewmodel.ProductDetailViewModel
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.viewmodel.SaleRecordUi
import com.retaildemo.v2.ui.window.SanxAlertDialog
import com.retaildemo.v2.util.qrContentOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 商品详情（Gemini 重构方案落地）：
 * 商品核心区 → 数据看板（价格/库存/进价/货位）→ 规格条码（二维码预览）→ 最近动态
 * 底部固定操作栏：打印价签 / 记一笔。
 */
@Composable
fun ProductDetailPage(
    onBack: () -> Unit,
    onRecordSale: () -> Unit,
    onOpenProduct: (String) -> Unit = {},
    onKnowledge: () -> Unit = {},
    onOpenBill: (String) -> Unit = {},
    viewModel: ProductDetailViewModel = hiltViewModel(),
) {
    val product by viewModel.product.collectAsState()
    val locations by viewModel.locations.collectAsState()
    val shelfLocations by viewModel.shelfLocations.collectAsState()
    val bindMessage by viewModel.bindMessage.collectAsState()
    val movements by viewModel.movements.collectAsState()
    val similar by viewModel.similar.collectAsState()
    // XZG-API-RO：单件实时库存（管家婆当场值，点刷新才查；失败自动回退本地快照）
    val liveStock by viewModel.liveStock.collectAsState()
    val liveLoading by viewModel.liveLoading.collectAsState()
    val liveMessage by viewModel.liveMessage.collectAsState()
    // XZG-API-RO：改条码（输入 → 一货一码校验 → 人工确认 → 写回）
    val barcodeEditVisible by viewModel.barcodeEditVisible.collectAsState()
    val barcodeInput by viewModel.barcodeInput.collectAsState()
    val barcodeCheck by viewModel.barcodeCheck.collectAsState()
    val barcodeStage by viewModel.barcodeStage.collectAsState()
    val barcodeSyncMessage by viewModel.barcodeSyncMessage.collectAsState()
    val crossSell by viewModel.crossSell.collectAsState()
    val substitutes by viewModel.substitutes.collectAsState()
    val showQr by viewModel.showQr.collectAsState()
    val recentSales by viewModel.recentSales.collectAsState()
    val salesStats by viewModel.salesStats.collectAsState()
    val returnMessage by viewModel.returnMessage.collectAsState()
    val returnedBillId by viewModel.returnedBillId.collectAsState()
    // I4 数据标记闭环 + §11 本地补充
    val markMessage by viewModel.markMessage.collectAsState()
    val printMessage by viewModel.printMessage.collectAsState()
    val supplement by viewModel.supplement.collectAsState()
    // I19 商品照片
    val photos by viewModel.photos.collectAsState()
    val photosOffline by viewModel.photosOffline.collectAsState()
    // I26 图片回传管家婆（受理 → 后台压缩上传写回 → 回读校验为准）
    val imagePushConfirm by viewModel.imagePushConfirm.collectAsState()
    val imagePushMessage by viewModel.imagePushMessage.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(printMessage) {
        printMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearPrintMessage()
        }
    }

    var showBindSheet by remember { mutableStateOf(false) }
    var returnTarget by remember { mutableStateOf<SaleRecordUi?>(null) }
    var returnQty by remember { mutableStateOf("1") }
    var showMarkDialog by remember { mutableStateOf(false) }
    var showSuppDialog by remember { mutableStateOf(false) }
    var showPhotoFull by remember { mutableStateOf(false) }
    // I6 知识浮窗：问问这件货（带 sku/name/spec 上下文）
    var showAiSheet by remember { mutableStateOf(false) }

    // I4 照片：相册选图 → VM 压缩存 supplement/ 私有目录（复用 B2 存储契约，只存文件名）
    var photoFor by remember { mutableStateOf("mark") }
    var markPhoto by remember { mutableStateOf<String?>(null) }
    var suppPhoto by remember { mutableStateOf<String?>(null) }
    // I4 §11 编辑信息表单态（打开时以上游/已补录值预填）
    var markType by remember { mutableStateOf(com.retaildemo.v2.data.local.entity.DataMarkEntity.TYPE_PRICE_WRONG) }
    var markNote by remember { mutableStateOf("") }
    var suppBrand by remember { mutableStateOf("") }
    var suppSpec by remember { mutableStateOf("") }
    var suppNote by remember { mutableStateOf("") }
    var suppBarcode by remember { mutableStateOf("") }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.cacheSupplementPhoto(uri) { name ->
            if (name == null) {
                android.widget.Toast.makeText(context, "图片处理失败", android.widget.Toast.LENGTH_SHORT).show()
            } else if (photoFor == "mark") markPhoto = name else suppPhoto = name
        }
    }

    // I27 商品拍照=连拍会话（CameraBurstContract）：相机内连拍多张→「完成」一次批量上传，
    // 中途×/返回=全部删除零残留；拍一张也当作「一张会话」，交互入口不变。
    // 相机权限由 CameraCaptureActivity 自行申请；单拍语义在 ViewModel 侧保留（其他入口在用）。
    var burstBase by remember { mutableStateOf<java.io.File?>(null) }
    var photoViewerIndex by remember { mutableStateOf(-1) }
    val takeBurst = rememberLauncherForActivityResult(CameraBurstContract()) { files ->
        burstBase = null
        if (files.isNotEmpty()) viewModel.onCameraPhotos(files)
    }
    // I27 相册升级为系统 Photo Picker 多选（13+ 原生多选，旧机回落可多选的文档选择器）
    val productPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(CameraBurstContract.MAX_SHOTS),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadFromUris(uris)
    }
    fun startProductCapture(productId: Long) {
        ScanFeedback.click(context)
        val base = com.retaildemo.v2.data.repo.ProductPhotoStore.newTargetFile(context, productId)
        burstBase = base
        takeBurst.launch(base)
    }

    val cellScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val code = result.data?.getStringExtra(ScannerActivity.EXTRA_BARCODE)
        if (!code.isNullOrBlank()) {
            // 语义纠偏：扫货位码把商品绑到储位 = 绑定，用"扣上一声"
            ScanFeedback.play(context, ScanFeedback.Sfx.BIND_OK)
            viewModel.bindToCell(code.trim())
        }
    }

    // XZG-API-RO 改条码扫码器（老板令「更新条码不能靠手打」）：
    // 调起相机扫包装码 → 扫完自动回填并自动发起一货一码校验，店员连「校验」都不用点！
    val barcodeScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val code = result.data?.getStringExtra(ScannerActivity.EXTRA_BARCODE)
        if (!code.isNullOrBlank()) {
            ScanFeedback.play(context, ScanFeedback.Sfx.SCAN_OK)
            // 回填即自动发起一货一码预查（onBarcodeInputChanged 内部自带防抖预查）——
            // 原先紧跟着的 viewModel.checkBarcode() 已被这次重构删掉，留在会是编译错（代修 2026-09-14）
            viewModel.onBarcodeInputChanged(code.trim())
        }
    }

    // I19 全屏照片查看器（照片删空自动关闭）
    if (photoViewerIndex >= 0 && photos.isNotEmpty()) {
        PhotoViewerDialog(
            photos = photos,
            initialIndex = photoViewerIndex.coerceAtMost(photos.size - 1),
            load = { viewModel.loadPhotoBitmap(it) },
            onClose = { photoViewerIndex = -1 },
            onDelete = { viewModel.deletePhoto(it) },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            retaildemoTopBar(
                title = "商品详情",
                subtitle = product?.sku,
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        ScanFeedback.click(context)
                        showAiSheet = true
                    }) {
                        Icon(Icons.Default.SmartToy, contentDescription = "AI 问问", tint = TextSecondary)
                    }
                    IconButton(onClick = {
                        ScanFeedback.click(context)
                        markPhoto = null
                        showMarkDialog = true
                    }) {
                        Icon(Icons.Default.Flag, contentDescription = "标记问题", tint = TextSecondary)
                    }
                },
            )
        },
        bottomBar = {
            // 06 稿 3:804：底栏贴底向上投 L4 dockedLayers；高 52dp 规范双按钮
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = Color.White,
                modifier = Modifier
                    .navigationBarsPadding()
                    .sxElevation(SxElevation.L4, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), docked = true),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 06 稿 PNG 底栏（design/screens/06_product.png 实测）：主按钮「加入单据」在左
                    // （x16.5..251 宽 234.5dp 充满余量），次按钮「打印价签」在右（262.5..373 宽 110.5dp）。
                    // ⚠️ 此前顺序对调（画布 3:805/3:809 注记误导），2026-09-19 以 PNG 为真源纠正。
                    Button(
                        onClick = {
                            ScanFeedback.play(context, ScanFeedback.Sfx.COMMIT_OK)
                            product?.barcode?.let {
                                com.retaildemo.v2.data.repo.PendingProductHolder.pendingBarcode = it
                            }
                            onRecordSale()
                        },
                        shape = RoundedCornerShape(Design.CardRadius.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("加入单据", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Button(
                        onClick = {
                            ScanFeedback.click(context)
                            viewModel.enqueuePrintJob()
                        },
                        shape = RoundedCornerShape(Design.CardRadius.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceSubtle,
                            contentColor = TextSecondary,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp), tint = TextSecondary)
                        Spacer(Modifier.width(6.dp))
                        Text("打印价签", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                    }
                }
            }
        },
    ) { padding ->
        LaunchedEffect(bindMessage) {
            bindMessage?.let {
                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
                viewModel.clearBindMessage()
            }
        }
        // XZG-API-RO 实时库存/条码写回反馈（成功不打扰，界面自己切到结果）
        LaunchedEffect(liveMessage) {
            liveMessage?.let {
                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
                viewModel.clearLiveMessage()
            }
        }
        // I24 改条码弹窗：点确认即本地生效，管家婆由后台回写（校验没过会自动撤回并告知）
        if (barcodeEditVisible) {
            BarcodeEditDialog(
                currentBarcode = product?.barcode,
                input = barcodeInput,
                busy = barcodeStage == "submitting",
                occupiedBy = barcodeCheck?.takeIf { !it.isFree }?.owner,
                onInputChange = viewModel::onBarcodeInputChanged,
                onScan = { barcodeScanLauncher.launch(ScannerActivity.intent(context)) },
                onConfirm = viewModel::confirmBarcodeWrite,
                onDismiss = viewModel::dismissBarcodeEdit,
            )
        }
        // I24 条码同步反馈：受理 / 已同步管家婆 / 校验没过已撤回（独立通道，弹窗关了也要看得见）
        LaunchedEffect(barcodeSyncMessage) {
            barcodeSyncMessage?.let {
                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                viewModel.clearBarcodeSyncMessage()
            }
        }
        // I26 图片反馈：已受理 / 已同步管家婆 / 没传成（同款独立通道）
        LaunchedEffect(imagePushMessage) {
            imagePushMessage?.let {
                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                viewModel.clearImagePushMessage()
            }
        }
        // I26 图片回传二次确认：上游图片存储「只进不出」（文件删不掉），必须店员显式确认
        if (imagePushConfirm) {
            SanxAlertDialog(
                onDismissRequest = viewModel::dismissImagePush,
                title = { Text("回传图片到管家婆", style = MaterialTheme.typography.titleSmall) },
                text = {
                    Column {
                        Text(
                            "将把该商品的主图回传给管家婆：自动压缩提速，不动管家婆已配的其他商品图——只追加、不覆盖。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "注意：图片传过去后在管家婆删不掉（只能撤回引用），请确认这是要正式展示的商品图。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Warning,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                },
                confirmText = "确认回传",
                onConfirm = viewModel::confirmImagePush,
                dismissText = "取消",
            )
        }
        // B6 退货反馈与跳转
        LaunchedEffect(returnMessage) {
            returnMessage?.let {
                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
                viewModel.clearReturnMessage()
            }
        }
        LaunchedEffect(returnedBillId) {
            returnedBillId?.let {
                viewModel.consumeReturned()
                onOpenBill(it)
            }
        }
        // I4 标记/补录反馈
        LaunchedEffect(markMessage) {
            markMessage?.let {
                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
                viewModel.clearMarkMessage()
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            val p = product
            if (p == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("加载中…", color = TextSecondary)
                }
            } else {
                val shelfDisplay = shelfLocations.firstOrNull()?.let {
                    if (shelfLocations.size > 1) "${it.display} 等${shelfLocations.size}处" else it.display
                } ?: locations.firstOrNull()?.location_code

                // 1. 主图容器（06 稿 AST 3:740，高 160dp，圆角 16dp）
                ProductHeroMediaCard(
                    photos = photos,
                    onOpenViewer = { photoViewerIndex = it },
                    onCapture = { if (p.id != 0L) startProductCapture(p.id) },
                    onPick = {
                        productPhotoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    load = { viewModel.loadPhotoBitmap(it, 384) },
                    canPush = p.id != 0L && photos.any { it.remote != null },
                    onPush = { viewModel.requestImagePush() },
                    photosOffline = photosOffline,
                    onRetryLoad = { viewModel.retryLoadPhotos() },
                )

                // 2. 信息块（06 稿 AST 3:742，裸铺无白卡）
                ProductInfoBlock(
                    product = p,
                    locationCode = shelfDisplay,
                    onBind = {
                        ScanFeedback.click(context)
                        showBindSheet = true
                    },
                )

                // 3. 价格卡（06 稿 AST 3:756，白卡 rx14 L2，售价行 + 双列进货价/毛利掩码行）
                ProductPriceCard(product = p, salesStats = salesStats, livePrice = liveStock?.sellPrice)

                // 4. 库存货位卡（06 稿 AST 3:782，白卡 rx14 L2，库存行 + 40dp 货位药丸条 + 最近流水摘要条）
                ProductStockLocationCard(
                    product = p,
                    liveStock = liveStock,
                    liveLoading = liveLoading,
                    onRefreshLive = { viewModel.refreshLiveStock() },
                    locationCode = shelfDisplay,
                    onBind = {
                        ScanFeedback.click(context)
                        showBindSheet = true
                    },
                    movements = movements,
                    onOpenMovements = {},
                )

                Spacer(Modifier.height(4.dp))

                // 5. 次级业务管理折叠卡：商品规格条码、二维码大图、管家婆本地补录（收纳在主卡下方，不破坏主结构）
                DetailCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("规格与条码详情", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "编辑信息",
                            fontSize = 13.sp,
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                ScanFeedback.click(context)
                                suppBrand = supplement?.brand ?: ""
                                suppSpec = supplement?.spec ?: ""
                                suppNote = supplement?.note ?: ""
                                suppBarcode = supplement?.pendingBarcode ?: ""
                                suppPhoto = supplement?.photo
                                showSuppDialog = true
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(IntrinsicSize.Min),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            val effectiveBarcode = p.barcode?.takeIf { it.isNotBlank() } ?: p.sku?.replace(".", "") ?: "-"
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                                Text("条码", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(48.dp))
                                Text(
                                    effectiveBarcode,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "复制条码",
                                    tint = TextSecondary,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            cm.setPrimaryClip(
                                                android.content.ClipData.newPlainText("barcode", if (effectiveBarcode != "-") effectiveBarcode else ""),
                                            )
                                            android.widget.Toast.makeText(context, "条码已复制", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                )
                                // XZG-API-RO：改条码（校验 → 确认 → 写回管家婆）
                                Spacer(Modifier.width(10.dp))
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "改条码",
                                    tint = Primary,
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clickable { viewModel.openBarcodeEdit() },
                                )
                            }
                            DetailRow("规格", (supplement?.spec ?: p.spec ?: "-") + if (supplement?.spec != null) "（本地）" else "")
                            if (!p.model.isNullOrBlank()) {
                                DetailRow("型号", p.model, mono = true)
                            }
                            if (!p.sku.isNullOrBlank() && p.sku != effectiveBarcode) {
                                DetailRow("SKU", p.sku, mono = true)
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { viewModel.showQr() },
                        ) {
                            val qrCode = qrContentOf(p.sku, p.barcode)
                            val qrBmp = remember(qrCode) {
                                generateQrBitmap(qrCode, 200)
                            }
                            if (qrBmp != null) {
                                Image(
                                    bitmap = qrBmp.asImageBitmap(),
                                    contentDescription = "商品二维码，点击放大",
                                    modifier = Modifier.size(64.dp),
                                )
                            }
                            Text(
                                "点击放大",
                                fontSize = 11.sp,
                                color = Primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    // I4 §11 本地补充展示（有补录才出现；价格永不出现）
                    supplement?.let { s ->
                        if (!s.brand.isNullOrBlank() || !s.note.isNullOrBlank() || !s.pendingBarcode.isNullOrBlank() || !s.photo.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = Border)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("本地补充", fontSize = 11.sp, color = TextSecondary)
                                Spacer(Modifier.width(6.dp))
                                Text("by ${s.updatedBy.ifBlank { "店员" }}", fontSize = 10.sp, color = TextSecondary)
                            }
                            if (!s.brand.isNullOrBlank()) DetailRow("品牌", s.brand)
                            if (!s.note.isNullOrBlank()) DetailRow("备注", s.note)
                            if (!s.pendingBarcode.isNullOrBlank()) {
                                DetailRow("待绑码", s.pendingBarcode, mono = true)
                            }
                            if (!s.photo.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                val thumb = remember(s.photo) {
                                    runCatching {
                                        val f = com.retaildemo.v2.data.repo.ReceiptPhotoStore.fileIn(
                                            context, "supplement", s.photo,
                                        )
                                        android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                                    }.getOrNull()
                                }
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = "本地补充照片，点击放大",
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clickable { showPhotoFull = true },
                                    )
                                } else {
                                    Text("照片已存（文件丢失）", fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                        }
                    }
                }

                // 施工必备配套辅料
                if (crossSell.isNotEmpty()) {
                    DetailCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("施工必备配套辅料", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Primary.copy(alpha = 0.1f),
                            ) {
                                Text(
                                    "防漏配件",
                                    fontSize = 10.sp,
                                    color = Primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(crossSell.size) { i ->
                                val item = crossSell[i]
                                Card(
                                    onClick = { productDetailNavKey(item.barcode, item.sku)?.let(onOpenProduct) },
                                    shape = RoundedCornerShape(Design.CardRadius.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    modifier = Modifier.width(145.dp),
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(
                                            item.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        if (!item.spec.isNullOrBlank()) {
                                            Text(
                                                item.spec,
                                                fontSize = 11.sp,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            PriceText(
                                                price = item.sell_price,
                                                integerSize = 14.sp,
                                                decimalSize = 12.sp,
                                                symbolSize = 11.sp,
                                                color = Danger,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                if (item.stock_qty > 0) "余${item.stock_qty}" else "无货",
                                                fontSize = 11.sp,
                                                color = if (item.stock_qty > 0) TextSecondary else Danger,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // 同类在售替代品
                if (substitutes.isNotEmpty()) {
                    DetailCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("同类在售替代品", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (p.stockQty <= 0) Danger.copy(alpha = 0.1f) else Primary.copy(alpha = 0.1f),
                            ) {
                                Text(
                                    if (p.stockQty <= 0) "缺货推荐替代" else "在售同规",
                                    fontSize = 10.sp,
                                    color = if (p.stockQty <= 0) Danger else Primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(substitutes.size) { i ->
                                val item = substitutes[i]
                                Card(
                                    onClick = { productDetailNavKey(item.barcode, item.sku)?.let(onOpenProduct) },
                                    shape = RoundedCornerShape(Design.CardRadius.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    modifier = Modifier.width(145.dp),
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(
                                            item.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        val subSubtitle = item.spec ?: item.brand
                                        if (!subSubtitle.isNullOrBlank()) {
                                            Text(
                                                subSubtitle,
                                                fontSize = 11.sp,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            PriceText(
                                                price = item.sell_price,
                                                integerSize = 14.sp,
                                                decimalSize = 12.sp,
                                                symbolSize = 11.sp,
                                                color = Danger,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                if (item.stock_qty > 0) "余${item.stock_qty}" else "无货",
                                                fontSize = 11.sp,
                                                color = if (item.stock_qty > 0) TextSecondary else Danger,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // 若配套与替代都为空，但有旧版 similar，保留兼容显示
                if (crossSell.isEmpty() && substitutes.isEmpty() && similar.isNotEmpty()) {
                    DetailCard {
                        Text("同类推荐", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(similar.size) { i ->
                                val s2 = similar[i]
                                Card(
                                    onClick = { productDetailNavKey(s2.barcode, s2.sku)?.let(onOpenProduct) },
                                    shape = RoundedCornerShape(Design.CardRadius.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color.White,
                                    ),
                                    modifier = Modifier.width(140.dp),
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(
                                            s2.name,
                                            fontSize = 13.sp,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        PriceText(
                                            price = s2.sell_price,
                                            integerSize = 14.sp,
                                            decimalSize = 12.sp,
                                            symbolSize = 11.sp,
                                            color = Danger,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // 相关知识（常驻卡片）
                DetailCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                ScanFeedback.click(context)
                                com.retaildemo.v2.data.repo.PendingKeywordHolder.pendingKeyword = p.name
                                onKnowledge()
                            }
                            .padding(vertical = 6.dp),
                    ) {
                        Text("相关知识", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("按「${p.name}」搜索 →", fontSize = 12.sp, color = Primary)
                    }
                }
                Spacer(Modifier.height(10.dp))

                // 最近销售记录（B6 退货定位原单）
                DetailCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("最近销售记录", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text("退货从此发起", fontSize = 11.sp, color = TextSecondary)
                    }
                    Spacer(Modifier.height(6.dp))
                    if (recentSales.isEmpty()) {
                        Text(
                            "暂无已出库销售记录（未出库的退货直接在账单删行/改量即可）",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                    } else {
                        recentSales.forEach { rec ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${rec.customerName} · ${rec.createdAt.take(16)}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${rec.qty}件 ×¥%.2f  ${rec.billId}".format(rec.price),
                                        fontSize = 11.sp,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                                OutlinedButton(onClick = {
                                    returnQty = ""
                                    returnTarget = rec
                                }) { Text("退货", fontSize = 12.sp) }
                            }
                            HorizontalDivider(color = Border)
                        }
                    }
                }

                // 库存变动
                DetailCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("库存变动", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text("最近 10 条", fontSize = 11.sp, color = TextSecondary)
                    }
                    Spacer(Modifier.height(6.dp))
                    if (movements.isEmpty()) {
                        Text("暂无流水变动", fontSize = 12.sp, color = TextSecondary)
                    } else {
                        movements.take(3).forEach { m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 3.dp),
                            ) {
                                Text(
                                    (if (m.change_qty >= 0) "+" else "") + m.change_qty.toString(),
                                    color = if (m.change_qty >= 0) Success else Danger,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.width(48.dp),
                                )
                                Text(
                                    m.movement_type + " · " + (m.created_at ?: "").take(16),
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }

        // 二维码全屏
        if (showQr) {
            product?.let { p2 ->
                QrOverlay(
                    items = listOf(
                        com.retaildemo.v2.data.local.entity.BillItem(
                            barcode = p2.barcode ?: p2.sku ?: "",
                            sku = p2.sku ?: "",
                            name = p2.name,
                            spec = p2.spec ?: "",
                            unit = p2.unit ?: "",
                            qty = 1,
                            price = p2.sellPrice,
                            costPrice = p2.costPrice,
                        ),
                    ),
                    initialIndex = 0,
                    onDismiss = { viewModel.dismissQr() },
                )
            }
        }

        // B6 退货数量确认弹层
        returnTarget?.let { rec ->
            SanxAlertDialog(
                onDismissRequest = { returnTarget = null },
                title = { Text("生成退货单", style = MaterialTheme.typography.titleSmall) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "原单 ${rec.billId}\n${rec.customerName} · 售出 ${rec.qty} 件 ×¥%.2f".format(rec.price),
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                        NumberField(
                            value = returnQty,
                            onValueChange = { returnQty = it },
                            label = "退货数量（1~${rec.qty}）",
                            decimals = false,
                        )
                        Text(
                            "将复制该商品行、数量取负生成负数单（已出库，走日结扫码入管家婆）。",
                            fontSize = 11.sp,
                            color = TextSecondary,
                        )
                    }
                },
                confirmText = "确认退货",
                confirmEnabled = (returnQty.toIntOrNull() ?: 0) in 1..rec.qty,
                onConfirm = {
                    val q = returnQty.toIntOrNull() ?: 0
                    if (q in 1..rec.qty) {
                        viewModel.createReturn(rec.billId, product?.sku ?: "", q)
                        returnTarget = null
                    }
                },
                dismissText = "取消",
            )
        }

        // I4 标记弹窗：三类型大按钮 + 备注 + 可选拍照 → ≤10 秒可达，只写 data_marks 表
        if (showMarkDialog) {
            val types = listOf(
                com.retaildemo.v2.data.local.entity.DataMarkEntity.TYPE_NO_PRICE to "无售价",
                com.retaildemo.v2.data.local.entity.DataMarkEntity.TYPE_PRICE_WRONG to "价格不准",
                com.retaildemo.v2.data.local.entity.DataMarkEntity.TYPE_STOCK_MISMATCH to "账实不符",
            )
            SanxAlertDialog(
                onDismissRequest = { showMarkDialog = false },
                title = { Text("标记数据问题", style = MaterialTheme.typography.titleSmall) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            types.forEach { (v, label) ->
                                val sel = markType == v
                                OutlinedButton(
                                    onClick = { markType = v },
                                    modifier = Modifier.weight(1f),
                                    colors = if (sel) {
                                        ButtonDefaults.outlinedButtonColors(containerColor = PrimaryLight)
                                    } else ButtonDefaults.outlinedButtonColors(),
                                ) { Text(label, fontSize = 13.sp) }
                            }
                        }
                        OutlinedTextField(
                            value = markNote,
                            onValueChange = { markNote = it },
                            label = { Text("备注（选填，如：管家婆 12.5，这 15）") },
                            singleLine = false,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (markPhoto != null) "照片已选 ✓" else "没照片也行",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                photoFor = "mark"
                                photoPicker.launch("image/*")
                            }) { Text("拍照/选图") }
                        }
                    }
                },
                confirmText = "确认标记",
                onConfirm = {
                    viewModel.markProduct(markType, markNote, markPhoto)
                    markNote = ""
                    showMarkDialog = false
                },
                dismissText = "取消",
            )
        }

        // I4 §11 编辑信息：品牌/规格/备注/照片/待绑新码 → 存独立补充表；价格永不出现
        if (showSuppDialog) {
            SanxAlertDialog(
                onDismissRequest = { showSuppDialog = false },
                title = { Text("编辑本地信息", style = MaterialTheme.typography.titleSmall) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "只补本地缺的信息（同步不覆盖）；价格不在此改，去记账改价。",
                            fontSize = 11.sp,
                            color = TextSecondary,
                        )
                        OutlinedTextField(
                            value = suppBrand, onValueChange = { suppBrand = it },
                            label = { Text("品牌（选填）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = suppSpec, onValueChange = { suppSpec = it },
                            label = { Text("规格型号（选填）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = suppNote, onValueChange = { suppNote = it },
                            label = { Text("备注（选填）") }, singleLine = false, maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = suppBarcode, onValueChange = { suppBarcode = it.filter { c -> !c.isWhitespace() } },
                            label = { Text("待绑新码（选填，扫到实物新码才填）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (suppPhoto != null) "照片已选 ✓" else "没照片也行",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                photoFor = "supp"
                                photoPicker.launch("image/*")
                            }) { Text("拍照/选图") }
                        }
                    }
                },
                confirmText = "保存",
                onConfirm = {
                    viewModel.saveSupplement(suppBrand, suppSpec, suppNote, suppPhoto, suppBarcode)
                    showSuppDialog = false
                },
                dismissText = "取消",
            )
        }

        // I4 本地补充大图
        if (showPhotoFull) {
            val fullName = supplement?.photo
            SanxAlertDialog(
                onDismissRequest = { showPhotoFull = false },
                title = { Text("补充照片", style = MaterialTheme.typography.titleSmall) },
                text = {
                    val bmp = remember(fullName) {
                        fullName?.let {
                            runCatching {
                                val f = com.retaildemo.v2.data.repo.ReceiptPhotoStore.fileIn(
                                    context, "supplement", it,
                                )
                                android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                            }.getOrNull()
                        }
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "补充照片大图",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("照片文件找不到了", fontSize = 12.sp, color = TextSecondary)
                    }
                },
                confirmText = "关闭",
            )
        }

                // I6 知识浮窗：问问这件货（sku/name/spec 注入 chat 上下文，回答关联该商品）
        if (showAiSheet) {
            product?.let { p2 ->
                com.retaildemo.v2.ui.components.KnowledgeFloatSheet(
                    product = com.retaildemo.v2.data.remote.ChatContext(
                        sku = p2.sku ?: "",
                        name = p2.name,
                        spec = p2.spec ?: "",
                    ),
                    onDismiss = { showAiSheet = false },
                )
            }
        }

        // 绑定货位弹层（扫货位码）
        if (showBindSheet) {
            SanxAlertDialog(
                onDismissRequest = { showBindSheet = false },
                title = { Text("绑定货位", style = MaterialTheme.typography.titleSmall) },
                text = {
                    Column {
                        Text("把商品放回货架，扫货位上的标签码即可绑定", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                showBindSheet = false
                                cellScanLauncher.launch(ScannerActivity.intent(context))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("扫货位码")
                        }
                    }
                },
                dismissText = "关闭",
            )
        }
    }
}

@Composable
private fun DetailCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(label, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(48.dp))
        Text(
            value,
            fontSize = 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

/** I19 商品照片区：缩略图横滑 + 拍照/相册补图（盘点/巡检随时补拍，一货多图）。 */
@Composable
private fun PhotoSectionCard(
    photos: List<com.retaildemo.v2.ui.viewmodel.ProductDetailViewModel.PhotoUi>,
    canCapture: Boolean,
    canPush: Boolean,
    offline: Boolean,
    load: suspend (com.retaildemo.v2.ui.viewmodel.ProductDetailViewModel.PhotoUi) -> android.graphics.Bitmap?,
    onCapture: () -> Unit,
    onPick: () -> Unit,
    onOpenViewer: (Int) -> Unit,
    onRetryLoad: () -> Unit,
    onPush: () -> Unit,
) {
    DetailCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("商品照片", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            if (photos.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(4.dp), color = Primary.copy(alpha = 0.1f)) {
                    Text(
                        "${photos.size} 张",
                        fontSize = 10.sp,
                        color = Primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // I26 回传管家婆：显式发起、只传主图、二次确认后才受理（上游图片文件删不掉）
            if (canPush) {
                Text(
                    "回传管家婆",
                    fontSize = 12.sp,
                    color = Primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onPush)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                "相册多选 +",
                fontSize = 12.sp,
                color = Primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onPick)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        // I19 下行可见性：服务端照片列表拉取失败绝不静默（弱网下不亮这条=照片真没了；
        // 亮了=照片在服务器上好好的，点一下重试即可回显）
        if (offline) {
            Spacer(Modifier.height(6.dp))
            Text(
                "服务端照片未加载（网络不稳）· 点击重试",
                fontSize = 11.sp,
                color = Warning,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRetryLoad)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos.size) { i ->
                val ph = photos[i]
                PhotoThumb(photo = ph, load = load, onClick = { onOpenViewer(i) })
            }
            item {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(Design.CardRadius.dp))
                        .background(if (canCapture) PrimaryLight else SurfaceSubtle)
                        .clickable(enabled = canCapture, onClick = onCapture),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = "拍照补图",
                            tint = Primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text("连拍", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoThumb(
    photo: com.retaildemo.v2.ui.viewmodel.ProductDetailViewModel.PhotoUi,
    load: suspend (com.retaildemo.v2.ui.viewmodel.ProductDetailViewModel.PhotoUi) -> android.graphics.Bitmap?,
    onClick: () -> Unit,
) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, photo) { value = load(photo) }
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(Design.CardRadius.dp))
            .background(SurfaceSubtle)
            .clickable(onClick = onClick),
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "商品照片缩略图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (photo.isLocal) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Warning.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
            ) {
                Text(
                    "待传",
                    fontSize = 9.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** I19 全屏照片查看器：左右滑切换 + 计数 + 删除（对齐账单大码轮播交互语言）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoViewerDialog(
    photos: List<com.retaildemo.v2.ui.viewmodel.ProductDetailViewModel.PhotoUi>,
    initialIndex: Int,
    load: suspend (com.retaildemo.v2.ui.viewmodel.ProductDetailViewModel.PhotoUi) -> android.graphics.Bitmap?,
    onClose: () -> Unit,
    onDelete: (com.retaildemo.v2.ui.viewmodel.ProductDetailViewModel.PhotoUi) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { photos.size })
    // U8：与全局浮层同一套弹簧进场（淡入为主，全屏看图不做缩放以免照片发虚）
    val winEnter = com.retaildemo.v2.ui.window.rememberWindowEnter()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = winEnter.alpha.value }
                .background(Color.Black),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val ph = photos.getOrNull(page)
                val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, ph) {
                    value = ph?.let { load(it) }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b != null) {
                        Image(
                            bitmap = b.asImageBitmap(),
                            contentDescription = "照片大图",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text("加载中…", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 32.dp, start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { photos.getOrNull(pagerState.currentPage)?.let(onDelete) }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除照片", tint = Color.White)
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            ) {
                Text("关闭", color = Color.White)
            }
        }
    }
}

/**
 * XZG-API-RO 改条码弹窗：**单个改 · 一货一码校验 · 人工确认**三道（老板 2026-09-13 令）。
 *
 * 流程固定为三段，不给"跳过校验直接写"的入口：
 *   ① 输入新条码 → 点「校验」→ 走服务端查这个码是否已被别的商品占用；
 *   ② 校验通过 → 弹出「谁 从旧码 改成 新码」的确认区，店员再点一次「确认写回管家婆」；
 *   ③ 提交后按回读结果显示成败（回读对不上会明确说出来，不谎报成功）。
 */
@Composable
/**
 * I24 改条码弹窗（**单步**）。
 *
 * 旧实现是两步「校验 → 确认」，而且校验拿不到结果就不放行——店员每次都得先等管家婆，
 * 且真正的写入还要再等一轮（服务端当时要全量分页扫 12,631 条找商品编号）。
 *
 * 现在点「确认修改」即提交：条码**当场在本机生效**（马上就能扫到），
 * 管家婆由后台回写；回读校验没过会自动撤回本地绑定并告知店员。
 * [occupiedBy] 是**预查提示**（非空 = 这个码在管家婆里已是别人的），只提醒、**不挡确认**。
 */
private fun BarcodeEditDialog(
    currentBarcode: String?,
    input: String,
    busy: Boolean = false,
    occupiedBy: com.retaildemo.v2.data.remote.BarcodeOwnerDto?,
    onInputChange: (String) -> Unit,
    onScan: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SanxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改条码", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "当前条码：${currentBarcode?.takeIf { it.isNotBlank() } ?: "（无）"}",
                    fontSize = 12.sp, color = TextSecondary,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    label = { Text("新条码（扫码或手输）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = onScan) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "扫码录入",
                                tint = Primary,
                            )
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { if (input.isNotBlank()) onConfirm() },
                    ),
                )
                Text(
                    "点右侧扫码枪图标开相机扫，或直接用外接巴枪扫入。",
                    fontSize = 11.sp, color = TextSecondary,
                )
                Text(
                    "确认后**先在本机立刻生效**，后台写回管家婆；" +
                        "写回不成功会自动撤销并告知。",
                    fontSize = 11.sp, color = TextSecondary,
                )
                if (occupiedBy != null) {
                    Text(
                        "预查提示：这个码在管家婆里属于「${occupiedBy.sku ?: ""} ${occupiedBy.name ?: ""}」，" +
                            "继续提交会被后台校验挡下并自动撤销。",
                        fontSize = 11.sp, color = Warning,
                    )
                }
            }
        },
        confirmText = "确认修改",
        onConfirm = onConfirm,
        confirmEnabled = input.isNotBlank(),
        confirmDanger = true,
        confirmCloses = true,
        dismissText = "取消",
        onDismiss = onDismiss,
    )
}

/**
 * 06 稿 AST 3:740 · 商品大主图（高 160dp，圆角 16dp）
 * 有实物照片时展示首图，并提供缩略角标；无实物图时展示工装风摄影占位区，支持直接调起连拍拍照或相册。
 */
@Composable
private fun ProductHeroMediaCard(
    photos: List<com.retaildemo.v2.ui.viewmodel.ProductDetailViewModel.PhotoUi>,
    onOpenViewer: (Int) -> Unit,
    onCapture: () -> Unit,
    onPick: () -> Unit,
    load: suspend (com.retaildemo.v2.ui.viewmodel.ProductDetailViewModel.PhotoUi) -> android.graphics.Bitmap?,
    canPush: Boolean,
    onPush: () -> Unit,
    photosOffline: Boolean,
    onRetryLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceSubtle),
        contentAlignment = Alignment.Center,
    ) {
        if (photos.isNotEmpty()) {
            val firstPhoto = photos.first()
            val bmp by produceState<android.graphics.Bitmap?>(null, firstPhoto) {
                value = load(firstPhoto)
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp!!.asImageBitmap(),
                    contentDescription = "商品主图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onOpenViewer(0) },
                )
            } else {
                Text("图片加载中…", fontSize = 12.sp, lineHeight = 16.sp, color = TextWeak)
            }

            // 右下角照片管理气泡（张数 + 拍照补图）
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        "${photos.size} 张照片",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = "拍摄新照片",
                        tint = Color.White,
                        modifier = Modifier
                            .size(15.dp)
                            .clickable(onClick = onCapture),
                    )
                }
            }

            // I26 反哺恢复（2026-09-19）：回传管家婆入口——只在「已入库 + 服务器上有照片」时出现
            if (canPush) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clickable(onClick = onPush),
                ) {
                    Text(
                        "↑ 回传管家婆",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            if (photosOffline) {
                Text(
                    "照片加载失败 · 点重试",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRetryLoad),
                )
            }
        } else {
            // 无照片工装占位
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onCapture)
                    .padding(16.dp),
            ) {
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.PhotoCamera,
                    contentDescription = null,
                    tint = TextWeak,
                    modifier = Modifier.size(32.dp),
                )
                Text("暂无实物照片 · 点击拍照", fontSize = 12.sp, lineHeight = 16.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                Text("或点此打开系统相册选择", fontSize = 10.sp, lineHeight = 14.sp, color = Primary, modifier = Modifier.clickable(onClick = onPick))
                if (photosOffline) {
                    Text(
                        "照片加载失败 · 点重试",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Danger,
                        modifier = Modifier.clickable(onClick = onRetryLoad),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 06 稿 AST 3:742 · 裸铺信息块（品名 20sp Bold + 货位胶囊 + 规格条码文本 + 3 颗分类微标签）
 */
@Composable
private fun ProductInfoBlock(
    product: com.retaildemo.v2.data.local.entity.ProductEntity,
    locationCode: String?,
    onBind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        // 品名行（3:743）：品名 20sp Bold + 右侧货位胶囊（3:745）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = product.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain,
                lineHeight = 26.sp,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            // 3:745 货位胶囊（高 26dp，圆角 20dp，青色底）
            val locLabel = locationCode?.ifBlank { null } ?: "未上架"
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = CyanLight,
                modifier = Modifier
                    .height(26.dp)
                    .clickable(onClick = onBind),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Cyan),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = locLabel,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CyanDark,
                    )
                }
            }
        }

        // 规格条码行（3:748）——型号（出厂货号）有值才出现在规格与条码之间
        val specText = listOfNotNull(
            product.spec?.takeIf { it.isNotBlank() }?.let { "规格 $it" },
            product.model?.takeIf { it.isNotBlank() }?.let { "型号 $it" },
            product.barcode?.takeIf { it.isNotBlank() }?.let { "条码 $it" },
            product.unit?.takeIf { it.isNotBlank() }?.let { "单位 $it" },
        ).joinToString(" · ")
        Text(
            text = specText.ifBlank { "条码 ${product.barcode ?: product.sku ?: "-"}" },
            fontSize = 12.sp,
            color = TextWeak,
            lineHeight = 16.sp,
        )

        // 标签行（3:749）：3 颗 20dp 灰微标签
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val cat = product.category?.takeIf { it.isNotBlank() } ?: "五金通用"
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SurfaceSubtle,
                modifier = Modifier.height(20.dp),
            ) {
                Text(
                    text = cat,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (!product.brand.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SurfaceSubtle,
                    modifier = Modifier.height(20.dp),
                ) {
                    Text(
                        text = product.brand,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SurfaceSubtle,
                modifier = Modifier.height(20.dp),
            ) {
                Text(
                    text = "在售正品",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * 06 稿 AST 3:756 · 价格卡（白卡 rx14 L2 阴影）
 * 包含：售价行（左大红价，右三行统计信息）+ 成本行（双列浅灰卡，长按 0.5s 浮签浮出真值）
 */
@Composable
private fun ProductPriceCard(
    product: com.retaildemo.v2.data.local.entity.ProductEntity,
    salesStats: com.retaildemo.v2.ui.viewmodel.ProductSalesStats,
    livePrice: Double?,
    modifier: Modifier = Modifier,
) {
    // 2026-10-03 老板报「同步后列表新价、详情旧价」：有管家婆当场价时优先显示（与库存卡
    // shownQty 同款口径），镜像快照只作离线兜底；毛利率同步按当场价算，避免半新半旧。
    val shownPrice = livePrice ?: product.sellPrice
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .sxElevation(SxElevation.L2),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // 3:757 售价行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 左列：标签 + 大红价（有当场价时亮「管家婆当场」标记，与库存卡同款口径）
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (livePrice != null) "零售价 · 管家婆当场" else "零售价",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = TextWeak,
                    )
                    PriceText(
                        price = shownPrice,
                        color = Danger,
                        integerSize = 30.sp,
                        symbolSize = 14.sp,
                        decimalSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // 右列：统计说明三行（3:764；行2/3 按稿=近30天均价/昨日卖出，口径见 Gate 0 · 21_product_stats_rows.md）
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text("单位 ${product.unit ?: "个"} · 含税", fontSize = 11.sp, lineHeight = 15.sp, color = TextWeak)
                    Text(
                        text = salesStats.avg30?.let { "近 30 天均价 ¥%.2f".format(it) } ?: "近 30 天无成交",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = TextWeak,
                    )
                    Text("昨天卖出 ${salesStats.soldYesterday} 件", fontSize = 11.sp, lineHeight = 15.sp, color = TextWeak)
                }
            }

            // 3:768 成本行（双列浅灰卡，高 56dp，圆角 10dp，长按 0.5s 浮签浮出真值）
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 左列：进货价卡
                Box(modifier = Modifier.weight(1f)) {
                    HoldToReveal(
                        holdMs = 500,
                        label = "进货价",
                        masked = {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = SurfaceSubtle,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                ) {
                                    Text("进货价 · 长按0.5秒", fontSize = 10.sp, lineHeight = 14.sp, color = TextWeak)
                                    Spacer(Modifier.height(3.dp))
                                    Text("¥ •••", fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                                }
                            }
                        },
                        revealed = {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Design.ScrimInk,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                ) {
                                    Text("实录进价", fontSize = 10.sp, lineHeight = 14.sp, color = Color.White.copy(alpha = 0.7f))
                                    Spacer(Modifier.height(3.dp))
                                    Text("¥%.2f".format(product.costPrice), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        },
                    )
                }

                // 右列：毛利率卡（售价按当场价口径，进货价仍为镜像快照）
                val pct = marginPct(shownPrice, product.costPrice) ?: 0
                Box(modifier = Modifier.weight(1f)) {
                    HoldToReveal(
                        holdMs = 500,
                        label = "毛利率",
                        masked = {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = SurfaceSubtle,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                ) {
                                    Text("毛利率 · 长按0.5秒", fontSize = 10.sp, lineHeight = 14.sp, color = TextWeak)
                                    Spacer(Modifier.height(3.dp))
                                    Text("••%", fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                                }
                            }
                        },
                        revealed = {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Design.ScrimInk,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                ) {
                                    Text("毛利测算", fontSize = 10.sp, lineHeight = 14.sp, color = Color.White.copy(alpha = 0.7f))
                                    Spacer(Modifier.height(3.dp))
                                    Text("$pct%", fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = Success)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 06 稿 AST 3:782 · 库存货位卡（白卡 rx14 L2 阴影）
 * 包含：库存行（大字 22sp + 绿徽章 + 预警线 + 实时管家婆刷新）
 *       + 货位行（40dp 浅灰药丸条 + 浅青货位胶囊 + 箭头）
 *       + 最近流水行（出库记录摘要 + 全部流水链接）
 */
@Composable
private fun ProductStockLocationCard(
    product: com.retaildemo.v2.data.local.entity.ProductEntity,
    liveStock: com.retaildemo.v2.data.remote.LiveStockDto?,
    liveLoading: Boolean,
    onRefreshLive: () -> Unit,
    locationCode: String?,
    onBind: () -> Unit,
    movements: List<com.retaildemo.v2.data.remote.MovementDto>,
    onOpenMovements: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shownQty = liveStock?.stockQty ?: product.stockQty
    val isEnough = shownQty > 10

    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .sxElevation(SxElevation.L2),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // 3:783 库存行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 左侧：标签 + 数量大字 + 状态徽章
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("库存", fontSize = 11.sp, lineHeight = 15.sp, color = TextWeak)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = com.retaildemo.v2.util.formatQty(shownQty),
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = product.unit ?: "件",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.width(8.dp))
                    // 充足/紧张徽章
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isEnough) SuccessLight else WarningLight,
                        modifier = Modifier.height(22.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (isEnough) Success else Warning),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = if (isEnough) "充足" else if (shownQty <= 0) "缺货" else "紧张",
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isEnough) Success else Warning,
                            )
                        }
                    }
                }

                // 右侧：预警线与实时刷新
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (liveStock != null) "管家婆当场值" else "预警线 10 件",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = TextWeak,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (liveLoading) "查询中…" else "实时刷新",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primary,
                        modifier = Modifier
                            .clickable(enabled = !liveLoading, onClick = onRefreshLive),
                    )
                }
            }

            // 3:793 货位行（高 40dp，圆角 10dp，底色 SurfaceSubtle）
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SurfaceSubtle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clickable(onClick = onBind),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    // 浅青货位胶囊
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = CyanLight,
                        modifier = Modifier.height(24.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        ) {
                            Text(
                                text = locationCode?.ifBlank { null } ?: "未指定货位",
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CyanDark,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (locationCode.isNullOrBlank()) "点击扫码分配货架格位" else "顺手可取 · 现货货架",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = TextWeak,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = "绑定货位",
                        tint = TextWeak,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // 3:800 最近流水行
            val latestM = movements.firstOrNull()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (latestM != null) {
                        "${(latestM.created_at ?: "").take(16)} · ${latestM.movement_type} ${latestM.change_qty}"
                    } else "暂无近期出入库流水记录",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = TextWeak,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "全部流水",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primary,
                    modifier = Modifier.clickable(onClick = onOpenMovements),
                )
            }
        }
    }
}

