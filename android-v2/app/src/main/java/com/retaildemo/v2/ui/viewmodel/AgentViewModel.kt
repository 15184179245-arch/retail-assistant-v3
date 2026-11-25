package com.retaildemo.v2.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.remote.AgentConfirmRequest
import com.retaildemo.v2.data.remote.AgentImagePath
import com.retaildemo.v2.data.remote.AgentRunRequest
import com.retaildemo.v2.data.remote.AgentStreamClient
import com.retaildemo.v2.data.remote.AgentStreamEvent
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.PendingAction
import com.retaildemo.v2.data.remote.retaildemoApi
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class AgentOnline { UNKNOWN, ONLINE, OFFLINE }

/** 会话历史落盘上限（气泡条数；超出丢最旧的，控文件体积）。 */
private const val HISTORY_MAX = 200

/** 无具体工具时的状态轮播词（老板 2026-09-19：不能让店员干等着）。 */
private val ROTATING_STATUS = listOf(
    "正在思考…",
    "翻翻小本本…",
    "过一遍货架…",
    "对对价格…",
    "琢磨怎么回答…",
    "翻翻聊天记录…",
)

/** 工具名 → 店员能看懂的动作。匹配用小写子串，宁滥勿缺（_seen 什么词都能编）。 */
private fun friendlyToolStatus(rawName: String): String {
    val n = rawName.substringAfterLast('.')
            .removePrefix("mcp__retaildemo__").removePrefix("sx_").lowercase()
    return when {
        n.contains("xzg") -> "正在查管家婆实盘价…"
        n.contains("stock") || n.contains("inventory") || n.contains("low") -> "正在查库存…"
        n.contains("debt") || n.contains("bill") -> "正在翻账本…"
        n.contains("pending") || n.contains("confirm") -> "正在整理待确认单据…"
        n.contains("knowledge") -> if (n.contains("add")) "正在记小本本…" else "正在查知识库…"
        n.contains("search") || n.contains("query") || n.contains("product") -> "正在翻商品库…"
        n.contains("price") -> "正在对价格…"
        n.contains("memory") || n.contains("remember") -> "正在翻记忆…"
        n.contains("session") -> "正在翻聊天记录…"
        n.contains("vision") || n.contains("image") -> "正在看图…"
        n.contains("skill") -> "正在翻技能书…"
        n.contains("report") -> "正在整理日报…"
        n.contains("print") -> "正在摆弄打印机…"
        n.contains("terminal") || n.contains("command") || n.contains("process") ||
            n.contains("file") || n.contains("read") -> "正在跑命令…"
        else -> if (rawName.isBlank()) "正在动手查…" else "正在调用 ${rawName.take(24)}…"
    }
}

/**
 * I16 老王常驻面板 VM，L10 流式化：问答走 /api/agent/stream（SSE 逐字上屏），
 * 列表手术全部收敛在 [AgentConversation]（纯状态机，单测覆盖）。
 * Activity 级保活（在 NavHost 外 hiltViewModel()），会话历史只存进程内（手册 J 节：Room 不升级）。
 * 降级体验：服务端 serve 不可达时自动回落全文路径（degraded 标），APP 侧无感。
 * I17：相册/相机图 → 上传网关落盘 → 随问句带 image_paths 发给老王看图识货。
 */
@HiltViewModel
class AgentViewModel @Inject constructor(
    private val api: retaildemoApi,
    private val config: AppConfig,
    private val streamClient: AgentStreamClient,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val convo = AgentConversation()
    private val gson = com.google.gson.Gson()

    /** 会话历史落盘文件（本机当前窗口一份；新对话即清空重写）。 */
    private val historyFile: java.io.File
        get() = java.io.File(context.filesDir, "agent_chat_history.json")

    private val _messages = MutableStateFlow<List<AgentBubble>>(emptyList())
    val messages: StateFlow<List<AgentBubble>> = _messages.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingAction>>(emptyList())
    val pending: StateFlow<List<PendingAction>> = _pending.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _online = MutableStateFlow(AgentOnline.UNKNOWN)
    val online: StateFlow<AgentOnline> = _online.asStateFlow()

    /** I17：已上传附件的服务端路径（一次一张，发送后清空）。 */
    private val _attachedPath = MutableStateFlow<String?>(null)
    val attachedPath: StateFlow<String?> = _attachedPath.asStateFlow()

    /** 本地图片预览（Bitmap），拍照或选图后即时在输入栏呈现，随发送或删除清空。 */
    private val _localBitmap = MutableStateFlow<Bitmap?>(null)
    val localBitmap: StateFlow<Bitmap?> = _localBitmap.asStateFlow()

    /** 2026-09-19 老板令「上传文件」：已上传文件（服务端路径 + 原文件名），发送后清空。 */
    data class AttachedFile(val path: String, val name: String)

    private val _attachedFile = MutableStateFlow<AttachedFile?>(null)
    val attachedFile: StateFlow<AttachedFile?> = _attachedFile.asStateFlow()

    private val _attaching = MutableStateFlow(false)
    val attaching: StateFlow<Boolean> = _attaching.asStateFlow()

    /** L10（hermes-android 协议对照）：进行中的状态行——「正在思考…/正在查库存…」；空闲为 null。 */
    private val _toolStatus = MutableStateFlow<String?>(null)
    val toolStatus: StateFlow<String?> = _toolStatus.asStateFlow()

    /** 状态轮播器：busy 期间每 3 秒换一个通用词；工具事件来了就锁定具体动作不被轮掉。 */
    private var statusJob: kotlinx.coroutines.Job? = null
    private val _statusLocked = java.util.concurrent.atomic.AtomicBoolean(false)

    val sessionId: String get() = config.agentSessionId

    /** 进行中的回答气泡 id；-1 = 空闲。回调在 OkHttp 线程与主线程两侧都会碰到，读写都经此单字段。 */
    @Volatile
    private var replyId: Long = -1

    private var handle: AgentStreamClient.StreamHandle? = null
    private var lastQuestion: String = ""
    private var lastImagePath: String? = null

    init {
        ensureDeviceSession()
        loadHistory()
        publish()
        checkHealth()
    }

    /**
     * 2026-09-19 老板令「每个设备独立的会话窗口」：首启为空时按本机设备号派生会话 id
     * （同设备稳定复用，跨设备互不相通）；旧版空 id 会落到网关 app-default 全店共聊。
     */
    private fun ensureDeviceSession() {
        if (config.agentSessionId.isBlank()) {
            config.agentSessionId = ("dev" + config.deviceId.replace("-", "")).take(24)
        }
    }

    /** 重启找回上次会话窗口：读落盘气泡（streaming 占位不入档）。 */
    private fun loadHistory() {
        try {
            if (!historyFile.exists()) return
            val text = historyFile.readText()
            if (text.isBlank()) return
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                java.util.List::class.java, AgentBubble::class.java).type
            val items: List<AgentBubble>? = gson.fromJson(text, type)
            convo.restore(items.orEmpty().filter { !it.streaming }.takeLast(HISTORY_MAX))
        } catch (_: Exception) {
            // 坏档不挡新对话：直接当空窗口
        }
    }

    /** 气泡落盘（只在气泡生命周期落点调用，流式增量不触发 IO）。 */
    private fun persistHistory() {
        try {
            val items = convo.bubbles.filter { !it.streaming }.takeLast(HISTORY_MAX)
            val tmp = java.io.File(historyFile.parentFile, historyFile.name + ".tmp")
            tmp.writeText(gson.toJson(items))
            if (!tmp.renameTo(historyFile)) {
                historyFile.writeText(gson.toJson(items))
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        persistHistory()
        super.onCleared()
    }

    private fun publish() {
        _messages.value = convo.bubbles
    }

    fun checkHealth() {
        viewModelScope.launch {
            _online.value = try {
                val hermes = api.agentHealth().body()?.data?.hermes ?: false
                if (hermes) AgentOnline.ONLINE else AgentOnline.OFFLINE
            } catch (e: Exception) {
                AgentOnline.OFFLINE
            }
        }
    }

    /** I17：相册选图 → 上传网关落盘 → 拿服务端路径待发。失败给系统提示，不弹窗。 */
    fun attachImage(uri: android.net.Uri) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (bytes != null) {
            _localBitmap.value = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        upload(bytes)
    }

    /** I17：相机拍出的本地文件直接读字节上传。 */
    fun attachCameraImage(file: java.io.File) {
        val bytes = try {
            file.takeIf { it.exists() && it.length() > 0 }?.readBytes()
        } catch (e: Exception) {
            null
        }
        if (bytes != null) {
            _localBitmap.value = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        upload(bytes)
    }

    private fun upload(bytes: ByteArray?) {
        if (_attaching.value) return
        _attaching.value = true
        viewModelScope.launch {
            _attachedPath.value = try {
                requireNotNull(bytes) { "empty" }
                val body = okhttp3.RequestBody.create(null, bytes)
                api.agentImage(body).body()?.data?.path
            } catch (e: Exception) {
                null
            }
            if (_attachedPath.value == null) {
                sendSystemNote("图片上传失败，可直接文字描述或扫码")
                _localBitmap.value = null
            }
            _attaching.value = false
        }
    }

    fun clearAttached() {
        _attachedPath.value = null
        _localBitmap.value = null
    }

    /** 2026-09-19 老板令「上传文件」：文档/表格等走 SAF 选择器 → 上传网关落盘 → 待发。 */
    fun attachFile(uri: android.net.Uri) {
        if (_attaching.value) return
        _attaching.value = true
        viewModelScope.launch {
            var bytes: ByteArray? = null
            var displayName = ""
            try {
                bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                context.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) displayName = c.getString(idx) ?: ""
                    }
                }
            } catch (_: Exception) {
            }
            _attachedFile.value = try {
                requireNotNull(bytes) { "empty" }
                val body = okhttp3.RequestBody.create(null, bytes)
                val data = api.agentFile(displayName, body).body()?.data
                val d = requireNotNull(data) { "no path" }
                val path = requireNotNull(d.path) { "no path" }
                AttachedFile(path, d.name.ifBlank { displayName.ifBlank { "文件" } })
            } catch (_: Exception) {
                null
            }
            if (_attachedFile.value == null) {
                sendSystemNote("文件上传失败（支持 txt/csv/pdf/doc/xls/图片，≤20MB）")
            }
            _attaching.value = false
        }
    }

    fun clearAttachedFile() {
        _attachedFile.value = null
    }

    /** 系统提示气泡（权限/失败等非回答信息）。 */
    fun sendSystemNote(note: String) {
        convo.addSystem(note)
        publish()
        persistHistory()
    }

    fun send(text: String) {
        val q = text.trim()
        val img = _attachedPath.value
        val file = _attachedFile.value
        if (q.isBlank() && img == null && file == null) return
        if (_busy.value) return
        val shown = when {
            q.isNotBlank() -> q
            img != null -> "（看图识别）"
            else -> "（发了个文件：${file!!.name}，帮我看看）"
        }
        convo.addUser(shown, img != null)
        publish()
        persistHistory()
        submit(q.ifBlank { shown }, img, file?.path)
    }

    /** 失败重试 / 重新生成：丢掉最后一条回答，原样重发上一问（不重复加用户气泡）。 */
    fun reAskLast() {
        if (_busy.value || lastQuestion.isBlank()) return
        convo.dropLastReply()
        publish()
        persistHistory()
        submit(lastQuestion, lastImagePath)
    }

    /** 店员主动停止：保留已到手的半截回答。 */
    fun stop() {
        val h = handle ?: return
        h.cancel()
        handle = null
        if (replyId >= 0) convo.stopReply(replyId)
        replyId = -1
        _busy.value = false
        _toolStatus.value = null
        statusJob?.cancel()
        statusJob = null
        publish()
        persistHistory()
    }

    /** 新对话：清屏 + 重置服务端会话记忆（serve 侧与 v2 记忆两侧都丢），落盘历史同步清空。 */
    fun newChat() {
        stop()
        convo.clear()
        _pending.value = emptyList()
        publish()
        persistHistory()
        config.agentSessionId = UUID.randomUUID().toString().replace("-", "").take(24)
        val fresh = config.agentSessionId
        // reset 请求的回执只用于让服务端丢记忆，UI 不等它
        handle = streamClient.stream(
            AgentRunRequest(message = "", sessionId = fresh, reset = true),
            object : AgentStreamClient.Listener {
                override fun onEvent(evt: AgentStreamEvent) = Unit
                override fun onClosed() = Unit
                override fun onFailed(t: Throwable) = Unit
            },
        )
        sendSystemNote("已开启新对话")
    }

    private fun submit(message: String, img: String?, filePath: String? = null) {
        lastQuestion = message
        lastImagePath = img
        replyId = convo.beginReply()
        publish()
        _busy.value = true
        _toolStatus.value = "正在思考…"
        _statusLocked.set(false)
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            var i = 0
            while (isActive && _busy.value) {
                delay(3200)
                if (_busy.value && !_statusLocked.get()) {
                    i = (i + 1) % ROTATING_STATUS.size
                    _toolStatus.value = ROTATING_STATUS[i]
                }
            }
        }
        // 附件随发随清（图片/文件互不覆盖；一次发一个文件）
        _attachedPath.value = null
        _attachedFile.value = null
        _localBitmap.value = null
        handle = streamClient.stream(
            AgentRunRequest(
                message = message,
                sessionId = config.agentSessionId,
                imagePaths = listOfNotNull(img),
                staff = config.staffName,
                filePaths = listOfNotNull(filePath),
            ),
            object : AgentStreamClient.Listener {
                override fun onEvent(evt: AgentStreamEvent) {
                    val id = replyId
                    if (id < 0) return
                    when (evt.type) {
                        "delta" -> {
                            convo.appendDelta(id, evt.text.orEmpty())
                            publish()
                        }
                        "thinking" -> {
                            _toolStatus.value = "正在思考…"
                            _statusLocked.set(false)
                        }
                        "tool" -> {
                            _toolStatus.value = friendlyToolStatus(evt.name.orEmpty())
                            _statusLocked.set(true)
                        }
                        "done" -> {
                            val data = evt.data
                            if (data != null) {
                                if (data.sessionId.isNotBlank()) config.agentSessionId = data.sessionId
                                _pending.value = data.pendingActions
                                _online.value = if (data.degraded) AgentOnline.OFFLINE else AgentOnline.ONLINE
                                convo.finalizeReply(
                                    id,
                                    data.answer.takeIf { it.isNotBlank() },
                                    data.degraded,
                                )
                            } else {
                                convo.finalizeReply(id, null, false)
                            }
                            finish()
                        }
                        "ui" -> {
                            evt.messages?.let {
                                convo.attachUi(id, it)
                                publish()
                            }
                        }
                        "error" -> {
                            convo.failReply(id, evt.userMessage ?: "老王暂时不可用，稍后再试")
                            finish()
                        }
                    }
                }

                override fun onClosed() {
                    if (replyId >= 0) {
                        convo.failReply(replyId, "回答中断了，点重试再来一次")
                        finish()
                    }
                }

                override fun onFailed(t: Throwable) {
                    if (handle?.isCancelled == true) return // stop() 已收尾
                    if (replyId >= 0) {
                        convo.failReply(
                            replyId,
                            if (t.message?.contains("canceled") == true) {
                                "（已停止）"
                            } else {
                                "连不上老王，检查服务器后再试"
                            },
                        )
                        finish()
                    }
                }
            },
        )
    }

    private fun finish() {
        handle = null
        replyId = -1
        _busy.value = false
        _toolStatus.value = null
        statusJob?.cancel()
        statusJob = null
        publish()
        persistHistory()
    }

    fun confirm(actionId: String, ok: Boolean) {
        viewModelScope.launch {
            try {
                val body = api.agentConfirm(
                    AgentConfirmRequest(actionId, ok),
                ).body()?.data
                val tip = when (body?.state) {
                    "executed" -> "已执行（${body.actionId}）"
                    "cancelled" -> "已取消（${body.actionId}）"
                    else -> "该单据已失效，请重新发起"
                }
                sendSystemNote(tip)
                _pending.value = _pending.value.filter { it.actionId != actionId }
            } catch (e: Exception) {
                sendSystemNote("确认请求失败，稍后再试")
            }
        }
    }
}
