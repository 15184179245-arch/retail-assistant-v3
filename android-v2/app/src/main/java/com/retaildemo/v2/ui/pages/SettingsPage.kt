package com.retaildemo.v2.ui.pages

import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.RosterItemDto
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.retaildemo.v2.ui.components.directTap
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.BorderStrong
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.DangerLight
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.viewmodel.SettingsViewModel
import com.retaildemo.v2.ui.window.SanxAlertDialog

/** 设置：服务器地址 / 店员名 / 连接测试 / 手动拉商品库。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    onBack: () -> Unit,
    // 09 稿重排（2026-09-19 老板令「以设计稿为准」）：工作台业务队列/台账入口迁入本页，由宿主传入导航回调
    onOpen: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val serverUrl by viewModel.serverUrl.collectAsState()
    val staffName by viewModel.staffName.collectAsState()
    val trustAllTls by viewModel.trustAllTls.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val syncing by viewModel.productsSyncing.collectAsState()
    val syncPhase by viewModel.syncPhase.collectAsState()
    val syncPercent by viewModel.syncPercent.collectAsState()
    val syncText by viewModel.syncText.collectAsState()
    val restoring by viewModel.restoring.collectAsState()
    val staffList by viewModel.staffList.collectAsState()
    val staffId by viewModel.staffId.collectAsState()
    val soundOn by viewModel.soundOn.collectAsState()
    val vibrateOn by viewModel.vibrateOn.collectAsState()
    val lastSyncText by viewModel.lastSyncText.collectAsState()
    val cleanupResult by viewModel.cleanupResult.collectAsState()
    val context = LocalContext.current

    var urlInput by remember(serverUrl) { mutableStateOf(serverUrl) }
    var staffInput by remember(staffName) { mutableStateOf(staffName) }
    var addStaffInput by remember { mutableStateOf("") }

    // U6 名册改绑弹窗（打开时拉名册；离线/失败回退本机名单）
    var showRosterDialog by remember { mutableStateOf(false) }
    var rosterItems by remember { mutableStateOf<List<RosterItemDto>>(emptyList()) }
    var rosterLoading by remember { mutableStateOf(false) }
    LaunchedEffect(showRosterDialog) {
        if (showRosterDialog && rosterItems.isEmpty()) {
            rosterLoading = true
            rosterItems = viewModel.loadRoster()
            rosterLoading = false
        }
    }

    // U6 重建离线商品库二次确认
    var showRebuildConfirm by remember { mutableStateOf(false) }
    if (showRebuildConfirm) {
        SanxAlertDialog(
            onDismissRequest = { showRebuildConfirm = false },
            title = { Text("重建离线商品库？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将从服务器重新拉取全部商品并覆盖本地离线库（12,000+ SKU），耗时视网络而定。\n\n拉取成功才会覆盖；网络不可用时本地商品库不会被动，查价能力不受影响。",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
            },
            confirmText = "确认重建",
            confirmDanger = true,
            onConfirm = {
                showRebuildConfirm = false
                viewModel.rebuildProductDb()
            },
            dismissText = "取消",
        )
    }

    // U6 名册改绑弹窗：优先服务端名册（工号+名字一起绑），离线回退本机名单（只绑名字）
    if (showRosterDialog) {
        SanxAlertDialog(
            onDismissRequest = { showRosterDialog = false },
            title = { Text("从名册改绑店员", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rosterLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("正在获取人员名册…", fontSize = 13.sp, color = TextSecondary)
                        }
                    } else if (rosterItems.isNotEmpty()) {
                        Text("在册店员（账单归属按工号追溯）", fontSize = 11.sp, color = TextWeak)
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            rosterItems.forEach { item ->
                                val isCurrent = item.id == staffId
                                // 方案 A 去套娃（画布 66:1106/66:1184 实测）：原 Surface 描边@187 +
                                // PrimaryLight/surfaceVariant 底色一并去掉，行直接落在弹窗白底上；
                                // 选中态改由「左 3dp 品牌竖条 + 字重 700 + primary 文字」三件套表达。
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .directTap(sfx = ScanFeedback.Sfx.COMMIT_OK) {
                                            viewModel.saveStaffIdentity(item.id, item.display_name)
                                            staffInput = item.display_name
                                            showRosterDialog = false
                                        },
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    ) {
                                        if (isCurrent) {
                                            // 画布 66:1113：竖条 x=12 · w=3 · h=28 · r=1.5 · primary #FF6A00
                                            Box(
                                                Modifier
                                                    .padding(end = 9.dp)
                                                    .size(width = 3.dp, height = 28.dp)
                                                    .background(Primary, RoundedCornerShape(1.5.dp)),
                                            )
                                        }
                                        Text(
                                            text = item.display_name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isCurrent) Primary else TextMain,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (isCurrent) {
                                            Icon(Icons.Default.Check, null, tint = Primary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            "名册不可达（服务器未连或离线），以下为本机名单，仅改名字不改工号：",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                        // ★ 这里**不许**再挂 verticalScroll：外层 SanxAlertDialog 的 text 槽已是唯一
                        // 滚动容器（window/SanxWindow.kt:536-543 用 weight(1f, fill=false) 给出无限高），
                        // 内层再挂一层拿到的是 infinity maxHeight，打开离线名册即抛
                        // "Vertically scrollable component was measured with an infinity maximum height constraints"。
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            staffList.forEach { s ->
                                // 方案 A 去套娃（画布 66:1106 方案注记2）：这组本就没有选中态，
                                // 连框带底一起撤掉，行界只靠 4dp 网格留白表达。
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .directTap(sfx = ScanFeedback.Sfx.COMMIT_OK) {
                                            viewModel.saveStaffName(s)
                                            staffInput = s
                                            showRosterDialog = false
                                        },
                                ) {
                                    Text(
                                        text = s,
                                        fontSize = 13.sp,
                                        color = TextMain,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            dismissText = "关闭",
        )
    }

    Scaffold(
        topBar = {
            retaildemoTopBar(
                title = "系统设置",
                subtitle = "网关通讯 · 身份绑定 · 离线数据同步",
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            // 稿（画布 21 设置）：卡 y=118(h72) / 201(h65) / 277(h347) / 635(h64) / 710(h64) / 785(h64)。
            // 像素三点法实测（21_settings.png, dpr2）：卡白止 623.0，边框 623~624，页底 624~635，
            // 下卡白起 636 → **卡间距 12**（4 处间距实测 11/12/11/11，取 12）。
            // 原 14.dp 偏大 2dp。
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // 服务器网关设置卡片
            SettingsGroupCard(
                title = "服务器网关",
                icon = Icons.Default.Dns,
                badge = "核心通信",
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("网关地址 (IP 或 域名:端口)") },
                    placeholder = { Text("http://192.0.2.10:18767") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Design.InputRadius.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Border,
                    ),
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AssistChip(
                        onClick = {
                            ScanFeedback.click(context)
                            viewModel.setPresetStoreLan()
                            urlInput = AppConfig.DEFAULT_STORE_LAN_URL
                            viewModel.testConnection()
                        },
                        label = { Text("店面 Wi-Fi (192.0.2.10)", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = PrimaryLight, labelColor = Primary),
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        border = null,
                    )
                    AssistChip(
                        onClick = {
                            ScanFeedback.click(context)
                            viewModel.setPresetWan()
                            urlInput = AppConfig.DEFAULT_WAN_URL
                            viewModel.testConnection()
                        },
                        label = { Text("外网穿透", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        border = null,
                    )
                    AssistChip(
                        onClick = {
                            ScanFeedback.click(context)
                            viewModel.setPresetLan()
                            urlInput = viewModel.serverUrl.value
                            viewModel.testConnection()
                        },
                        label = { Text(if (AppConfig.isEmulator()) "模拟器" else "本机", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        border = null,
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = {
                            ScanFeedback.click(context)
                            viewModel.saveServerUrl(urlInput)
                        },
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存地址", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            ScanFeedback.click(context)
                            viewModel.testConnection()
                        },
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("测试连接", fontWeight = FontWeight.SemiBold)
                    }
                }

                testResult?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    val isOk = msg.contains("✓") || msg.contains("正常")
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isOk) SuccessLight else DangerLight,
                        border = BorderStroke(0.5.dp, if (isOk) Success else Danger),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = msg,
                            color = if (isOk) Success else Danger,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // M2 从服务器恢复账单：重装/换机后的救命按钮
                OutlinedButton(
                    onClick = {
                        ScanFeedback.click(context)
                        viewModel.restoreBills()
                    },
                    enabled = !restoring,
                    shape = RoundedCornerShape(Design.PillRadius.dp),
                    border = BorderStroke(0.5.dp, Primary),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (restoring) "正在从服务器恢复…" else "从服务器恢复账单",
                        fontWeight = FontWeight.SemiBold,
                        color = Primary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "重装/换机后点这里把所有账单要回来；服务器上有什么就恢复什么，不会删除本机已有的账单。",
                    fontSize = 12.sp,
                    color = TextWeak,
                )

                Spacer(Modifier.height(12.dp))

                // TLS 选项
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "信任自签证书 / 公网隧道",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMain,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "适用于樱花穿透、FRP、自建内网穿透等 HTTPS 场景",
                                fontSize = 11.sp,
                                color = TextSecondary,
                            )
                        }
                        Switch(
                            checked = trustAllTls,
                            onCheckedChange = {
                                ScanFeedback.click(context)
                                viewModel.setTrustAllTls(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Primary,
                            ),
                        )
                    }
                }
            }

            // 店员身份设置卡片
            SettingsGroupCard(
                title = "店员身份绑定",
                icon = Icons.Default.People,
                badge = "开单签名",
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = staffInput,
                        onValueChange = { staffInput = it },
                        label = { Text("当前经办店员") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Design.InputRadius.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Border,
                        ),
                    )
                    Button(
                        onClick = {
                            // 语义纠偏：保存店员身份=提交类动作（此前借用扫码成功铃音）
                            ScanFeedback.play(context, ScanFeedback.Sfx.COMMIT_OK)
                            viewModel.saveStaffName(staffInput)
                        },
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Text("切换", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(6.dp))

                // U6 名册绑定态上屏 + 从名册改绑入口（M3 归属一等字段）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (staffId.isBlank()) "未绑名册（手输名字，离线可用）" else "名册工号 $staffId · 账单归属按工号追溯",
                        fontSize = 11.sp,
                        color = TextWeak,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        ScanFeedback.click(context)
                        showRosterDialog = true
                    }) {
                        Text("从名册改绑", fontSize = 12.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text("常用店员名单（点击切换，无需重复输入）", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(6.dp))

                if (staffList.isEmpty()) {
                    Text("名单为空，在下方输入店员名加入", fontSize = 12.sp, color = TextWeak)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        staffList.forEach { s ->
                            val isCurrent = s == staffName
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isCurrent) PrimaryLight else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(0.5.dp, if (isCurrent) Primary else Border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        ScanFeedback.click(context)
                                        staffInput = s
                                        viewModel.saveStaffName(s)
                                    },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = s,
                                        fontSize = 14.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCurrent) Primary else TextMain,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isCurrent) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Primary,
                                        ) {
                                            Text(
                                                "当前在用",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    TextButton(onClick = {
                                        ScanFeedback.click(context)
                                        viewModel.removeStaff(s)
                                    }) {
                                        Text("移除", fontSize = 12.sp, color = Danger)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = addStaffInput,
                        onValueChange = { addStaffInput = it },
                        placeholder = { Text("加入新店员，如：小王", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Design.InputRadius.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Border,
                        ),
                    )
                    OutlinedButton(
                        onClick = {
                            if (addStaffInput.isNotBlank()) {
                                ScanFeedback.click(context)
                                viewModel.addStaff(addStaffInput.trim())
                                addStaffInput = ""
                            }
                        },
                        enabled = addStaffInput.isNotBlank(),
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                    ) {
                        Text("加入")
                    }
                }
            }

            // 商品离线库同步
            SettingsGroupCard(
                title = "商品离线数据中心",
                icon = Icons.Default.CloudSync,
                badge = "12,000+ SKU",
            ) {
                Text(
                    text = "APP 拥有完整本地离线商品库（含拼音缩写索引与条形码映射）。扫码枪录入与快速查价直接运行在手机本地 SQLite，无网亦可极速响应。",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp,
                )

                Spacer(Modifier.height(6.dp))

                // U6 上次同步时间上屏（OFFLINE_FIRST_PLAN 5.4 承诺项）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = TextWeak,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(lastSyncText, fontSize = 11.sp, color = TextWeak)
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            ScanFeedback.click(context)
                            viewModel.pullProducts()
                        },
                        enabled = !syncing,
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(syncText.ifBlank { com.retaildemo.v2.data.repo.syncPhaseLabel(syncPhase.ifBlank { null }) })
                        } else {
                            Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("立即同步管家婆最新数据", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // S17 进度条：拉取阶段=真实页数（「拉取商品 7/26 页」+ 百分比），
                // 其余阶段按权重推进；手机回拉单独一段（92%→100%）。
                if (syncing) {
                    Spacer(Modifier.height(10.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                syncText.ifBlank { "同步中…" },
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            syncPercent?.let {
                                Text(
                                    "$it%",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Primary,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        val percent = syncPercent
                        if (percent != null) {
                            LinearProgressIndicator(
                                progress = { (percent.coerceIn(0, 100)) / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = Primary,
                                trackColor = Border,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = Primary,
                                trackColor = Border,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "点击＝真实登录管家婆拉取当场最新数据（不是读服务器缓存）：新增/改价/库存变更只落变更行；系统另在后台每 6 小时自动对齐一次。",
                    fontSize = 11.sp,
                    color = TextWeak,
                )
            }

            // U6 扫码反馈开关（巴枪范式的音效+震动，按店环境可关）
            SettingsGroupCard(
                title = "扫码反馈",
                icon = Icons.Default.VolumeUp,
                badge = "巴枪范式",
            ) {
                SettingsSwitchRow(
                    icon = Icons.Default.VolumeUp,
                    title = "操作提示音",
                    subtitle = "扫码成功/报错/点击的嘀声反馈",
                    checked = soundOn,
                    onChecked = { viewModel.setSoundOn(it) },
                )
                Spacer(Modifier.height(8.dp))
                SettingsSwitchRow(
                    icon = Icons.Default.Vibration,
                    title = "扫码震动",
                    subtitle = "扫码与关键操作的短震动确认",
                    checked = vibrateOn,
                    onChecked = { viewModel.setVibrateOn(it) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "开关即时生效、关 App 重开仍保留；提示音跟手机按键/快门音同一音量（系统音量），手机静音/振动档也照常响（跟真巴枪一样）。",
                    fontSize = 11.sp,
                    color = TextWeak,
                )
            }

            // U6 数据清理（工作台「高级网络与系统设置」入口承诺项，此卡兑现）
            SettingsGroupCard(
                title = "数据清理",
                icon = Icons.Default.DeleteSweep,
                badge = "可再生的才清",
            ) {
                SettingsActionRow(
                    icon = Icons.Default.History,
                    title = "清空搜索历史",
                    subtitle = "查价/记账搜索框的历史关键词（最多保留 30 条）",
                    enabled = true,
                    danger = false,
                    onClick = {
                        ScanFeedback.click(context)
                        viewModel.clearSearchHistory()
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    icon = Icons.Default.DeleteSweep,
                    title = "清理缓存文件",
                    subtitle = "图片与网络缓存，不影响商品库/账单等任何业务数据",
                    enabled = true,
                    danger = false,
                    onClick = {
                        ScanFeedback.click(context)
                        viewModel.clearAppCache()
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    icon = Icons.Default.RestartAlt,
                    title = "重建离线商品库",
                    subtitle = "增量同步异常时自救：从服务器全量重拉并覆盖本地商品库",
                    enabled = !syncing,
                    danger = true,
                    onClick = {
                        ScanFeedback.click(context)
                        showRebuildConfirm = true
                    },
                )
                cleanupResult?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    val isOk = msg.contains("✓")
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isOk) SuccessLight else DangerLight,
                        border = BorderStroke(0.5.dp, if (isOk) Success else Danger),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = msg,
                            color = if (isOk) Success else Danger,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "账单、客户、串货台账等业务数据永不在此清理；重建商品库拉取成功才覆盖，失败零删除。",
                    fontSize = 11.sp,
                    color = TextWeak,
                )
            }

            // 09 稿重排承接（2026-09-19）：原「我的」页业务队列/台账入口整体迁此，功能零删减
            SettingsGroupCard(
                title = "业务队列与台账",
                icon = Icons.Default.Badge,
                badge = "协同",
            ) {
                SettingsActionRow(
                    icon = Icons.AutoMirrored.Filled.ListAlt,
                    title = "数据标记队列",
                    subtitle = "巡架发现的价格不准/账实不符，一键导出给管家婆调账",
                    enabled = true,
                    danger = false,
                    onClick = {
                        ScanFeedback.click(context)
                        onOpen("mark_queue")
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    icon = Icons.Default.Check,
                    title = "待办清单",
                    subtitle = "进货提醒 · 巡店补货 · 现场杂务备忘",
                    enabled = true,
                    danger = false,
                    onClick = {
                        ScanFeedback.click(context)
                        onOpen("todos")
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    icon = Icons.Default.CheckCircle,
                    title = "日结 · 打烊清单",
                    subtitle = "打烊前对一遍：挂账、待出库、回收站一键盘点",
                    enabled = true,
                    danger = false,
                    onClick = {
                        ScanFeedback.click(context)
                        onOpen("closeday")
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    icon = Icons.Default.CloudDownload,
                    title = "出入库明细",
                    subtitle = "每笔出入库留痕可查，日终对账不扯皮",
                    enabled = true,
                    danger = false,
                    onClick = {
                        ScanFeedback.click(context)
                        onOpen("movements")
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    icon = Icons.Default.History,
                    title = "待补全无码货清单",
                    subtitle = "店里无码临时记账商品台账，管家婆建档销账",
                    enabled = true,
                    danger = false,
                    onClick = {
                        ScanFeedback.click(context)
                        onOpen("temp_queue")
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    icon = Icons.Default.SwapHoriz,
                    title = "串货台账",
                    subtitle = "串货垫钱报销核销 + 回写正式码 + 导出对账",
                    enabled = true,
                    danger = false,
                    onClick = {
                        ScanFeedback.click(context)
                        onOpen("strade_ledger")
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    icon = Icons.Default.Security,
                    title = "未定价商品清单",
                    subtitle = "上游管家婆售价为0需核定商品",
                    enabled = true,
                    danger = false,
                    onClick = {
                        ScanFeedback.click(context)
                        onOpen("unpriced")
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    icon = Icons.Default.WarningAmber,
                    title = "低库存预警",
                    subtitle = "低于安全水位库存商品实时排查与盘点",
                    enabled = true,
                    danger = false,
                    onClick = {
                        ScanFeedback.click(context)
                        onOpen("low_stock")
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    icon = Icons.Default.CloudSync,
                    title = "经营大盘与数据管理 (Web)",
                    subtitle = "营业毛利大盘、店员业绩榜、搜空日志、管家婆调整清单导出",
                    enabled = true,
                    danger = false,
                    onClick = {
                        ScanFeedback.click(context)
                        val base = if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) serverUrl else "http://$serverUrl"
                        val url = "${base.trimEnd('/')}/dashboard"
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        }
                    },
                )
            }

            // 应用关于信息与内部版本推送
            Surface(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                color = Color.White,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        "示例零售全能助手 v3",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "com.retaildemo.v2 · 当前版本 v${com.retaildemo.v2.BuildConfig.VERSION_NAME} (Code ${com.retaildemo.v2.BuildConfig.VERSION_CODE})",
                        fontSize = 11.sp,
                        color = TextWeak,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { viewModel.checkForUpdateManual() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
                        border = BorderStroke(1.dp, Primary),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(androidx.compose.material.icons.Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("检查新版本 (内部推送)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badge: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    modifier = Modifier.weight(1f),
                )
                if (badge != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = PrimaryLight,
                    ) {
                        Text(
                            text = badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** U6 设置页开关行（屏内私有）：图标+标题+说明+Switch，沿用无边框浅色语言。 */
@Composable
private fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                Spacer(Modifier.height(1.dp))
                Text(subtitle, fontSize = 11.sp, color = TextSecondary)
            }
            Switch(
                checked = checked,
                onCheckedChange = {
                    ScanFeedback.click(context)
                    onChecked(it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Primary,
                ),
            )
        }
    }
}

/** U6 设置页操作行（屏内私有）：图标+标题+说明+整行可点，danger=true 用于重建类动作。 */
@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    danger: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (danger) Danger else Primary
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (danger) DangerLight else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) accent else TextWeak,
                )
                Spacer(Modifier.height(1.dp))
                Text(subtitle, fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}
