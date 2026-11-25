package com.retaildemo.v2.ui.viewmodel

/**
 * L10：老王会话流状态机（纯 Kotlin，无 Android 依赖，可单测）。
 * 职责：气泡列表的唯一变更入口——流式增量、收尾、失败、重试/重新生成的列表手术都在这里，
 * AgentViewModel 只负责 IO 与生命周期。role = user | lao | system。
 */
data class AgentBubble(
    val id: Long,
    val role: String,
    val text: String,
    val degraded: Boolean = false,
    val hasImage: Boolean = false,
    val streaming: Boolean = false,
    val failed: Boolean = false,
    val ts: Long = System.currentTimeMillis(),
    /** V7 生成式 UI 报文（A2UI 消息数组）；null＝这条回答没有卡片。 */
    val ui: com.google.gson.JsonArray? = null,
)

class AgentConversation {

    private val lock = Any()
    private val _bubbles = mutableListOf<AgentBubble>()
    val bubbles: List<AgentBubble>
        get() = synchronized(lock) { _bubbles.toList() }

    private var nextId: Long = 1
    fun newId(): Long = nextId++

    /** 店员一句话（可带图）。 */
    fun addUser(text: String, hasImage: Boolean) = synchronized(lock) {
        _bubbles.add(AgentBubble(newId(), "user", text, hasImage = hasImage))
    }

    /** 开一个流式回答占位（空文本+streaming=true，UI 显示「正在想」）。 */
    fun beginReply(): Long = synchronized(lock) {
        val id = newId()
        _bubbles.add(AgentBubble(id, "lao", "", streaming = true))
        id
    }

    /** 流式增量追加到指定回答。 */
    fun appendDelta(id: Long, text: String) = synchronized(lock) {
        if (text.isEmpty()) return
        val idx = _bubbles.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val b = _bubbles[idx]
            _bubbles[idx] = b.copy(text = b.text + text)
        }
    }

    /** 收尾：done 帧。全文路径（没收到过 delta）时 fullAnswer 非空直接落地。 */
    fun finalizeReply(id: Long, fullAnswer: String?, degraded: Boolean) = synchronized(lock) {
        val idx = _bubbles.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val b = _bubbles[idx]
            val text = when {
                b.text.isNotEmpty() -> b.text
                !fullAnswer.isNullOrBlank() -> fullAnswer
                else -> "（老王没说话，再问一次试试）"
            }
            _bubbles[idx] = b.copy(text = text, degraded = degraded, streaming = false)
        }
    }

    /** 生成式 UI 帧：把卡片报文挂到本轮回答上（先于 done 到达）。 */
    fun attachUi(id: Long, messages: com.google.gson.JsonArray) = synchronized(lock) {
        val idx = _bubbles.indexOfFirst { it.id == id }
        if (idx >= 0) _bubbles[idx] = _bubbles[idx].copy(ui = messages)
    }

    /** 失败：气泡落败态，文案用服务端 user_message。 */
    fun failReply(id: Long, message: String) = synchronized(lock) {
        val idx = _bubbles.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val b = _bubbles[idx]
            _bubbles[idx] = b.copy(text = message, streaming = false, failed = true)
        }
    }

    /** 店员主动停止：保留已到手的半截回答。 */
    fun stopReply(id: Long) = synchronized(lock) {
        val idx = _bubbles.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val b = _bubbles[idx]
            _bubbles[idx] = b.copy(
                text = b.text.ifEmpty { "（已停止）" },
                streaming = false,
            )
        }
    }

    /** 丢掉最后一条老王回答（重试/重新生成前置手术）。返回被丢气泡 id；没有则 -1。 */
    fun dropLastReply(): Long = synchronized(lock) {
        val idx = _bubbles.indexOfLast { it.role == "lao" }
        if (idx < 0) -1 else {
            val id = _bubbles[idx].id
            _bubbles.removeAt(idx)
            id
        }
    }

    /** 最后一条店员消息（重试/重新生成的弹丸）。没有则 null。 */
    fun lastUserMessage(): AgentBubble? = synchronized(lock) {
        _bubbles.lastOrNull { it.role == "user" }
    }

    fun addSystem(note: String) = synchronized(lock) {
        _bubbles.add(AgentBubble(newId(), "system", note))
    }

    fun clear() = synchronized(lock) { _bubbles.clear() }

    /** 从落盘历史恢复（APP 重启后找回上次会话窗口）；id 从历史最大值续起。 */
    fun restore(items: List<AgentBubble>) = synchronized(lock) {
        _bubbles.clear()
        _bubbles.addAll(items)
        nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
    }
}
