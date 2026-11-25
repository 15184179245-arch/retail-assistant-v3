package com.retaildemo.v2

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.retaildemo.v2.ui.components.directTap
import androidx.compose.ui.res.painterResource
import com.retaildemo.v2.R
import com.retaildemo.v2.ui.components.glassSurface
import com.retaildemo.v2.ui.pages.BillsPage
import com.retaildemo.v2.ui.pages.NewBillPage
import com.retaildemo.v2.ui.pages.SettingsPage
import com.retaildemo.v2.ui.pages.BillDetailPage
import com.retaildemo.v2.ui.pages.SearchPage
import com.retaildemo.v2.ui.pages.ProductDetailPage
import com.retaildemo.v2.ui.pages.LowStockPage
import com.retaildemo.v2.ui.pages.MovementsPage
import com.retaildemo.v2.ui.pages.ShelfPage
import com.retaildemo.v2.ui.pages.WorkbenchPage
import com.retaildemo.v2.ui.pages.PrinterManagePage
import com.retaildemo.v2.ui.pages.CustomersPage
import com.retaildemo.v2.ui.pages.PrintQueuePage
import com.retaildemo.v2.ui.pages.ReportsPage
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.ui.pages.OnboardingPage
import com.retaildemo.v2.ui.pages.StradeLedgerPage
import com.retaildemo.v2.ui.pages.TempQueuePage
import com.retaildemo.v2.ui.pages.MarkQueuePage
import com.retaildemo.v2.ui.pages.TodosPage
import com.retaildemo.v2.ui.components.ScanFeedback
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.platform.LocalContext
import com.retaildemo.v2.ui.theme.Primary
import dagger.hilt.android.AndroidEntryPoint

// 画布 01 稿原生矢量图标接入（3:187、3:192、3:197、3:202 矢量单源）
private data class Tab(
    val route: String,
    val label: String,
    val iconRes: Int,
)

private val tabs = listOf(
    Tab("bills", "记账", R.drawable.ic_nav_billing),
    Tab("search", "查价", R.drawable.ic_nav_price),
    Tab("shelf", "货架", R.drawable.ic_nav_shelf),
    Tab("workbench", "我的", R.drawable.ic_nav_profile),
)

// 2026-09-19 性能收口：BottomBar 每次重组都要判 current 是否命中 tab 路由，
// 旧写法 tabs.map{}.contains 每次现建 List；Set 化零分配。
private val tabRoutes: Set<String> = tabs.map { it.route }.toSet()

object NfcEventBus {
    val targetCell = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
}

/**
 * 壳层可见性总线（2026-09-18 立）。
 *
 * 有些页面在**同一路由内部**会下钻到"子形态"，而子形态按设计稿**不该有悬浮 tab 栏**
 * （Ardot 3:16 格详情底部只有橙色 CTA `358×52 rx=14`，实测该 SVG 里根本没有
 * `348×62 rx=31` 那个胶囊）。`MainActivity` 的 `showBar` 只看路由名，
 * 看不见路由内部的父子切换，所以需要页面主动把"我现在是不是子形态"报上来。
 *
 * 用法契约：
 * - **谁设谁清**。进入子形态前设 `false`，离开（含 `DisposableEffect.onDispose`）必须恢复 `true`，
 *   否则 tab 栏会永久消失——那比不隐藏更糟。
 * - 只在"确实要在子形态里藏 tab 栏"时才用；能用路由区分的（如 new_bill）不要走这里。
 */
object ShellChromeState {
    val showTabBar = kotlinx.coroutines.flow.MutableStateFlow(true)
}

@AndroidEntryPoint
class MainActivity : androidx.activity.ComponentActivity() {
    @javax.inject.Inject
    lateinit var updateManager: com.retaildemo.v2.util.AppUpdateManager

    private var nfcAdapter: android.nfc.NfcAdapter? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = try { android.nfc.NfcAdapter.getDefaultAdapter(this) } catch (_: Exception) { null }
        handleNfcIntent(intent)
        updateManager.checkForUpdate(isManual = false)
        setContent {
            com.retaildemo.v2.ui.theme.retaildemoTheme {
                retaildemoNavHost(updateManager)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let { adapter ->
            try {
                val pi = android.app.PendingIntent.getActivity(
                    this, 0,
                    android.content.Intent(this, javaClass).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
                adapter.enableForegroundDispatch(this, pi, null, null)
            } catch (_: Exception) {
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action == android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED
        ) {
            val records = parseNdefTexts(intent)
            // 位置码可能在任意一条记录里：第三方写贴工具会写「URL + 文本」多条记录。
            // 旧实现只认第一条文本 —— 第一条不是位置码就整张贴作废，却**照样跳到货架页**，
            // 店员看到的就是「碰一下只切到货架页、没落到那一架」（老板 2026-09-14 反馈）。
            val payload = com.retaildemo.v2.nfc.ShelfNfc.pickTagPayload(records)
            if (payload == null) {
                // 不是位置码就不许劫持界面：把贴里到底是什么讲清楚（现场排障的唯一线索）
                val raw = records.firstOrNull { !it.isNullOrBlank() }?.trim()
                if (!raw.isNullOrBlank()) {
                    android.widget.Toast.makeText(
                        this,
                        "这张贴不是货架位置码（贴里是「${raw.take(20)}」）\n请用「货架 → 写贴」重新写这张贴",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                return
            }
            ScanFeedback.success(this)
            NfcEventBus.targetCell.tryEmit(payload)
        }
    }

    /**
     * 取出 NDEF **全部**记录的可读文本。
     *
     * 文本记录（RTD_TEXT）要跳过语言码前缀；其余记录（如绝对 URI 型贴）整段就是内容 ——
     * 若对 URI 记录也套语言码前缀解析，会把开头几个字节吃掉变成乱码（旧实现的隐患）。
     */
    private fun parseNdefTexts(intent: android.content.Intent): List<String> {
        return try {
            val raw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES, android.os.Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES)
            } ?: return emptyList()

            val out = ArrayList<String>(raw.size)
            for (item in raw) {
                val msg = item as? android.nfc.NdefMessage ?: continue
                for (record in msg.records) {
                    val payload = record.payload ?: continue
                    if (payload.isEmpty()) continue
                    val type = record.type ?: ByteArray(0)
                    val isText = record.tnf == android.nfc.NdefRecord.TNF_WELL_KNOWN &&
                        type.contentEquals(android.nfc.NdefRecord.RTD_TEXT)
                    val text = if (isText) {
                        val langLen = payload[0].toInt() and 0x3F
                        if (payload.size > 1 + langLen) {
                            String(payload, 1 + langLen, payload.size - 1 - langLen, Charsets.UTF_8)
                        } else {
                            String(payload, Charsets.UTF_8)
                        }
                    } else {
                        String(payload, Charsets.UTF_8)
                    }
                    if (text.isNotBlank()) out.add(text)
                }
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

@Composable
fun retaildemoNavHost(updateManager: com.retaildemo.v2.util.AppUpdateManager? = null) {
    val nav = rememberNavController()
    // 监听 NFC 碰贴直达货架
    androidx.compose.runtime.LaunchedEffect(Unit) {
        NfcEventBus.targetCell.collect {
            nav.navigate("shelf") {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    /* V7 生成式 UI：卡片动作走白名单总线，统一在这里导航（Agent 页拿不到 NavController） */
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.retaildemo.v2.ui.agent.a2ui.AgentActionBus.requests.collect { nav.navigate(it) }
    }
    // APP 内部版本推送弹窗
    if (updateManager != null) {
        com.retaildemo.v2.ui.components.AppUpdateDialog(updateManager)
    }
    // I1 首启分流：未绑定且仍是默认店员名 → 先进引导页（onboarding 不在 tabs，底栏自然隐藏）
    // 2026-09-19 性能收口：AppConfig remember 化——旧写法每次导航重组都新建实例并重读
    // SharedPreferences（首次读取是主线程磁盘 IO，正好卡在冷启动首帧上）。
    val navHostContext = androidx.compose.ui.platform.LocalContext.current
    val config = androidx.compose.runtime.remember { AppConfig(navHostContext) }
    val start = if (!config.staffBound && config.staffName == "店员") "onboarding" else "bills"

    // 真·悬浮底栏架构：内容 NavHost 全屏铺满延伸至屏幕下沿外，BottomBar 作为悬浮 Overlay 浮在上方
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                androidx.compose.animation.slideInHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(com.retaildemo.v2.ui.theme.Design.NavSlideMs, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                ) { it / 4 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.retaildemo.v2.ui.theme.Design.NavSlideMs))
            },
            exitTransition = {
                androidx.compose.animation.slideOutHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(com.retaildemo.v2.ui.theme.Design.NavSlideMs, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                ) { -it / 4 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.retaildemo.v2.ui.theme.Design.NavSlideMs))
            },
            popEnterTransition = {
                androidx.compose.animation.slideInHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(com.retaildemo.v2.ui.theme.Design.NavSlideMs, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                ) { -it / 4 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.retaildemo.v2.ui.theme.Design.NavSlideMs))
            },
            popExitTransition = {
                androidx.compose.animation.slideOutHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(com.retaildemo.v2.ui.theme.Design.NavSlideMs, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                ) { it / 4 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.retaildemo.v2.ui.theme.Design.NavSlideMs))
            },
        ) {
            // ---- 1. 记账 ----
            composable("onboarding") {
                OnboardingPage(onDone = {
                    nav.navigate("bills") { popUpTo("onboarding") { inclusive = true } }
                })
            }
            composable("bills") {
                BillsPage(
                    onNewBill = { nav.navigate("new_bill") },
                    onSettings = { nav.navigate("settings") },
                    onOpenBill = { nav.navigate("bill_detail/$it") },
                    onOpenCloseDay = { nav.navigate("closeday") },
                )
            }
            composable("closeday") {
                com.retaildemo.v2.ui.pages.CloseDayPage(
                    onBack = { nav.popBackStack() },
                    onOpenBills = { nav.navigate("bills") },
                    onOpenStrade = { nav.navigate("strade_ledger") },
                    onOpenQueue = { nav.navigate("temp_queue") },
                    onOpenRecycleBin = { nav.navigate("recyclebin") },
                )
            }
            composable("recyclebin") {
                com.retaildemo.v2.ui.pages.RecycleBinPage(onBack = { nav.popBackStack() })
            }
            composable("new_bill") {
                NewBillPage(
                    onBack = { nav.popBackStack() },
                    onBillSaved = { billId ->
                        nav.navigate("bill_detail/$billId") {
                            popUpTo("bills")
                        }
                    },
                )
            }
            composable(
                "bill_detail/{billId}",
                arguments = listOf(navArgument("billId") { type = NavType.StringType }),
            ) { BillDetailPage(onBack = { nav.popBackStack() }, onOpenBill = { nav.navigate("bill_detail/$it") }) }

            // ---- 2. 查价 ----
            composable("search") { SearchPage(onOpenProduct = { nav.navigate("product/$it") }) }
            composable(
                "product/{barcode}",
                arguments = listOf(navArgument("barcode") { type = NavType.StringType }),
            ) {
                ProductDetailPage(
                    onBack = { nav.popBackStack() },
                    onRecordSale = { nav.navigate("new_bill") },
                    onOpenProduct = { nav.navigate("product/$it") },
                    onKnowledge = {},
                    onOpenBill = { nav.navigate("bill_detail/$it") },
                )
            }

            // ---- 3. 货架（三级：货架→层→格；名字完全自定义；本地 Room，断网可用） ----
            composable("shelf") {
                ShelfPage(
                    onOpenProduct = { nav.navigate("product/$it") },
                    onOpenWriteTag = { nav.navigate("shelf_write") },
                    onOpenStocktake = { nav.navigate("stocktake") },
                    onOpenAdjust = { nav.navigate("adjust") },
                )
            }
            composable("shelf_write") {
                com.retaildemo.v2.ui.pages.ShelfWritePage(onBack = { nav.popBackStack() })
            }
            // I20 收编：盘点/调账原先无路由（孤儿页），从货架页给入口
            composable("stocktake") {
                com.retaildemo.v2.ui.pages.StocktakePage(onBack = { nav.popBackStack() })
            }
            composable("adjust") {
                com.retaildemo.v2.ui.pages.AdjustPage(onBack = { nav.popBackStack() })
            }
            composable("low_stock") { LowStockPage(onBack = { nav.popBackStack() }) }
            composable("movements") { MovementsPage(onBack = { nav.popBackStack() }) }

            // ---- 4. 我的 · 工作台（09 稿：店卡 + 四格数据条 + 老王卡 + 双列六宫格 + 设置行） ----
            composable("workbench") { WorkbenchPage(nav) }
            composable("more") { WorkbenchPage(nav) }
            composable("temp_queue") { TempQueuePage(onBack = { nav.popBackStack() }) }
            composable("strade_ledger") { StradeLedgerPage(onBack = { nav.popBackStack() }) }
            composable("mark_queue") { MarkQueuePage(onBack = { nav.popBackStack() }) }
            composable("print_queue") { PrintQueuePage(onBack = { nav.popBackStack() }) }
            composable("reports") { ReportsPage(onBack = { nav.popBackStack() }) }
            composable("printer_manage") { PrinterManagePage(onBack = { nav.popBackStack() }, onOpenQueue = { nav.navigate("print_queue") }) }
            composable("settings") { SettingsPage(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(it) }) }
            composable("customers") { CustomersPage(onBack = { nav.popBackStack() }) }
            composable("todos") { TodosPage(onBack = { nav.popBackStack() }) }
            composable("unpriced") { com.retaildemo.v2.ui.pages.UnpricedPage(onBack = { nav.popBackStack() }) }

            // ---- L10: 老王全屏 AI 工作台（一键跳转独立页面，老板令） ----
            composable("agent_chat") {
                com.retaildemo.v2.ui.pages.AgentChatPage(
                    vm = androidx.hilt.navigation.compose.hiltViewModel(),
                    onBack = { nav.popBackStack() },
                )
            }
        }

        // ---- L10 老王悬浮胶囊：可拖动贴边、可×收起（2026-09-11 老板令「浮窗要能移动能关闭」） ----
        // 位置存 AppConfig.agentBallX/Y（-1=未存过→默认右下角），收起态存 agentDockHidden，「我的」页可恢复
        val agentVm: com.retaildemo.v2.ui.viewmodel.AgentViewModel =
            androidx.hilt.navigation.compose.hiltViewModel()
        val agentOnline by agentVm.online.collectAsState()
        androidx.compose.runtime.LaunchedEffect(Unit) { agentVm.checkHealth() }

        val navBackStackForAgent by nav.currentBackStackEntryAsState()
        val currentAgentRoute = navBackStackForAgent?.destination?.route
        // 在老王自身工作台内部、新开单流程(02开单专注工作台)时隐藏胶囊，其余页面常驻待命
        val showDock = currentAgentRoute != "agent_chat" && currentAgentRoute != "onboarding" && currentAgentRoute != "new_bill"
        // 收起态：本地镜像保证点×立即消失；按路由换 key 重读 prefs——工作台「恢复」后回来即重现
        val dockHidden = androidx.compose.runtime.remember(currentAgentRoute) {
            androidx.compose.runtime.mutableStateOf(config.agentDockHidden)
        }
        if (showDock && !dockHidden.value) {
            val dockContext = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                com.retaildemo.v2.ui.components.AgentFloatDock(
                    online = agentOnline,
                    onClick = {
                        if (currentAgentRoute != "agent_chat") {
                            nav.navigate("agent_chat")
                        }
                    },
                    onDismiss = {
                        config.agentDockHidden = true
                        dockHidden.value = true
                        android.widget.Toast.makeText(
                            dockContext,
                            "已收起老王，可在「我的」页找回",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                    initialX = config.agentBallX.dp,
                    initialY = config.agentBallY.dp,
                    screenW = maxWidth,
                    screenH = maxHeight,
                    onPositionChanged = { x, y ->
                        config.agentBallX = x.value
                        config.agentBallY = y.value
                    },
                )
            }
        }

        // ---- 打印队列常驻悬浮出签胶囊（2026-09-10 老板令「打印队列做成浮窗，随时查看/随时打印/随时更改」） ----
        val navBackStack by nav.currentBackStackEntryAsState()
        val currentRoute = navBackStack?.destination?.route
        val onQueuePages = currentRoute == "print_queue" || currentRoute == "printer_manage" || currentRoute == "new_bill"
        if (!onQueuePages) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.BottomEnd,
            ) {
                com.retaildemo.v2.ui.components.PrintQueueFloatDock(
                    bottomInset = 84.dp,
                )
            }
        }

        // ---- 真·悬浮底栏（常驻主导航 Tabs，浮于最顶层，穿透其下滚动的卡片） ----
        BottomBar(nav)
    }
}

@Composable
private fun BoxScope.BottomBar(nav: NavHostController) {
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    // 页面可以按"自己进没进子形态"要求临时收起 tab 栏（见 ShellChromeState 契约）。
    val pageWantsTabBar by com.retaildemo.v2.ShellChromeState.showTabBar.collectAsState()
    val showBar = current in tabRoutes && pageWantsTabBar
    if (!showBar) return
    // 01 稿 3:2 绝对几何：348x62 rx=31 居中悬浮胶囊，内嵌 82x54 rx=26 独立 Tab 胶囊
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .width(348.dp)
                .height(62.dp)
                .glassSurface(shape = RoundedCornerShape(31.dp))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = current == tab.route
                val pillBg by animateColorAsState(
                    targetValue = if (selected) Primary else androidx.compose.ui.graphics.Color.Transparent,
                    label = "tabPillBg",
                )
                // 01 稿：选中 tab=整格 pill 包住 icon+字、白 icon 白字；未选中 #667085（TextWeak）
                val tabTint by animateColorAsState(
                    targetValue = if (selected) androidx.compose.ui.graphics.Color.White
                    else com.retaildemo.v2.ui.theme.TextWeak,
                    label = "tabTint",
                )
                Box(
                    modifier = Modifier
                        .width(82.dp)
                        .height(54.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(pillBg)
                        .directTap(sfx = if (selected) null else ScanFeedback.Sfx.KEY) {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(tab.iconRes),
                            contentDescription = tab.label,
                            tint = tabTint,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tab.label,
                            fontSize = com.retaildemo.v2.ui.theme.Design.FontXxs.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = tabTint,
                        )
                    }
                }
            }
        }
    }
}
