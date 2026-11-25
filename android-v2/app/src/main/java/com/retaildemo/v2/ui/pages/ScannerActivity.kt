package com.retaildemo.v2.ui.pages

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size as AndroidSize
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.retaildemo.v2.ui.components.PrimaryActionButton
import com.retaildemo.v2.ui.components.directTap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.repo.ProductRepo
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.pages.scanner.HandoverLiveList
import com.retaildemo.v2.ui.pages.scanner.HandoverLiveListModel
import com.retaildemo.v2.ui.pages.scanner.HandoverSummary
import com.retaildemo.v2.ui.pages.scanner.ReceiptM
import com.retaildemo.v2.ui.pages.scanner.ScanReceipt
import com.retaildemo.v2.ui.pages.scanner.ScanReceiptCard
import com.retaildemo.v2.ui.pages.scanner.ScanReceiptSpec
import com.retaildemo.v2.ui.window.SanxAlertDialog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.NightBg
import com.retaildemo.v2.ui.theme.NightSuccess
import com.retaildemo.v2.ui.theme.NightWarning
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.retaildemoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 扫码/识别（CameraX + ML Kit）。
 *
 * 三种模式：
 * - **条码单次**（默认，`EXTRA_MODE=barcode` + `EXTRA_CONTINUOUS=false`）：识别到条码立刻回传并 finish，
 *   行为与改造前完全一致，7 个旧调用点（调整/账单详情/新建账单/货位绑定/查价/货架/盘点）无需改动。
 * - **条码连续**（`EXTRA_CONTINUOUS=true`）：相机常开，识别到条码不 finish，
 *   条码追加进列表并出**三态回执**（U9 离场门 + 2026-09-20 三态口径，判定收在
 *   [ScanReceiptSpec]、目击记录与重复裁定收在 [ScanBuffer.observe]）：
 *     · **M1 命中新件** = 成功音 + 绿卡，计入；
 *     · **M2 重复扫码** = 震动 + 暖黄卡「已在本次扫过 ×N · 本次未计入」+ 出路行「要加数量 → 回「记一笔」的货筐里改」，**绝不自动 +1**；
 *     · **M3 库外条码** = 弱灰卡「库里没有 · 不在商品库里 · 已忽略，未计入清单」，不计入交接件数
 *       （码本身照旧回传——货位绑定等场景要的正是原始码，单据侧忽略与否由调用方裁定）。
 *   取景框只认框内的码（[ViewfinderHit]，治「一堆货摆一起全扫上了」）；
 *   框下方 [HandoverLiveList] 边扫边列这一整趟的交接清单。点「完成」一次性回传全部条码。
 * - **文字识别**（`EXTRA_MODE=text`）：针对无条码/手写码/手写商品名的货，
 *   相机常开实时识别文字，取景框下方实时预览，店员对准并确认识别正确后点「确认」回传 [EXTRA_TEXT]。
 *
 * 条码连续模式同时把最后一个条码写入 [EXTRA_BARCODE]，旧调用点误取时不会崩。
 */
@AndroidEntryPoint
class ScannerActivity : ComponentActivity() {

    /**
     * 回执卡要显示品名/单价，所以扫码页必须能查库（Gate 0 §四 登记的「D2 技术前置」）。
     * `ProductRepo.getByBarcode` 是**本地优先**（Room 先查，未命中才走网络），
     * 本地命中通常 <50ms，不会拖慢扫码节奏；未命中会异步补上，不阻塞下一件。
     */
    @Inject
    lateinit var productRepo: ProductRepo

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var previewView: PreviewView
    private val buffer = ScanBuffer()
    private var barcodeScanner: BarcodeScanner? = null
    private var textRecognizer: TextRecognizer? = null
    private var camera: Camera? = null

    /** 结果已回传 / 页面已结束，防止重复 setResult。 */
    private var delivered = false

    /** TEMP-DIAG：一次性诊断日志开关（验证完连同日志一起删）。 */
    private var diagLogged = false

    /** TEMP-DIAG：首次识别到条码时打印 bbox 与坐标系（确认旋转语义的唯一实证口）。 */
    private var bboxLogged = false

    // ── 会话诊断（ScanDiag，2026-09-26）：从真机拿数据定位"慢"在哪一环 ──
    //  每次扫码会话一行日志（deliver/destroy 时随 LogPipeline 上报），量级可忽略。
    @Inject
    lateinit var appLogger: com.retaildemo.v2.data.repo.AppLogger

    private var diagSessionStartAt = 0L
    private var diagFrames = 0
    private var diagDetected = 0
    private var diagInFrame = 0
    private var diagOutFrame = 0
    private var diagFirstDetectedAt = 0L

    /** 框外引导：最近一次「识别到码但在框外」的时刻（0=无）；UI 侧据此显示引导 1.5s。 */
    private var outsideHintAt by mutableStateOf(0L)
    private var outsideHintVisible by mutableStateOf(false)
    private var lastOutsideHintMs = 0L
    private var lastAssistFocusMs = 0L

    // ── 回执卡（2026-09-20 改版：把「盲扫计数」改成「刚扫那件」的大回执）──
    //    对标快递交接：扫到一件 → 大字告诉你扫到了什么、算没算数。

    /** 本次扫码会话内每码的累计件数（回执卡显示 ×N 用）。 */
    private val sessionQty = mutableMapOf<String, Int>()

    /** 条码 → 商品 的会话内缓存，避免同一件反复查库。 */
    private val lookupCache = mutableMapOf<String, ProductEntity?>()

    /** 当前回执（null=不显示）。 */
    private var receipt: ScanReceipt? by mutableStateOf(null)

    /**
     * **M0 待确认**的那一件（null = 没有待裁决的）。
     *
     * 与 [receipt] 的区别是"要不要等人"：`receipt` 是**已经裁定好**的提醒卡（M2 重复 / M3 库外），
     * 到点自己退；`pending` 是**等问题回答**的，**常驻不退**，直到店员点「计入」或「不算」。
     * 卡片显示的是 `pending ?: receipt`。
     */
    private var pending: ScanReceipt? by mutableStateOf(null)

    /**
     * 被新扫的码顶替掉的那一件的品名（不为空时卡上留一行「没算」的告知）。
     * 顶替**必须说出来** —— 静默作废会让店员以为已计入。
     */
    private var pendingReplaced: String? by mutableStateOf(null)

    /** 流式交接清单（取景框下方那一栏）：一个码一行，最新在最前。 */
    private val handoverRows = mutableStateListOf<ScanReceipt>()

    /**
     * 交接台客户名（画布 M 帧「单据条-客户」）。只影响显示，不参与任何判定。
     */
    private val handoverCustomer: String by lazy {
        intent?.getStringExtra(EXTRA_CUSTOMER).orEmpty()
    }

    /**
     * 取景框上沿（px，自窗口顶）—— **画框（[Viewfinder]）与算识别区域（[insideViewfinder]）
     * 用同一个值**，所以「看到的框」与「生效的框」不可能不一致。
     *
     * 由 `ScannerScreen` 里的**定高堆栈**算出（状态栏 inset + 顶栏 + 单据条 + 间隙），
     * 见 [setFrameTop]。相机分析线程读这个 `@Volatile` 副本。
     *
     * ★ 为什么不用 `onGloballyPositioned` 去「实测」：
     *   本工程 Compose BOM 2024.06.00（ui 1.6.8）里 `LayoutCoordinates` **既没有
     *   `positionInParent()`（1.7 才加）也没有 `boundsInParent()`**，实测两次都
     *   `Unresolved reference`。而定高堆栈本来就是画布 M 帧的写法（`状态栏 32 → 顶栏 48
     *   → 间隙 4 → 单据条 44 → 间隙 12 → 取景框`），直接算出来更准、可单测、无版本风险。
     *   行高由 [TopBarHeight] / [BillBarHeight] 钉死，不会跟布局漂。
     */
    @Volatile
    private var frameTopPx: Float = 0f

    /** 见 [frameTopPx]：唯一写入口。 */
    private fun setFrameTop(px: Float) {
        frameTopPx = px
    }

    /**
     * 取景框上沿（px）—— 画框与算识别区域**唯一**的取值口（一处公式、三处调用）：
     *
     *  · **交接台**（记账「记一笔」的连扫，画布 M 帧）：顶部 chrome 实测底沿 + 12dp，
     *    即「顶栏 → 单据条 → 取景框」这条**画布新定的绝对堆栈**。
     *  · **其余旧屏**（稿 3:1780/3:1781：查价 / 货位绑定 / 调整 / 账单详情 / 新建账单 /
     *    货架 / 盘点 共 7 个调用点）：沿用旧稿的**比例位置** [LEGACY_VIEWFINDER_TOP_RATIO]。
     *
     * ★ 为什么不一刀切（2026-09-26 自查时自己拦下的一次越界）：
     *   把绝对上沿无条件套到全部模式，会把这 7 个调用点的瞄准位置**整体上移约 190dp**
     *   （拿手机的姿势、对准的习惯全变）。画布 M 帧只重设计了交接台那一屏，
     *   旧屏的设计稿仍是 y=273 —— 拿新锚点套旧稿属**未经设计的观感变更**，
     *   违反「UI 禁视觉臆测坐标」。所以交接台用新堆栈、旧屏原样不动。
     *
     * @param handoverTopPx 交接台模式下由顶部 chrome 定高堆栈算出的绝对上沿（px）。
     *   相机线程传 [frameTopPx]（`@Volatile` 副本），Compose 传 `ScannerScreen` 算出的
     *   同一个值 —— 两者同源，不会分叉。
     */
    private fun frameTopOf(handover: Boolean, viewH: Float, handoverTopPx: Float): Float =
        if (handover) handoverTopPx
        else (viewH - viewfinderSizePx) * LEGACY_VIEWFINDER_TOP_RATIO

    /** 手动输入条码弹层是否展开（画布底栏「手输钮」）。 */
    private var manualInputOpen by mutableStateOf(false)

    /** 回执出现时刻（同码再次触发也要重置计时，故单独记时刻做 key）。 */
    private var receiptAt by mutableStateOf(0L)

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 条码单次模式稳定窗口（S16c）：纯数字码首帧不立即交付——劣质帧易误读
     * （丢尾数/补前导零/短片段，2026-09-11 孙文龙新设备首扫报障实证），
     * [SINGLE_SETTLE_MS] 窗口内后续帧择优（[betterBarcode]），窗口期满才交付。
     * 非纯数字码（QR/文本码）保持旧行为首帧即交付，零行为变化。
     */
    private var singlePending: String? = null
    private val singleDeliver = Runnable { onSingleResult() }

    private val continuousMode: Boolean by lazy {
        intent?.getBooleanExtra(EXTRA_CONTINUOUS, false) ?: false
    }

    /** 文字识别模式（`EXTRA_MODE=text`）。 */
    private val textMode: Boolean by lazy {
        intent?.getStringExtra(EXTRA_MODE) == MODE_TEXT
    }

    /**
     * 交接台模式（记账「记一笔」的连扫）：取景框下方才挂流式交接清单。
     * 货架「连扫入此格」同样是 continuous，但它的码是拿去**绑货位**的，
     * 挂一张「已计入 N 件 · ¥合计」的交接单会把两件事混成一件事。
     */
    private val handoverMode: Boolean by lazy {
        intent?.getBooleanExtra(EXTRA_HANDOVER, false) ?: false
    }

    // ---- Compose 状态（一律只在主线程更新）----
    private val scannedCodes = mutableStateListOf<String>()
    private var lastCode: String? by mutableStateOf(null)
    private var torchOn by mutableStateOf(false)
    private var hasFlash by mutableStateOf(false)
    private var pulseAt by mutableStateOf(0L)
    private var showHint by mutableStateOf(false)
    /** 文字模式：实时预览的识别文字。 */
    private var previewText by mutableStateOf<String?>(null)

    /** 上次成功识别时刻（uptimeMillis）；非 Compose 状态，仅主线程读写。 */
    private var lastScanAtMs = 0L

    /** 手动对焦后的静默期：这段时间内不做周期性重对焦，别抢用户刚点出来的焦点。 */
    private var manualFocusUntilMs = 0L

    private val refocusRunnable = object : Runnable {
        override fun run() {
            if (!delivered && SystemClock.uptimeMillis() >= manualFocusUntilMs) {
                triggerFocus(centerX(), centerY())
            }
            mainHandler.postDelayed(this, REFOCUS_INTERVAL_MS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "需要相机权限才能扫码/识别", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        setContent {
            retaildemoTheme {
                ScannerScreen()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = deliverResult()
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------------------------------------------------------------- 相机

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()

                // ★★ 2026-09-20：Preview 与 ImageAnalysis **必须共用同一个 ResolutionSelector**。
                //
                // 为什么：这是两个独立的 use case，各自按**自己的宽高比**做 FILL_CENTER 居中裁剪。
                // 预览是店员**看到的**画面，分析帧是我们**判定**的画面 —— 两者宽高比不同，
                // 裁剪区域就不同，于是「取景框内」在两张图上根本不是同一个范围：
                // 实测推演（预览 4:3 / 分析 16:9，视图 9:20）下，分析帧的有效区域比取景框
                // **宽出约 1.33 倍** → 框外左右各多出一截仍会被扫到，误扫只减不除。
                //
                // 共用同一个 selector ⇒ 宽高比必然相同 ⇒ 裁剪区域必然一致 ⇒
                // `ViewfinderHit` 的 FILL_CENTER 换算精确成立。
                // （Google 官方另推荐 `ViewPort`/`UseCaseGroup` 的变换矩阵；这里不采用是因为
                //   `ViewPort` 会把**预览也裁到取景框**、变成放大镜视图，属用户未要求的观感变更。）
                //
                // ⚠️ 改动此处的纪律：两个 builder 必须继续共用这一个对象，不要各写各的。
                // ⚠️ API 实测（CameraX 1.3.4，javap 翻缓存 AAR 得到，勿凭记忆写）：
                //    · AspectRatioStrategy 只有 RATIO_4_3_FALLBACK_AUTO_STRATEGY /
                //      RATIO_16_9_FALLBACK_AUTO_STRATEGY 两个常量（**没有** FALLBACK_4_3 那个）
                //    · setResolutionStrategy 是 **ResolutionSelector.Builder** 的方法，
                //      ImageAnalysis.Builder 上**没有**（写到那儿会 Unresolved reference）
                //    · 分辨率只管画质，**裁剪区域只由宽高比决定** —— 所以两者共用一个 selector
                //      时，即便各自选中的分辨率尺寸不同，裁剪仍然对齐
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                    )
                    // 默认分辨率偏低，小文字/密集文字糊、识别慢；720p 是识别率与帧率的平衡点。
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            AndroidSize(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                if (textMode) {
                    val recognizer = TextRecognition.getClient(
                        ChineseTextRecognizerOptions.Builder().build(),
                    )
                    textRecognizer = recognizer
                    analysis.setAnalyzer(executor) { proxy ->
                        if (delivered) proxy.close() else analyzeTextFrame(recognizer, proxy)
                    }
                } else {
                    val options = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                            Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                            Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                            Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                            Barcode.FORMAT_QR_CODE, Barcode.FORMAT_ITF,
                            Barcode.FORMAT_DATA_MATRIX,
                        )
                        .build()
                    val scanner = BarcodeScanning.getClient(options)
                    barcodeScanner = scanner
                    analysis.setAnalyzer(executor) { proxy ->
                        if (delivered) proxy.close() else analyzeBarcodeFrame(scanner, proxy)
                    }
                }

                provider.unbindAll()
                val cam = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                camera = cam
                hasFlash = runCatching { cam.cameraInfo.hasFlashUnit() }.getOrDefault(false)
                lastScanAtMs = SystemClock.uptimeMillis()
                diagSessionStartAt = lastScanAtMs
                mainHandler.postDelayed(refocusRunnable, REFOCUS_INTERVAL_MS)
            } catch (e: Exception) {
                Toast.makeText(this, "相机启动失败：${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeBarcodeFrame(scanner: BarcodeScanner, proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        // TEMP-DIAG（验证完即删）：确认 InputImage.getWidth/Height 到底是旋转前还是旋转后的尺寸。
        // 实测结论：rotation=90 时 input 仍报 1280x720（**旋转前**，不交换）
        // → 所以坐标换算必须走 ViewfinderHit.imageSpaceOf 自己折算，不能直接用 input 尺寸。
        if (!diagLogged) {
            diagLogged = true
            val (dw, dh) = ViewfinderHit.imageSpaceOf(
                proxy.width, proxy.height, proxy.imageInfo.rotationDegrees,
            )
            android.util.Log.w(
                "SX_DIAG",
                "proxy=${proxy.width}x${proxy.height} rotation=${proxy.imageInfo.rotationDegrees} " +
                    "input=${input.width}x${input.height} 换算用=${dw.toInt()}x${dh.toInt()} " +
                    "view=${previewView.width}x${previewView.height} vfPx=${viewfinderSizePx}",
            )
        }
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                // ★ 2026-09-20：**只认取景框内**的码。此前取景框是纯装饰（全仓
                //   setViewPort/cropRect 零命中），整幅画面里所有条码都会被识别 ——
                //   「一堆货摆在一起都给扫上了」的根因就在这。
                val inFrame = barcodes.filter { insideViewfinder(it, proxy) }
                // TEMP-DIAG：首次真的识别到条码时，把 bbox 与所用图像坐标系一起打出来。
                // 这是「bbox 到底在旋转前还是旋转后坐标系」的**唯一实证口** ——
                // 真机扫一次即可确认（见 Gate 0 §7.2 未验项）。
                if (barcodes.isNotEmpty() && !bboxLogged) {
                    bboxLogged = true
                    val (iw, ih) = ViewfinderHit.imageSpaceOf(
                        proxy.width, proxy.height, proxy.imageInfo.rotationDegrees,
                    )
                    val bb = barcodes.first().boundingBox
                    android.util.Log.w(
                        "SX_DIAG",
                        "首条码 bbox=$bb 坐标系=${iw.toInt()}x${ih.toInt()} " +
                            "raw=${proxy.width}x${proxy.height} rot=${proxy.imageInfo.rotationDegrees} " +
                            "命中取景框=${insideViewfinder(barcodes.first(), proxy)}",
                    )
                }
                val value = inFrame.firstOrNull()?.rawValue
                // 会话诊断计数（deliver/destroy 时随 ScanDiag 一次上报，量级=每会话一行）
                diagFrames++
                if (barcodes.isNotEmpty()) {
                    diagDetected++
                    if (inFrame.isNotEmpty()) diagInFrame++ else diagOutFrame++
                    if (diagFirstDetectedAt == 0L) diagFirstDetectedAt = SystemClock.uptimeMillis()
                }
                // 框外引导（2026-09-26 老板实测「对准半天」）：识别到了但不在框内时，
                // **必须说出来**——此前静默丢弃，店员不知道相机已经看到码了。节流 800ms。
                if (barcodes.isNotEmpty() && inFrame.isEmpty() && !delivered) {
                    mainHandler.post {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastOutsideHintMs > OUTSIDE_HINT_MIN_GAP_MS) {
                            lastOutsideHintMs = now
                            outsideHintAt = now
                        }
                    }
                }
                // 卡住辅助对焦：看得到码却迟迟没成交（>1.2s），多半是脱焦——
                // 打一次中心 AF（节流 2.5s），让"对准半天"的自我修复。
                if (barcodes.isNotEmpty() && !delivered && value.isNullOrBlank()) {
                    mainHandler.post {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastScanAtMs > STUCK_FOCUS_AFTER_MS &&
                            now - lastAssistFocusMs > ASSIST_FOCUS_MIN_GAP_MS
                        ) {
                            lastAssistFocusMs = now
                            triggerFocus(centerX(), centerY())
                        }
                    }
                }
                if (!value.isNullOrBlank() && !delivered) {
                    val code = value.trim()
                    if (continuousMode) {
                        // U9：离场门/重复确认裁定收在 ScanBuffer.observe（目击记录与裁定同帧完成，
                        // 串行无竞态），主线程只做状态与反馈，避免一帧连发把主线程刷爆。
                        val decision = buffer.observe(inFrame.map { it.rawValue })
                        if (decision != null) {
                            mainHandler.post {
                                if (delivered) return@post
                                when (decision.verdict) {
                                    ScanVerdict.NEW -> onCodeRead(decision.code)
                                    ScanVerdict.DUPLICATE -> onDuplicateScan(decision.code)
                                    ScanVerdict.IGNORED -> Unit
                                }
                            }
                        }
                    } else if (!delivered) {
                        // S16c 稳定窗口：劣质帧误读不立即交付；短于 6 位的纯数字=截断碎片，直接丢弃继续扫
                        if (!isPlausibleBarcodeRead(code)) return@addOnSuccessListener
                        mainHandler.post {
                            if (delivered) return@post
                            val pending = singlePending
                            if (pending == null) {
                                singlePending = code
                                mainHandler.postDelayed(singleDeliver, SINGLE_SETTLE_MS)
                            } else {
                                singlePending = betterBarcode(pending, code)
                            }
                        }
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** 取景框边长（px）——与 [Viewfinder] 的画法**同源**，勿各算一套。 */
    private val viewfinderSizePx: Float
        get() = ViewfinderSize.value * resources.displayMetrics.density

    /**
     * 条码中心是否落在取景框内（2026-09-20 新增，治「邻货被一起扫进来」）。
     *
     * 视图↔图像换算按 PreviewView 的 `FILL_CENTER` 语义，见 [ViewfinderHit]。
     * 视图尚未布局完（宽高为 0）时**不过滤**——宁可多扫也不能把有效识别全丢掉。
     */
    private fun insideViewfinder(barcode: Barcode, proxy: ImageProxy): Boolean {
        val bb = barcode.boundingBox ?: return false
        val vw = previewView.width.toFloat()
        val vh = previewView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return true
        val size = viewfinderSizePx
        // ⚠️ 顶部 chrome 还没量到（首帧，仅交接台）时**不过滤** —— 否则取景框上沿会取 0，
        //    整幅画面除最上面一条外全被判成「框外」，扫码直接不工作。
        val topPx = frameTopOf(handoverMode, vh, frameTopPx)
        if (topPx <= 0f) return true
        // 与 [Viewfinder] 的画法**调同一个函数**，同源是结构性的（不靠注释约定）
        val (left, top) = ViewfinderHit.frameLeftTop(vw, size, topPx)
        // ⚠️ 图像坐标系必须**自己按 rotation 折算** —— 实测 InputImage.getWidth/Height
        //    在 rotation=90 时仍返回旋转前的 1280x720（不交换），而 bbox 按官方文档
        //    是在**旋转后**的 buffer 坐标系里。直接用 input 尺寸会竖屏 90° 错位。
        val (imgW, imgH) = ViewfinderHit.imageSpaceOf(
            proxy.width, proxy.height, proxy.imageInfo.rotationDegrees,
        )
        return ViewfinderHit.hit(
            cx = bb.exactCenterX(),
            cy = bb.exactCenterY(),
            viewW = vw,
            viewH = vh,
            imgW = imgW,
            imgH = imgH,
            frameLeft = left,
            frameTop = top,
            frameSize = size,
            // 2026-09-26 老板实测「要对准半天才能扫出来」：画的 236dp 框太小，命中判定按
            // 框的 30% 外扩（画的框不动）；一整框之外的邻货仍被挡住，防串扫底线不破。
            inflatePx = size * HIT_INFLATE_RATIO,
        )
    }

    private fun analyzeTextFrame(recognizer: TextRecognizer, proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim()
                if (text.isNotEmpty() && !delivered) {
                    mainHandler.post { onTextPreview(text) }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onTextPreview(text: String) {
        // 相同文字每帧重复回调，去重避免 UI 疯狂刷新；文字变了才更新预览。
        if (text == previewText) return
        previewText = text
        lastScanAtMs = SystemClock.uptimeMillis()
        showHint = false
    }

    /**
     * 读到一件**新码**（2026-09-26 老板令：每扫一件确认一次）。
     *
     * ★ 与旧流程的根本区别：**读到 ≠ 计入**。
     *   这里只把该件挂成 M0 待确认，件数 / 金额 / 清单**一律不动**；
     *   等店员在卡上点「计入」才真的算数（见 [onConfirmPending]）。
     *
     * 动因是老板原话：「扫码太灵敏…误扫概率太大」「每扫一件弹一次确认…
     * **我这边和快递不一样，每单并不多**」—— 一单就几件，一件一确认不拖速度，
     * 却能把误扫拦在入账之前。
     */
    private fun onCodeRead(code: String) {
        // 同一件还在等裁决时相机又触发：忽略（它已经在问你，别把问题重复堆上来）
        if (pending?.code == code) return
        lastScanAtMs = SystemClock.uptimeMillis()
        showHint = false
        // 这一声是「读到了」（SCAN_OK）；真正的「落筐声」(ITEM_ADD) 留到点「计入」那一刻
        playReceiptTone(ReceiptM.M0_PENDING)
        // 上一件还没裁决就被新码顶替 → 记下它的名字（新卡上明说「没算」），
        // 并让 buffer **忘掉它**，否则那件货再也扫不回来（见 ScanBuffer.forget）。
        // ⚠️ 顶替必须**说出来**：静默作废是最危险的失败型（店员以为算了，其实没算）。
        val replaced = pending
        if (replaced != null) {
            pendingReplaced = replaced.name
            buffer.forget(replaced.code)
        } else {
            pendingReplaced = null
        }
        showReceiptFor(code, duplicate = false)
    }

    /**
     * 店员点「计入」—— **这一下才真的算进这一单**。
     *
     * 音效不在这里放：卡上那颗按钮自带 `Sfx.ITEM_ADD`（落筐声），再放一次会双响。
     */
    private fun onConfirmPending() {
        val p = pending ?: return
        pending = null
        pendingReplaced = null
        val code = p.code
        scannedCodes.add(code)
        lastCode = code
        lastScanAtMs = SystemClock.uptimeMillis()
        pulseAt = lastScanAtMs
        sessionQty[code] = (sessionQty[code] ?: 0) + 1
        val decided = p.copy(
            qty = ScanReceiptSpec.piecesOf(ReceiptM.M1_HIT, sessionQty[code] ?: 0),
            state = ReceiptM.M1_HIT,
        )
        receipt = decided
        receiptAt = SystemClock.uptimeMillis()   // 重新计时：绿卡停一会儿再自己退
        val merged = HandoverLiveListModel.mergeHandover(handoverRows.toList(), decided)
        handoverRows.clear()
        handoverRows.addAll(merged)
    }

    /**
     * 店员点「不算」—— 撤掉，就像没扫过。
     *
     * ★ **必须让 buffer 忘掉它**（[ScanBuffer.forget]）：`observe` 在裁定 NEW 的那一刻
     *   就把码记进了"已扫过"，而它现在只是"读到过"。不 forget 的话，这件货再扫只会得到
     *   DUPLICATE（卡上写「本次未计入」）—— **误扫之后再也补不回来**，只能走手动输入。
     *   误扫→纠正是最常见的动作，必须能重来。
     */
    private fun onDiscardPending() {
        val p = pending ?: return
        pending = null
        pendingReplaced = null
        buffer.forget(p.code)
        // 撤回音（摘下来）由卡上那颗按钮的 directTap 放，不在这里重复播
    }

    /** 音效一律按三态真源 [ScanReceiptSpec] 播（rule 14④：只走语义事件表，不直接引 R.raw）。 */
    private fun playReceiptTone(state: ReceiptM) {
        ScanReceiptSpec.toneOf(state)?.let { ScanFeedback.play(this, it) }
    }

    /** 回执请求序号：后扫的件顶掉先前的，避免慢查询回来盖掉新回执。 */
    private var receiptSeq = 0

    /**
     * 出回执卡。
     *
     * 纪律：**绝不为了查库拖住扫码**。查过的码走会话缓存直接上屏；
     * 没查过的异步查完再上屏（本地命中通常 <50ms），查不到就翻成「库里没有」灰卡并计入灰档。
     * 用序号防串台：慢查询回来时若已被后扫的件顶掉，就丢弃。
     *
     * 回执与交接清单**同源**：同一个 [ScanReceipt] 一次判定，两处显示不会说两套话。
     */
    private fun showReceiptFor(code: String, duplicate: Boolean) {
        val seq = ++receiptSeq
        receiptAt = SystemClock.uptimeMillis()
        uiScope.launch {
            val p = if (lookupCache.containsKey(code)) {
                lookupCache[code]
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { productRepo.getByBarcode(code) }.getOrNull()
                }.also { lookupCache[code] = it }
            }
            if (seq != receiptSeq) return@launch      // 已被后扫的件顶掉
            // ① 先按既有真源定"已裁决"的态 —— 「库外优先于重复」这条规则收在 ScanReceiptSpec，不在这重写；
            // ② 再把 M1「命中新件」**升格成 M0 待确认**：新口径下「命中」只说明有了候选，
            //    还没被店员裁决过。M1 从此**只能由 onConfirmPending 产出来**。
            val decided = ScanReceiptSpec.stateOf(
                inStore = p != null,
                duplicate = duplicate || scannedCodes.contains(code),
            )
            val state =
                if (decided == ReceiptM.M1_HIT) ReceiptM.M0_PENDING else decided
            val r = ScanReceipt(
                code = code,
                name = p?.name ?: code,
                spec = p?.spec.orEmpty(),
                price = p?.sellPrice ?: 0.0,
                qty = ScanReceiptSpec.piecesOf(state, sessionQty[code] ?: 0),
                state = state,
            )
            if (state == ReceiptM.M0_PENDING) {
                // 待确认：卡上等裁决 —— **不进清单、不计件数**（这一条是本次需求的全部要害）
                pending = r
                receipt = null
            } else {
                // M2 / M3 是"已经裁定好"的提醒卡：按老板令既不重复录、也不收库外货，故都不进清单
                pending = null
                receipt = r
            }
        }
    }

    /**
     * 回扫本次已扫过的码：**只提醒、绝不录入**（2026-09-20 老板令）。
     *
     * 原 U9 会弹底部确认条「再计一件 / 忽略」交店员裁决 —— 已废。
     * 加数量一律到清单里手动改：扫码太灵敏，自动累加（或一点就累加）会放大误扫。
     */
    private fun onDuplicateScan(code: String) {
        // ★ M0 待确认卡**常驻等回答**（R6 设计；2026-09-27 老板实测回归：
        //   「点确认的窗口相机移走就掉了」）。同一码过离场门后的重复目击，
        //   不得把正在等裁决的卡顶掉——相机挪开/晃动/盯着不动，卡都必须还在，
        //   等店员点「计入 / 不算」二选一。
        if (pending?.code == code) return
        lastScanAtMs = SystemClock.uptimeMillis()
        showHint = false
        // M2：语义上这不是"出错"，是"这件已经计过了"——DUPLICATE 自带 90ms 震动，
        // 与真正的扫码失败（尖锐嘀低）明确区分。店员听音即知该不该再扫。
        playReceiptTone(ReceiptM.M2_DUP)
        // sessionQty 不增 —— 本次未计入
        showReceiptFor(code, duplicate = true)
    }

    /**
     * 手动输入条码（画布「底栏-手输钮」）—— **与相机扫到走同一条判定**，不另开捷径。
     *
     * 重复判据用「本次会话**已裁决计入**的码」= `scannedCodes`，与 `ScanBuffer` 的
     * 重复裁定同口径（ScanBuffer 只是多带一个 1200ms 离场门来吞相机连帧，
     * 手输不存在连帧问题）。
     *
     * ⚠️ 库外码重复输入时仍出 M3 灰卡（`ScanReceiptSpec.stateOf` 先问「是不是店里的」），
     * 不会被说成「重复扫到」—— 它压根没进过单。
     *
     * ★ 走的是与相机**同一条入口** [onCodeRead]：手输的件同样要过「计入 / 不算」那道闸
     *   —— 老板令「每扫一件弹一次确认」，手输也是一件。
     */
    private fun onManualBarcode(raw: String) {
        val code = raw.trim()
        if (code.isEmpty() || delivered) return
        if (scannedCodes.contains(code)) onDuplicateScan(code) else onCodeRead(code)
    }

    private fun onSingleResult() {
        if (delivered) return // 窗口期内用户已手动取消/回退，绝不补交
        val code = singlePending ?: return
        singlePending = null
        scannedCodes.add(code)
        lastCode = code
        ScanFeedback.play(this, ScanFeedback.Sfx.SCAN_OK)
        deliverResult()
    }

    // ---------------------------------------------------------------- 对焦 / 手电筒

    private fun centerX(): Float = previewView.width.takeIf { it > 0 }?.div(2f) ?: 0f

    private fun centerY(): Float = previewView.height.takeIf { it > 0 }?.div(2f) ?: 0f

    private fun triggerFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val w = previewView.width
        val h = previewView.height
        if (w <= 0 || h <= 0) return
        val px = x.coerceIn(0f, w.toFloat())
        val py = y.coerceIn(0f, h.toFloat())
        val point = previewView.meteringPointFactory.createPoint(px, py)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).setAutoCancelDuration(AUTO_CANCEL_SEC, TimeUnit.SECONDS).build()
        runCatching { cam.cameraControl.startFocusAndMetering(action) }
    }

    private fun focusAt(x: Float, y: Float) {
        triggerFocus(x, y)
        manualFocusUntilMs = SystemClock.uptimeMillis() + MANUAL_FOCUS_HOLD_MS
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        val next = !torchOn
        torchOn = next
        // 货架底下光线暗，手电筒是刚需；设备不支持时 enableTorch 的 future 会失败，静默忽略即可。
        runCatching { cam.cameraControl.enableTorch(next) }
    }

    // ---------------------------------------------------------------- 结果

    private fun deliverResult() {
        if (delivered) return
        logScanDiag(deliveredFlag = true)
        // 还有一件在等裁决 → 先拦下，不放它出门。
        // 绝不能带着「没答复的问题」离开：那一件既没计入、也不在清单里，走了就成了
        // 「明明扫过却没了」—— 不崩不报错，只有对账时才发现（本项目最怕的一类失败）。
        val p = pending
        if (p != null) {
            Toast.makeText(
                this,
                "还有「${p.name}」没确认 —— 先点「计入」，或者点「不算」",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        delivered = true
        if (textMode) {
            // 文字模式：只有店员确认过文字才回传；没识别到就取消。
            val text = previewText
            if (text.isNullOrBlank()) {
                setResult(RESULT_CANCELED)
            } else {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
            }
        } else {
            val codes = scannedCodes.toList()
            if (codes.isEmpty()) {
                setResult(RESULT_CANCELED)
            } else {
                val out = Intent()
                    .putStringArrayListExtra(EXTRA_BARCODES, ArrayList(codes))
                    .putExtra(EXTRA_BARCODE, codes.last())
                // 交接台：这一下按的是「确定 · 保存账单」→ 告诉调用方**存了它**
                if (handoverMode) out.putExtra(EXTRA_SAVE_BILL, true)
                setResult(RESULT_OK, out)
            }
        }
        finish()
    }

    /** 会话诊断一行上报（deliver / destroy 各一次）：识别到了没有、命中比例、成交延迟。 */
    private fun logScanDiag(deliveredFlag: Boolean) {
        if (textMode || diagFrames == 0) return
        val now = SystemClock.uptimeMillis()
        val firstDetMs = if (diagFirstDetectedAt > 0) diagFirstDetectedAt - diagSessionStartAt else -1L
        val latMs = if (deliveredFlag && diagFirstDetectedAt > 0) now - diagFirstDetectedAt else -1L
        appLogger.log(
            com.retaildemo.v2.data.repo.LogPipeline.LEVEL_INFO, "ScanDiag",
            "mode=${if (handoverMode) "handover" else if (continuousMode) "continuous" else "single"} " +
                "frames=$diagFrames det=$diagDetected in=$diagInFrame out=$diagOutFrame " +
                "delivered=$deliveredFlag firstDetMs=$firstDetMs latMs=$latMs",
        )
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refocusRunnable)
        mainHandler.removeCallbacks(singleDeliver)
        logScanDiag(deliveredFlag = delivered)
        delivered = true
        runCatching { barcodeScanner?.close() }
        runCatching { textRecognizer?.close() }
        uiScope.cancel()          // 回执卡的查库协程随页面一起收掉，避免泄漏
        executor.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    @Composable
    private fun ScannerScreen() {
        var pulsing by remember { mutableStateOf(false) }

        // ── 顶部 chrome 的**定高堆栈** → 取景框上沿 ──────────────────────────
        //    状态栏 inset + 顶栏 48 +（交接台）间隙 4 + 单据条 44 + 间隙 12。
        //    与画布 M 帧的堆栈逐值同源（`_reports/redraw/gen_scan_handover_spec.py`）。
        //    行高由 TopBarHeight / BillBarHeight 钉死，所以这个算式不会跟布局漂。
        val density = LocalDensity.current
        val statusBarDp = with(density) {
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        }
        val frameTopValueDp = ViewfinderHit.handoverFrameTop(
            statusBarPx = statusBarDp.value,
            topBarPx = TopBarHeight.value,
            billBarGapPx = BillBarGap.value,
            billBarPx = if (handoverMode) BillBarHeight.value else 0f,
            frameGapPx = FrameTopGap.value,
        )
        val frameTopDp = frameTopValueDp.dp
        val frameTopPxLocal = with(density) { frameTopDp.toPx() }
        // 相机分析线程读的那份副本（命中判定用）
        SideEffect { setFrameTop(frameTopPxLocal) }

        // 命中脉冲：条码识别成功瞬间取景框变绿 + 打钩气泡，约 200ms 后复原。
        LaunchedEffect(pulseAt) {
            if (pulseAt == 0L) return@LaunchedEffect
            pulsing = true
            delay(PULSE_MS)
            pulsing = false
        }

        // 久扫不出：给温和提示，不打断扫描（每 1s 自检一次）。
        LaunchedEffect(Unit) {
            while (!delivered) {
                delay(HINT_CHECK_MS)
                showHint = lastScanAtMs > 0 &&
                    SystemClock.uptimeMillis() - lastScanAtMs > HINT_DELAY_MS
            }
        }

        // 框外引导（B14 扫码优化）：相机已经看到码但在框外 → 显示 1.5s 引导，
        // 把"静默丢弃"变成"告诉你往哪移"（显示与隐藏都在主线程，节流在分析器侧）。
        LaunchedEffect(outsideHintAt) {
            if (outsideHintAt > 0L) {
                outsideHintVisible = true
                delay(1500)
                outsideHintVisible = false
            }
        }

        // 回执卡自动消失：留住「刚扫那件」的记忆，但不常驻挡视线。
        LaunchedEffect(receiptAt) {
            val t = receiptAt
            delay(RECEIPT_HOLD_MS)
            if (receiptAt == t) receipt = null
        }

        Box(modifier = Modifier.fillMaxSize().background(NightBg)) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            // 取景框 + 半透明遮罩（不吃点击）。
            Viewfinder(pulsing = pulsing, handoverFrameTopPx = frameTopPxLocal)

            // 点击对焦层：在预览之上、按钮之下（Compose 命中测试自顶向下，按钮先吃事件）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset -> focusAt(offset.x, offset.y) }
                    },
            )

            // ── 顶部 chrome：顶栏 + （交接台）单据条 ─────────────────────────────
            //     高度由定高常量决定，取景框上沿已在 ScannerScreen 顶部按同一个堆栈算好
            //     （见 frameTopDp / frameTopPx 字段）。
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                // 稿 3:1780 顶栏：关闭 + 中央 title + 手电筒。
                // ⚠️ 行高钉 TopBarHeight（画布 M 帧「顶栏」），下面取景框上沿才算得准。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(TopBarHeight)
                        .padding(horizontal = 8.dp),
                ) {
                    IconButton(
                        onClick = { deliverResult() },
                        modifier = Modifier.size(TopBarIconSize),
                    ) {
                        Icon(Icons.Default.Close, "取消", tint = Color.White)
                    }
                    Text(
                        if (textMode) "扫文字 / 数字" else "扫商品码 / 货位码",
                        fontSize = Design.FontMd.sp,
                        lineHeight = 20.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    if (hasFlash) {
                        IconButton(
                            onClick = { toggleTorch() },
                            modifier = Modifier.size(TopBarIconSize),
                        ) {
                            Icon(
                                imageVector = if (torchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                                contentDescription = if (torchOn) "关闭手电筒" else "打开手电筒",
                                tint = if (torchOn) NightWarning else Color.White,
                            )
                        }
                    } else {
                        Spacer(Modifier.size(TopBarIconSize))
                    }
                }
                // 画布 M 帧「单据条」：客户 · 已扫 N 件 · ¥合计。
                // 只在交接台挂出——货架「连扫入此格」的码是拿去绑货位的，挂一张钱数会把两件事混成一件。
                if (handoverMode) {
                    Spacer(Modifier.height(BillBarGap))
                    HandoverBillBar(
                        customer = handoverCustomer,
                        summary = HandoverLiveListModel.summarizeHandover(handoverRows.toList()),
                    )
                }
            }

            if (!textMode) {
                // 取景框下方那一栏：取景提示（常驻）+ 回执卡（刚扫那件）+ 流式交接清单（这一整趟）。
                // 位置由 frameTopPx 推出 —— 与取景框**同一个上沿值**，框一动这一栏跟着动。
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // 与取景框**同一个取值口**：框一动，这一栏跟着动，不会错位。
                    val slotTop = frameTopDp + ViewfinderSize + FrameSlotGap
                    // 清单高度上限 = 真实剩余空间（矮屏上不能顶到底栏）。
                    // 画布 M 帧给的是 4 行 × 56dp = 224dp 的参考值，这里取「参考值与实际可用高度的小者」。
                    val listMax = (maxHeight - slotTop - SlotContentReserve - BottomReserve)
                        .coerceIn(0.dp, HandoverListMaxHeight)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = slotTop),
                    ) {
                        // 画布 M 帧「取景-提示」：常驻在框下，不因为出了回执卡就消失。
                        Text(
                            "对准条码，听到「嘀」就松手",
                            fontSize = Design.FontSm.sp,
                            lineHeight = 18.sp,
                            color = Color.White.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center,
                        )
                        // 显示的是 `pending ?: receipt`：**待确认卡常驻**（它在等问题回答），
                        // 已裁定的提醒卡（M2 重复 / M3 库外）照旧到点自己退。
                        val r = pending ?: receipt
                        if (r != null) {
                            Spacer(Modifier.height(8.dp))
                            val isPending = pending != null
                            ScanReceiptCard(
                                receipt = r,
                                // 顶替告知**任何态都显示**：新码可能落在 M2/M3 上，
                                // 那时更要说清"上一件没算"，否则那条信息就丢了。
                                confirmHint = pendingReplaced?.let { "上一件「$it」没确认 · 没算" },
                                onConfirm = if (isPending) ({ onConfirmPending() }) else null,
                                onDiscard = if (isPending) ({ onDiscardPending() }) else null,
                            )
                        }
                        if (handoverMode && handoverRows.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            HandoverLiveList(handoverRows.toList(), maxHeight = listMax)
                        }
                    }
                }
            }

            // 底部：交接台=完成 + 手动输入 并排 / 其余=计数 + 完成
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                ScannerBottomBar(
                    textMode = textMode,
                    continuous = continuousMode,
                    handover = handoverMode,
                    count = scannedCodes.size,
                    last = lastCode,
                    preview = previewText,
                    hint = showHint,
                    outsideHint = outsideHintVisible,
                    onDone = { deliverResult() },
                    onManualInput = { manualInputOpen = true },
                )
            }

            // 画布 M 帧「底栏-手输钮」的落点：手动输入条码弹层。
            if (manualInputOpen) {
                ManualBarcodeDialog(
                    onDismiss = { manualInputOpen = false },
                    onSubmit = { raw ->
                        manualInputOpen = false
                        onManualBarcode(raw)
                    },
                )
            }
        }
    }

    @Composable
    private fun ScannerBottomBar(
        textMode: Boolean,
        continuous: Boolean,
        handover: Boolean,
        count: Int,
        last: String?,
        preview: String?,
        hint: Boolean,
        outsideHint: Boolean,
        onDone: () -> Unit,
        onManualInput: () -> Unit,
    ) {
        Surface(
            color = NightBg.copy(alpha = 0.62f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (handover) {
                HandoverBottomBar(
                    hint = hint,
                    outsideHint = outsideHint,
                    onDone = onDone,
                    onManualInput = onManualInput,
                )
            } else {
                StandardScannerBottomBar(
                    textMode = textMode,
                    continuous = continuous,
                    count = count,
                    last = last,
                    preview = preview,
                    hint = hint,
                    outsideHint = outsideHint,
                    onDone = onDone,
                    onManualInput = onManualInput,
                )
            }
        }
    }

    /**
     * 交接台**之外**的底栏（单次扫码 / 连扫入格等旧调用点）—— 版式与文案原样保留，
     * 唯一改动是把原来那个 `// TODO` 的「手动输入条码」ghost 条换成**真按钮**（修 D6），
     * 因为枪没电 / 条码磨花时它是这些流程唯一的出路。
     */
    @Composable
    private fun StandardScannerBottomBar(
        textMode: Boolean,
        continuous: Boolean,
        count: Int,
        last: String?,
        preview: String?,
        hint: Boolean,
        outsideHint: Boolean,
        onDone: () -> Unit,
        onManualInput: () -> Unit,
    ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (textMode) {
                            Text(
                                "识别到：${preview ?: "—"}",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (preview.isNullOrBlank()) "对准货上的字，保持 10–20cm"
                                else "确认无误后点「确认」，识别不准就再对准一点",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                            )
                        } else if (continuous) {
                            Text(
                                "已扫 $count 件",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "最后一件：${last ?: "—"}",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                            )
                        } else {
                            Text(
                                "对准商品条码，保持 10–20cm",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (outsideHint) {
                            Text(
                                "已识别到条码 · 请把它移进取景框",
                                color = NightWarning,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        } else if (hint) {
                            Text(
                                "扫不出？离远一点、开手电筒，或点击屏幕对焦",
                                color = NightWarning,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    if (textMode) {
                        Spacer(Modifier.width(10.dp))
                        PrimaryActionButton(
                            text = "确认",
                            onClick = onDone,
                            enabled = !preview.isNullOrBlank(),
                            sfx = ScanFeedback.Sfx.COMMIT_OK,
                            height = Design.MinTouchTarget.dp,
                        )
                    } else if (continuous) {
                        Spacer(Modifier.width(10.dp))
                        PrimaryActionButton(
                            text = "完成${if (count > 0) "（$count）" else ""}",
                            onClick = onDone,
                            sfx = ScanFeedback.Sfx.COMMIT_OK,
                            height = Design.MinTouchTarget.dp,
                        )
                    }
                }
                // 稿 3:1780：「用扫描枪更顺手」副文案 + 「手动输入条码」备用通道。
                // ★ 交接台之外（单次扫码 / 连扫入格等旧调用点）保留这条备用通道 ——
                //   枪没电或条码磨花时它是唯一的出路，不能只给交接台。交接台里它改成
                //   底栏并排的「手输钮」（画布 M 帧），不在这里重复出现。
                if (!textMode) {
                    Text(
                        "用扫描枪更顺手 —— 这是没带枪时的备用通道",
                        fontSize = Design.FontXs.sp,
                        lineHeight = 15.sp,
                        color = Color.White.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    ManualInputButton(
                        onClick = onManualInput,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .navigationBarsPadding(),
                    )
                }
            }
    }

    /**
     * 交接台底栏（画布 M 帧「底栏」x16 y806 w358 h72）：
     * **完成钮 + 手输钮并排两个真按钮**（画布 完成钮 180×52 / 手输钮 146×52）。
     *
     * ★ 这里退役了旧底栏的两样东西，理由写在代码里免得被"恢复"：
     *   1. **「已扫 N 件 / 最后一件：<条码原文>」** —— 件数与合计已在顶部单据条上、
     *      逐件明细在框下方的交接清单里，这里再来一份就是三处说同一件事；
     *      而「最后一件：<条码原文>」恰是 D1「盲扫」的原症状（只报一串条码，不报品名）。
     *   2. **通栏 ghost「手动输入条码」** —— 它是个 `// TODO` 死按钮（点了没反应，D6）。
     *      画布本来就把手输做成**底栏里的一个钮**，这里照画布落地成真按钮。
     */
    @Composable
    private fun HandoverBottomBar(
        hint: Boolean,
        outsideHint: Boolean,
        onDone: () -> Unit,
        onManualInput: () -> Unit,
    ) {
        Column {
            if (outsideHint) {
                Text(
                    "已识别到条码 · 请把它移进取景框",
                    color = NightWarning,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            } else if (hint) {
                Text(
                    "扫不出？离远一点、开手电筒，或点击屏幕对焦",
                    color = NightWarning,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                PrimaryActionButton(
                    // 老板令 2026-09-26：「上面取景框 / 中间商品条栏 / 底下一个确定，
                    // 按下去**保存账单**就完事了」—— 这一下同时是人工确认（防误扫）与落账。
                    // 件数不在这里重复报：它就在上方单据条上。
                    text = "确定 · 保存账单",
                    onClick = onDone,
                    sfx = ScanFeedback.Sfx.COMMIT_OK,
                    height = BottomActionHeight,
                    modifier = Modifier.weight(1f),
                )
                ManualInputButton(
                    onClick = onManualInput,
                    modifier = Modifier.width(ManualInputButtonWidth),
                )
            }
        }
    }

    /**
     * 手动输入条码按钮（画布「底栏-手输钮」）：备用通道，但不是**死**通道。
     * 夜视档 ghost 描边钮 —— 相机页是深底，不能用亮色档的 `SecondaryActionButton`。
     */
    @Composable
    private fun ManualInputButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
        Surface(
            color = Color.White.copy(alpha = 0.12f),
            shape = RoundedCornerShape(Design.ButtonRadius.dp),
            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.28f)),
            modifier = modifier
                .height(BottomActionHeight)
                .directTap(sfx = ScanFeedback.Sfx.KEY, onClick = onClick),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "手动输入条码",
                    color = Color.White,
                    fontSize = Design.FontMd.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    /**
     * 单据条（画布 M 帧「单据条」x16 y88 w358 h44）：**客户 · 已扫 N 件 · ¥合计**。
     *
     * 它就是快递交接里的「大计数」——挂在顶栏之下、取景框之上，
     * 边扫边抬眼就能确认「这一趟几件、多少钱」，不用低头翻清单。
     *
     * 只在记账交接台挂出：货架「连扫入此格」拿的是同一个连续扫码页，
     * 但那些码是去**绑货位**的，给它们挂一张钱数会把两件事说成一件。
     */
    @Composable
    private fun HandoverBillBar(customer: String, summary: HandoverSummary) {
        Surface(
            color = NightBg.copy(alpha = 0.72f),
            shape = RoundedCornerShape(Design.CardRadius.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BillBarHeight)
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    text = customer.ifBlank { "散客" },
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = Design.FontMd.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(Color.White.copy(alpha = 0.22f)),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "已扫 ${summary.pieces} 件",
                    color = Color.White,
                    fontSize = Design.FontMd.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "¥" + ScanReceiptSpec.money(summary.amount),
                    color = NightSuccess,
                    fontSize = Design.FontMd.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    /**
     * 手动输入条码弹层 —— 画布 M 帧「底栏-手输钮」的落点。
     *
     * ★ 它修的是 Gate 0 §二 **D6**：原按钮只写着 `// TODO: 唤起手动输入条码弹层`，
     *   画在屏幕上、点下去没反应（一个"看得见却不存在"的按钮是这页最差的东西）。
     *
     * ★ 它**不是补录入口**（§5.4 明令去掉所有补录入口）——它不改任何口径：
     *   提交后走 [onManualBarcode]，与相机扫到**同一条判定**，
     *   于是「库外码忽略」「重复码不额外计入」这两条老板令对手输一样成立。
     *   它只是换了一个**读码来源**（枪没带 / 条码磨花）。
     */
    @Composable
    /**
     * 手动录入弹窗（B15 改版，2026-09-27 老板令「手输条码改成搜索选择」）：
     * 不知道条码就**按名字/规格搜出来点选**——本地全量商品毫秒级候选；
     * 输入本身像完整条码（纯数字 ≥6 位）且搜不到候选时，才保留旧「按条码录入」路径。
     * 点选/录入的码都走同一条 [onSubmit] 判定流程（回执卡照常问「计入 / 不算」）。
     */
    private fun ManualBarcodeDialog(
        onDismiss: () -> Unit,
        onSubmit: (String) -> Unit,
    ) {
        var value by remember { mutableStateOf("") }
        var candidates by remember { mutableStateOf<List<ProductEntity>>(emptyList()) }
        LaunchedEffect(value) {
            val q = value.trim()
            delay(150)
            candidates = if (q.length < 2) emptyList() else runCatching {
                productRepo.searchLocal(q).take(8)
            }.getOrDefault(emptyList())
        }
        val looksLikeCode = value.trim().let { it.all { c -> c.isDigit() } && it.length >= 6 }
        val hasExact = candidates.any {
            it.barcode.equals(value.trim(), true) || it.sku.equals(value.trim(), true)
        }
        SanxAlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    "搜商品录入",
                    fontSize = Design.FontMdPlus.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "按名称/规格/条码搜，点一下就录入；不知道条码也能找到货。",
                        fontSize = Design.FontSm.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.filterNot { c -> c.isWhitespace() || c == '\n' } },
                        singleLine = true,
                        placeholder = {
                            Text(
                                "商品名 / 规格 / 条码",
                                fontSize = Design.FontMd.sp,
                                lineHeight = 20.sp,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (candidates.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            candidates.forEach { p ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            val code = p.barcode?.ifBlank { p.sku.orEmpty() } ?: p.sku.orEmpty()
                                            if (code.isNotBlank()) onSubmit(code)
                                        }
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            p.name,
                                            fontSize = Design.FontMd.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            lineHeight = 17.sp,
                                        )
                                        val meta = listOfNotNull(
                                            p.spec.takeIf { !it.isNullOrBlank() },
                                            p.model?.takeIf { it.isNotBlank() },
                                        ).joinToString(" · ")
                                        if (meta.isNotBlank()) {
                                            Text(
                                                meta,
                                                fontSize = Design.FontXs.sp,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                lineHeight = 14.sp,
                                            )
                                        }
                                    }
                                    PriceText(
                                        price = p.sellPrice,
                                        color = Danger,
                                        integerSize = 14.sp,
                                        symbolSize = 10.sp,
                                        decimalSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            // ⚠️ 这里是「录入」**不是**「计入」：它只把码交给判定流程，
            //    紧接着卡上还会问一次「计入 / 不算」。
            //    同一个词在同一流程里出现两次、含义还不同，店员会以为"我不是刚点过计入吗"。
            confirmText = "按条码录入",
            confirmEnabled = looksLikeCode && !hasExact,
            onConfirm = { onSubmit(value.trim()) },
            dismissText = "取消",
            onDismiss = onDismiss,
        )
    }

    /**
     * 居中取景框：半透明遮罩 + 四角 + 中线；命中脉冲时整框变绿。
     *
     * ★ 尺寸是**绝对量**，不是屏幕比例（2026-09-18 修正，血账）：
     * 原实现用比例式 `frameW = w*0.78` / `frameH = min(frameW*0.55, h*0.40)`，
     * 在 390dp 屏上得 **304 x 167**，而稿要求 **236 x 236 正方形**：
     *   · 宽差 **+68 dp**（框比稿宽出一大截，几乎顶到屏幕左右）
     *   · 高差 **−69 dp**（稿是正方形，代码做成扁矩形）
     *   · 左距 42.9 vs 稿 77.0（差 −34 dp）、上距 279.3 vs 稿 273.0（差 +6 dp）
     * 更要命的是**比例式会随屏宽漂移**：360dp 屏 → 280.8x154.4，411.4dp 屏 → 320.9x176.5，
     * 而稿恒为 236x236 —— 同一份稿在不同设备上不可能既满足 0.78 又满足 236。
     *
     * 三方对齐证据（画布 ↔ 设计稿 PNG ↔ 代码，画布与 PNG 逐值相同）：
     *   取景框 宽/高  画布 236/236   PNG 实测 x=77.0..312.5（跨 236.0）、y=273.0..508.5（跨 236.0）
     *   框内中线     画布 x93 w204 @y390   PNG 实测 y=390.0..391.5
     * 修正为绝对值后，在 390dp 屏上三者一致；在别的屏上也保持 236x236、水平居中。
     * （竖直位置沿用原 `- h*0.07f` 的视觉上移量：稿是 844 高屏上的 273 绝对位置，
     *   换到不同屏高时按同比例上移才符合「光学居中略偏上」的取景习惯。）
     */
    @Composable
    private fun Viewfinder(pulsing: Boolean, handoverFrameTopPx: Float) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // 稿 3:1781：236x236 正方形取景框（绝对值，勿改回比例式）
            val frameW = ViewfinderSize.toPx()
            val frameH = frameW
            // ⚠️ 与 insideViewfinder() 调**同一个函数 + 同一个上沿值** —— 同源是结构性的。
            // `frameTopOf` 是唯一取值口：交接台用它上方那条**定高堆栈**算出的绝对上沿
            // （由 ScannerScreen 以参数传入 → 是 Compose 追踪的值，会正确重绘）；旧屏沿用旧稿比例。
            val topPx = frameTopOf(handoverMode, h, handoverFrameTopPx)
            val (left, top) = ViewfinderHit.frameLeftTop(w, frameW, topPx)
            val right = left + frameW
            val bottom = top + frameH

            val mask = NightBg.copy(alpha = 0.55f)
            drawRect(color = mask, topLeft = Offset.Zero, size = Size(w, top))
            drawRect(color = mask, topLeft = Offset(0f, bottom), size = Size(w, h - bottom))
            drawRect(color = mask, topLeft = Offset(0f, top), size = Size(left, frameH))
            drawRect(color = mask, topLeft = Offset(right, top), size = Size(w - right, frameH))

            val color = if (pulsing) NightSuccess else Color.White
            val stroke = 4.dp.toPx()
            // 稿 rx=20 是**圆角矩形**的整段圆弧，画成四角臂时要按弧长折算：
            // 整段直角臂长 = 20（水平）+ 20（竖直）；用二次贝塞尔近似圆弧，臂取 20*0.55 ≈ 11dp。
            val corner = ViewfinderCorner.toPx()

            drawLine(color, Offset(left, top), Offset(left + corner, top), stroke, StrokeCap.Square)
            drawLine(color, Offset(left, top), Offset(left, top + corner), stroke, StrokeCap.Square)
            drawLine(color, Offset(right, top), Offset(right - corner, top), stroke, StrokeCap.Square)
            drawLine(color, Offset(right, top), Offset(right, top + corner), stroke, StrokeCap.Square)
            drawLine(color, Offset(left, bottom), Offset(left + corner, bottom), stroke, StrokeCap.Square)
            drawLine(color, Offset(left, bottom), Offset(left, bottom - corner), stroke, StrokeCap.Square)
            drawLine(color, Offset(right, bottom), Offset(right - corner, bottom), stroke, StrokeCap.Square)
            drawLine(color, Offset(right, bottom), Offset(right, bottom - corner), stroke, StrokeCap.Square)

            val midY = (top + bottom) / 2f
            drawLine(
                color = color.copy(alpha = 0.35f),
                start = Offset(left + 8.dp.toPx(), midY),
                end = Offset(right - 8.dp.toPx(), midY),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }

    companion object {
        /**
         * 取景框边长（dp）—— 稿 3:1781 是 **236x236 正方形**，属绝对值。
         * 画布与设计稿 PNG 逐值相同（PNG 实测 x=77.0..312.5、y=273.0..508.5，跨 236.0）。
         * ⚠️ 勿改回 `w * 0.78` 之类的比例式：那会随屏宽漂移，360dp→280.8、411.4dp→320.9，
         * 永远无法同时满足「236」与「0.78」。
         * 注：Kotlin 的 `const val` 只允许基本类型/String，Dp 必须用普通 `val`。
         */
        private val ViewfinderSize = 236.dp

        /**
         * 四角臂长（dp）：稿 rx=20 是圆角矩形的连续圆弧，画成直角臂需按弧长折算。
         * 整段直角臂长 = 20(水平)+20(竖直)；二次贝塞尔近似圆弧取 ≈0.55 系数 → 11dp。
         * 原值 26dp 比稿的圆角还长，视觉上四角「过伸」。
         */
        private val ViewfinderCorner = 11.dp

        /**
         * 顶栏行高（dp）。画布 M 帧「顶栏」= 关闭 44 + 标题 + 手电筒 44；
         * 钉成定高（不是自适应）才能让下面那条「取景框上沿」算得准。
         */
        private val TopBarHeight = 48.dp

        /** 单据条高度（dp）—— 画布 M 帧「单据条 h=44」。 */
        private val BillBarHeight = 44.dp

        /** 顶栏与单据条之间的间隙（dp）。 */
        private val BillBarGap = 4.dp

        /**
         * 单据条下沿 → 取景框上沿的间隙（dp）。
         * 画布 M 帧：单据条 y88..132，取景框 y=144 —— 差 12。
         *
         * ★ 这个值取代了旧的 `VIEWFINDER_TOP_RATIO = 0.449f`（屏幕剩余高度比例）。
         * 画布 M 帧把取景框上移到顶栏 + 单据条之下，框上方从此是**定高内容**，
         * 再用「屏幕剩余高度 × 比例」就会在不同屏高上漂（390×844 与 393×851 各落一处）。
         * 绝对上沿 = 顶部 chrome 实测高度 + 本间隙，与屏高无关。
         */
        private val FrameTopGap = 12.dp

        /** 取景框下沿 → 回执卡/清单槽位 的间隙（dp）。 */
        private val FrameSlotGap = 14.dp

        /**
         * 旧屏（稿 3:1780/3:1781）取景框的**竖直比例** —— 稿在 844 高屏上框顶 y=273，
         * 竖直居中得 304、再上移 31dp。**只对非交接台的旧调用点生效**（见 [frameTopFor]）。
         *
         * ⚠️ 交接台**不再用**这个值：画布 M 帧把框放在顶栏+单据条之下的**绝对**位置，
         * 用比例会在不同屏高上漂。两个锚点并存是**有意**的，别顺手合一。
         */
        const val LEGACY_VIEWFINDER_TOP_RATIO: Float = 0.449f

        /** 顶栏图标钮尺寸（dp）—— 画布 M 帧「顶栏」里关闭/手电筒的画法尺寸。 */
        private val TopBarIconSize = 44.dp

        /** 底栏按钮高（dp）—— 画布 M 帧「底栏-完成钮 / 底栏-手输钮」实测 h=52。 */
        private val BottomActionHeight = 52.dp

        /** 底栏「手输钮」宽度（dp）—— 画布实测 w=146。 */
        private val ManualInputButtonWidth = 146.dp

        /** 交接清单高度上限（dp）：画布 M 帧给的是 4 行 × 56dp。 */
        private val HandoverListMaxHeight = 224.dp

        /** 槽位里除清单以外要占掉的固定高度（dp）：取景提示 18 + 间隙 8 + 回执卡 100 + 间隙 8。 */
        private val SlotContentReserve = 134.dp

        /** 底栏 + 导航栏预留高度（dp）—— 清单不能顶到底栏上去。 */
        private val BottomReserve = 96.dp

        /** 单个条码结果（旧协议，所有旧调用点只读这个）。 */
        const val EXTRA_BARCODE = "extra_barcode"

        /** 连续模式批量条码结果。 */
        const val EXTRA_BARCODES = "extra_barcodes"

        /** 文字识别结果（`EXTRA_MODE=text` 时回传）。 */
        const val EXTRA_TEXT = "extra_text"

        /** 启动参数：true = 条码连续模式（扫完点「完成」回传），false = 条码单次（扫到即回传）。 */
        const val EXTRA_CONTINUOUS = "extra_continuous"

        /**
         * 启动参数：true = 记账「交接台」（取景框下方挂 [HandoverLiveList] 流式清单）。
         * 只在 continuous 之上叠加显示层，不改变回传协议。
         */
        const val EXTRA_HANDOVER = "extra_handover"

        /**
         * 启动参数：交接台的**客户名**（画布 M 帧「单据条-客户」）。
         * 只用于单据条显示，不参与任何判定；没给就显示「散客」。
         */
        const val EXTRA_CUSTOMER = "extra_customer"

        /**
         * 回传参数：交接台按的是「**确定 · 保存账单**」，调用方收到后要**紧接着把账单存了**
         * （老板令 2026-09-26：上面取景框 / 中间商品条栏 / 底下一个确定，按下去账就存，不跳页）。
         *
         * 只有 [handoverMode] 才带这个标志 —— 其余旧调用点（查价/货位绑定…）扫完即回传，
         * 由调用方自己决定怎么用，**没有任何保存语义**。
         */
        const val EXTRA_SAVE_BILL = "extra_save_bill"

        /** 启动参数：识别模式，"barcode"（默认）或 "text"。 */
        const val EXTRA_MODE = "extra_mode"

        const val MODE_BARCODE = "barcode"
        const val MODE_TEXT = "text"

        /** 命中脉冲时长（取景框变绿 + 打钩气泡）。 */
        const val PULSE_MS: Long = 220L

        /** 多久没识别成功就弹温和提示。 */
        const val HINT_DELAY_MS: Long = 5000L
        const val HINT_CHECK_MS: Long = 1000L

        /** 周期性重对焦间隔（CameraX 无内置周期 AF，用 3s 触发一次中心 AF 模拟）。 */
        const val REFOCUS_INTERVAL_MS: Long = 3000L

        /** 手动对焦后暂停周期重对焦的时长。 */
        const val MANUAL_FOCUS_HOLD_MS: Long = 5000L

        /** 单次模式稳定窗口时长（S16c）：首帧到交付的择优窗口。
         * 枪对焦不稳时误读帧往往只持续 1~2 帧，400ms 足够等后续清晰帧（720p 约 12 帧）。
         */
        const val SINGLE_SETTLE_MS: Long = 400L

        /**
         * 取景框命中区外扩比例（2026-09-26 老板实测「要对准半天才能扫出来」）：
         * 画的 236dp 框只当瞄准参考，命中判定按框的 30% 四周外扩——
         * 有效区约 378dp 见方（≈2.6 倍面积），好对准；一整框之外的邻货仍被挡住
         * （9/20「一堆货摆在一起全被扫上」的底线不破）。
         */
        const val HIT_INFLATE_RATIO: Float = 0.30f

        /** 框外引导的最小间隔（ms）：条码在框外是每帧都发生的事，不节流会常驻刷屏。 */
        const val OUTSIDE_HINT_MIN_GAP_MS = 800L

        /** 看得到码却迟迟没成交多久后打一次辅助对焦。 */
        const val STUCK_FOCUS_AFTER_MS = 1200L

        /** 辅助对焦最小间隔（给一次 AF 扫描留够完成时间）。 */
        const val ASSIST_FOCUS_MIN_GAP_MS = 2500L

        /** 回执卡停留时长：够看清「刚扫那件是什么」，又不常驻挡视线。 */
        const val RECEIPT_HOLD_MS: Long = 1800L

        /**
         * S16c 扫码读取可信度门槛：纯数字码需 ≥6 位才可信（EAN-13=13 / EAN-8=8 /
         * UPC-A=12 / UPC-E=8 / ITF 可变），更短=截断误读碎片直接丢弃；非纯数字码
         * （QR/CODE128 文本）无固定长度语义，一律放行保持旧行为。
         */
        fun isPlausibleBarcodeRead(code: String): Boolean =
            if (code.all { it.isDigit() }) code.length >= 6 else true

        /**
         * S16c 稳定窗口择优：同为纯数字码时按去前导零（枪对 EAN 补零）后的长度比较，
         * 更长=更接近完整码；等长或任一非纯数字保留先识别者（第 1 个即旧行为基准，稳定优先）。
         */
        fun betterBarcode(a: String, b: String): String =
            if (a.all { it.isDigit() } && b.all { it.isDigit() }) {
                if (b.trimStart('0').length > a.trimStart('0').length) b else a
            } else a

        /** 单次对焦动作的自动取消时长（秒）。 */
        const val AUTO_CANCEL_SEC: Long = 3L

        fun intent(
            context: Context,
            continuous: Boolean = false,
            mode: String = MODE_BARCODE,
            handover: Boolean = false,
            customer: String? = null,
        ): Intent =
            Intent(context, ScannerActivity::class.java)
                .putExtra(EXTRA_CONTINUOUS, continuous)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_HANDOVER, handover && continuous)
                .putExtra(EXTRA_CUSTOMER, customer.orEmpty())
    }
}
