package com.retaildemo.v2.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.RosterItemDto
import com.retaildemo.v2.ui.components.ScanFeedback

import com.retaildemo.v2.ui.components.ctaGradient
import com.retaildemo.v2.ui.theme.BorderStrong
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.viewmodel.SettingsViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * I1 首启引导：一机一人——「这台机器谁用」。
 *
 * M3（2026-09-11）升级：优先从**服务端人员名册**（GET /api/roster）下拉选择，
 * 选中即写入员工 id（归属一等字段，改名不断链）；名册不可达时回退本机名单/手输，
 * 此时 id 为空——归属仍记显示名，等设置页重新绑定名册即可补齐。
 * 仍然不设密码不登录（「全员管理员身份」拍板：名册不是权限系统）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OnboardingPage(
    onDone: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val config = remember(context) { AppConfig(context) }
    var name by remember { mutableStateOf("") }
    var staffList by remember { mutableStateOf(config.staffList.sorted()) }
    // M3 名册：非空时优先展示（带 id）；空=服务器不可达，走本机名单/手输降级
    var roster by remember { mutableStateOf<List<RosterItemDto>>(emptyList()) }
    var selectedId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        roster = viewModel.loadRoster()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = PrimaryLight,
                modifier = Modifier.size(76.dp),
                border = BorderStroke(1.5.dp, Primary),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Construction,
                        contentDescription = "示例零售全能助手",
                        tint = Primary,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("示例零售全能助手", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Primary)
            Spacer(Modifier.height(6.dp))
            Text("这台机器谁用？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "选好店员，之后每笔记账、串货都自动带上归属。不用登录，随时可在设置里改绑。",
                fontSize = 13.sp,
                color = TextSecondary,
            )
            Spacer(Modifier.height(20.dp))

            if (roster.isNotEmpty()) {
                Text("从人员名册选择（推荐）", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    roster.forEach { item ->
                        val selected = selectedId == item.id
                        FilterChip(
                            selected = selected,
                            onClick = {
                                ScanFeedback.click(context)
                                selectedId = item.id
                                name = item.display_name
                            },
                            label = {
                                Text(
                                    item.display_name,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            shape = RoundedCornerShape(Design.PillRadius.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryLight,
                                selectedLabelColor = Primary,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "从名册选 = 账单归属到这个人，以后改名也不会错位",
                    fontSize = 12.sp,
                    color = TextWeak,
                )
                Spacer(Modifier.height(14.dp))
            }

            if (staffList.isNotEmpty()) {
                Text(
                    if (roster.isEmpty()) "快捷选择店员" else "本机常用名字（离线备选）",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    staffList.forEach { s ->
                        val selected = name == s && selectedId.isBlank()
                        FilterChip(
                            selected = selected,
                            onClick = {
                                ScanFeedback.click(context)
                                selectedId = ""
                                name = s
                            },
                            label = { Text(s, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                            shape = RoundedCornerShape(Design.PillRadius.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryLight,
                                selectedLabelColor = Primary,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    selectedId = "" // 手输 = 脱离名册，归属只记显示名
                },
                label = { Text("或直接输入店员姓名") },
                singleLine = true,
                shape = RoundedCornerShape(Design.InputRadius.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = BorderStrong,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val n = name.trim()
                    if (n.isBlank() || n == "店员") return@Button
                    ScanFeedback.success(context)
                    // M3：走 ViewModel 统一入口——名册 id 一并落盘（空=未绑名册的降级态）
                    viewModel.saveStaffIdentity(selectedId, n)
                    onDone()
                },
                enabled = name.isNotBlank() && name.trim() != "店员",
                shape = RoundedCornerShape(Design.InputRadius.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Design.MinTouchTarget.dp)
                    .ctaGradient(RoundedCornerShape(Design.InputRadius.dp), enabled = name.isNotBlank() && name.trim() != "店员"),
            ) {
                Text("确认并进入工作台", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
