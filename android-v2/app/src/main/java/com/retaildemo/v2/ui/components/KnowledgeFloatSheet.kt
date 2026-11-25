package com.retaildemo.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.data.remote.ChatContext
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.BorderStrong
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.viewmodel.KnowledgeFloatViewModel
import com.retaildemo.v2.ui.components.ScanFeedback

/**
 * I6 知识浮窗：长在场景上的 AI 外挂（记账长按/查价按钮唤起，不打断手上活）。
 * U8 小窗化：ModalBottomSheet → Flyme 可拖拽小窗（把手拖动/贴边/点外关闭），对话感更强。
 * 预置三问覆盖 80% 场景；AI 回复一律带"仅供参考"尾标（卡验收 + §9-6 高危红线就高不就低）；
 * 知识库命中只列标题（I7 建内容前不冒充答案）；纯文本渲染，不引 Markdown 库。
 * 知识入库（2026-09-19）：双模式——问一问 / 知识入库，店员把问答沉淀进知识库
 * （走网关 /api/knowledge/add → 知识服务 /add，approved 直上架入库即搜）。
 */
@Composable
fun KnowledgeFloatSheet(
    product: ChatContext?,
    onDismiss: () -> Unit,
    viewModel: KnowledgeFloatViewModel = hiltViewModel(),
) {
    val busy by viewModel.busy.collectAsState()
    val answer by viewModel.answer.collectAsState()
    val addState by viewModel.addState.collectAsState()
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("ask") } // ask=问一问 | add=知识入库
    var addQuestion by remember { mutableStateOf("") }
    var addAnswer by remember { mutableStateOf("") }
    var addCategory by remember { mutableStateOf("") }
    val context = LocalContext.current

    // 入库成功后清空表单，方便连续录下一条；提示文案保留在场
    LaunchedEffect(addState.done) {
        if (addState.done) {
            addQuestion = ""
            addAnswer = ""
        }
    }

    com.retaildemo.v2.ui.window.SanxSmallWindow(
        onDismiss = onDismiss,
        title = if (product?.name.isNullOrBlank()) "AI 问问" else "问问 · ${product?.name}",
        subtitle = product?.spec?.takeIf { it.isNotBlank() },
        contentHeightFraction = 0.8f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 模式切换：问一问 / 知识入库
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ask" to "问一问", "add" to "知识入库").forEach { (key, label) ->
                    val selected = mode == key
                    AssistChip(
                        onClick = {
                            ScanFeedback.click(context)
                            mode = key
                        },
                        label = { Text(label, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium) },
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) Primary else PrimaryLight,
                            labelColor = if (selected) Color.White else Primary,
                        ),
                        border = null,
                    )
                }
            }

            if (mode == "ask") {
                AskModeContent(
                    busy = busy,
                    answer = answer,
                    text = text,
                    onTextChange = { text = it },
                    onAsk = { viewModel.ask(text, product) },
                    product = product,
                    context = context,
                )
            } else {
                AddModeContent(
                    addState = addState,
                    question = addQuestion,
                    onQuestionChange = { addQuestion = it },
                    answer = addAnswer,
                    onAnswerChange = { addAnswer = it },
                    category = addCategory,
                    onCategoryChange = { addCategory = it },
                    onSubmit = {
                        ScanFeedback.click(context)
                        viewModel.addKnowledge(addQuestion, addAnswer, addCategory)
                    },
                    context = context,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 问一问模式：预置三问 + 提问输入 + AI 回答卡（原 I6 内容原样）。 */
@Composable
private fun AskModeContent(
    busy: Boolean,
    answer: com.retaildemo.v2.ui.viewmodel.FloatAnswer?,
    text: String,
    onTextChange: (String) -> Unit,
    onAsk: () -> Unit,
    product: ChatContext?,
    context: android.content.Context,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("这货怎么用", "配什么件", "有没有替代").forEach { preset ->
            AssistChip(
                onClick = {
                    ScanFeedback.click(context)
                    onTextChange(preset)
                    onAsk()
                },
                label = { Text(preset, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium) },
                shape = RoundedCornerShape(Design.PillRadius.dp),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = PrimaryLight,
                    labelColor = Primary,
                ),
                border = null,
            )
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("问这件货的用法、规格或搭配…", fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(Design.InputRadius.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = BorderStrong,
            ),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                ScanFeedback.click(context)
                onAsk()
            },
            enabled = !busy && text.isNotBlank(),
            shape = RoundedCornerShape(Design.InputRadius.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
        ) { Text("提问", fontWeight = FontWeight.SemiBold) }
    }
    if (busy) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary)
            Text("AI 正在结合商品档案分析…", fontSize = 12.sp, color = TextSecondary)
        }
    }
    answer?.let { a ->
        Surface(
            shape = RoundedCornerShape(Design.CardRadius.dp),
            color = Design.PageBg,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    a.reply,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = TextMain,
                )
                // 卡验收：AI 内容带"仅供参考"标注（就高不就低，不依赖类目关键词判定）
                Text(
                    "（仅供参考，大件/电/气/承压用途请以实物和说明书为准）",
                    fontSize = 11.sp,
                    color = Danger,
                    fontWeight = FontWeight.Medium,
                )
                if (a.knowledgeTitles.isNotEmpty()) {
                    HorizontalDivider(thickness = 0.5.dp, color = Border)
                    Text("关联百科知识：", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                    a.knowledgeTitles.forEach { t ->
                        Text("· $t", fontSize = 12.sp, color = Primary)
                    }
                }
            }
        }
    }
}

/** 知识入库模式：问题+答案（+可选类目）→ 网关 → 知识服务入库，approved 即搜。 */
@Composable
private fun AddModeContent(
    addState: com.retaildemo.v2.ui.viewmodel.KnowledgeAddState,
    question: String,
    onQuestionChange: (String) -> Unit,
    answer: String,
    onAnswerChange: (String) -> Unit,
    category: String,
    onCategoryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    context: android.content.Context,
) {
    Text(
        "把顾客常问的问答沉淀进知识库，全店 APP 立刻可搜（不含售价）。",
        fontSize = 12.sp,
        color = TextSecondary,
    )
    OutlinedTextField(
        value = question,
        onValueChange = onQuestionChange,
        placeholder = { Text("问题，如：云石机切瓷砖要加水吗？", fontSize = 13.sp, lineHeight = 20.sp) },
        minLines = 1,
        maxLines = 2,
        shape = RoundedCornerShape(Design.InputRadius.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            unfocusedBorderColor = BorderStrong,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = answer,
        onValueChange = onAnswerChange,
        placeholder = { Text("答案，说清用法/规格/注意事项…", fontSize = 13.sp, lineHeight = 20.sp) },
        minLines = 3,
        maxLines = 6,
        shape = RoundedCornerShape(Design.InputRadius.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            unfocusedBorderColor = BorderStrong,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = category,
        onValueChange = onCategoryChange,
        placeholder = { Text("类目（可选，如 手动工具）", fontSize = 13.sp, lineHeight = 20.sp) },
        singleLine = true,
        shape = RoundedCornerShape(Design.InputRadius.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            unfocusedBorderColor = BorderStrong,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onSubmit,
            enabled = !addState.busy && question.isNotBlank() && answer.isNotBlank(),
            shape = RoundedCornerShape(Design.InputRadius.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
        ) {
            if (addState.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Spacer(Modifier.size(6.dp))
            }
            Text("入库", fontWeight = FontWeight.SemiBold)
        }
        Text(
            "问/答必填 · 不含售价 · 入库后立刻可搜",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
    }
    if (addState.message.isNotBlank()) {
        Text(
            addState.message,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            color = if (addState.done) Primary else Danger,
        )
    }
}
