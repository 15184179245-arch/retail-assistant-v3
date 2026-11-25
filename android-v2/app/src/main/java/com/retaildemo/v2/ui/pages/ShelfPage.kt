package com.retaildemo.v2.ui.pages

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.data.local.entity.DataMarkEntity
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.repo.NameCandidate
import com.retaildemo.v2.data.repo.ShelfLocationRef
import com.retaildemo.v2.data.repo.ShelfPhotoLoader
import com.retaildemo.v2.nfc.ShelfNfc
import com.retaildemo.v2.ui.components.PrimaryActionButton
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.ghostCell
import com.retaildemo.v2.ui.components.scrimBottomBrush
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.components.ctaGradient
import com.retaildemo.v2.ui.components.toolSurface
import com.retaildemo.v2.ui.theme.BgGray
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Card
import com.retaildemo.v2.ui.theme.Cyan
import com.retaildemo.v2.ui.theme.CyanLight
import com.retaildemo.v2.ui.theme.CtaGradientFrom
import com.retaildemo.v2.ui.theme.CtaGradientTo
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryDark
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.TextDisabled
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import com.retaildemo.v2.ui.viewmodel.BindTarget
import com.retaildemo.v2.ui.viewmodel.BindingUi
import com.retaildemo.v2.ui.viewmodel.Crumb
import com.retaildemo.v2.ui.viewmodel.LevelUi
import com.retaildemo.v2.ui.viewmodel.ProductSnap
import com.retaildemo.v2.ui.viewmodel.ShelfUi
import com.retaildemo.v2.ui.viewmodel.ShelfViewModel
import com.retaildemo.v2.ui.window.SanxAlertDialog
import com.retaildemo.v2.ui.viewmodel.SlotUi
import com.retaildemo.v2.ui.viewmodel.ZoneUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 建名对象类型。 */
private enum class NameKind { ZONE, SHELF, LEVEL, SLOT }

/**
 * 建名/改名对话框状态。
 * @param shelfId 顶层货架 id（用于「就用它」时定位）
 * @param parentId LEVEL 用货架 id；SLOT 用层 id；SHELF/ZONE 忽略（货架父级走 zoneId）
 * @param zoneId  新建货架/分区时的父分区（null = 顶层）
 * @param editId 非空 = 改名既有对象
 */
private data class NameDialog(
    val kind: NameKind,
    val shelfId: Long = 0L,
    val parentId: Long = 0L,
    val parentName: String = "",
    val zoneId: Long? = null,
    val editId: Long? = null,
    val initial: String = "",
)

private data class DupState(
    val dialog: NameDialog,
    val pendingName: String,
    val candidates: List<NameCandidate>,
)

/** 备注编辑目标（货架/层/格三级共用）。 */
private data class NoteTarget(
    val kind: NameKind,
    val id: Long,
    val title: String,
    val current: String?,
)

private data class LevelAction(val shelfId: Long, val level: LevelUi)
private data class SlotAction(
    val levelId: Long,
    val slotId: Long,
    val slotName: String,
    val tagId: String,
    val parentPath: String,
    val note: String? = null,
)

/**
 * I20 货架理货主屏（三级：货架 → 层 → 格；名字完全自定义；数据全在本地 Room）。
 *
 * 对齐设计稿 `design/screens/shelf.html`：
 * 帧 A 主屏（层全展开 + 格网格 + 找货入口）· 帧 B 连扫绑定 · 帧 C 空态 · 帧 D 新建同名查重。
 *
 * 与旧版的关键差别：
 * - 货架条/层/格**全部来自真实数据**（旧版硬编码 A–F 架、货位数写死 12/24/16…）
 * - 绑定时**零网络**：旧版走 `InventoryRepo.bindingsOfCell` 打后端五级表，断网即废且接口恒空
 * - **一品多货位**：一个商品可同时出现在多个格/层，找货时全部列出、解绑一处不动别处
 * - 出签直接走**现有打印系统**（`SCENE_SHELF` 入打印队列，蓝牙/网络自动分流）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfPage(
    onOpenProduct: (String) -> Unit,
    onOpenWriteTag: () -> Unit = {},
    onOpenStocktake: () -> Unit = {},
    onOpenAdjust: () -> Unit = {},
    viewModel: ShelfViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tree by viewModel.tree.collectAsState()
    val zoneTree by viewModel.zoneTree.collectAsState()
    val currentZoneId by viewModel.currentZoneId.collectAsState()
    val currentShelves by viewModel.currentShelves.collectAsState()
    val currentZoneName by viewModel.currentZoneName.collectAsState()
    val selectedShelfId by viewModel.selectedShelfId.collectAsState()
    val target by viewModel.target.collectAsState()
    val jump by viewModel.jumpTarget.collectAsState()
    val bindingMode by viewModel.bindingMode.collectAsState()
    val sessionCount by viewModel.sessionBoundCount.collectAsState()
    val justBoundIds by viewModel.justBoundIds.collectAsState()
    val busy by viewModel.busy.collectAsState()

    val keyword by viewModel.findKeyword.collectAsState()
    val results by viewModel.findResults.collectAsState()
    val picked by viewModel.pickedProduct.collectAsState()
    val pickedLocations by viewModel.pickedLocations.collectAsState()
    val orphanLocations by viewModel.orphanLocations.collectAsState()
    val snapshots by viewModel.snapshots.collectAsState()

    var showZonePicker by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var dupState by remember { mutableStateOf<DupState?>(null) }
    var levelAction by remember { mutableStateOf<LevelAction?>(null) }
    var slotAction by remember { mutableStateOf<SlotAction?>(null) }
    var shelfAction by remember { mutableStateOf<ShelfUi?>(null) }
    var zoneAction by remember { mutableStateOf<ZoneUi?>(null) }
    var pendingDeleteShelf by remember { mutableStateOf<ShelfUi?>(null) }
    var pendingDeleteZone by remember { mutableStateOf<ZoneUi?>(null) }
    var pendingZoneImpact by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var moveTarget by remember { mutableStateOf<ShelfUi?>(null) }
    var noteTarget by remember { mutableStateOf<NoteTarget?>(null) }
    var noteInput by remember { mutableStateOf("") }
    var unmatchedCodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var markTarget by remember { mutableStateOf<BindingUi?>(null) }
    var markNote by remember { mutableStateOf("") }
    var markLocation by remember { mutableStateOf("") }

    val selectedShelf = tree.firstOrNull { it.id == selectedShelfId } ?: tree.firstOrNull()

    // ★★ 07 / 08 是**同一屏的父子两个形态**（老板 2026-09-18 定调：「这两屏是父子关系」）。
    //   07 货架立面 = 父形态：shelf 的层 × 格总览，底部**只有悬浮 tab 栏**（Ardot 3:14 实测
    //      y>600 仅 y=761 `348×62 rx=31`，无任何动作 dock），另加 y=675 一行居中灰字提示。
    //   08 格详情 = 子形态：点某一个格进入，顶部换成「‹ A架 · 1层 · 01 / 碰贴直达 · 已绑 N 件」
    //      + 右上「出签」，正文是该格的绑定清单，底部是固定橙色大 CTA
    //      `358×52 rx=14` @y=768「完成（本次 N 件）」（Ardot 3:16 实测，渐变 #FF7C19→#FF6B00）。
    //   判据：target 精确到「格」（slotId != null）即子形态；只有层（slotId == null）留在父形态。
    //   这样「点格卡里的某个格」= 下钻进子屏，父屏的格网格点选语义不变。
    val cellTarget = target?.takeIf { it.slotId != null }
    val inCell = cellTarget != null

    // 08 子形态按设计稿**不显示悬浮 tab 栏**（Ardot 3:16 底部只有橙色 CTA）。
    // 子形态与父形态同属 `shelf` 路由，MainActivity 看不见这次切换，所以由本页上报。
    // 契约「谁设谁清」：onDispose 必须恢复 true，否则离开货架页后 tab 栏会永久消失。
    DisposableEffect(inCell) {
        com.retaildemo.v2.ShellChromeState.showTabBar.value = !inCell
        onDispose { com.retaildemo.v2.ShellChromeState.showTabBar.value = true }
    }

    // 碰贴/位置码直达的滚动状态：货架横条 & 正文（层列表）各一份（见下方两处 LaunchedEffect）
    val stripState = rememberLazyListState()
    val levelListState = rememberLazyListState()

    // 页面进入：同步一次（多终端拉别端建的位置/结构；失败静默，离线优先不破）
    LaunchedEffect(Unit) { viewModel.syncNow() }

    LaunchedEffect(Unit) {
        viewModel.toastEvent.collect { toast(context, it) }
    }

    // 碰贴直达：载荷 SX:<tagId> → 定位到货架/层/格。
    // 音效已在 MainActivity 判定"确实是位置码"时播过，这里不再重复（同一次碰贴两声是语义 bug）。
    LaunchedEffect(Unit) {
        com.retaildemo.v2.NfcEventBus.targetCell.collect { raw ->
            val tagId = ShelfNfc.decodeTagPayload(raw) ?: return@collect
            viewModel.onNfcTag(tagId) { label -> toast(context, "已定位到「$label」") }
        }
    }

    // 直达之后把正文滚到目标层：多层货架里目标常常在屏幕外，「选中了但看不见」等于没到。
    LaunchedEffect(jump?.nonce, selectedShelf?.id) {
        val j = jump ?: return@LaunchedEffect
        val shelf = selectedShelf ?: return@LaunchedEffect
        if (j.shelfId != shelf.id) return@LaunchedEffect
        val idx = j.levelId?.let { id -> shelf.levels.indexOfFirst { it.id == id } } ?: -1
        if (idx >= 0) levelListState.animateScrollToItem(idx + if (bindingMode) 1 else 0)
    }

    val bindScanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val codes = if (result.resultCode == Activity.RESULT_OK) parseScanResult(result.data) else emptyList()
        if (codes.isEmpty()) {
            ScanFeedback.error(context)
        } else {
            // 语义纠偏：这是「绑定」不是「扫码」。此前借用扫码成功音，等于告诉店员"扫到了"，
            // 而实际发生的是"扣上了"——音效必须说清到底发生了什么。
            ScanFeedback.play(context, ScanFeedback.Sfx.BIND_OK)
            viewModel.bindScanned(codes) { miss -> unmatchedCodes = miss }
        }
    }

    val findScanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val codes = if (result.resultCode == Activity.RESULT_OK) parseScanResult(result.data) else emptyList()
        if (codes.isEmpty()) {
            ScanFeedback.error(context)
        } else {
            ScanFeedback.success(context)
            viewModel.findProductByCode(codes.first())
        }
    }

    Scaffold(
        topBar = {
            val ct = cellTarget
            if (ct != null) {
                // 08 子屏导航（Ardot 3:16 y=61）：左返回箭头 + 「A架 · 1层 · 01」大标题，
                // 副标题「碰贴直达 · 已绑 N 件」，右上「出签」。父屏没有这一层。
                val cellSlot = selectedShelf
                    ?.levels?.firstOrNull { it.id == ct.levelId }
                    ?.slots?.firstOrNull { it.id == ct.slotId }
                val cellBound = cellSlot?.bindings?.size ?: 0
                retaildemoTopBar(
                    title = ct.label,
                    subtitle = "碰贴直达 · 已绑 $cellBound 件",
                    onBack = { viewModel.selectLevelOnly(ct.shelfId, ct.levelId) },
                    actions = {
                        TextButton(onClick = {
                            cellSlot?.let { slot ->
                                viewModel.enqueueLabel(
                                    name = slot.name,
                                    tagId = slot.tagId,
                                    parentPath = "${ct.shelfName} · ${ct.levelName}",
                                )
                            }
                        }) {
                            Text("出签", fontSize = Design.FontMd.sp, color = Primary)
                        }
                    },
                )
            } else {
                // ★ 07 父屏顶栏（Ardot 3:14 实测）：
                //   y=65 `134×30 rx=15` 区域胶囊「一楼 · 五金区 ▾」（点开分区选择器）
                //   y=64 `50×32 rx=10 #FFF3E8`「写贴」—— **只有这一个按钮**。
                //   ✗ 旧实现的「货架理货」大标题 + 副标题「三级货架·扫到即绑·断网可用」
                //     +「入库出签」第二个按钮，全部来自 **09-11 已废弃的 shelf.html 帧稿**；
                //     `design/WORKFLOW.md` 第 70 行定死「画布为设计唯一事实」，
                //     第 87 行「与现役真源冲突的旧结构 → 砸掉重建」。故此处整体替换。
                ShelfAreaTopBar(
                    zoneName = currentZoneName,
                    onOpenZonePicker = { showZonePicker = true },
                    onWriteTag = onOpenWriteTag,
                )
            }
        },
        // ★ 07（货架立面，父屏）的底部**不该有动作 dock**。
        //   设计真值（Ardot 3:14，SVG 实测 y>600 的容器）：y=596 本架概览卡 → y=675 居中灰字提示
        //   → y=761 `348×62 rx=31 #FFF` 悬浮 tab 栏。**没有任何第二条底栏**。
        //   原先这里的「绑定至 X / 扫码枪连扫，不用退出 / 开始连扫」是把 08（子屏）的
        //   y=188 浅橙「开始连扫」条错位搬到了父屏底部撑成 dock：
        //   它盖住格卡、又被老王浮球 + 悬浮 tab 栏叠压（2026-09-18 老板实拍）。
        //   「开始连扫」的正确位置 = 08 子屏顶部状态条下方（见 SheetCellSection）。
        bottomBar = {},
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BgGray),
        ) {
            // 08 子屏（Ardot 3:16）顶栏之下**没有**找货搜索框：
            // 子屏的正事是「连扫入此格」，此时搜商品会与"扫进来"抢同一块注意力，
            // 也会把 y=120 扫货条挤到屏幕外。要搜商品请先返回 07 父屏。
            if (!inCell) {
                FindBar(
                    keyword = keyword,
                    onKeywordChange = viewModel::onFindKeywordChange,
                    onScan = {
                        ScanFeedback.click(context)
                        findScanLauncher.launch(ScannerActivity.intent(context, continuous = false))
                    },
                    onClear = viewModel::clearFind,
                    // 手输/粘贴位置码（SX:xxxx）回车即直达 —— 没贴 NFC、手边也没巴枪时的兜底；
                    // 走的仍是"位置码优先"那条路（findProductByCode）。
                    onSubmit = { viewModel.findProductByCode(keyword) },
                )

                if (picked != null || orphanLocations.isNotEmpty()) {
                    FindResultPanel(
                        product = picked,
                        locations = pickedLocations,
                        orphanLocations = orphanLocations,
                        onBindHere = { picked?.let(viewModel::bindProductToTarget) },
                        onJump = viewModel::jumpTo,
                        onOpenProduct = onOpenProduct,
                        onClear = viewModel::clearFind,
                    )
                } else if (results.isNotEmpty()) {
                    FindSuggestions(results = results, onPick = viewModel::selectProduct)
                }
            }

            // ---- 07 父形态专属：搜索框 → 货架 chips → 碰一碰提示条 ----
            // ★ 顺序严格照 Ardot 3:14：搜索框 y=116 → chips y=176 → NFC 条 y=220。
            //   旧实现把「分区胶囊 + 货架横条」塞在搜索框**上方**（旧 ShelfTopologyHeader，已删），
            //   与画布顺序相反 —— 已按画布改正。
            // 08 子屏（Ardot 3:16）顶栏之下直接就是「连扫中」状态条，没有这三样。
            if (!inCell) {
                Spacer(Modifier.height(Design.SpaceSm.dp))

                ShelfChipsRow(
                    shelves = currentShelves,
                    selectedShelfId = selectedShelf?.id,
                    stripState = stripState,
                    onSelectShelf = { id ->
                        ScanFeedback.click(context)
                        viewModel.selectShelf(id)
                    },
                    onLongPressShelf = { shelf -> shelfAction = shelf },
                    onAddClick = { showAddMenu = true },
                )

                if (selectedShelf != null) {
                    Spacer(Modifier.height(Design.SpaceMd.dp))
                    NfcHintBar()
                }
            } else {
                // 08 子屏顶部扫货条（Ardot 3:16 y=120 `390×56 rx=14 #FFF3E8`）：
                // 左橙点 + 「连扫中，扫一个绑一个 / 不用退出，扫完点完成」，右侧大号计数 +「本次已绑」。
                // 它**不在滚动区**（子屏正文往下滚，这条常驻），与 y=188 的「开始连扫」同为固定头。
                BindingScanHeader(count = sessionCount)
            }

            if (busy) {
                Box(
                    Modifier.fillMaxWidth().padding(Design.SpaceMd.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Primary, modifier = Modifier.size(22.dp))
                }
            }

            if (tree.isEmpty()) {
                EmptyShelfState(
                    onCreate = {
                        nameDialog = NameDialog(NameKind.SHELF, zoneId = currentZoneId)
                        nameInput = ""
                    },
                    onCreateZone = {
                        nameDialog = NameDialog(NameKind.ZONE, zoneId = currentZoneId)
                        nameInput = ""
                    },
                )
            } else if (currentShelves.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Design.SpaceXl.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "分区「$currentZoneName」下还没有货架",
                        fontSize = Design.FontMd.sp,
                        color = TextWeak,
                    )
                    Spacer(Modifier.height(Design.SpaceLg.dp))
                    GhostButton("＋ 在这里新建货架", Modifier.fillMaxWidth(0.62f)) {
                        nameDialog = NameDialog(NameKind.SHELF, zoneId = currentZoneId)
                        nameInput = ""
                    }
                }
            } else if (inCell) {
                // ================= 08 子形态：格详情 + 连扫（Ardot 3:16） =================
                val t = cellTarget!!
                val level = selectedShelf?.levels?.firstOrNull { it.id == t.levelId }
                val slot = level?.slots?.firstOrNull { it.id == t.slotId }
                if (level != null && slot != null) {
                    CellDetailSection(
                        slot = slot,
                        justBoundIds = justBoundIds,
                        snapshots = snapshots,
                        count = sessionCount,
                        onStartScan = {
                            viewModel.startBindingSession()
                            bindScanLauncher.launch(ScannerActivity.intent(context, continuous = true))
                        },
                        onFinish = viewModel::finishBindingSession,
                        onOpenProduct = { binding ->
                            scope.launch {
                                val code = viewModel.navCodeOf(binding)
                                if (code != null) {
                                    ScanFeedback.click(context)
                                    onOpenProduct(code)
                                } else {
                                    toast(context, "「${binding.name}」没有可用的条码，进不了详情")
                                }
                            }
                        },
                        onLongPressBinding = { binding ->
                            markNote = ""
                            markLocation = t.label
                            markTarget = binding
                        },
                        onAddProduct = { viewModel.selectLevelOnly(t.shelfId, t.levelId) },
                    )
                }
            } else {
                selectedShelf?.let { shelf ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = levelListState,
                        contentPadding = PaddingValues(
                            start = Design.SpaceLg.dp,
                            end = Design.SpaceLg.dp,
                            top = Design.SpaceSm.dp,
                            // 07 底部只有悬浮 tab 栏（348×62 + 12dp 边距，MainActivity 顶层 Overlay），
                            // 正文必须留出避让，否则最后一行会被压住。沿项目既有 90dp 公约。
                            bottom = 90.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Design.SpaceMd.dp),
                    ) {
                        items(shelf.levels, key = { it.id }) { level ->
                            LevelCard(
                                level = level,
                                target = target,
                                snapshots = snapshots,
                                photoLoader = viewModel.photoLoader,
                                onSelectLevelOnly = {
                                    ScanFeedback.click(context)
                                    viewModel.selectLevelOnly(shelf.id, level.id)
                                },
                                onSelectSlot = { slotId ->
                                    ScanFeedback.click(context)
                                    viewModel.selectLevel(shelf.id, level.id, slotId)
                                },
                                onNewSlot = {
                                    nameDialog = NameDialog(
                                        kind = NameKind.SLOT,
                                        shelfId = shelf.id,
                                        parentId = level.id,
                                        parentName = level.name,
                                    )
                                    nameInput = ""
                                },
                                onLongPressLevel = { levelAction = LevelAction(shelf.id, level) },
                                onLongPressSlot = { slot ->
                                    slotAction = SlotAction(
                                        levelId = level.id,
                                        slotId = slot.id,
                                        slotName = slot.name,
                                        tagId = slot.tagId,
                                        parentPath = "${shelf.name} · ${level.name}",
                                        note = slot.note,
                                    )
                                },
                                // 商品卡：点=进商品详情（老板 2026-09-13 指示），长按=解绑/标记
                                onOpenProduct = { binding ->
                                    scope.launch {
                                        val code = viewModel.navCodeOf(binding)
                                        if (code != null) {
                                            ScanFeedback.click(context)
                                            onOpenProduct(code)
                                        } else {
                                            toast(context, "「${binding.name}」没有可用的条码，进不了详情")
                                        }
                                    }
                                },
                                onLongPressBinding = { binding ->
                                    markNote = ""
                                    markLocation = "${shelf.name} · ${level.name}"
                                    markTarget = binding
                                },
                            )
                        }

                        if (shelf.levels.isEmpty()) {
                            item {
                                Text(
                                    "「${shelf.name}」还没有层。先按店里的叫法建一层（如「1层」「上层」），再往里加格。",
                                    fontSize = Design.FontSm.sp,
                                    lineHeight = (Design.FontSm + 4).sp,
                                    color = TextWeak,
                                    modifier = Modifier.padding(vertical = Design.SpaceSm.dp),
                                )
                            }
                        } else {
                            // 07 稿 3:1130 本架概览卡：统计层格数量、满格率及在架件数
                            val totalSlots = shelf.levels.sumOf { it.slots.size }
                            // ★ 2026-09-18 纠错：此处原为
                            //   `levels.sumOf { l -> l.slots.sumOf { it.bindings.size } }`
                            //   —— **漏算了 `directBindings`**（只绑到层、未细究到格的商品）。
                            //   后果：同一货架**层头显示 6 件、本架概览卡显示 4 件**，自相矛盾。
                            //   正解：直接复用 `LevelUi.bindingCount`
                            //   （= `directBindings.size + slots.sumOf { it.bindings.size }`），
                            //   与层头 `"${level.slotCount} 格 · ${level.bindingCount} 件"` **同源**，
                            //   从结构上杜绝两处口径漂移。
                            val totalBindings = shelf.levels.sumOf { it.bindingCount }
                            // 「空位」= **已存在的格里没绑货的**（虚线空格卡）。
                            // ⚠️ 不包含末位的「＋ 新建格」占位卡 —— 那还不是一个格。
                            // 设计稿自校验：1层卡3 是空格(1) + 2层卡3 是新建格(0) = 1
                            //              → 概览卡写「空位 1 个」 ✓（口径正确，勿改成 count 整层）
                            val emptySlots = shelf.levels.sumOf { l -> l.slots.count { it.bindings.isEmpty() } }
                            item {
                                Card(
                                    shape = RoundedCornerShape(Design.CardRadius.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sxElevation(SxElevation.L2),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 13.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(
                                                text = "${shelf.name} · ${shelf.levels.size} 层 $totalSlots 格",
                                                fontSize = 14.sp,
                                                lineHeight = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextMain,
                                            )
                                            Text(
                                                text = "在架 $totalBindings 件 · 空位 $emptySlots 个",
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp,
                                                color = TextWeak,
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(17.dp),
                                            color = SurfaceSubtle,
                                            modifier = Modifier
                                                .height(34.dp)
                                                .clickable { shelfAction = shelf },
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(horizontal = 14.dp),
                                            ) {
                                                Text(
                                                    text = "整理货架",
                                                    fontSize = 13.sp,
                                                    lineHeight = 17.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = TextSecondary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
                                GhostButton("＋ 新建层", Modifier.weight(1f)) {
                                    nameDialog = NameDialog(
                                        kind = NameKind.LEVEL,
                                        shelfId = shelf.id,
                                        parentId = shelf.id,
                                        parentName = shelf.name,
                                    )
                                    nameInput = ""
                                }
                                GhostButton("出整架标签", Modifier.weight(1f)) {
                                    viewModel.enqueueShelfLabels(shelf)
                                }
                            }
                        }

                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
                                GhostButton("按货位盘点", Modifier.weight(1f)) { onOpenStocktake() }
                                GhostButton("调账", Modifier.weight(1f)) { onOpenAdjust() }
                            }
                        }

                        // 07 稿 y=675 居中灰字（#666F85，14sp）：把「下一步做什么」写在卡片流末尾，
                        // 替代原先那条悬在底部的假 dock。它是内容，跟着滚，不是常驻操作条。
                        item {
                            Text(
                                "扫商品码 → 选一个空格 → 自动绑定，全程不用联网",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Design.SpaceSm.dp),
                                fontSize = Design.FontSm.sp,
                                lineHeight = 20.sp,
                                color = TextWeak,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------- 顶部拓扑：分区选择器弹窗 ----------------
    if (showZonePicker) {
        val zoneOptions = flattenZoneOptions(zoneTree)
        SanxAlertDialog(
            onDismissRequest = { showZonePicker = false },
            title = { Text("选择货架所在区域", fontWeight = FontWeight.Bold, fontSize = Design.FontLg.sp) },
            text = {
                Column {
                    ActionRow(if (currentZoneId == null) "✓ 全部货架（未过滤）" else "全部货架（未过滤）") {
                        viewModel.openZone(null)
                        showZonePicker = false
                    }
                    zoneOptions.forEach { (zid, label) ->
                        val isCur = zid == currentZoneId
                        ActionRow(if (isCur) "✓ $label" else label) {
                            viewModel.openZone(zid)
                            showZonePicker = false
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = Design.SpaceSm.dp), color = Design.CardStroke)
                    ActionRow("＋ 新建楼层/分区") {
                        nameDialog = NameDialog(NameKind.ZONE, zoneId = currentZoneId)
                        nameInput = ""
                        showZonePicker = false
                    }
                }
            },
            dismissText = "关闭",
        )
    }

    // ---------------- 顶部 ＋ 快捷新建菜单 ----------------
    if (showAddMenu) {
        SanxAlertDialog(
            onDismissRequest = { showAddMenu = false },
            title = { Text("新建...", fontWeight = FontWeight.Bold, fontSize = Design.FontLg.sp) },
            text = {
                Column {
                    ActionRow("＋ 新建货架" + if (currentZoneId != null) "（归入「$currentZoneName」）" else "") {
                        nameDialog = NameDialog(NameKind.SHELF, zoneId = currentZoneId)
                        nameInput = ""
                        showAddMenu = false
                    }
                    ActionRow("＋ 新建楼层/分区" + if (currentZoneId != null) "（建在「$currentZoneName」下）" else "") {
                        nameDialog = NameDialog(NameKind.ZONE, zoneId = currentZoneId)
                        nameInput = ""
                        showAddMenu = false
                    }
                    ActionRow("管理区域/楼层...") {
                        showAddMenu = false
                        showZonePicker = true
                    }
                }
            },
            dismissText = "取消",
        )
    }

    // ---------------- I23 分区菜单 / 删分区确认 / 货架换分区 ----------------
    zoneAction?.let { zone ->
        SanxAlertDialog(
            onDismissRequest = { zoneAction = null },
            title = { Text("「${zone.name}」", fontWeight = FontWeight.Bold, fontSize = Design.FontLg.sp) },
            text = {
                Column {
                    if (!zone.note.isNullOrBlank()) {
                        Text(
                            "备注：${zone.note}",
                            fontSize = Design.FontXs.sp,
                            color = TextWeak,
                            modifier = Modifier.padding(bottom = Design.SpaceXs.dp),
                        )
                    }
                    ActionRow("改名") {
                        nameDialog = NameDialog(NameKind.ZONE, editId = zone.id)
                        nameInput = zone.name
                        zoneAction = null
                    }
                    ActionRow(if (zone.note.isNullOrBlank()) "写备注" else "改备注") {
                        noteTarget = NoteTarget(NameKind.ZONE, zone.id, zone.name, zone.note)
                        noteInput = zone.note.orEmpty()
                        zoneAction = null
                    }
                    ActionRow("在里面建分区") {
                        nameDialog = NameDialog(NameKind.ZONE, zoneId = zone.id)
                        nameInput = ""
                        zoneAction = null
                    }
                    ActionRow("在里面建货架") {
                        nameDialog = NameDialog(NameKind.SHELF, zoneId = zone.id)
                        nameInput = ""
                        zoneAction = null
                    }
                    ActionRow("上移一位") {
                        viewModel.moveZone(zone.id, -1)
                        zoneAction = null
                    }
                    ActionRow("下移一位") {
                        viewModel.moveZone(zone.id, 1)
                        zoneAction = null
                    }
                    ActionRow("删除这个分区（${zone.zoneCount} 个子分区 · ${zone.shelfCount} 个货架）", danger = true) {
                        scope.launch { pendingZoneImpact = viewModel.zoneImpact(zone.id) }
                        pendingDeleteZone = zone
                        zoneAction = null
                    }
                }
            },
            dismissText = "关闭",
        )
    }

    // 删分区是级联删除（子分区 + 其下货架一起没），必须二次确认并报清影响面
    pendingDeleteZone?.let { zone ->
        val impact = pendingZoneImpact
        SanxAlertDialog(
            onDismissRequest = { pendingDeleteZone = null; pendingZoneImpact = null },
            title = { Text("删除「${zone.name}」？", fontWeight = FontWeight.Bold, fontSize = Design.FontLg.sp) },
            text = {
                Text(
                    if (impact == null) "正在统计影响范围…"
                    else "这个分区里有 ${impact.first} 个子分区、${impact.second} 个货架，" +
                        "会连同下面的层、格和位置绑定一起删掉。商品本身不受影响。删了找不回来。",
                    fontSize = Design.FontSm.sp,
                    color = TextSecondary,
                )
            },
            confirmText = "确认删除",
            confirmDanger = true,
            onConfirm = {
                viewModel.deleteZone(zone.id)
                pendingDeleteZone = null
                pendingZoneImpact = null
            },
            dismissText = "取消",
        )
    }

    // 货架换分区（文件夹式归类）
    moveTarget?.let { shelf ->
        val zoneOptions = flattenZoneOptions(zoneTree)
        SanxAlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("把「${shelf.name}」放到", fontWeight = FontWeight.Bold, fontSize = Design.FontLg.sp) },
            text = {
                Column {
                    ActionRow("未分类（顶层）") {
                        viewModel.moveShelfToZone(shelf.id, null)
                        moveTarget = null
                    }
                    zoneOptions.forEach { (zid, label) ->
                        if (zid != shelf.zoneId) {
                            ActionRow(label) {
                                viewModel.moveShelfToZone(shelf.id, zid)
                                moveTarget = null
                            }
                        }
                    }
                }
            },
            dismissText = "取消",
        )
    }

    // ---------------- 新建 / 改名 ----------------
    dupState?.let { dup ->
        DupDialog(
            parentName = dup.dialog.parentName,
            kindLabel = when (dup.dialog.kind) {
                NameKind.ZONE -> "分区"
                NameKind.SHELF -> "货架"
                NameKind.LEVEL -> "层"
                NameKind.SLOT -> "格"
            },
            pendingName = dup.pendingName,
            candidates = dup.candidates,
            onUseExisting = { cand ->
                val d = dup.dialog
                when (d.kind) {
                    NameKind.ZONE -> viewModel.openZone(cand.id)
                    NameKind.SHELF -> viewModel.selectShelf(cand.id)
                    NameKind.LEVEL -> viewModel.selectLevelOnly(d.shelfId, cand.id)
                    NameKind.SLOT -> viewModel.selectLevel(d.shelfId, d.parentId, cand.id)
                }
                dupState = null
                nameDialog = null
                nameInput = ""
            },
            onStillCreate = {
                createOrRename(dup.dialog, dup.pendingName, viewModel)
                dupState = null
                nameDialog = null
                nameInput = ""
            },
            onDismiss = { dupState = null },
        )
    }

    nameDialog?.let { dialog ->
        NewNameDialog(
            dialog = dialog,
            value = nameInput,
            onValueChange = { nameInput = it },
            onDismiss = { nameDialog = null; nameInput = "" },
            onConfirm = {
                val clean = nameInput.trim()
                if (clean.isEmpty()) return@NewNameDialog
                if (dialog.editId != null) {
                    // 改名：只动 name，tagId 不变（贴不用重贴）
                    when (dialog.kind) {
                        NameKind.ZONE -> viewModel.renameZone(dialog.editId, clean)
                        NameKind.SHELF -> viewModel.renameShelf(dialog.editId, clean)
                        NameKind.LEVEL -> viewModel.renameLevel(dialog.editId, clean)
                        NameKind.SLOT -> viewModel.renameSlot(dialog.editId, clean)
                    }
                    nameDialog = null
                    nameInput = ""
                } else {
                    scope.launch {
                        val dups = when (dialog.kind) {
                            // 分区/货架暂不做跨区查重（文件夹语义允许同名分区；货架查重保留旧行为）
                            NameKind.ZONE -> emptyList()
                            NameKind.SHELF -> viewModel.checkShelfName(clean)
                            NameKind.LEVEL -> viewModel.checkLevelName(dialog.parentId, clean)
                            NameKind.SLOT -> viewModel.checkSlotName(dialog.parentId, clean)
                        }
                        if (dups.isEmpty()) {
                            createOrRename(dialog, clean, viewModel)
                            nameDialog = null
                            nameInput = ""
                        } else {
                            dupState = DupState(dialog, clean, dups)
                        }
                    }
                }
            },
        )
    }

    // ---------------- 顶层货架：改名 / 备注 / 排序 / 出签 / 删除 ----------------
    // 老板 2026-09-13 实测：「整个货架不能删，没看见我新建的测试吗」——
    // 之前货架条只能点选，改名/删除全藏在层、格的长按里，顶层压根没有入口。
    shelfAction?.let { shelf ->
        SanxAlertDialog(
            onDismissRequest = { shelfAction = null },
            title = { Text("「${shelf.name}」", fontWeight = FontWeight.Bold, fontSize = Design.FontLg.sp) },
            text = {
                Column {
                    if (!shelf.note.isNullOrBlank()) {
                        Text(
                            "备注：${shelf.note}",
                            fontSize = Design.FontXs.sp,
                            color = TextWeak,
                            modifier = Modifier.padding(bottom = Design.SpaceXs.dp),
                        )
                    }
                    ActionRow("改名") {
                        nameDialog = NameDialog(kind = NameKind.SHELF, editId = shelf.id)
                        nameInput = shelf.name
                        shelfAction = null
                    }
                    ActionRow(if (shelf.note.isNullOrBlank()) "写备注" else "改备注") {
                        noteTarget = NoteTarget(NameKind.SHELF, shelf.id, shelf.name, shelf.note)
                        noteInput = shelf.note.orEmpty()
                        shelfAction = null
                    }
                    ActionRow("左移一位") {
                        viewModel.moveShelf(shelf.id, -1)
                        shelfAction = null
                    }
                    ActionRow("右移一位") {
                        viewModel.moveShelf(shelf.id, +1)
                        shelfAction = null
                    }
                    ActionRow("出整架标签") {
                        viewModel.enqueueShelfLabels(shelf)
                        shelfAction = null
                    }
                    ActionRow("移入其他分区…") {
                        moveTarget = shelf
                        shelfAction = null
                    }
                    ActionRow("删除整个货架（${shelf.levels.size} 层 · ${shelf.bindingCount} 件）", danger = true) {
                        pendingDeleteShelf = shelf
                        shelfAction = null
                    }
                }
            },
            dismissText = "关闭",
        )
    }

    // 删整架是级联删除（层/格/绑定一起没），必须二次确认
    pendingDeleteShelf?.let { shelf ->
        SanxAlertDialog(
            onDismissRequest = { pendingDeleteShelf = null },
            title = { Text("删除「${shelf.name}」？", fontWeight = FontWeight.Bold, fontSize = Design.FontLg.sp) },
            text = {
                Text(
                    "这架下面的 ${shelf.levels.size} 层、各层里的格，以及 ${shelf.bindingCount} 条位置绑定会一起删掉。" +
                        "商品本身不受影响（只是不再标它放在哪）。删了找不回来。",
                    fontSize = Design.FontSm.sp,
                    color = TextSecondary,
                )
            },
            confirmText = "确认删除",
            confirmDanger = true,
            onConfirm = {
                viewModel.deleteShelf(shelf.id)
                pendingDeleteShelf = null
            },
            dismissText = "取消",
        )
    }

    // ---------------- 备注（三级共用） ----------------
    noteTarget?.let { nt ->
        SanxAlertDialog(
            onDismissRequest = { noteTarget = null },
            title = { Text("备注 · ${nt.title}", fontSize = Design.FontLg.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
                    Text(
                        "写点提示给自己或同事看，比如「放水管配件」「靠墙那头」。只作提示，不参与任何功能。",
                        fontSize = Design.FontXs.sp,
                        color = TextWeak,
                    )
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text("备注（留空 = 清除）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmText = "保存",
            onConfirm = {
                when (nt.kind) {
                    NameKind.ZONE -> viewModel.setZoneNote(nt.id, noteInput)
                    NameKind.SHELF -> viewModel.setShelfNote(nt.id, noteInput)
                    NameKind.LEVEL -> viewModel.setLevelNote(nt.id, noteInput)
                    NameKind.SLOT -> viewModel.setSlotNote(nt.id, noteInput)
                }
                noteTarget = null
            },
            dismissText = "取消",
        )
    }

    // ---------------- 长按：层操作 ----------------
    levelAction?.let { action ->
        SanxAlertDialog(
            onDismissRequest = { levelAction = null },
            title = { Text("「${action.level.name}」", fontWeight = FontWeight.Bold, fontSize = Design.FontLg.sp) },
            text = {
                Column {
                    ActionRow("改名") {
                        nameDialog = NameDialog(
                            kind = NameKind.LEVEL,
                            shelfId = action.shelfId,
                            parentId = action.shelfId,
                            editId = action.level.id,
                        )
                        nameInput = action.level.name
                        levelAction = null
                    }
                    ActionRow(if (action.level.note.isNullOrBlank()) "写备注" else "改备注") {
                        noteTarget = NoteTarget(NameKind.LEVEL, action.level.id, action.level.name, action.level.note)
                        noteInput = action.level.note.orEmpty()
                        levelAction = null
                    }
                    ActionRow("上移") {
                        viewModel.moveLevel(action.level.id, action.shelfId, -1)
                        levelAction = null
                    }
                    ActionRow("下移") {
                        viewModel.moveLevel(action.level.id, action.shelfId, +1)
                        levelAction = null
                    }
                    ActionRow("只绑到这一层（不加格）") {
                        viewModel.selectLevelOnly(action.shelfId, action.level.id)
                        levelAction = null
                    }
                    ActionRow("出这一层标签") {
                        val shelfName = tree.firstOrNull { it.id == action.shelfId }?.name.orEmpty()
                        viewModel.enqueueLabel(action.level.name, action.level.tagId, shelfName)
                        levelAction = null
                    }
                    ActionRow("删除这一层", danger = true) {
                        viewModel.deleteLevel(action.level.id)
                        levelAction = null
                    }
                }
            },
            dismissText = "关闭",
        )
    }

    // ---------------- 长按：格操作 ----------------
    slotAction?.let { action ->
        val slotBindings = selectedShelf?.levels
            ?.firstOrNull { it.id == action.levelId }
            ?.slots?.firstOrNull { it.id == action.slotId }
            ?.bindings.orEmpty()

        SanxAlertDialog(
            onDismissRequest = { slotAction = null },
            title = { Text("「${action.slotName}」储位管理", fontWeight = FontWeight.Bold, fontSize = Design.FontLg.sp) },
            text = {
                Column {
                    if (slotBindings.isNotEmpty()) {
                        Text(
                            "存放商品（${slotBindings.size} 件）：",
                            fontSize = Design.FontXs.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWeak,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        slotBindings.forEach { b ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        b.name,
                                        fontSize = Design.FontSm.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextMain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val snap = snapshots[b.productId]
                                    val price = snap?.sellPrice
                                    val spec = snap?.spec?.trim().orEmpty()
                                    val sub = buildString {
                                        if (price != null && price > 0) append("¥").append(moneyText(price))
                                        if (spec.isNotEmpty()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(spec)
                                        }
                                    }
                                    if (sub.isNotEmpty()) {
                                        Text(sub, fontSize = Design.FontXs.sp, color = TextWeak)
                                    }
                                }
                                TextButton(onClick = {
                                    slotAction = null
                                    scope.launch {
                                        val code = viewModel.navCodeOf(b)
                                        if (code != null) onOpenProduct(code)
                                    }
                                }) {
                                    Text("看详情", fontSize = Design.FontXs.sp, color = Primary)
                                }
                                TextButton(onClick = {
                                    slotAction = null
                                    viewModel.unbind(b.id)
                                }) {
                                    Text("解绑", fontSize = Design.FontXs.sp, color = Danger)
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = Design.SpaceSm.dp),
                            color = Design.CardStroke,
                        )
                    }

                    ActionRow("设为绑定目标（扫码枪连扫入此格）") {
                        viewModel.selectLevel(selectedShelf?.id ?: 0L, action.levelId, action.slotId)
                        slotAction = null
                    }
                    ActionRow("改名") {
                        nameDialog = NameDialog(
                            kind = NameKind.SLOT,
                            parentId = action.levelId,
                            editId = action.slotId,
                        )
                        nameInput = action.slotName
                        slotAction = null
                    }
                    ActionRow(if (action.note.isNullOrBlank()) "写备注" else "改备注") {
                        noteTarget = NoteTarget(NameKind.SLOT, action.slotId, action.slotName, action.note)
                        noteInput = action.note.orEmpty()
                        slotAction = null
                    }
                    ActionRow("出这一格标签") {
                        viewModel.enqueueLabel(action.slotName, action.tagId, action.parentPath)
                        slotAction = null
                    }
                    ActionRow("删除这一格", danger = true) {
                        viewModel.deleteSlot(action.slotId)
                        slotAction = null
                    }
                }
            },
            dismissText = "关闭",
        )
    }

    // ---------------- 未命中：标记待补全（铁律 12 的兜底出口） ----------------
    if (unmatchedCodes.isNotEmpty()) {
        SanxAlertDialog(
            onDismissRequest = { unmatchedCodes = emptyList() },
            title = { Text("这些码库里查不到", fontSize = Design.FontLg.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
                    Text(unmatchedCodes.joinToString("、"), fontSize = Design.FontSm.sp, color = TextSecondary)
                    Text(
                        "可以标进「数据标记队列」，交给管家婆那边慢慢补；现在先跳过也不影响继续扫。",
                        fontSize = Design.FontXs.sp,
                        color = TextWeak,
                    )
                }
            },
            confirmText = "标记待补全",
            onConfirm = {
                viewModel.markUnmatched(unmatchedCodes)
                unmatchedCodes = emptyList()
            },
            dismissText = "跳过",
        )
    }

    // ---------------- 商品级问题标记 ----------------
    markTarget?.let { binding ->
        SanxAlertDialog(
            onDismissRequest = { markTarget = null },
            title = { Text("标记问题 · ${binding.name}", fontSize = Design.FontLg.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
                    Text("记进队列交给管家婆后台处理；APP 不改库存、不改价。", fontSize = Design.FontXs.sp, color = TextWeak)
                    OutlinedTextField(
                        value = markNote,
                        onValueChange = { markNote = it },
                        label = { Text("说明（选填）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(onClick = { viewModel.toggleMisplaced(binding); markTarget = null }) {
                        Text(
                            if (binding.misplaced) "取消「不在这个位置」标记" else "标记为「不在这个位置」",
                            color = Warning,
                            fontSize = Design.FontSm.sp,
                        )
                    }
                    TextButton(onClick = { viewModel.unbind(binding.id); markTarget = null }) {
                        Text(
                            "解绑这一处（该商品别的位置不受影响）",
                            color = Danger,
                            fontSize = Design.FontSm.sp,
                        )
                    }
                }
            },
            confirmText = "登记",
            onConfirm = {
                // 备注自动带「架·层」位置串（名字，不是 id），导出给人看（I20 Step6）
                val note = listOfNotNull(
                    markLocation.takeIf { it.isNotBlank() }?.let { "位置：$it" },
                    markNote.takeIf { it.isNotBlank() },
                ).joinToString("；")
                viewModel.markDiscrepancy(
                    name = binding.name,
                    sku = "",
                    barcode = binding.barcode.orEmpty(),
                    markType = DataMarkEntity.TYPE_STOCK_MISMATCH,
                    note = note,
                )
                markTarget = null
            },
            dismissText = "取消",
        )
    }
}

// =====================================================================================
// 辅助
// =====================================================================================

private fun createOrRename(dialog: NameDialog, cleanName: String, viewModel: ShelfViewModel) {
    when (dialog.kind) {
        NameKind.ZONE -> viewModel.createZone(cleanName, parentId = dialog.zoneId)
        NameKind.SHELF -> viewModel.createShelf(cleanName, zoneId = dialog.zoneId)
        NameKind.LEVEL -> viewModel.createLevel(dialog.parentId, cleanName)
        NameKind.SLOT -> viewModel.createSlot(dialog.parentId, cleanName)
    }
}

private fun toast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}

/** 分区平铺成「缩进 + 名字」选项（换分区弹窗用；文件夹路径一眼看清层级）。 */
private fun flattenZoneOptions(zones: List<ZoneUi>, depth: Int = 0): List<Pair<Long, String>> =
    zones.flatMap { z ->
        val indent = "　".repeat(depth)
        listOf(z.id to "$indent${if (depth > 0) "└ " else ""}${z.name}") + flattenZoneOptions(z.zones, depth + 1)
    }

// =====================================================================================
// 子组件
// =====================================================================================

@Composable
private fun FindBar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onScan: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Design.SpaceLg.dp, vertical = Design.SpaceSm.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("搜商品 / 扫码找货位", fontSize = Design.FontMd.sp, color = TextWeak) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextWeak) },
            trailingIcon = {
                if (keyword.isNotEmpty()) {
                    Text(
                        "✕",
                        color = TextWeak,
                        modifier = Modifier.clickable { onClear() }.padding(Design.SpaceSm.dp),
                    )
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(Design.InputRadius.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                onSubmit()
            }),
        )
        Spacer(Modifier.width(Design.SpaceSm.dp))
        Surface(
            color = Primary,
            shape = RoundedCornerShape(Design.ButtonRadius.dp),
            modifier = Modifier.size(48.dp).clickable { onScan() },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = "扫码找货位",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun FindSuggestions(results: List<ProductEntity>, onPick: (ProductEntity) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Design.SpaceLg.dp, vertical = Design.SpaceXs.dp).sxElevation(SxElevation.L2),
        colors = CardDefaults.cardColors(containerColor = Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(Design.CardRadius.dp),
    ) {
        Column(Modifier.padding(vertical = Design.SpaceXs.dp)) {
            results.take(6).forEach { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(p) }
                        .padding(horizontal = Design.SpaceMd.dp, vertical = Design.SpaceSm.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = Design.FontMd.sp, color = TextMain, fontWeight = FontWeight.Medium)
                        Text(
                            listOfNotNull(p.spec, p.barcode).joinToString(" · ").ifBlank { "—" },
                            fontSize = Design.FontXs.sp,
                            color = TextWeak,
                        )
                    }
                    Text("在哪", fontSize = Design.FontSm.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** 找货结果：**一品多货位 → 位置全部列出**。 */
@Composable
private fun FindResultPanel(
    product: ProductEntity?,
    locations: List<ShelfLocationRef>,
    orphanLocations: List<ShelfLocationRef>,
    onBindHere: () -> Unit,
    onJump: (ShelfLocationRef) -> Unit,
    onOpenProduct: (String) -> Unit,
    onClear: () -> Unit,
) {
    val shown = if (product != null) locations else orphanLocations
    Card(
        // 找货结果浮起卡：压在货架立面上，取 L3 浮起卡（一屏唯一）
        modifier = Modifier.fillMaxWidth().padding(horizontal = Design.SpaceLg.dp, vertical = Design.SpaceXs.dp).sxElevation(SxElevation.L3),
        colors = CardDefaults.cardColors(containerColor = Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(Design.CardRadius.dp),
    ) {
        Column(Modifier.padding(Design.SpaceMd.dp), verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        product?.name ?: "库外条码（没有商品档案）",
                        fontSize = Design.FontMdPlus.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                    )
                    Text(
                        when {
                            product != null && locations.size > 1 -> "它在 ${locations.size} 个位置（一品多货位）"
                            product != null && locations.size == 1 -> "它在 1 个位置"
                            product != null -> "还没绑定任何位置"
                            else -> "但有 ${orphanLocations.size} 处绑定记录"
                        },
                        fontSize = Design.FontXs.sp,
                        color = if (locations.size > 1) Primary else TextWeak,
                    )
                }
                TextButton(onClick = onClear) { Text("收起", fontSize = Design.FontSm.sp, color = TextWeak) }
            }

            shown.forEach { ref ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceSubtle, RoundedCornerShape(Design.InputRadius.dp))
                        .clickable { onJump(ref) }
                        .padding(horizontal = Design.SpaceMd.dp, vertical = Design.SpaceSm.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(ref.display, fontSize = Design.FontMd.sp, color = TextMain, fontWeight = FontWeight.Medium)
                        if (ref.misplaced) {
                            Text("已标记错位", fontSize = Design.FontXs.sp, color = Warning)
                        }
                    }
                    Text("去这里", fontSize = Design.FontSm.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
                Button(
                    onClick = onBindHere,
                    enabled = product != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(Design.ButtonRadius.dp),
                ) { Text("绑定到当前位置", fontSize = Design.FontSm.sp) }
                if (product != null) {
                    GhostButton("看商品", Modifier.weight(1f)) {
                        onOpenProduct(product.barcode?.ifBlank { product.sku.orEmpty() } ?: product.sku.orEmpty())
                    }
                }
            }
        }
    }
}

/**
 * 07 父屏顶栏（Ardot 3:14 y=64..96）。
 *
 * 设计实测：`y=65 134×30 rx=15` 区域胶囊「一楼 · 五金区 ▾」+ `y=64 50×32 rx=10 #FFF3E8`「写贴」。
 * **只有这一个按钮** —— 旧实现的「货架理货」标题/副标题与「入库出签」均来自已废弃帧稿，已删。
 *
 * 为什么自绘而不用 `retaildemoTopBar`：设计稿这里是**紧凑胶囊 + 单一浅橙小按钮**，
 * 不是「大标题 + 副标题 + 文字按钮」的通用顶栏版式；套用通用版式就是没复刻。
 */
@Composable
private fun ShelfAreaTopBar(
    zoneName: String,
    onOpenZonePicker: () -> Unit,
    onWriteTag: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Design.SpaceLg.dp, end = Design.SpaceLg.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 区域胶囊（y=65 134×30 rx=15）：点开分区/楼层选择器
        Row(
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(SurfaceSubtle)
                .clickable { onOpenZonePicker() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(zoneName, fontSize = Design.FontSm.sp, fontWeight = FontWeight.Bold, color = TextMain, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text("▾", fontSize = Design.FontXs.sp, color = TextWeak)
        }

        Spacer(Modifier.weight(1f))

        // 「写贴」浅橙小按钮（y=64 50×32 rx=10 #FFF3E8）—— 设计稿里父屏唯一的动作入口
        Box(
            modifier = Modifier
                .height(32.dp)
                .widthIn(min = 50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PrimaryLight)
                .clickable { onWriteTag() }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("写贴", fontSize = Design.FontSm.sp, fontWeight = FontWeight.Bold, color = Primary)
        }
    }
}

/**
 * 07 父屏「货架 chips」行（Ardot 3:14 y=176 `50×32 rx=16`）。
 *
 * ★ 位置要点：设计稿里它在**搜索框下方**（y=176 vs 搜索框 y=116），
 * 而旧实现把分区胶囊和货架条塞进了搜索框**上方**同一行 —— 顺序反了。
 * 选中态 `#FFF3E8` + 橙字，未选 `#F2F4F7` + 深灰字；尾部 `69×32` 是「＋ 新建」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfChipsRow(
    shelves: List<ShelfUi>,
    selectedShelfId: Long?,
    stripState: LazyListState,
    onSelectShelf: (Long) -> Unit,
    onLongPressShelf: (ShelfUi) -> Unit,
    onAddClick: () -> Unit,
) {
    // 选中的货架必须自己滚进视野：碰贴直达的目标可能排在很后面，
    // 选中了却看不见 = 等于没跳过去。
    LaunchedEffect(selectedShelfId, shelves.size) {
        val idx = shelves.indexOfFirst { it.id == selectedShelfId }
        if (idx >= 0) stripState.animateScrollToItem(idx)
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Design.SpaceLg.dp),
        state = stripState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(shelves, key = { it.id }) { shelf ->
            val on = shelf.id == selectedShelfId
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .widthIn(min = 50.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) PrimaryLight else SurfaceSubtle)
                    .combinedClickable(
                        onClick = { onSelectShelf(shelf.id) },
                        onLongClick = { onLongPressShelf(shelf) },
                    )
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    shelf.name,
                    fontSize = Design.FontSm.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) Primary else TextSecondary,
                    maxLines = 1,
                )
            }
        }
        item {
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceSubtle)
                    .clickable { onAddClick() }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("＋ 新建", fontSize = Design.FontSm.sp, fontWeight = FontWeight.Medium, color = Primary)
            }
        }
    }
}

@Composable
private fun NfcHintBar() {
    // 美学公理 A2（单点信号律）：NFC 提示此前是青底青点，等于在品牌橙之外又开了一个
    // 高饱和色源，一屏两色互抢注意力。这里收敛为中性面 + 品牌色小信号点——
    // 青色从此只服务「信息态」（雷达/定位），不再出现在品牌操作面上。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Design.SpaceLg.dp, vertical = Design.SpaceXs.dp)
            .background(SurfaceSubtle, RoundedCornerShape(Design.CardRadius.dp))
            .padding(horizontal = Design.SpaceMd.dp, vertical = Design.SpaceSm.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(Primary, RoundedCornerShape(50)))
        Spacer(Modifier.width(Design.SpaceSm.dp))
        Text(
            "碰一碰货架牌，直达本架",
            fontSize = Design.FontSm.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptyShelfState(onCreate: () -> Unit, onCreateZone: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = Design.SpaceXl.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = TextDisabled, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(Design.SpaceMd.dp))
        Text("这里还是空的", fontSize = Design.FontLg.sp, fontWeight = FontWeight.Bold, color = TextMain)
        Spacer(Modifier.height(Design.SpaceXs.dp))
        Text(
            "货架、层、格的名字都由你定。\n楼层/分区可以像文件夹一样层层套，货架放进去归类。",
            fontSize = Design.FontSm.sp,
            color = TextWeak,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Design.SpaceXl.dp))
        Button(
            onClick = onCreate,
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
            shape = RoundedCornerShape(Design.ButtonRadius.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("新建第一个货架", fontSize = Design.FontLg.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(Design.SpaceSm.dp))
        GhostButton("先建一个分区/楼层", Modifier.fillMaxWidth(), onClick = onCreateZone)
    }
}

/**
 * 08 子屏扫货条（Ardot 3:16 y=120，`390×56 rx=14 #FFF3E8`）——常驻，不在滚动区。
 * 左侧橙点 + 主标「连扫中，扫一个绑一个」+ 副标「不用退出，扫完点完成」；
 * 右侧大号计数 +「本次已绑」。语义与 07 的假 dock 完全不同：它是**状态**不是入口。
 */
@Composable
private fun BindingScanHeader(count: Int) {
    // 设计 3:16 y=120 `390×56 rx=14`：**贴顶栏下沿、左右贴边**（x=0..390），
    // 不是"页面里的一张卡"。所以外层只留与设计等比的最小间距，不要再套一层 SpaceLg 外距。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgGray)
            .padding(horizontal = Design.SpaceLg.dp, vertical = Design.SpaceSm.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PrimaryLight, RoundedCornerShape(Design.InputRadius.dp))
                .padding(horizontal = Design.SpaceMd.dp, vertical = Design.SpaceSm.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(Primary, RoundedCornerShape(50)))
            Spacer(Modifier.width(Design.SpaceSm.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "连扫中，扫一个绑一个",
                    fontSize = Design.FontMd.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryDark,
                )
                Text(
                    "不用退出，扫完点完成",
                    fontSize = Design.FontXs.sp,
                    lineHeight = 15.sp,
                    color = PrimaryDark,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$count",
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Primary,
                )
                Text("本次已绑", fontSize = Design.FontXs.sp, lineHeight = 15.sp, color = PrimaryDark)
            }
        }
    }
}

/**
 * 08 子屏正文（Ardot 3:16）：
 * - y=188 `390×64` 浅橙实心「开始连扫」大按钮（图标 + 文案，未连扫时才是入口）
 * - y=264「本次绑定 · 刚刚 · N 件」分组标题 + 该组商品卡（右侧绿色「已绑」胶囊）
 * - y=503「本格原有 · N 件 · 下次扫码去重」+ 商品卡（右侧灰色「已在格中」）
 * - 底部固定橙渐变 CTA `358×52 rx=14` @y=768「完成（本次 N 件）」
 *
 * 底 CTA 必须是**固定底栏**（Scaffold bottomBar 语义，用 Box 叠在底部）——
 * 设计稿里它钉在 y=768 不随内容滚动；此前把状态条塞进 LazyColumn 当 item，
 * 一滚就跑掉，"扫完点完成"的出口会消失在屏幕外。
 */
@Composable
private fun CellDetailSection(
    slot: SlotUi,
    justBoundIds: Set<Long>,
    snapshots: Map<Long, ProductSnap>,
    count: Int,
    onStartScan: () -> Unit,
    onFinish: () -> Unit,
    onOpenProduct: (BindingUi) -> Unit,
    onLongPressBinding: (BindingUi) -> Unit,
    onAddProduct: () -> Unit,
) {
    // 分组：本次刚绑的 vs 本格原有的。设计稿靠 `justBound` 标记分两组。
    val fresh = slot.bindings.filter { it.id in justBoundIds }
    val existing = slot.bindings.filter { it.id !in justBoundIds }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Design.SpaceLg.dp,
                end = Design.SpaceLg.dp,
                // 设计 3:16：扫货条底 y=176 → 「开始连扫」顶 y=188，只隔 12 设计px（≈4dp）。
                // 原先用 SpaceMd(16dp) 把按钮推下去近百 px，与"紧接着状态条"的稿面不符。
                top = Design.SpaceXs.dp,
                // 给固定底 CTA 留位：52dp 按钮 + 12dp 距底 + 安全余量
                bottom = 88.dp,
            ),
            // ⚠️ 2026-09-18：曾误用 dpr=3 换算，以为卡间距比稿窄 2dp，一度改成 spacedBy(10.dp)。
            //    修正 dpr=2.75 后实测「卡顶→卡顶」步进 = 187dev = 68.0dp，
            //    **与设计稿 58(卡高)+10(间隙)=68dp 完全一致** → 原值本就合格，已回退。
            verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
        ) {
            item {
                // y=188 浅橙实心大按钮（#FF7C19 实心 + 白字，390×64 rx=14 贴边）
                // ★ 高度显式钉 64dp（Ardot 3:16 实测 378..504 png = 63~64dp）。
                //   ✗ 原用 contentPadding vertical=18dp 靠内容撑，实测只有 55dp，比稿矮 8dp。
                Button(
                    onClick = onStartScan,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(Design.InputRadius.dp),
                    contentPadding = PaddingValues(horizontal = Design.SpaceLg.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .ctaGradient(RoundedCornerShape(Design.InputRadius.dp)),
                ) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(Design.SpaceSm.dp))
                    Text(
                        "开始连扫",
                        fontSize = Design.FontLg.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }

            if (fresh.isNotEmpty()) {
                item { CellGroupHeader("本次绑定", "刚刚 · ${fresh.size} 件") }
                items(fresh, key = { "f${it.id}" }) { b ->
                    CellBindingRow(
                        binding = b,
                        badgeText = "已绑",
                        badgeColor = Success,
                        badgeBg = SuccessLight,
                        snapshots = snapshots,
                        onOpen = { onOpenProduct(b) },
                        onLongPress = { onLongPressBinding(b) },
                    )
                }
            }

            if (existing.isNotEmpty()) {
                item { CellGroupHeader("本格原有", "${existing.size} 件 · 下次扫码去重") }
                items(existing, key = { "e${it.id}" }) { b ->
                    CellBindingRow(
                        binding = b,
                        badgeText = "已在格中",
                        badgeColor = TextWeak,
                        // null = 纯灰字无胶囊（Ardot 3:16 实测）
                        badgeBg = null,
                        snapshots = snapshots,
                        onOpen = { onOpenProduct(b) },
                        onLongPress = { onLongPressBinding(b) },
                    )
                }
            }

            if (slot.bindings.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "这格还是空的",
                            fontSize = Design.FontMdPlus.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMain,
                        )
                        Spacer(Modifier.height(Design.SpaceXs.dp))
                        Text(
                            "点上面的「开始连扫」，用巴枪扫商品码，扫一个绑一个",
                            fontSize = Design.FontSm.sp,
                            lineHeight = 20.sp,
                            color = TextWeak,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(Design.SpaceLg.dp))
                        GhostButton("回货架搜商品加入", Modifier.fillMaxWidth(0.7f), onClick = onAddProduct)
                    }
                }
            }
        }

        // ---- 底部固定橙色大 CTA（Ardot 3:16 y=768 `358×52 rx=14`，渐变 #FF7C19→#FF6B00）----
        // ★ 子形态**不显示悬浮 tab 栏**（本页已把 ShellChromeState.showTabBar 置 false），
        //   所以这里不需要 90dp 避让公约 —— 设计稿里 CTA 底(y=820)甚至比 tab 栏顶(y=761)还低，
        //   本就是"没有 tab 栏"的布局。只留 12dp 距屏底（对齐设计 y=820 到 844 的 24px 换算量级）。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    start = Design.SpaceLg.dp,
                    end = Design.SpaceLg.dp,
                    bottom = 12.dp,
                ),
        ) {
            Button(
                onClick = onFinish,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(14.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(CtaGradientFrom, CtaGradientTo),
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ),
            ) {
                Text(
                    "完成（本次 $count 件）",
                    fontSize = Design.FontLg.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun CellGroupHeader(title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Design.SpaceSm.dp, bottom = Design.SpaceXs.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = Design.FontMd.sp, fontWeight = FontWeight.Bold, color = TextMain)
        Spacer(Modifier.width(Design.SpaceSm.dp))
        Text(detail, fontSize = Design.FontXs.sp, color = TextWeak)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CellBindingRow(
    binding: BindingUi,
    badgeText: String,
    badgeColor: Color,
    badgeBg: Color?,
    snapshots: Map<Long, ProductSnap>,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val snap = snapshots[binding.productId]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ★ 卡高 58~59dp（Ardot 3:16 实测 592..708 png = 58dp）。
            //   内容 20(品名行高) + 16(条码行高) = 36dp，上下 padding 12dp → 60dp 名义高，
            //   实测实机 58.5dp / Δ+0.5dp（`ui_geometry_diff.py --color card` 判定 ✅ 在容差内）。
            //   ⚠️ 2026-09-18 曾误用 dpr=3 换算，一度以为此处高 6.5dp 并加过 `.height(58.dp)`;
            //      修正 dpr=2.75 后实测证明**原值本就合格**，故已回退，不加显式高度。
            .toolSurface(shape = RoundedCornerShape(Design.CardRadius.dp), level = SxElevation.L2)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = Design.SpaceMd.dp, vertical = Design.SpaceMd.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ★ 08 稿是**纯文字两行卡**（品名 Bold + 条码小灰），**没有图片缩略图** ——
        //   Ardot 3:16 实测 4 张 `358×58 rx=14` 白卡内只有文本叶子。
        //   ✗ 旧实现照搬 07 格卡的「44dp 首字占位图」，属跨屏语法串味，已删。
        Column(Modifier.weight(1f)) {
            Text(
                binding.name,
                fontSize = Design.FontMd.sp,
                lineHeight = 20.sp,
                // 稿里品名是 Bold（比 07 卡墙内的 SemiBold 重一档，因为行卡只有两行文字）
                fontWeight = FontWeight.Bold,
                color = TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                binding.barcode ?: snap?.spec ?: "无条码",
                fontSize = Design.FontXs.sp,
                lineHeight = 16.sp,
                color = TextWeak,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(Design.SpaceSm.dp))

        // ★ 徽章两态（Ardot 3:16 实测）：
        //   「已绑」= 绿胶囊 `40×22 rx=11 #E6FDF4` + 绿字；
        //   「已在格中」= **纯灰字，无胶囊无底色**（稿里第 4 张卡只有左侧文字）。
        //   ✗ 旧实现对两者都套胶囊，把「本格原有」也画成了徽章 → 语义层级被抹平。
        if (badgeBg != null) {
            Surface(color = badgeBg, shape = RoundedCornerShape(Design.PillRadius.dp)) {
                Text(
                    badgeText,
                    fontSize = Design.FontXs.sp,
                    color = badgeColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = Design.SpaceSm.dp, vertical = 4.dp),
                )
            }
        } else {
            Text(
                badgeText,
                fontSize = Design.FontXs.sp,
                color = badgeColor,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
/**
 * 07 父屏「一层」= 层分组头 + 横向商品卡墙（Ardot 3:14）。
 *
 * 设计实测（design px）：
 * - 层分组头 `y=277  87×20`：「1层」粗体 + 「3 格 · 3 件」小灰字，**右侧折叠箭头 ▾**（@x≈362）
 * - 卡墙 `y=314  114×108 rx=12`，横向排布（x=16/138/260，间距 8）
 * - 卡内价格**蒙版条** `y=378  114×44`，压在卡片下半部，**半透明墨**承品名/价格/件数（白字）
 * - 末位是「空格卡」（虚线 + 「空 · 点一下绑定」）或「＋ 新建格」实底卡
 *
 * ✗ 旧实现的问题（对齐已废弃帧稿）：
 *   ① 层是个**大卡片**（toolSurface L2），设计里层头是**裸行**，没有外层卡片
 *   ② 层头右侧常驻「绑到这层」按钮，设计里**没有**
 *   ③ 格用 `aspectRatio(0.95f)` 自适应宽 + 三列宫格，设计是**固定 114×108 横向卡墙**
 *   ④ 卡内无价格蒙版条，品名/价格压在整图蒙版上
 */
@Composable
private fun LevelCard(
    level: LevelUi,
    target: BindTarget?,
    snapshots: Map<Long, ProductSnap>,
    photoLoader: ShelfPhotoLoader,
    onSelectLevelOnly: () -> Unit,
    onSelectSlot: (Long) -> Unit,
    onNewSlot: () -> Unit,
    onLongPressLevel: () -> Unit,
    onLongPressSlot: (SlotUi) -> Unit,
    onOpenProduct: (BindingUi) -> Unit,
    onLongPressBinding: (BindingUi) -> Unit,
) {
    var collapsed by rememberSaveable(level.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        // ---- 层分组头（裸行：粗体层名 + 灰字统计 + 右侧折叠箭头）----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onSelectLevelOnly, onLongClick = onLongPressLevel)
                .padding(horizontal = 2.dp, vertical = Design.SpaceXs.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(level.name, fontSize = Design.FontMd.sp, fontWeight = FontWeight.Bold, color = TextMain)
            Spacer(Modifier.width(Design.SpaceSm.dp))
            Text(
                "${level.slotCount} 格 · ${level.bindingCount} 件",
                fontSize = Design.FontSm.sp,
                color = TextWeak,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (collapsed) "▸" else "▾",
                fontSize = Design.FontSm.sp,
                color = TextWeak,
                modifier = Modifier.clickable { collapsed = !collapsed },
            )
        }

        if (!collapsed) {
            Spacer(Modifier.height(Design.SpaceSm.dp))

            // ---- 商品卡墙（114×108 rx=12，间距 8）----
            // ★★ 2026-09-18 纠错：「3 等分铺满」不是「死守 114dp + 横向滚动」。
            //
            //   画布 3:14 真值：3 张 `114×108` 卡 @ x=16 / 138 / 260，间距 8，
            //   算术 = 16+114+8+114+8+114+16 = **390dp = 画布全宽**（780px ÷ 2px/dp）。
            //   → 稿是「一屏正好 3 张、铺满、无可滚动余量」的**静态墙**。
            //
            //   ✗ 旧实现写死 `LazyRow` + `.size(width = 114.dp)`：
            //     实机屏 411.4dp（1080px ÷ 2.625）比稿的 390dp 宽 21.4dp，
            //     固定 114dp 时每行只放得下 2 张，第 3 张被挤到下一行，
            //     右侧凭空多出 **160dp 死白**（应 16dp）—— 稿件完全没落地。
            //
            //   ✅ 正解：3 等分自适应，卡宽 = (屏宽 - 16×2 边距 - 8×2 间距) / 3。
            //     411.4dp 屏 → 121.1dp/卡；390dp 屏 → 114dp/卡（与原稿逐值相等）。
            //     保持「一屏 3 张铺满」的**意图**，且天然适配任意屏宽。
            //   ★ 超出 3 张仍可横向滑动（老板 2026-09-18 定），
            //     此时卡宽锁定 3 等分值，不因张数变化而漂移。
            val cardW = computeWallCardWidth()
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(level.slots, key = { it.id }) { slot ->
                    val idx = level.slots.indexOf(slot) + 1
                    SlotWallCard(
                        slot = slot,
                        index = idx,
                        width = cardW,
                        selected = target?.slotId == slot.id,
                        snapshots = snapshots,
                        photoLoader = photoLoader,
                        onClick = {
                            // 有货 → 进商品详情；空 → 选为绑定目标
                            if (slot.bindings.isNotEmpty()) onOpenProduct(slot.bindings.first())
                            else onSelectSlot(slot.id)
                        },
                        onLongClick = { onLongPressSlot(slot) },
                    )
                }
                items(level.directBindings, key = { "d${it.productId}" }) { b ->
                    // ★ 「整层存放」合入同一卡墙（WORKFLOW.md 一.2「做设计的超集」）。
                    //   语义：这货属于**层**、不属于任何格 → 不进格序号序列，
                    //   角标改用 `层` 二字，与 `01/02/03` 明确区分，但**卡片骨架完全一致**
                    //  （114×108 rx=12 + 44dp 半透明墨蒙版条 + 同款角标位/字号）。
                    //   ✗ 上一轮把它做成卡墙下方**另一个分组条 + 另一排小卡** =
                    //     WORKFLOW 第 90 行明令禁止的「以业务扩展为借口保留走样的老结构」。
                    DirectLayerCard(
                        binding = b,
                        width = cardW,
                        snap = snapshots[b.productId],
                        photoLoader = photoLoader,
                        onClick = { onOpenProduct(b) },
                        onLongClick = { onLongPressBinding(b) },
                    )
                }
                // 末位「＋ 新建格」（设计里 2 层第 3 张是实底新建卡）
                item {
                    Box(
                        modifier = Modifier
                            .size(width = cardW, height = WallCardHeight)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceSubtle)
                            .clickable { onNewSlot() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("＋", fontSize = 20.sp, color = TextSecondary)
                            Spacer(Modifier.height(4.dp))
                            Text("新建格", fontSize = Design.FontSm.sp, color = TextSecondary)
                        }
                    }
                }
            }

            // ✗ 原「整层存放（未分格）」独立分组条 + 独立小卡排已删除 ——
            //   已按 WORKFLOW.md 一.2 合入上方同一卡墙（`DirectLayerCard`，角标 `层`）。
        }
    }
}

/**
 * 07 卡墙「一屏 3 张」的自适应卡宽。
 *
 * 画布 3:14 真值：`16 + 114 + 8 + 114 + 8 + 114 + 16 = 390dp`（画布全宽）。
 * 所以「114dp」不是一个固定常量，而是**390dp 屏幕上 3 等分的结果**。
 * 实机屏宽各异（本机 411.4dp），死守 114dp 会让第 3 张换行 + 右侧 160dp 空白。
 *
 * 故按意图重算：`(屏宽 - 左右边距 16×2 - 卡间距 8×2) / 3`。
 * - 390dp 屏 → 114.0dp（与原稿逐值相等）
 * - 411.4dp 屏 → 121.1dp
 *
 * 下限 96dp 防止极窄屏（如折叠机小窗）把卡片压成不可读。
 */
@Composable
private fun computeWallCardWidth(): Dp {
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val avail = screenW - WallCardMargin * 2 - WallCardGap * 2
    return (avail / 3).coerceAtLeast(96.dp)
}

/** 卡墙左右边距（画布真值 16dp）。 */
private val WallCardMargin = 16.dp

/** 卡墙卡间距（画布真值 8dp）。 */
private val WallCardGap = 8.dp

/** 卡墙卡片高度（画布真值 108dp，与宽度无关，恒定）。 */
private val WallCardHeight = 108.dp

/**
 * 07 卡墙里的单个储位卡（Ardot 3:14 `114×108 rx=12`）。
 *
 * 结构（严格按稿）：
 * - 上半：商品图（或空格虚线框）；**左上** 序号角标 `01`（深色半透明）；右上 `+N` 溢出角标
 * - 下半：**价格蒙版条 `114×44`**（半透明墨蒙版，承品名 / 价格 / 件数，白字）
 * - 空格：虚线边框 + 居中「空 · 点一下绑定」
 *
 * ★ 材质真源（2026-09-18 纠错，此前此处注释是错的）：
 * `3:14` 画布导出里四条渐变（宿主 `<rect width="114" height="44"/>`，正好对应四张卡）
 * 的 stop 是 `#0A1121` @ `0 / 0.18 / 0.78 / 0.78` —— 即 `scrimBottomBrush()`
 * （`tokens.json`: `scrimInk`/`scrimFade 0.18`/`scrimPlateau 0.78`）。
 * **橙色 `#FF7C19 → #FF6B00` 在 `3:14` 里出现 0 次**，那是 CTA 按钮的令牌
 * （`3:16`/`3:1687` 的 `209×52 rx=14` / `358×52 rx=14`），**不可用于卡片**。
 *
 * 稿里蒙版是**固定的 44px 高**，不是「整卡盖一层渐变」——
 * 所以品名永远坐在稳定的深色底上，不会因图片明暗而看不清。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotWallCard(
    slot: SlotUi,
    index: Int,
    width: Dp,
    selected: Boolean,
    snapshots: Map<Long, ProductSnap>,
    photoLoader: ShelfPhotoLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val hasStock = slot.bindings.isNotEmpty()
    val first = slot.bindings.firstOrNull()
    val snap = first?.let { snapshots[it.productId] }

    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, first?.productId) {
        value = if (first == null) null else withContext(Dispatchers.IO) {
            runCatching { photoLoader.bitmapOf(first.productId, maxW = 320) }.getOrNull()
        }
    }

    // 有货 → 走通用卡壳（与整层卡同一骨架，绝不漂移）
    if (hasStock && first != null) {
        WallCardShell(
            name = first.name,
            bitmap = bmp,
            width = width,
            price = snap?.sellPrice?.takeIf { it > 0 }?.let { "¥${moneyText(it)}" } ?: "—",
            badge = "%02d".format(index),
            badgeBg = Design.ScrimInk.copy(alpha = 0.55f),
            trailing = if (slot.bindings.size > 1) "${slot.bindings.size} 件" else null,
            // ★ 2026-09-18 拍板：`+N` = **本格混装的其它商品种数**（不是件数）。
            //
            // 取证（画布 3:14 四个数据点全过 + 设计稿放大肉眼核对）：
            //   · 1层卡1 `2 件` PVC弯头  —— 单一 SKU → **无角标**
            //   · 1层卡2 `1 件` PPR弯头  —— 照片里有第二种管件 → **+1**
            //   · 2层卡1 `5 件` / 卡2 `8 件` —— 各自单一 SKU → **无角标**
            //   ⇒ `+N ≠ 件数`（1 件有角标、8 件没有）；唯一自洽读法是**混装种数**。
            //
            // 次证（几何）：三个序号角标都在**左上**（卡左上内缩 6dp：x=22/144/266），
            //   而 `+1` 在 x=216 —— 落在卡2（138..252）内部靠右，右内缩 12dp，
            //   是**右上角**的独立信息位。位置本身就证明它与序号角标不同类。
            //
            // 数据来源：`SlotUi.bindings` 是 `List<BindingUi>`，每项带 `productId`，
            //   故「其它商品种数」= distinct productId − 1（减去当前展示的 first）。
            //   **真实可推导，不是发明语义。**
            overflow = slot.bindings.map { it.productId }.distinct().size - 1,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        return
    }

    Box(
        modifier = Modifier
            .size(width = width, height = WallCardHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
            .then(
                // ★ 2026-09-18 纠错：空格卡是**虚线**框，不是实线。
                //   设计稿实测（07_shelf.png x=520/x=746 两条竖边）：
                //   108/216 非底色像素 = 50% 占空比 → 虚线。
                //   虚线 = 「这里可以新建」的邀请语义；实线 = 「已有内容」。
                //   旧实现用 .border(1.dp, Border) 画成实线，把邀请语义画成了占用语义。
                //   复用现成的 Modifier.ghostCell()（AppSurfaces.kt:369），
                //   它的 doc 写的就是「幽灵格：虚线细描边的空位容器」—— 不要另造轮子。
                if (selected) Modifier.border(2.dp, Primary, RoundedCornerShape(12.dp))
                else Modifier.ghostCell(radius = 12.dp, color = Border)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // 空格卡：居中提示（稿里「03 / 空 · 点一下绑定」）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (selected) PrimaryLight else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (selected) "已选" else "空 · 点一下绑定",
                fontSize = Design.FontXs.sp,
                color = if (selected) Primary else TextWeak,
                textAlign = TextAlign.Center,
            )
        }

        // 左上序号角标（稿里 01 / 02 / 03）
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Design.ScrimInk.copy(alpha = 0.55f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                "%02d".format(index),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/**
 * 卡墙通用卡片外壳（`114×108 rx=12` + 44dp 价格半透明墨蒙版条）。
 *
 * ★ 抽出来是为了**保证「格卡」与「整层卡」骨架绝不漂移** ——
 *   两者在画布语法里是**同一种卡**，只有左上角标语义不同（格位序号 `01` vs 全层 `层`）。
 *   WORKFLOW.md 一.2 要求业务扩展「按设计稿的网格语法合入」，
 *   共用外壳就是「同一网格」的代码级保证；分写成两个函数必然日久走样。
 *
 * @param badge   左上角标文案（格卡=`01`/`02`…，整层卡=`层`）
 * @param badgeBg 角标底色（格卡=墨色半透明；整层卡=品牌橙，示「非格位」）
 * @param trailing 右下尾巴文案（格卡=`N 件`；整层卡传 null 不显示）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WallCardShell(
    name: String,
    bitmap: android.graphics.Bitmap?,
    width: Dp,
    price: String,
    badge: String,
    badgeBg: Color,
    trailing: String?,
    overflow: Int = 0,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = width, height = WallCardHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
            .border(0.5.dp, Border, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(SurfaceSubtle), contentAlignment = Alignment.Center) {
                Text(
                    name.take(2),
                    fontSize = Design.FontLg.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDisabled,
                )
            }
        }

        // 底部固定 44dp 价格蒙版条。
        // ★★ 材质真源 = 半透明墨蒙版（2026-09-18 纠错）：
        //   画布 3:14 该矩形（114×44）的 stop 是 #0A1121 @ 0/0.18/0.78/0.78，
        //   等价于 AppSurfaces.scrimBottomBrush()（tokens: scrimInk/scrimFade .18/scrimPlateau .78）。
        //   ⚠️ 曾误用 CTA 令牌 CtaGradientFrom/To（#FF7C19→#FF6B00，实心不透明）
        //   —— 那是按钮令牌（画布 3:16 / 3:1687），套到照片卡上会**整块盖住商品图**。
        // 固定高而非整卡蒙版：品名永远坐在稳定的深色底上，不随图片明暗漂移。
        //
        // ★★ 2026-09-18 行距纠错（第 3 层「行结构」实测，共两轮）：
        //
        //   【第一轮】原实现用 `Spacer(Modifier.weight(1f))` 把两行**撑到两端**
        //     （space-between），实测行距 **22.8dp**，稿子是 **16dp** —— 视觉上价格"掉下去"。
        //
        //   【第二轮】把 weight 换成固定 `top=10dp` 后，实机复测
        //     （`scripts/wsl-emu.sh shot` + 3.13.12 venv 的 PIL 逐行扫白像素）：
        //       品名带 距卡底 21.7dp   价格带 **距卡底 3.1dp**   带间距 19.8dp
        //     而稿子是 **品名带距卡底 26.0dp / 价格带距卡底 10.5dp / 带间距 17.0dp**。
        //     ⇒ 价格带**仍偏低 7.4dp**（几乎贴着卡片下沿）。根因：只钉住**顶**，
        //     「顶 padding + 两个行盒高度」的和是隐式的，行盒（12sp/14sp）比字面高，
        //     内容一超高就整体往下溢（44dp 蒙版条容不下 10+?+4+? ）。
        //
        //   设计稿真值（`07_shelf.png` 卡1 footer 逐行扫白字带，dpr=2，2026-09-18 复测）：
        //     scrim 378.0..422.0（h=44）
        //     品名带 387.0..396.0  → 距 scrim 顶 **9.0dp**，距 scrim 底 26.0dp
        //     价格带 404.0..411.5  → 距 scrim 顶 **26.0dp**，距 scrim 底 **10.5dp**
        //     ⇒ **上下近似对称（上 9 / 下 10.5）**，两行带间距 **17dp**。
        //
        //   ✅ 正解 = **两端都钉死**：品名 `align(TopStart)` 钉顶，价格行 `align(BottomStart)` 钉底。
        //     这样任何一侧行长变化都不会把另一侧顶歪。
        //
        //   【第三轮 —— 定标】钉死后实机复测（`wsl-emu.sh install` + shot，**必须先 install
        //     再 shot**，见该脚本铁律⓿：relaunch 不装包，会量到旧包像素 = 幻觉结论）：
        //       品名带 385.9..396.2  目标 387.0..396.0  → 差 −1.1 / +0.2  ✅
        //       价格带 396.6..404.6  目标 404.0..411.5  → 差 −7.4 / −6.9  ❌ 偏高 6.9dp
        //     **根因（量具语义）**：`padding(bottom=N)` 钉的是 **Row 的 layout box 底**，
        //     不是**字面底**。box 高 = lineHeight(14sp) 的完整行盒，glyph 坐在行盒**偏上**处，
        //     故 box 底 ↦ 字面底 之间恒有一段 descender/leading 空白（此处 ≈ 6.9dp）。
        //     ⇒ 经验定标：**字面底 = box 底 + 6.9dp 空白**。要字面底落在距卡底 10.5dp，
        //       box 底须距卡底 10.5 − 6.9 ≈ 3.6dp，取 **4dp**。
        //   【第四轮 —— 收敛】R3 实测：品名 386.7..396.6（稿 387.0..396.0，差 −0.3/+0.6 ✅）
        //     价格 401.5..410.3（稿 404.0..411.5，差 −2.5/−1.2）。方向已对，残差 1.2dp 偏上。
        //     ⇒ bottom 4dp → **3dp**（等价于把行盒空隙定标从 6.9 修到 5.9）。
        //     ⚠️ 6.9/5.9 都是**特定字号 13sp/lineHeight=14sp 的行盒属性**，换字号必须重定标。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(44.dp)
                .background(scrimBottomBrush()),
        ) {
            Text(
                name,
                fontSize = 11.sp,
                // 稿子两行带间距 17dp；显式给 lineHeight 防「继承行高撑开」（本项目第 7 号陷阱）
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, end = 8.dp, top = 10.dp)
                    .fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // 稿: 价格字面底距卡底 10.5dp − 行盒空隙 ≈ 6dp → box 底距卡底 3dp（R4 定标）
                    .padding(start = 8.dp, end = 8.dp, bottom = 3.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    price,
                    fontSize = 13.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                )
                if (trailing != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        trailing,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                    )
                }
            }
        }

        // 左上角标
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(badgeBg)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(badge, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        // 右上「+N」混装角标 —— **语义 = 本格混装的其它商品种数**（2026-09-18 拍板，非件数）。
        //
        // 取证（画布 3:14 几何 + 设计稿 07_shelf.png 放大肉眼核对，四个数据点全过）：
        //   画布第一层 y=320 有 **4 个** 24×18 角标矩形：x=22 / 144 / 216 / 266。
        //     x=22  = 卡1 序号（左上，内缩 6）   —— 卡1 = `PVC弯头` `2 件`，单一 SKU
        //     x=144 = 卡2 序号（左上，内缩 6）   —— 卡2 = `PPR弯头` `1 件`，**+1**
        //     x=216 = **卡2 右上「+1」**（右内缩 12）  ← 与序号角标**不同类**
        //     x=266 = 卡3 序号（左上，内缩 6，底 `#F2F4F7` = 空格卡）
        //   第2层两张卡（`5 件` / `8 件`）右上**均为空**（几何：y=482 只有 2 个角标）。
        //
        // ⇒ 排除了「件数」读法：1 件有 `+1`、8 件反而没有。
        // ⇒ 唯一自洽读法：**本格混装了 N 种其它商品**（多 SKU 共格）。
        //   · 卡1 单一 SKU → 无角标 ✓
        //   · 卡2 照片里有第二种管件 → `+1` ✓
        //   · 第2层两张各自单一 SKU → 无角标 ✓
        //
        // 数据来源真实可推导：`SlotUi.bindings` 是 `List<BindingUi>`，每项带 `productId`，
        // 故 N = distinct(productId) − 1（减去卡面正在展示的 first）。调用处已接线。
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Design.ScrimInk.copy(alpha = 0.65f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("+$overflow", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/**
 * 整层存放（未分格）的商品卡 —— **与格卡同一张卡**（Ardot 3:14 网格语法的超集）。
 *
 * 语义：该商品挂在**层**上，不属于任何格。故：
 * - 骨架、尺寸、蒙版条**与 [SlotWallCard] 完全一致**（共用 [WallCardShell]）；
 * - 唯一区别是左上角标为 `层`（品牌橙底，示「非格位」），而非格位序号；
 * - 右下不显示「N 件」（层上没有「格内件数」的概念）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DirectLayerCard(
    binding: BindingUi,
    width: Dp,
    snap: ProductSnap?,
    photoLoader: ShelfPhotoLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, binding.productId) {
        value = withContext(Dispatchers.IO) {
            runCatching { photoLoader.bitmapOf(binding.productId, maxW = 320) }.getOrNull()
        }
    }
    WallCardShell(
        name = binding.name,
        bitmap = bmp,
        width = width,
        price = snap?.sellPrice?.takeIf { it > 0 }?.let { "¥${moneyText(it)}" } ?: "—",
        badge = "层",
        badgeBg = Primary,
        trailing = null,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

// ★★ 2026-09-18 删除 `GoodsFace(...)`（原 71 行，本文件历史实现）。
//
// 决策依据（不是"看着重复就删"，而是逐条比对过）：
//   1. **它已被取代**：`git show HEAD:<file> | grep -o GoodsFace` = 3 处（当时是活路径），
//      而 `WallCardShell` 在 HEAD **不存在** —— 说明 GoodsFace 是重建前的旧实现，
//      被本轮的 `WallCardShell` 挤成死代码。项目铁律要求先做这一步身份核对再删。
//   2. **它结构上做不到稿子的行结构**：07 卡底 footer 实测（`_reports/_chk_footer.png` 放大）
//      是**两行两段**——第 1 行品名左对齐；第 2 行「价格左 + 件数右」**分列**。
//      而 `GoodsFace` 的 footer 是一个左对齐 `Column`（name + sub 顺次换行），
//      **无法把件数推到右边**。稿子的「2 件」右对齐它表达不了。
//   3. **它把价格并进了 sub 行**：`subLine = "¥12.50 · <规格>"`（`·` 分隔），
//      与稿子「价格单独占第 2 行左侧」不符。
//
// 保留其**两条有独立价值的注释**（已并入 `WallCardShell` 的 KDoc）：
//   · 有图 → 压 scrimBottomBrush 墨蒙版，白字对比度有保底；
//   · **无图 → 不上蒙版**（蒙版的唯一理由是压住不可控的图片亮度；没图就没有压暗的必要，
//     改在哑光卡面写深色字，读起来是「待补图」而不是「图裂了」）。
//
// 死代码已归档：`_reports/decisions/18_goodsface_removed_HEAD_version.txt`（HEAD 全文，2123 行）

/** 价格显示：整数不带 .0，小数保留两位（与管家婆/价签同一观感）。 */
private fun moneyText(v: Double): String =
    if (kotlin.math.abs(v % 1.0) < 1e-6) v.toLong().toString() else String.format("%.2f", v)

/**
 * 「当前位置」商品区：选中层/格后，这里就是该位置的东西（图片墙）。
 *
 * 空的（还没绑过东西）也要给出路：一句话说明 + 直接进连扫，不让店员面对一片空白猜。
 */

@Composable
private fun NewNameDialog(
    dialog: NameDialog,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when {
        dialog.editId != null -> "改名（标签不用重贴）"
        dialog.kind == NameKind.SHELF -> "新建货架"
        dialog.kind == NameKind.LEVEL -> "在「${dialog.parentName}」下新建层"
        else -> "新建格"
    }
    SanxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = Design.FontLg.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
                Text("名字随便起：中文、数字、混合都行", fontSize = Design.FontXs.sp, color = TextWeak)
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(
                            if (dialog.kind == NameKind.SHELF) "如：水管架 / A架 / 门口那排"
                            else "如：1层 / 上层 / 左边那格",
                        )
                    },
                )
            }
        },
        confirmText = if (dialog.editId != null) "改名" else "创建",
        onConfirm = onConfirm,
        dismissText = "取消",
    )
}

@Composable
private fun DupDialog(
    parentName: String,
    kindLabel: String,
    pendingName: String,
    candidates: List<NameCandidate>,
    onUseExisting: (NameCandidate) -> Unit,
    onStillCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 优先取 exact=true（最该合并的那个），退而求其次 candidates.first()
    val primary = candidates.firstOrNull { it.exact } ?: candidates.firstOrNull()
    val primaryName = primary?.name ?: pendingName
    val titleText = if (parentName.isBlank()) "新建$kindLabel" else "在 $parentName 下新建$kindLabel"
    val warningText = if (parentName.isBlank())
        "已有相近的名字：同一个位置建两个名，货会分散在两边"
    else
        "$parentName 下已有相近的名字：同一个位置建两个名，货会分散在两边"
    SanxAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    titleText,
                    fontSize = Design.FontLg.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                )
                Spacer(Modifier.height(Design.SpaceXs.dp))
                Text(
                    "名字随便起：中文、数字、混合都行",
                    fontSize = Design.FontXs.sp,
                    lineHeight = 15.sp,
                    color = TextSecondary,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
                // ① 待输名字回显（橙描边输入框样式，照搬稿 3:1346）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(Design.InputRadius.dp))
                        .border(
                            BorderStroke(2.dp, Primary),
                            RoundedCornerShape(Design.InputRadius.dp),
                        )
                        .padding(vertical = Design.SpaceMd.dp, horizontal = Design.SpaceMd.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        pendingName,
                        fontSize = Design.FontMd.sp,
                        lineHeight = 20.sp,
                        color = TextMain,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
                // ② #FFF9E6 警示卡
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarningLight, RoundedCornerShape(Design.InputRadius.dp))
                        .padding(horizontal = Design.SpaceMd.dp, vertical = Design.SpaceSm.dp),
                ) {
                    Text(
                        warningText,
                        fontSize = Design.FontXs.sp,
                        lineHeight = 15.sp,
                        color = TextSecondary,
                    )
                }
                // 候选行（保留点击"就用它"=绑到已有节点的语义）
                candidates.forEach { cand ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceSubtle, RoundedCornerShape(Design.InputRadius.dp))
                            .clickable { onUseExisting(cand) }
                            .padding(horizontal = Design.SpaceMd.dp, vertical = Design.SpaceSm.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            cand.name,
                            fontSize = Design.FontMd.sp,
                            lineHeight = 20.sp,
                            color = TextMain,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "合并到它",
                            fontSize = Design.FontSm.sp,
                            lineHeight = 18.sp,
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // ④ 脚注
                Text(
                    "合并会把这条的绑定一起搬过去，不会丢商品。",
                    fontSize = Design.FontXs.sp,
                    lineHeight = 15.sp,
                    color = TextWeak,
                )
            }
        },
        confirmText = "合并到「$primaryName」",
        onConfirm = { primary?.let(onUseExisting) },
        confirmEnabled = primary != null,
        dismissText = "仍然新建",
        onDismiss = onStillCreate,
    )
}

@Composable
private fun ActionRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Design.SpaceMd.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = Design.FontMd.sp,
            color = if (danger) Danger else TextMain,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun GhostButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = SurfaceSubtle,
        shape = RoundedCornerShape(Design.ButtonRadius.dp),
        modifier = modifier.height(46.dp).clickable { onClick() },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = Design.FontSm.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
        }
    }
}
