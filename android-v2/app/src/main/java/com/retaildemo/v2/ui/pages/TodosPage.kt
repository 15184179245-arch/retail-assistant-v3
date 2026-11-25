package com.retaildemo.v2.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.data.remote.TodoDto
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.theme.BgGray
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Card
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.viewmodel.TodosViewModel

/**
 * 待办清单页：店员进货备忘、补货提醒、货架维修与现场待办事项。
 */
@Composable
fun TodosPage(
    onBack: () -> Unit,
    viewModel: TodosViewModel = hiltViewModel(),
) {
    val todos by viewModel.todos.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val msg by viewModel.msg.collectAsState()
    val context = LocalContext.current

    var inputText by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(msg) {
        msg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMsg()
        }
    }

    val submitAdd = {
        if (inputText.isNotBlank()) {
            ScanFeedback.click(context)
            viewModel.create(inputText)
            inputText = ""
        }
    }

    Scaffold(
        topBar = {
            retaildemoTopBar(
                title = "待办清单",
                subtitle = "店员协同 · 现场备忘 · 进补货",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            ScanFeedback.click(context)
                            viewModel.load()
                        }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = TextMain,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BgGray,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // 快速新建输入栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "记录要办的事，如：补膨胀螺丝 / 换3号货架标",
                            fontSize = 13.sp,
                            color = TextWeak,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Card,
                        unfocusedContainerColor = Card,
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Border,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitAdd() }),
                )

                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(enabled = inputText.isNotBlank()) { submitAdd() },
                    shape = CircleShape,
                    color = if (inputText.isNotBlank()) Primary else PrimaryLight,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "添加",
                            tint = if (inputText.isNotBlank()) Color.White else TextWeak,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 列表或空状态
            if (busy && todos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                }
            } else if (todos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        title = "暂无待办事项",
                        subtitle = "进货提醒、巡店补货、现场杂务均可记在这里",
                        icon = Icons.AutoMirrored.Filled.ListAlt,
                    )
                }
            } else {
                val pendingCount = todos.count { it.status != "completed" }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "共 ${todos.size} 项 · 待处理 $pendingCount 项",
                        fontSize = 12.sp,
                        color = TextSecondary,
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(todos, key = { it.id }) { item ->
                        TodoCard(
                            todo = item,
                            onToggle = {
                                ScanFeedback.click(context)
                                viewModel.toggle(item.id)
                            },
                            onDelete = {
                                ScanFeedback.click(context)
                                viewModel.delete(item.id)
                            },
                        )
                    }
                    item {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoCard(
    todo: TodoDto,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDone = todo.status == "completed"

    Card(
        modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 自定义大触控复选框
            // 复选框：选中=实心主色 + 白勾；未选中=透明底 + 细边框（下面 Surface 全权负责外观）
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                    color = if (isDone) Primary else Color.Transparent,
                    border = if (!isDone) androidx.compose.foundation.BorderStroke(1.5.dp, Border) else null,
                ) {
                    if (isDone) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "已完成",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // 文本与元数据
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = todo.title,
                    fontSize = 15.sp,
                    fontWeight = if (isDone) FontWeight.Normal else FontWeight.Medium,
                    textDecoration = if (isDone) TextDecoration.LineThrough else null,
                    color = if (isDone) TextWeak else TextMain,
                )

                val metaTokens = buildList {
                    if (todo.priority == "high") add("重要")
                    todo.created_by?.takeIf { it.isNotBlank() }?.let { add("店员: $it") }
                    todo.created_at?.takeIf { it.isNotBlank() }?.let { add(it.take(16)) }
                }

                if (metaTokens.isNotEmpty()) {
                    Text(
                        text = metaTokens.joinToString(" · "),
                        fontSize = 11.sp,
                        color = if (todo.priority == "high" && !isDone) Danger else TextWeak,
                    )
                }
            }

            // 删除操作
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "删除待办",
                    tint = TextWeak,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
