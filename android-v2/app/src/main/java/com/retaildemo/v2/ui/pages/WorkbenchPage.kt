package com.retaildemo.v2.ui.pages

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.components.ctaGradient
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.viewmodel.BillsViewModel
import com.retaildemo.v2.ui.viewmodel.SettingsViewModel
import com.retaildemo.v2.ui.viewmodel.StockViewModel

/**
 * 「我的 · 工作台」是否开放管理专区（2026-09-11 老板令：全面开放免密直达）。
 * 保留此纯函数：AdminZoneVisibilityTest 单测钉住该口径。
 */
fun shouldShowAdminZone(jwtToken: String? = null): Boolean = true

/**
 * 09 稿《我的 · 工作台》（design/screens/09_workbench.png，画布 3:18）逐件落地：
 * 大标题头（我的 + ⚙️）→ 店卡（logo + 店名 + 店员 + 在线）→ 四格数据条 →
 * 老王卡（问一句）→ 双列六宫格（打印与价签/客户与欠款/调账·盘点/同步与备份/货架写贴/帮助与反馈）
 * → 设置行卡 → 版本页脚。
 * 2026-09-19 老板令「没有裁决流程，都以设计稿为准」：废除旧的业务清单长列表形态；
 * 队列/台账类入口整体迁往设置页「业务队列与台账」分组（SettingsPage）。
 */
@Composable
fun WorkbenchPage(
    nav: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val staffName by viewModel.staffName.collectAsState()

    // 2026-09-11 老板令「浮窗能关闭」：悬浮球收起后保留此处的恢复入口
    val dockHidden = remember { mutableStateOf(AppConfig(context).agentDockHidden) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // ---- 页头：大标题「我的」+ 店名副标 + ⚙️ 设置圆钮（09 稿 y70..102）----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "我的",
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                )
                Text(
                    text = "示例零售",
                    fontSize = 13.sp,
                    color = TextWeak,
                )
            }
            Surface(
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier
                    .size(44.dp)
                    .sxElevation(SxElevation.L1, shape = CircleShape)
                    .clickable {
                        ScanFeedback.click(context)
                        nav.navigate("settings")
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 店卡（09 稿 y126..205）：logo 方块 + 店名 + 店员行 + 在线胶囊 ----
        Card(
            shape = RoundedCornerShape(Design.CardRadius.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .sxElevation(SxElevation.L2, shape = RoundedCornerShape(Design.CardRadius.dp)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PrimaryLight,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "示例零售",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "店员 $staffName",
                        fontSize = 12.sp,
                        color = TextSecondary,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SuccessLight,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Success, CircleShape),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "在线",
                            color = Success,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 四格数据条（今日单量 / 挂账待录 / 已出库 / 库存预警）----
        val workbenchBillsVm: BillsViewModel = hiltViewModel()
        val workbenchStats by workbenchBillsVm.todayStats.collectAsState()
        val workbenchStockVm: StockViewModel = hiltViewModel()
        val workbenchLowStock by workbenchStockVm.low.collectAsState()
        Card(
            shape = RoundedCornerShape(Design.CardRadius.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .sxElevation(SxElevation.L2, shape = RoundedCornerShape(Design.CardRadius.dp)),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                StatsCell(
                    label = "今日单量",
                    value = workbenchStats.count + workbenchStats.todayPendingCount,
                    valueColor = TextMain,
                    modifier = Modifier.weight(1f),
                ) { nav.navigate("bills") { launchSingleTop = true } }
                StatsCell(
                    label = "挂账待录",
                    value = workbenchStats.todayPendingCount,
                    valueColor = Warning,
                    modifier = Modifier.weight(1f),
                ) { nav.navigate("bills") { launchSingleTop = true } }
                StatsCell(
                    label = "已出库",
                    value = workbenchStats.count,
                    valueColor = Success,
                    modifier = Modifier.weight(1f),
                ) { nav.navigate("bills") { launchSingleTop = true } }
                StatsCell(
                    label = "库存预警",
                    value = workbenchLowStock.size,
                    valueColor = Danger,
                    modifier = Modifier.weight(1f),
                ) { nav.navigate("low_stock") }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 老王卡（09 稿 y325..377）：标题 + 一句话 + 「问一句」橙胶囊 ----
        Card(
            shape = RoundedCornerShape(Design.CardRadius.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .sxElevation(SxElevation.L2, shape = RoundedCornerShape(Design.CardRadius.dp)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ScanFeedback.click(context)
                        nav.navigate("agent_chat")
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "老王 · AI 店员",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "问价、查货、问库存，一句话的事",
                        fontSize = 12.sp,
                        color = TextSecondary,
                    )
                }
                Button(
                    onClick = {
                        ScanFeedback.click(context)
                        nav.navigate("agent_chat")
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(17.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .ctaGradient(RoundedCornerShape(17.dp)),
                ) {
                    Text("问一句", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (dockHidden.value) {
            TextButton(onClick = {
                AppConfig(context).agentDockHidden = false
                dockHidden.value = false
            }) {
                Text("恢复老王悬浮窗", color = Primary, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- B14 多维度报表入口行（09 稿同款导航行卡）----
        Card(
            shape = RoundedCornerShape(Design.CardRadius.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    ScanFeedback.click(context)
                    nav.navigate("reports")
                }
                .sxElevation(SxElevation.L2, shape = RoundedCornerShape(Design.CardRadius.dp)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "多维度报表",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                        lineHeight = 22.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "按店员 · 客户 · 品类 · 收款方式 · 商品，时段自选",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 双列六宫格（09 稿 y395..622：tile 高 67dp、列距 12、静态墙 N=2 等分）----
        val tiles = listOf(
            WorkbenchTile(Icons.Default.Print, "打印与价签") {
                nav.navigate("printer_manage")
            },
            WorkbenchTile(Icons.Default.People, "客户与欠款") {
                nav.navigate("customers")
            },
            WorkbenchTile(Icons.Default.Assignment, "调账 · 盘点") {
                nav.navigate("stocktake")
            },
            WorkbenchTile(Icons.Default.Sync, "同步与备份") {
                nav.navigate("settings")
            },
            WorkbenchTile(Icons.Default.Nfc, "货架写贴") {
                nav.navigate("shelf_write")
            },
            WorkbenchTile(Icons.Default.HelpOutline, "帮助与反馈") {
                // 帮助与反馈的落地=问老王（应用内唯一帮助通道；反馈走台账导出）
                nav.navigate("agent_chat")
            },
        )
        tiles.chunked(2).forEach { rowTiles ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                rowTiles.forEach { tile ->
                    WorkbenchTileCard(
                        icon = tile.icon,
                        label = tile.label,
                        modifier = Modifier
                            .weight(1f)
                            .height(67.dp),
                        onClick = {
                            ScanFeedback.click(context)
                            tile.onClick()
                        },
                    )
                }
            }
        }

        // ---- 设置行卡（09 稿 y636..685）----
        Card(
            shape = RoundedCornerShape(Design.CardRadius.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    ScanFeedback.click(context)
                    nav.navigate("settings")
                }
                .sxElevation(SxElevation.L1, shape = RoundedCornerShape(Design.CardRadius.dp)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "设置",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                )
                Text(
                    text = " · 服务器地址 / 音效 / 震动",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = null,
                    tint = TextWeak,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ---- 09 稿页脚：版本与数据口径声明 ----
        Text(
            text = "示例零售助手 v${com.retaildemo.v2.BuildConfig.VERSION_NAME} · 数据本地优先，先记账后对账",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = TextWeak,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        )

        // 尾距 110dp：悬浮底栏(62+12dp)+余量，避免脚注被底栏盖住（与 01 列表 90dp 同理）
        Spacer(Modifier.height(110.dp).navigationBarsPadding())
    }
}

private data class WorkbenchTile(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/** 双列宫格 tile：白卡 rx14、高 67dp，icon + 标签左对齐（09 稿 y395..622）。 */
@Composable
private fun WorkbenchTileCard(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.sxElevation(SxElevation.L1, shape = RoundedCornerShape(Design.CardRadius.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = TextMain,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain,
            )
        }
    }
}

@Composable
private fun StatsCell(
    label: String,
    value: Int,
    valueColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Text(
            text = "$value",
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, lineHeight = 15.sp, color = TextWeak)
    }
}
