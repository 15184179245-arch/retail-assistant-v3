package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.remote.ChatContext
import com.retaildemo.v2.data.remote.ChatMessage
import com.retaildemo.v2.data.remote.ChatRequest
import com.retaildemo.v2.data.remote.KnowledgeAddRequest
import com.retaildemo.v2.data.remote.retaildemoApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** I6 浮窗回答：AI 回复正文 + 知识库命中标题（知识来源只列标题，不冒充答案）。 */
data class FloatAnswer(val reply: String, val knowledgeTitles: List<String> = emptyList())

/** 知识入库状态：busy 提交中 / done 成功（可继续录下一条）/ message 结果提示。 */
data class KnowledgeAddState(
    val busy: Boolean = false,
    val done: Boolean = false,
    val message: String = "",
)

/**
 * I6 知识浮窗 VM：发问走现有 chat 服务，context 注入当前商品（三场景唤起时带入）。
 * 降级与 S4 同款体验：超时/断网 → 明确提示离线，不卡死不误报。
 */
@HiltViewModel
class KnowledgeFloatViewModel @Inject constructor(
    private val api: retaildemoApi,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _answer = MutableStateFlow<FloatAnswer?>(null)
    val answer: StateFlow<FloatAnswer?> = _answer.asStateFlow()

    private val _addState = MutableStateFlow(KnowledgeAddState())
    val addState: StateFlow<KnowledgeAddState> = _addState.asStateFlow()

    fun ask(text: String, product: ChatContext?) {
        val q = text.trim()
        if (q.isBlank() || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _answer.value = try {
                val body = api.chat(ChatRequest(listOf(ChatMessage("user", q)), context = product)).body()
                FloatAnswer(
                    reply = body?.response?.takeIf { it.isNotBlank() } ?: "（服务没有回复，稍后再试）",
                    knowledgeTitles = body?.knowledge
                        ?.map { it.question.ifBlank { it.keyword } }
                        .orEmpty()
                        .filter { it.isNotBlank() }
                        .take(3),
                )
            } catch (e: Exception) {
                FloatAnswer("当前离线，AI 暂不可用")
            }
            _busy.value = false
        }
    }

    fun clear() { _answer.value = null }

    /**
     * 知识入库：店员把问答沉淀进知识库（走网关 /api/knowledge/add → 知识服务 /add）。
     * 服务端 INSERT OR IGNORE + source='manual' + approved 直上架，入库后立刻可搜。
     */
    fun addKnowledge(question: String, answer: String, category: String) {
        val q = question.trim()
        val a = answer.trim()
        if (q.isBlank() || a.isBlank() || _addState.value.busy) return
        _addState.value = KnowledgeAddState(busy = true)
        viewModelScope.launch {
            _addState.value = try {
                val body = api.knowledgeAdd(
                    KnowledgeAddRequest(question = q, answer = a, category = category.trim()),
                ).body()
                when {
                    body?.success == true && (body.data?.added ?: 0) > 0 ->
                        KnowledgeAddState(done = true, message = "已入库，问答现在就能搜到")
                    body?.success == true ->
                        KnowledgeAddState(done = true, message = "库里已有这条问法，没有重复入库")
                    else -> KnowledgeAddState(
                        message = body?.user_message ?: body?.error ?: "入库失败，稍后再试",
                    )
                }
            } catch (e: Exception) {
                KnowledgeAddState(message = "当前离线，入库没有成功")
            }
        }
    }

    fun resetAddState() { _addState.value = KnowledgeAddState() }
}
