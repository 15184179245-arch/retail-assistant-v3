package com.retaildemo.v2.ui.pages

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.retaildemoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 内置拍照（CameraX Preview + ImageCapture，参考 android/camera-samples 的 camerax-takeaphoto 范式）。
 *
 * 取代 `ActivityResultContracts.TakePicture()` 跳系统相机的老路：不离开 App、
 * 机型差异归一（厂商相机加载慢/易被杀/路径与 EXIF 行为不一）。调用经 [CameraCaptureContract]，
 * 传目标输出文件，拍成回 RESULT_OK；取消/失败回 RESULT_CANCELED 且不留半成品文件。
 *
 * 店员交互：点击对焦（对焦圈反馈）、手电筒补光（货架暗处刚需）、前后摄翻转、
 * 拍后全屏预览确认（重拍/使用照片），快门带巴枪式震动反馈。
 * 旋转元数据由 CameraX 写入 JPEG EXIF，下游压缩层（ProductPhotoRepo 等）照旧摆正。
 */
class CameraCaptureActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OUTPUT_PATH = "output_path"
        /** I27 true=连拍会话模式（见 [CameraBurstContract]）；缺省 false=单拍，老调用点零感知。 */
        const val EXTRA_MULTI = "multi"
        const val EXTRA_MAX_SHOTS = "max_shots"
        /** 连拍结果：已拍成文件的绝对路径列表。 */
        const val EXTRA_OUTPUT_PATHS = "output_paths"
        private const val AUTO_CANCEL_SEC = 3L
        private const val FOCUS_RING_MS = 900L
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    /** 结果已回传 / 页面已结束，防止重复 setResult。 */
    private var delivered = false

    // ---- Compose 灯状态（一律只在主线程更新）----
    private var torchOn by mutableStateOf(false)
    private var hasFlash by mutableStateOf(false)
    private var frontLens by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var capturedPath by mutableStateOf<String?>(null)
    private var focusRing by mutableStateOf<Offset?>(null)
    // ---- I27 连拍会话 ----
    private var multi = false
    private var maxShots = 20
    private var shotPaths by mutableStateOf<List<String>>(emptyList())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        multi = intent?.getBooleanExtra(EXTRA_MULTI, false) == true
        maxShots = intent?.getIntExtra(EXTRA_MAX_SHOTS, 20)?.coerceIn(2, 50) ?: 20
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        setContent {
            retaildemoTheme {
                CaptureScreen()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 连拍模式没有「刚拍一张待确认」状态：返回=整场放弃（closeCapture 会清空文件）
                if (!multi && capturedPath != null) retake() else closeCapture()
            }
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
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    // 商品照/盘点照要的是清楚，不怕快门慢这几十毫秒
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                imageCapture = capture
                val selector =
                    if (frontLens) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, capture)
                hasFlash = runCatching { camera?.cameraInfo?.hasFlashUnit() == true }.getOrDefault(false)
                torchOn = false
                // 进入页面先对画面中心对一次焦，店员举起即可拍
                previewView.post { triggerFocus(centerX(), centerY()) }
            } catch (e: Exception) {
                // 前摄不存在等绑定失败：回落到后摄重试一次
                if (frontLens) {
                    frontLens = false
                    startCamera()
                } else {
                    Toast.makeText(this, "相机启动失败：${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 连拍第 seq 张的落点（ProductPhotoStore 命名契约：基名 + `_<seq>`）。 */
    private fun shotFile(seq: Int): File? =
        intent?.getStringExtra(EXTRA_OUTPUT_PATH)
            ?.let { com.retaildemo.v2.data.repo.ProductPhotoStore.burstTargetFile(File(it), seq) }

    private fun takePhoto() {
        if (saving || delivered) return
        if (multi && shotPaths.size >= maxShots) {
            Toast.makeText(this, "一次最多拍 $maxShots 张，先点完成", Toast.LENGTH_SHORT).show()
            return
        }
        val capture = imageCapture ?: return
        val path = (if (multi) shotFile(shotPaths.size + 1)?.absolutePath
        else intent?.getStringExtra(EXTRA_OUTPUT_PATH)) ?: return
        val file = File(path)
        runCatching { file.parentFile?.mkdirs() }
        saving = true
        ScanFeedback.click(this)
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                mainHandler.post {
                    saving = false
                    if (multi) {
                        // 连拍不弹全屏确认：拍完直接回取景，缩略条立刻多一格（店员可继续拍）
                        shotPaths = shotPaths + path
                        // 语义音=「照片落进筐里」（ITEM_ADD），不是绑定音
                        ScanFeedback.play(this@CameraCaptureActivity, ScanFeedback.Sfx.ITEM_ADD)
                    } else {
                        capturedPath = path
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                mainHandler.post {
                    saving = false
                    ScanFeedback.error(this@CameraCaptureActivity)
                    Toast.makeText(
                        this@CameraCaptureActivity,
                        "拍照失败：${exception.message}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        })
    }

    /** 重拍：删掉刚写的目标文件，回到取景。 */
    private fun retake() {
        capturedPath?.let { runCatching { File(it).delete() } }
        capturedPath = null
    }

    private fun confirmPhoto() {
        if (capturedPath != null) deliverResult(RESULT_OK)
    }

    /** 取消：作废未交付的文件——单拍删目标文件，连拍把整场全部删掉，别给上传层留半成品。 */
    private fun closeCapture() {
        if (multi) {
            shotPaths.forEach { runCatching { File(it).delete() } }
            shotPaths = emptyList()
            deliverResult(RESULT_CANCELED)
            return
        }
        retake()
        deliverResult(RESULT_CANCELED)
    }

    /** I27 连拍「删掉上一张」：从尾上删文件+出列（空场时由按钮禁用挡住）。 */
    private fun popLastShot() {
        if (shotPaths.isEmpty()) return
        runCatching { File(shotPaths.last()).delete() }
        shotPaths = shotPaths.dropLast(1)
        ScanFeedback.play(this, ScanFeedback.Sfx.UNBIND)
    }

    /** I27 连拍「完成」：整场文件一次性交付（上传/补传由调用方 ProductPhotoRepo 统一处理）。 */
    private fun finishBurst() {
        if (shotPaths.isEmpty() || delivered) return
        delivered = true
        ScanFeedback.play(this, ScanFeedback.Sfx.PIPELINE_DONE)
        setResult(
            RESULT_OK,
            Intent().putStringArrayListExtra(EXTRA_OUTPUT_PATHS, ArrayList(shotPaths)),
        )
        finish()
    }

    private fun deliverResult(code: Int) {
        if (delivered) return
        delivered = true
        setResult(code, Intent())
        finish()
    }

    // ---------------------------------------------------------------- 对焦 / 手电筒

    private fun centerX(): Float = previewView.width.takeIf { it > 0 }?.div(2f) ?: 0f

    private fun centerY(): Float = previewView.height.takeIf { it > 0 }?.div(2f) ?: 0f

    private fun triggerFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val w = previewView.width
        val h = previewView.height
        if (w <= 0 || h <= 0) return
        val point = previewView.meteringPointFactory.createPoint(
            x.coerceIn(0f, w.toFloat()),
            y.coerceIn(0f, h.toFloat()),
        )
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).setAutoCancelDuration(AUTO_CANCEL_SEC, TimeUnit.SECONDS).build()
        runCatching { cam.cameraControl.startFocusAndMetering(action) }
    }

    private fun focusAt(x: Float, y: Float) {
        triggerFocus(x, y)
        focusRing = Offset(x, y)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        val next = !torchOn
        torchOn = next
        // 货架底下光线暗，手电筒是刚需；设备不支持时 enableTorch 的 future 会失败，静默忽略即可。
        runCatching { cam.cameraControl.enableTorch(next) }
    }

    override fun onDestroy() {
        delivered = true
        executor.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    @Composable
    private fun CaptureScreen() {
        var confirmBitmap by remember { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(capturedPath) {
            confirmBitmap = capturedPath?.let { p ->
                withContext(Dispatchers.IO) { decodePreview(p) }
            }
        }
        // 对焦圈显示 FOCUS_RING_MS 后自动消隐
        LaunchedEffect(focusRing) {
            if (focusRing != null) {
                delay(FOCUS_RING_MS)
                focusRing = null
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (capturedPath == null) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )
                // 点击对焦层：在预览之上、按钮之下（Compose 命中测试自顶向下，按钮先吃事件）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset -> focusAt(offset.x, offset.y) }
                        },
                )
            } else {
                confirmBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "刚拍的照片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            // 顶部：关闭 + 手电筒
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                IconButton(
                    onClick = { closeCapture() },
                    modifier = Modifier.size(Design.MinTouchTarget.dp),
                ) {
                    Icon(Icons.Default.Close, "取消", tint = Color.White)
                }
                if (multi) {
                    // 连拍模式底部留给相册条和「完成」，翻转挪到顶栏（顶栏=手电筒+翻转）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasFlash) {
                            IconButton(
                                onClick = { toggleTorch() },
                                modifier = Modifier.size(Design.MinTouchTarget.dp),
                            ) {
                                Icon(
                                    imageVector = if (torchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                                    contentDescription = if (torchOn) "关闭手电筒" else "打开手电筒",
                                    tint = if (torchOn) Color(0xFFFFC107) else Color.White,
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                frontLens = !frontLens
                                startCamera()
                            },
                            modifier = Modifier.size(Design.MinTouchTarget.dp),
                        ) {
                            Icon(Icons.Default.Cameraswitch, "翻转摄像头", tint = Color.White)
                        }
                    }
                } else if (hasFlash && capturedPath == null) {
                    IconButton(
                        onClick = { toggleTorch() },
                        modifier = Modifier.size(Design.MinTouchTarget.dp),
                    ) {
                        Icon(
                            imageVector = if (torchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                            contentDescription = if (torchOn) "关闭手电筒" else "打开手电筒",
                            tint = if (torchOn) Color(0xFFFFC107) else Color.White,
                        )
                    }
                } else {
                    Spacer(Modifier.size(Design.MinTouchTarget.dp))
                }
            }

            // 对焦圈
            focusRing?.let { pos -> FocusRing(pos) }

            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(56.dp),
                    color = Color.White,
                )
            }

            if (multi && capturedPath == null) {
                // I27 连拍：缩略条在上，底下「删掉上一张 | 快门 | 完成 n」——拍完不回确认页
                Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
                    if (shotPaths.isNotEmpty()) ShotStrip()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                    ) {
                        TextButton(
                            onClick = { popLastShot() },
                            enabled = shotPaths.isNotEmpty(),
                            modifier = Modifier.widthIn(min = Design.MinTouchTarget.dp),
                        ) {
                            Text("删掉上一张", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
                        }
                        Box(
                            modifier = Modifier
                                .size(78.dp)
                                .border(4.dp, Color.White, CircleShape)
                                .padding(5.dp)
                                .clip(CircleShape)
                                .background(if (saving) Color.White.copy(alpha = 0.4f) else Color.White)
                                .clickable(enabled = !saving) { takePhoto() },
                        )
                        Button(
                            onClick = { finishBurst() },
                            enabled = shotPaths.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        ) {
                            Text("完成 ${shotPaths.size}", fontSize = 17.sp)
                        }
                    }
                }
            } else if (capturedPath == null) {
                // 取景：快门 + 翻转摄像头
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 40.dp, vertical = 28.dp),
                ) {
                    Spacer(Modifier.size(48.dp))
                    Box(
                        modifier = Modifier
                            .size(78.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(5.dp)
                            .clip(CircleShape)
                            .background(if (saving) Color.White.copy(alpha = 0.4f) else Color.White)
                            .clickable(enabled = !saving) { takePhoto() },
                    )
                    IconButton(
                        onClick = {
                            frontLens = !frontLens
                            startCamera()
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Cameraswitch, "翻转摄像头", tint = Color.White)
                    }
                }
            } else {
                // 确认：重拍 / 使用照片
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    TextButton(onClick = { retake() }) {
                        Text("重拍", color = Color.White, fontSize = 17.sp)
                    }
                    Button(
                        onClick = { confirmPhoto() },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    ) {
                        Text("使用照片", fontSize = 17.sp)
                    }
                }
            }
        }
    }

    /** I27 连拍缩略条：已拍张数计数 + 每张 44dp 小图（横滑，最新在右）。 */
    @Composable
    private fun ShotStrip() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("${shotPaths.size}/$maxShots", color = Color.White, fontSize = 13.sp)
            shotPaths.forEachIndexed { i, p -> ShotThumb(p, i + 1) }
        }
    }

    /** 小图 160px 降采样同步解码（20 张上限、主线程可承受；OOM 红线见 decodePreview）。 */
    @Composable
    private fun ShotThumb(path: String, n: Int) {
        val bmp = remember(path) { decodePreview(path, 160) }
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "第 $n 张",
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.size(44.dp).background(Color.DarkGray, RoundedCornerShape(6.dp)))
        }
    }

    @Composable
    private fun FocusRing(pos: Offset) {
        val ring = 64.dp
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (pos.x - ring.toPx() / 2).roundToInt(),
                        (pos.y - ring.toPx() / 2).roundToInt(),
                    )
                }
                .size(ring)
                .alpha(0.85f)
                .border(2.dp, Color.White, CircleShape),
        )
    }

    /** 确认预览用降采样解码，别让整张原图常驻内存。 */
    private fun decodePreview(path: String, maxDim: Int = 2048): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
