package com.retaildemo.v2.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.retaildemo.v2.ui.pages.CameraCaptureContract
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.viewmodel.AgentBubble
import com.retaildemo.v2.ui.viewmodel.AgentOnline
import com.retaildemo.v2.ui.viewmodel.AgentViewModel
import java.io.File

/**
 * I16/I17 老王面板 v3（L10，对标 ChatBox/豆包聊天语法）：
 * - 流式逐字上屏（SSE）；回答走 Markdown 渲染（mikepenz，LLM 流式场景成熟件）
 * - 「正在想」呼吸指示；发送键在生成中变停止键；失败气泡可重试；上一答可重新生成
 * - 长按老王消息复制；「新对话」重置会话；非底部时出滚到底按钮
 * - 老王消息 = 橙色头像 + 平铺文本（无气泡，豆包式）；店员消息 = 品牌色实心圆角泡
 * - 待确认票据卡 = 写操作唯一执行入口（红线：AI 结构上无法自确认）；面板收起自动停麦
 */
@Composable
fun AgentPanelSheet(vm: AgentViewModel, bottomInset: androidx.compose.ui.unit.Dp = 0.dp, onDismiss: () -> Unit) {
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
    // 流式增量不改变条数：只在「本来就在底部」时跟随内容滚动，不打断店员上翻
    LaunchedEffect(lastText) {
        val last = messages.size + pending.size
        if (atBottom && last > 0) listState.scrollToItem(last - 1)
    }

    val context = LocalContext.current
    val speech = remember { com.retaildemo.v2.util.SpeechHelper(context) }
    val voiceAvailable = remember { androidx.compose.runtime.mutableStateOf(true) }
    val micOn = remember { androidx.compose.runtime.mutableStateOf(false) }
    val input = remember { androidx.compose.runtime.mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose {
            speech.stop()
            micOn.value = false
        }
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
    var cameraFilePath by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
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

    Surface(modifier = Modifier.fillMaxSize(), color = Design.PageBg) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(bottom = bottomInset),
        ) {
            // ---- 头部：老王 + 在线态 + 新对话 + 收起 ----
            Row(
                Modifier.fillMaxWidth().background(Design.CardSurface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).background(Primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("王", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("老王", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Design.TextMain)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(6.dp).background(
                                if (online == AgentOnline.ONLINE) Design.Success else Design.TextWeak,
                                CircleShape,
                            ),
                        )
                        Text(
                            when (online) {
                                AgentOnline.ONLINE -> " 在线"
                                AgentOnline.OFFLINE -> " 离线 · 本地指令模式"
                                else -> " 检查中…"
                            },
                            fontSize = 11.sp, color = Design.TextWeak,
                        )
                    }
                }
                Text(
                    "新对话", color = Design.TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.clickable { vm.newChat() }.padding(6.dp),
                )
                Text(
                    "收起", color = Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { onDismiss() }.padding(6.dp),
                )
            }

            // ---- 会话流 ----
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                Box(
                                    Modifier.size(28.dp).background(Primary, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { Text("王", color = Color.White, fontSize = 12.sp) }
                                Text(
                                    "我是老王，店里的事随便问：查价、库存、欠款，出库打印也能替你办（需确认）。",
                                    fontSize = 14.sp, lineHeight = 21.sp, color = Design.TextMain,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
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
                                        .background(Primary, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                )
                            }
                            "lao" -> LaoMessage(
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
                if (!atBottom) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .size(34.dp)
                            .background(Design.CardSurface, CircleShape)
                            .clickable {
                                val last = messages.size + pending.size
                                if (last > 0) scope.launch { listState.animateScrollToItem(last - 1) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "滚到底",
                            tint = Design.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // ---- 附件回显卡片（拍照或选图后即时显示缩略图） ----
            if (localBitmap != null || attachedFile != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    localBitmap?.let { bmp ->
                        Box(
                            modifier = Modifier
                                .size(52.dp)
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
                                        modifier = Modifier.size(18.dp),
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
                            border = androidx.compose.foundation.BorderStroke(1.dp, Design.CardStrokeStrong),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    af.name.take(20),
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

            // ---- 附件条：拍照识货 / 相册 / 扫码 ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AttachChip("拍照识货", enabled = !attaching && !busy) {
                    val f = File(context.cacheDir, "agent_cam_" + System.currentTimeMillis() + ".jpg")
                    cameraFilePath = f.absolutePath
                    takePicture.launch(f)
                }
                AttachChip("相册", enabled = !attaching && !busy) {
                    pickImage.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                }
                AttachChip("文件", enabled = !attaching && !busy) {
                    pickFile.launch("*/*")
                }
                AttachChip("扫码", enabled = !busy) {
                    scanLauncher.launch(Intent(context, com.retaildemo.v2.ui.pages.ScannerActivity::class.java))
                }
            }

            // ---- 画布 35 输入栏三键：灰底输入框 + PrimaryLight 语音键 + Primary 发送键 ----
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
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .background(
                            if (listening) Design.DangerLight else Design.GrayChip,
                            RoundedCornerShape(22.dp),
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input.value,
                        onValueChange = { input.value = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (listening) "正在聆听…（点麦克风结束）" else "输入消息，或点麦克风直接说",
                                color = Design.TextPlaceholder, fontSize = 13.sp,
                            )
                        },
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                    if (voiceAvailable.value) {
                        val micAlpha = if (listening) pulse else 1f
                        Box(
                            Modifier
                                .padding(end = 4.dp)
                                .size(36.dp)
                                .background(
                                    if (listening) Design.Danger.copy(alpha = 0.15f + 0.85f * micAlpha)
                                    else com.retaildemo.v2.ui.theme.PrimaryLight,
                                    CircleShape,
                                )
                                .clickable {
                                    if (listening) {
                                        speech.stop()
                                        micOn.value = false
                                    } else {
                                        micOn.value = true
                                        speech.start(
                                            onPartial = { partial -> input.value = partial },
                                            onFinal = { final -> input.value = final; micOn.value = false },
                                            onError = { msg ->
                                                micOn.value = false
                                                if (msg == "unavailable") voiceAvailable.value = false
                                                else input.value = msg
                                            },
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                androidx.compose.ui.res.painterResource(com.retaildemo.v2.R.drawable.ic_mic),
                                contentDescription = "语音输入",
                                tint = if (listening) Design.Danger else Primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                if (busy) {
                    // 停止键（ChatBox 式：生成中发送键变方块停止键）
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(Design.Danger, CircleShape)
                            .clickable { vm.stop() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(14.dp)
                                .background(Color.White, RoundedCornerShape(3.dp)),
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(Primary, CircleShape)
                            .clickable(enabled = !attaching && (input.value.isNotBlank() || vm.attachedPath.value != null || vm.attachedFile.value != null || localBitmap != null)) {
                                vm.send(input.value)
                                input.value = ""
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
                }
            }
        }
    }
}

/** 老王消息：橙色头像 + 名字 + Markdown 平铺（豆包式无气泡）；
 *  流式空文本时出「正在想」呼吸点；长按复制；最后一条答完出「重新生成」。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LaoMessage(
    m: AgentBubble,
    isLastLao: Boolean,
    canReAsk: Boolean,
    toolName: String?,
    onReAsk: () -> Unit,
) {
    val context = LocalContext.current
    val thinking = m.streaming && m.text.isEmpty()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier.size(28.dp).background(Primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("王", color = Color.White, fontSize = 12.sp) }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text("老王", fontSize = 11.sp, color = Design.TextWeak)
            when {
                thinking -> ThinkingDots(toolName)
                m.failed -> {
                    Text(
                        m.text, fontSize = 14.sp, lineHeight = 21.sp, color = Design.Danger,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                    if (isLastLao) {
                        Text(
                            "重试", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable(enabled = canReAsk, onClick = onReAsk)
                                .padding(top = 2.dp),
                        )
                    }
                }
                else -> Column(
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm?.setPrimaryClip(ClipData.newPlainText("laowang", m.text))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                    ),
                ) {
                    Markdown(content = m.text, modifier = Modifier.fillMaxWidth())
                    if (m.degraded) {
                        Text("老王离线 · 本地指令模式", fontSize = 11.sp, color = Design.TextWeak)
                    }
                }
            }
            if (isLastLao && canReAsk && !m.failed && !m.streaming) {
                Text(
                    "重新生成", fontSize = 12.sp, color = Design.TextWeak,
                    modifier = Modifier
                        .clickable(enabled = canReAsk, onClick = onReAsk)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

/** 「正在想」转圈圈 + 状态行（流式首字前的等待反馈）：
 *  服务端 thinking/tool 事件给出「正在思考…/正在查库存…」等实时动作，VM 侧还有轮播兜底，
 *  让店员看见老王在动手而不是干等。 */
@Composable
private fun ThinkingDots(statusLine: String? = null) {
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
            text = statusLine ?: "老王正在想…",
            fontSize = 14.sp, color = Design.TextWeak.copy(alpha = 0.5f + 0.5f * alpha),
            modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
        )
    }
}

/** 附件条小圆角 chip。 */
@Composable
private fun AttachChip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        color = Design.TextSecondary,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .background(Design.CardSurface, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
