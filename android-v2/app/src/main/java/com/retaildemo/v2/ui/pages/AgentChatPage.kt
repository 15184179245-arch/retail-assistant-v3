package com.retaildemo.v2.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.retaildemo.v2.ui.components.AgentConfirmCard
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.viewmodel.AgentBubble
import com.retaildemo.v2.ui.viewmodel.AgentOnline
import com.retaildemo.v2.ui.viewmodel.AgentViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * L10 老王全屏 AI 工作台（老板令「用浮窗形式一键跳转全屏」）：
 * - 正规全屏页面，告别半屏挤促体验；
 * - 沉浸式流式 Markdown 问答、打字机动画、工具调用动态感知；
 * - 预置常用业务问题 chips，随手一戳即查；
 * - 发送/停止键自由打断、长按复制、失败重试、重新生成。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AgentChatPage(
    vm: AgentViewModel,
    onBack: () -> Unit,
) {
    val messages by vm.messages.collectAsState()
    val pending by vm.pending.collectAsState()
    val online by vm.online.collectAsState()
    val busy by vm.busy.collectAsState()
    val toolStatus by vm.toolStatus.collectAsState()
    val localBitmap by vm.localBitmap.collectAsState()
    val attachedFile by vm.attachedFile.collectAsState()
    val attaching by vm.attaching.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val lastText = messages.lastOrNull()?.text ?: ""

    LaunchedEffect(messages.size, pending.size) {
        val last = messages.size + pending.size
        if (last > 0) listState.animateScrollToItem(last - 1)
    }
    LaunchedEffect(lastText) {
        val last = messages.size + pending.size
        if (atBottom && last > 0) listState.scrollToItem(last - 1)
    }

    val context = LocalContext.current
    val speech = remember { com.retaildemo.v2.util.SpeechHelper(context) }
    // 2026-09-10 语音修复+老板令「随动长按」：老王面板麦克风=按住说话松手识别（ServerAsr.startHold），
    // 错误直接提示（店内私服，网关可达性故障时回退系统识别无意义）。
    val serverAsr = remember { com.retaildemo.v2.util.ServerAsr(context) }
    val voiceAvailable = remember { mutableStateOf(true) }
    val micOn = remember { mutableStateOf(false) }
    val input = remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            serverAsr.stop()
            speech.stop()
            micOn.value = false
        }
    }

    var startAgentVoice: () -> Unit = {}
    val micPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) input.value = "需要麦克风权限才能语音输入，请按住麦克风再试"
    }
    startAgentVoice = {
        serverAsr.warmUp() // 按住瞬间预建连接，松手上传省一次 TLS 握手
        micOn.value = true
        serverAsr.startHold(
            onState = {},
            onLevel = { },
            onFinal = { final -> input.value = final; micOn.value = false },
            onError = { msg ->
                micOn.value = false
                input.value = if (msg == "unavailable") "语音识别暂不可用，请检查网络后重试" else msg
            },
        )
    }

    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) vm.attachImage(uri)
    }
    // 2026-09-19 老板令「上传文件」：文档/表格走系统选择器（SAF），上传网关落盘后随问句发
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) vm.attachFile(uri)
    }
    // 拍照识货：内置相机（CameraX，CameraCaptureContract）直写缓存文件，
    // 拍成回传即上传；相机权限由 CameraCaptureActivity 自行申请。
    // 用 rememberSaveable 保护路径，防止全屏相机高显存占用导致主页面重建时路径丢失
    var cameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var showToolsPanel by remember { mutableStateOf(false) }
    val takePicture = androidx.activity.compose.rememberLauncherForActivityResult(
        CameraCaptureContract(),
    ) { ok ->
        val path = cameraFilePath
        cameraFilePath = null
        if (ok && !path.isNullOrBlank()) {
            val f = File(path)
            if (f.exists() && f.length() > 0) vm.attachCameraImage(f)
        }
    }
    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val bc = res.data?.getStringExtra(com.retaildemo.v2.ui.pages.ScannerActivity.EXTRA_BARCODE)
        val txt = res.data?.getStringExtra(com.retaildemo.v2.ui.pages.ScannerActivity.EXTRA_TEXT)
        if (!bc.isNullOrBlank()) input.value = bc
        else if (!txt.isNullOrBlank()) input.value = txt
    }

    val presetQueries = listOf(
        "查今日营业额",
        "看看缺货和低库存",
        "谁欠我们钱最多",
        "4分和6分水管接头区别",
        "角阀漏水怎么快速检修",
    )

    Scaffold(
        topBar = {
            // 画布 25 页头：头像36 + 标题 + 右侧纯文字「新对话」，无状态行（离线态走底部离线标）
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).background(Design.AgentBrand, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("王", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("老王 AI 店员", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Design.TextMain)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Design.TextMain)
                    }
                },
                actions = {
                    Text(
                        "新对话",
                        fontSize = 12.sp,
                        color = Design.TextSecondary,
                        modifier = Modifier
                            .clickable { vm.newChat() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Design.PageBg),
            )
        },
        containerColor = Design.PageBg,
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            // ---- 会话消息流 ----
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Column(Modifier.padding(top = 20.dp, bottom = 10.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Box(
                                        Modifier.size(32.dp).background(Design.AgentBrand, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("王", color = Color.White, fontSize = 13.sp) }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "店里的事尽管问：查价、库存预警、欠款明细，调账出库也能代您处理（需您确认单据）。",
                                        fontSize = 14.sp, lineHeight = 22.sp, color = Design.TextMain,
                                    )
                                }
                                Spacer(Modifier.padding(top = 16.dp))
                                Text("试试问我：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Design.TextWeak)
                                Spacer(Modifier.padding(top = 8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(presetQueries) { q ->
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = Design.CardSurface,
                                            border = BorderStroke(1.dp, Border),
                                            modifier = Modifier.clickable {
                                                vm.send(q)
                                            },
                                        ) {
                                            Text(q, fontSize = 13.sp, color = Design.TextMain, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    items(messages, key = { it.id }) { m ->
                        when (m.role) {
                            "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Text(
                                    (if (m.hasImage) "[图片] " else "") + m.text,
                                    color = Color.White, fontSize = 14.sp, lineHeight = 20.sp,
                                    modifier = Modifier
                                        .widthIn(max = 280.dp)
                                        .background(Primary, RoundedCornerShape(16.dp))
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                )
                            }
                            "lao" -> LaoMessageView(
                                m = m,
                                isLastLao = messages.lastOrNull { it.role == "lao" }?.id == m.id,
                                canReAsk = !busy && messages.any { it.role == "lao" },
                                toolName = toolStatus,
                                onReAsk = { vm.reAskLast() },
                            )
                            else -> Text(
                                m.text, fontSize = 11.sp, color = Design.TextWeak,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    items(pending) { p ->
                        AgentConfirmCard(
                            action = p,
                            onConfirm = { vm.confirm(p.actionId, true) },
                            onCancel = { vm.confirm(p.actionId, false) },
                        )
                    }
                }

                // 浮动滚到底部箭头
                if (!atBottom) {
                    Surface(
                        shape = CircleShape,
                        color = Design.CardSurface,
                        border = BorderStroke(1.dp, Border),
                        modifier = Modifier
                            .sxElevation(SxElevation.L4, shape = CircleShape, background = Design.CardSurface)
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .size(34.dp)
                            .clickable {
                                val last = messages.size + pending.size
                                if (last > 0) scope.launch { listState.animateScrollToItem(last - 1) }
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "滚到底",
                                tint = Design.TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // ---- 快捷提问推荐条（有内容时以紧凑横向展示） ----
            if (messages.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(presetQueries) { q ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Design.CardSurface,
                            border = BorderStroke(1.dp, Border),
                            modifier = Modifier.clickable(enabled = !busy) {
                                vm.send(q)
                            },
                        ) {
                            Text(q, fontSize = 11.sp, color = Design.TextSecondary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }

            // ---- 画布 25 离线标：离线/降级时输入栏上方常驻一行 ----
            val laoOffline = online == AgentOnline.OFFLINE ||
                (messages.lastOrNull { it.role == "lao" }?.degraded == true)
            if (laoOffline) {
                Text(
                    "老王离线 · 本地指令模式",
                    fontSize = 11.sp,
                    color = Design.TextWeak,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            // ---- 附件回显卡片（拍照或选图后即时显示缩略图，解决拍完无回显假死感） ----
            if (localBitmap != null || attachedFile != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    localBitmap?.let { bmp ->
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Design.CardStrokeStrong, RoundedCornerShape(8.dp)),
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "待发送照片",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            if (attaching) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(18.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(bottomStart = 6.dp))
                                    .clickable { vm.clearAttached() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "移除照片",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }

                    attachedFile?.let { af ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Design.CardSurface,
                            border = BorderStroke(1.dp, Design.CardStrokeStrong),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    af.name.take(24),
                                    fontSize = 12.sp,
                                    color = Design.TextMain,
                                    maxLines = 1,
                                )
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "移除文件",
                                    tint = Design.TextWeak,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { vm.clearAttachedFile() },
                                )
                            }
                        }
                    }
                }
            }

            // ---- 对话栏：一体化胶囊输入栏（对齐豆包设计规范） ----
            val listening = micOn.value
            val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
                initialValue = 0.35f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "micA",
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 语音键：PrimaryLight 40 圆（按住说话）
                if (voiceAvailable.value) {
                    val micAlpha = if (listening) pulse else 1f
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(
                                if (listening) Design.Danger.copy(alpha = 0.15f + 0.85f * micAlpha)
                                else com.retaildemo.v2.ui.theme.PrimaryLight,
                                CircleShape,
                            )
                            // 随动长按：按下开始录音、松手立即识别
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                            context, android.Manifest.permission.RECORD_AUDIO,
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                        if (!granted) {
                                            micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                            try { awaitRelease() } finally { micOn.value = false }
                                            return@detectTapGestures
                                        }
                                        startAgentVoice()
                                        try {
                                            awaitRelease()
                                        } finally {
                                            serverAsr.release()
                                        }
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            androidx.compose.ui.res.painterResource(com.retaildemo.v2.R.drawable.ic_mic),
                            contentDescription = "按住说话",
                            tint = if (listening) Design.Danger else Primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // 胶囊文本输入框
                Row(
                    Modifier
                        .weight(1f)
                        .background(
                            if (listening) Design.DangerLight else Design.CardSurface,
                            RoundedCornerShape(22.dp),
                        )
                        .border(
                            1.dp,
                            if (listening) Color.Transparent else Design.CardStrokeStrong,
                            RoundedCornerShape(22.dp),
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input.value,
                        onValueChange = {
                            input.value = it
                            if (it.isNotBlank()) showToolsPanel = false
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (listening) "正在聆听…（松手识别）" else "发消息或按住说话…",
                                color = Design.TextPlaceholder, fontSize = 13.sp,
                            )
                        },
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }

                // 右侧动作区：生成中显示停止；有内容/附件时显示发送；空闲时显示快捷拍照与加号工具箱
                val hasContent = input.value.isNotBlank() || vm.attachedPath.value != null || vm.attachedFile.value != null || localBitmap != null
                if (busy) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(Design.Danger, CircleShape)
                            .clickable { vm.stop() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(14.dp)
                                .background(Color.White, RoundedCornerShape(3.dp)),
                        )
                    }
                } else if (hasContent) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(Primary, CircleShape)
                            .clickable(enabled = !attaching) {
                                vm.send(input.value)
                                input.value = ""
                                showToolsPanel = false
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    // 快捷拍照按钮：直接在输入栏右侧一触即拍
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(com.retaildemo.v2.ui.theme.PrimaryLight, CircleShape)
                            .clickable(enabled = !attaching && !busy) {
                                showToolsPanel = false
                                val f = File(context.cacheDir, "agent_cam_" + System.currentTimeMillis() + ".jpg")
                                cameraFilePath = f.absolutePath
                                takePicture.launch(f)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = "拍照识货",
                            tint = Primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // 扩展加号按钮：展开更多工具网格
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(
                                if (showToolsPanel) Primary else Design.CardSurface,
                                CircleShape,
                            )
                            .border(
                                1.dp,
                                if (showToolsPanel) Primary else Design.CardStrokeStrong,
                                CircleShape,
                            )
                            .clickable {
                                showToolsPanel = !showToolsPanel
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "工具箱",
                            tint = if (showToolsPanel) Color.White else Design.TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // ---- 加号底部扩展工具面板（豆包同款工具网格） ----
            if (showToolsPanel) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Design.CardSurface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentToolBoxItem(
                        icon = Icons.Default.PhotoCamera,
                        label = "拍照识货",
                        enabled = !attaching && !busy,
                    ) {
                        showToolsPanel = false
                        val f = File(context.cacheDir, "agent_cam_" + System.currentTimeMillis() + ".jpg")
                        cameraFilePath = f.absolutePath
                        takePicture.launch(f)
                    }
                    AgentToolBoxItem(
                        icon = Icons.Default.AddCircle,
                        label = "相册选图",
                        enabled = !attaching && !busy,
                    ) {
                        showToolsPanel = false
                        pickImage.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }
                    AgentToolBoxItem(
                        icon = Icons.Default.Edit,
                        label = "上传文件",
                        enabled = !attaching && !busy,
                    ) {
                        showToolsPanel = false
                        pickFile.launch("*/*")
                    }
                    AgentToolBoxItem(
                        icon = Icons.Default.QrCodeScanner,
                        label = "扫码填入",
                        enabled = !busy,
                    ) {
                        showToolsPanel = false
                        scanLauncher.launch(Intent(context, com.retaildemo.v2.ui.pages.ScannerActivity::class.java))
                    }
                }
            }
        }
    }
}

/** 一条 A2UI 报文 -> 一张卡。动作经 AgentRoute 白名单放行后才进导航总线。 */
@Composable
private fun A2uiCard(messages: com.google.gson.JsonArray) {
    val surface = androidx.compose.runtime.remember(messages) {
        com.retaildemo.v2.ui.agent.a2ui.A2ui.parse(messages.joinToString("\n") { it.toString() })
    }
    val root = surface.root ?: return
    Column(Modifier.padding(top = Design.SpaceSm.dp)) {
        com.retaildemo.v2.ui.agent.a2ui.A2uiNodeView(root, surface) { nav ->
            nav?.let { com.retaildemo.v2.ui.agent.a2ui.AgentActionBus.nav(it) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LaoMessageView(
    m: AgentBubble,
    isLastLao: Boolean,
    canReAsk: Boolean,
    toolName: String?,
    onReAsk: () -> Unit,
) {
    val context = LocalContext.current
    val thinking = m.streaming && m.text.isEmpty()
    // 画布 25 答复行：头像28 + 白卡气泡（#EAECF0 描边 · 圆角16 · 内距12/9），无姓名标签
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier.size(28.dp).background(Design.AgentBrand, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("王", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(8.dp))
        val copyToClipboard = {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("laowang", m.text))
            Toast.makeText(context, "已复制内容", Toast.LENGTH_SHORT).show()
        }
        val ui = m.ui
        if (ui != null && !thinking && !m.failed) {
            /* 单容器原则（规则 14③）：有卡片时卡片就是容器，
               结论文字直接放在卡上方 —— 绝不再套一层白气泡变成卡中卡。 */
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = { copyToClipboard() }),
                ) {
                    Markdown(content = m.text, modifier = Modifier.fillMaxWidth())
                }
                A2uiCard(ui)
            }
        } else {
            Box(
                Modifier
                    .weight(1f)
                    .background(Design.CardSurface, RoundedCornerShape(16.dp))
                    .border(1.dp, Border, RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = {}, onLongClick = { copyToClipboard() })
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                when {
                    thinking -> ToolThinkingDots(toolName)
                    m.failed -> Text(
                        m.text, fontSize = 14.sp, lineHeight = 21.sp, color = Design.Danger,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )

                    else -> Markdown(content = m.text, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    // 气泡外动作行：失败重试 / 重新生成（不占白卡）
    if ((m.failed && isLastLao) || (isLastLao && canReAsk && !m.failed && !m.streaming)) {
        Text(
            "重新生成",
            fontSize = 12.sp,
            color = if (m.failed) Primary else Design.TextWeak,
            fontWeight = if (m.failed) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .padding(start = 36.dp, top = 4.dp)
                .clickable(enabled = canReAsk, onClick = onReAsk),
        )
    }
}

@Composable
private fun ToolThinkingDots(statusLine: String? = null) {
    val alpha by rememberInfiniteTransition(label = "think").animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkA",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 3.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = Primary,
        )
        Text(
            text = statusLine ?: "老王正在思考…",
            fontSize = 14.sp, color = Design.TextWeak.copy(alpha = 0.5f + 0.5f * alpha),
            modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
        )
    }
}

@Composable
private fun AgentToolBoxItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(com.retaildemo.v2.ui.theme.PrimaryLight, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) Primary else Design.TextWeak,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (enabled) Design.TextMain else Design.TextWeak,
        )
    }
}
