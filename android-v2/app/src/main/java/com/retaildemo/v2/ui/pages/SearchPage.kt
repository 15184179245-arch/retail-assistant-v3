package com.retaildemo.v2.ui.pages

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.produceState
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.R
import com.retaildemo.v2.data.local.CategoryMap
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.repo.SaleFreq
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.SkeletonList
import com.retaildemo.v2.ui.components.StockBadge
import com.retaildemo.v2.ui.components.buildHighlightedText
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.viewmodel.SearchViewModel

/**
 * 查价页（U1 管线试点 v2，2026-09-08；契约=DESIGN.md v6 / design/tokens.json）。
 * 深度对标（1688 搜索页 / 京东生鲜 / 爱购 + M3 Expressive / Liquid Glass / One UI 共同语法）：
 * 1. 搜索即主角：无色块顶栏，橙色描边大搜索框 + 独立橙色扫码方块（1688 搜索页模式）。
 * 2. 单一品牌色纪律：橙只给动作/价格/激活态，其余灰阶；chip 全部无边框（未选灰填充/选中橙 tint）。
 * 3. 类目瓷片锚点：无商品图就用 50dp 橙 tint 方块（类目首字）立住卡片。
 * 4. 价格最大字：¥ 小、整数特大加粗（PriceText 24/13）。
 * 5. 动效：结果卡整卡弹簧按压（M3E）。逻辑/ViewModel/导航零改动；marginPct 契约保留。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchPage(
    onOpenProduct: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val saleFreq by viewModel.saleFreq.collectAsState()
    val history by viewModel.history.collectAsState(initial = emptyList())
    val loading by viewModel.loading.collectAsState()
    val frozenMode by viewModel.frozenMode.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val subCategories by viewModel.subCategories.collectAsState()
    val disambiguation by viewModel.disambiguation.collectAsState()
    val stockFilter by viewModel.stockFilter.collectAsState()
    val filterHidden by viewModel.filterHidden.collectAsState()
    val sortOption by viewModel.sortOption.collectAsState()
    val multiCandidates by viewModel.multiCandidates.collectAsState()
    val bindMessage by viewModel.bindMessage.collectAsState()
    val context = LocalContext.current
    var previewImage by remember { mutableStateOf<Triple<Long, String, android.graphics.Bitmap>?>(null) }
    var isMultiSelect by remember { mutableStateOf(false) }
    val selectedProducts = remember { androidx.compose.runtime.mutableStateMapOf<String, ProductEntity>() }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val barcode = result.data?.getStringExtra(ScannerActivity.EXTRA_BARCODE)
        if (!barcode.isNullOrBlank()) {
            ScanFeedback.success(context)
            // S16c：扫码必须强制重搜（绕过 debounce/distinctUntilChanged，同码重扫不被吞）
            viewModel.scanSearch(barcode.trim())
        }
    }

    // ===== 语音输入（2026-09-11 老板令：不要二级弹窗）——直接在搜索框麦克风上按住说话，
    // 松手立即识别并自动搜索；无浮层，状态内联在搜索框下方一行小字。 =====
    val serverAsr = remember { com.retaildemo.v2.util.ServerAsr(context) }
    var voiceHolding by remember { mutableStateOf(false) }
    var voiceRecognizing by remember { mutableStateOf(false) }
    var voiceMsg by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { serverAsr.stop() } }
    val voicePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) voiceMsg = "需要麦克风权限，请再按住麦克风"
    }
    val voiceStart: () -> Unit = {
        serverAsr.warmUp() // 按住瞬间预建连接，松手上传省一次 TLS 握手
        voiceHolding = true
        voiceRecognizing = false
        voiceMsg = null
        serverAsr.startHold(
            onState = {},
            onLevel = {},
            onFinal = { final ->
                voiceHolding = false
                voiceRecognizing = false
                if (final.isNotBlank()) {
                    ScanFeedback.success(context)
                    viewModel.onQueryChange(final)
                }
            },
            onError = { msg ->
                voiceHolding = false
                voiceRecognizing = false
                voiceMsg = msg
            },
        )
    }
    val voiceEnd: () -> Unit = {
        if (voiceHolding) {
            voiceHolding = false
            voiceRecognizing = true
        }
        serverAsr.release()
    }
    // F3 排障通道（仅 DEBUG 构建）：无麦克风环境喂测试音频走完整识别管线
    val voiceTestWav: () -> Unit = {
        voiceRecognizing = true
        voiceMsg = null
        val wav = java.io.File(context.getExternalFilesDir(null), "retaildemo_test_zh.wav")
        serverAsr.startFromFile(
            wavPath = wav.absolutePath,
            onPartial = { },
            onFinal = { final ->
                voiceRecognizing = false
                if (final.isNotBlank()) {
                    ScanFeedback.success(context)
                    viewModel.onQueryChange(final)
                }
            },
            onError = { msg ->
                voiceRecognizing = false
                voiceMsg = msg
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // ===== 搜索即主角：描边大搜索框 + 独立扫码方块 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Design.SpaceLg.dp, vertical = Design.SpaceSm.dp),
            horizontalArrangement = Arrangement.spacedBy(Design.SpaceMd.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.onQueryChange(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜品名 / 规格 / 拼音缩写 / 条码", fontSize = Design.FontMd.sp, color = TextWeak) },
                leadingIcon = {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.2.dp,
                            color = Primary,
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Primary)
                    }
                },
                trailingIcon = {
                    when {
                        query.isNotEmpty() -> IconButton(onClick = {
                            ScanFeedback.click(context)
                            viewModel.onQueryChange("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "清除", tint = TextWeak, modifier = Modifier.size(20.dp))
                        }
                        // 按住说话：长按麦克风即录音，松手识别自动搜索（无二级弹窗）
                        else -> Box(
                            modifier = Modifier
                                .size(40.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                                context, Manifest.permission.RECORD_AUDIO,
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (!granted) {
                                                voicePermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                try { awaitRelease() } finally { }
                                                return@detectTapGestures
                                            }
                                            voiceStart()
                                            try {
                                                awaitRelease()
                                            } finally {
                                                voiceEnd()
                                            }
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Mic, contentDescription = "按住说话",
                                tint = if (voiceHolding) Danger else Primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Primary,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    cursorColor = Primary,
                ),
            )
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Primary,
                modifier = Modifier
                    .size(50.dp)
                    .clickable {
                        ScanFeedback.click(context)
                        scanLauncher.launch(ScannerActivity.intent(context))
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = "扫码查价",
                        tint = Color.White,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
        }

        // 语音状态内联行：按住说话/识别中/错误（无弹窗，2026-09-11 老板令）
        if (voiceHolding || voiceRecognizing || voiceMsg != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Design.SpaceLg.dp, vertical = 0.dp),
            ) {
                Text(
                    when {
                        voiceHolding -> "● 聆听中…松手识别"
                        voiceRecognizing -> "识别中…"
                        else -> voiceMsg ?: ""
                    },
                    color = if (voiceRecognizing) Primary else Danger,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                if (!voiceHolding && !voiceRecognizing && voiceMsg != null) {
                    TextButton(
                        onClick = { voiceMsg = null },
                        contentPadding = PaddingValues(horizontal = Design.SpaceSm.dp, vertical = 0.dp),
                    ) {
                        Text("知道", fontSize = 12.sp, color = TextWeak)
                    }
                }
                if (com.retaildemo.v2.BuildConfig.DEBUG && !voiceHolding && !voiceRecognizing) {
                    TextButton(
                        onClick = voiceTestWav,
                        contentPadding = PaddingValues(horizontal = Design.SpaceSm.dp, vertical = 0.dp),
                    ) {
                        Text("测试音频", fontSize = 12.sp, color = TextWeak)
                    }
                }
            }
        }

        if (query.isBlank()) {
            // 热门类目：默认折叠单行横滑（老板 2026-09-08：分类多不许占满页面），可展开铺满
            var catsExpanded by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Design.SpaceLg.dp, vertical = Design.SpaceXs.dp),
            ) {
                Text("热门类目", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { catsExpanded = !catsExpanded },
                    contentPadding = PaddingValues(horizontal = Design.SpaceSm.dp, vertical = 0.dp),
                ) {
                    Text(if (catsExpanded) "收起 ▴" else "展开 ▾", fontSize = Design.FontSm.sp, color = TextWeak)
                }
            }
            if (catsExpanded) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                    verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Design.SpaceLg.dp, vertical = Design.SpaceSm.dp),
                ) {
                    viewModel.categories.forEach { cat ->
                        val selected = selectedCategory == cat
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) Design.TopBarGradientFrom else Color.White,
                            modifier = Modifier
                                .sxElevation(SxElevation.L1, shape = RoundedCornerShape(12.dp))
                                .clickable {
                                    ScanFeedback.click(context)
                                    viewModel.onCategoryClick(cat)
                                },
                        ) {
                            Text(
                                text = cat,
                                color = if (selected) Color.White else TextMain,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Design.SpaceLg.dp),
                ) {
                    viewModel.categories.forEach { cat ->
                        val selected = selectedCategory == cat
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) Design.TopBarGradientFrom else Color.White,
                            modifier = Modifier
                                .sxElevation(SxElevation.L1, shape = RoundedCornerShape(12.dp))
                                .clickable {
                                    ScanFeedback.click(context)
                                    viewModel.onCategoryClick(cat)
                                },
                        ) {
                            Text(
                                text = cat,
                                color = if (selected) Color.White else TextMain,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }

            // 二级子类 chips（点品类展开；点子类按名称全文检索）
            if (subCategories.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                    verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Design.SpaceLg.dp, vertical = Design.SpaceSm.dp),
                ) {
                    subCategories.forEach { sub ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Design.TopBarGradientFrom.copy(alpha = 0.35f)),
                            modifier = Modifier.clickable {
                                ScanFeedback.click(context)
                                viewModel.onSubCategoryClick(sub)
                            },
                        ) {
                            Text(
                                text = sub,
                                color = TextMain,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }

            // S12 库存筛选 Chips：全部 / 有货 / 无货
            Row(
                horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                modifier = Modifier.padding(horizontal = Design.SpaceLg.dp),
            ) {
                listOf("全部" to null, "有货" to 1, "无货" to 0).forEach { (label, mq) ->
                    val selected = stockFilter == mq
                    FilterChip(
                        selected = selected,
                        onClick = {
                            viewModel.setStockFilter(mq)
                            viewModel.onQueryChange(query)
                        },
                        label = { Text(label, fontSize = Design.FontSm.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        colors = FilledChipColors(selected),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = Color.Transparent,
                            selectedBorderColor = Color.Transparent,
                            borderWidth = 0.dp,
                            selectedBorderWidth = 0.dp,
                            enabled = true,
                            selected = selected,
                        ),
                    )
                }
            }

            // 2026-09-11 老板实测：筛选激活时服务器明明返回了结果却被本地过滤层全数藏掉、
            // 零提示=「扫什么都扫不出来」。藏了结果必须亮出来，一键还原。
            if (filterHidden > 0) {
                Text(
                    "库存筛选隐藏了 $filterHidden 件结果，点此显示全部",
                    fontSize = 12.sp,
                    color = Primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(horizontal = Design.SpaceLg.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            viewModel.setStockFilter(null)
                            viewModel.onQueryChange(query)
                        }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }

            // S3 同码多货裁决（一码对多物）
            multiCandidates?.let { cands ->
                if (cands.isNotEmpty()) {
                    Spacer(Modifier.height(Design.SpaceSm.dp))
                    Text(
                        "同码多货，请点选归位",
                        style = MaterialTheme.typography.titleSmall,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = Design.SpaceLg.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.padding(horizontal = Design.SpaceLg.dp)) {
                        cands.forEach { c ->
                            Card(
                                shape = RoundedCornerShape(Design.CardRadius.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .sxElevation(SxElevation.L2)
                                    .clickable { viewModel.onMultiPicked(c) },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = Design.SpaceMd.dp, vertical = 10.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, fontSize = Design.FontMd.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "库存 ${com.retaildemo.v2.util.formatQty(c.stockQty)} · " + (c.spec ?: "无规格"),
                                            fontSize = Design.FontSm.sp,
                                            color = TextSecondary,
                                        )
                                    }
                                    Text(
                                        "点选归位",
                                        fontSize = Design.FontSm.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Primary,
                                    )
                                }
                            }
                        }
                        bindMessage?.let { msg ->
                            Spacer(Modifier.height(Design.SpaceXs.dp))
                            Text(msg, fontSize = Design.FontSm.sp, color = Primary)
                        }
                    }
                }
            }

            // 最近浏览
            val recentViews by viewModel.recentViews.collectAsState(initial = emptyList())
            if (recentViews.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "最近浏览",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = Design.SpaceLg.dp),
                )
                Spacer(Modifier.height(Design.SpaceSm.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                    contentPadding = PaddingValues(horizontal = Design.SpaceLg.dp),
                ) {
                    items(recentViews, key = { it.barcode }) { v ->
                        Surface(
                            shape = RoundedCornerShape(Design.CardRadius.dp),
                            color = Color.White,
                            modifier = Modifier
                                .width(138.dp)
                                .sxElevation(SxElevation.L2)
                                .clickable { onOpenProduct(v.barcode) },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    v.name,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(Design.SpaceSm.dp))
                                // 未定价商品（上游售价=0）不能显示成红色「¥0.00」——那不是价格，
                                // 是「还没定价」；红色零价在一屏里既像报错又像真值，店员会误读。
                                PriceText(
                                    price = v.sellPrice,
                                    integerSize = 18.sp,
                                    symbolSize = 11.sp,
                                    decimalSize = 11.sp,
                                    showUnpricedText = true,
                                )
                            }
                        }
                    }
                }
            }

            // 搜索历史
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Design.SpaceLg.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("搜索历史", style = MaterialTheme.typography.titleSmall)
                    TextButton(
                        onClick = {
                            ScanFeedback.click(context)
                            viewModel.clearHistory()
                        },
                        contentPadding = PaddingValues(horizontal = Design.SpaceSm.dp, vertical = 2.dp),
                    ) {
                        Text("清空", fontSize = Design.FontSm.sp, color = TextWeak)
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                    modifier = Modifier.padding(horizontal = Design.SpaceLg.dp),
                ) {
                    history.forEach { h ->
                        Surface(
                            shape = RoundedCornerShape(Design.CardRadius.dp),
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .sxElevation(SxElevation.L2)
                                .clickable {
                                    ScanFeedback.click(context)
                                    viewModel.onQueryChange(h.keyword)
                                },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = Design.SpaceMd.dp, vertical = 11.dp),
                            ) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = null,
                                    tint = TextWeak,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(Modifier.width(Design.SpaceMd.dp))
                                Text(h.keyword, fontSize = Design.FontMdPlus.sp, fontWeight = FontWeight.Medium, color = TextMain)
                                Spacer(Modifier.weight(1f))
                                Text("›", color = TextWeak, fontSize = 15.sp)
                            }
                        }
                    }
                }
            } else if (recentViews.isEmpty()) {
                Spacer(Modifier.height(Design.SpaceXl.dp))
                EmptyState(
                    illustrationRes = R.drawable.ill_empty_search,
                    icon = Icons.Default.Search,
                    title = "查价与找货",
                    subtitle = "支持品名、规格、拼音首字母（如 PBG）与条码直接扫描",
                )
            }
        } else {
            Spacer(Modifier.height(Design.SpaceSm.dp))

            // 汇总行：结果数 + 批量出签入口
            // ⚠️ 位置对齐设计稿 05：**计数行在上、筛选行在下**。
            // 此前实机是「筛选在上、计数在下」，与稿相反（设计稿：找到 38 条 ·「pvc 弯头」在上，
            // 全部/有货/1楼·A架/管件 那行在下）。
            if (results.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Design.SpaceLg.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "共 ${results.size} 款商品 · ${sortOption.label}",
                            fontSize = Design.FontSm.sp,
                            color = TextWeak,
                        )
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.8.dp,
                                color = Primary,
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            isMultiSelect = !isMultiSelect
                            if (!isMultiSelect) selectedProducts.clear()
                        },
                        contentPadding = PaddingValues(horizontal = Design.SpaceSm.dp, vertical = 2.dp),
                    ) {
                        Text(
                            if (isMultiSelect) "取消" else "批量出签",
                            color = Primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // S10 细类筛选
            val subGroups = remember(results) {
                results.mapNotNull { CategoryMap.refine(it.name) }.distinct().sorted()
            }
            var subFilter by remember(results) { mutableStateOf<String?>(null) }
            val shown = if (subFilter == null) {
                results
            } else {
                results.filter { CategoryMap.refine(it.name) == subFilter }
            }

            // 筛选行：**单行**（对齐设计稿 05 —— 稿上是「全部 / 有货 / 📍1楼·A架 / 管件」一行四胶囊）。
            // 此前实机拆成「类目一行 + 排序一行」两行，比稿多占一行高度，且上下顺序与稿相反。
            // 现合并为一条横向滚动行：类目组 → 细分隔线 → 排序组，功能零损失。
            if (subGroups.size > 1 || results.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(horizontal = Design.SpaceLg.dp),
                ) {
                    if (subGroups.size > 1) {
                        item {
                            FilterChip(
                                selected = subFilter == null,
                                onClick = { subFilter = null },
                                label = { Text("全部 (${results.size})", fontSize = Design.FontSm.sp) },
                                shape = RoundedCornerShape(Design.PillRadius.dp),
                                colors = FilledChipColors(subFilter == null),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = Color.Transparent,
                                    selectedBorderColor = Color.Transparent,
                                    borderWidth = 0.dp,
                                    selectedBorderWidth = 0.dp,
                                    enabled = true,
                                    selected = subFilter == null,
                                ),
                            )
                        }
                        items(subGroups, key = { it }) { g ->
                            FilterChip(
                                selected = subFilter == g,
                                onClick = { subFilter = g },
                                label = { Text(g, fontSize = Design.FontSm.sp) },
                                shape = RoundedCornerShape(Design.PillRadius.dp),
                                colors = FilledChipColors(subFilter == g),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = Color.Transparent,
                                    selectedBorderColor = Color.Transparent,
                                    borderWidth = 0.dp,
                                    selectedBorderWidth = 0.dp,
                                    enabled = true,
                                    selected = subFilter == g,
                                ),
                            )
                        }
                        // 组间细分隔线：类目与排序是两件事，挤在同一行里必须能一眼分开
                        item {
                            Box(
                                Modifier
                                    .padding(horizontal = 2.dp)
                                    .size(width = 1.dp, height = 16.dp)
                                    .background(Border),
                            )
                        }
                    }
                    if (results.isNotEmpty()) {
                        items(com.retaildemo.v2.ui.viewmodel.SearchSortOption.values().toList()) { opt ->
                            val selected = sortOption == opt
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    ScanFeedback.click(context)
                                    viewModel.setSortOption(opt)
                                },
                                label = {
                                    Text(
                                        text = opt.label,
                                        fontSize = Design.FontSm.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    )
                                },
                                shape = RoundedCornerShape(Design.PillRadius.dp),
                                colors = FilledChipColors(selected),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = Color.Transparent,
                                    selectedBorderColor = Color.Transparent,
                                    borderWidth = 0.dp,
                                    selectedBorderWidth = 0.dp,
                                    enabled = true,
                                    selected = selected,
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Design.SpaceSm.dp))
            }

            // （原「汇总行」已上移到筛选行之前、「排序行」已并入筛选单行 —— 见上方注释：
            //   对齐设计稿 05 的「计数在上 / 筛选在下」顺序与单行筛选结构）

            if (loading && shown.isEmpty()) {
                SkeletonList()
            } else if (shown.isEmpty() && !frozenMode) {
                EmptyState(
                    illustrationRes = R.drawable.ill_empty_search,
                    icon = Icons.Default.Search,
                    title = "未找到在售商品",
                    subtitle = "换个关键词试试，或点击下方检索无货商品"
                )
                disambiguation?.let { hint ->
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { viewModel.searchDisambiguate(hint.term) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(hint.ask, color = Primary, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { viewModel.searchIncludeFrozen() },
                    shape = RoundedCornerShape(Design.CardRadius.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Design.SpaceLg.dp),
                ) {
                    Text("全量检索（含无货/零库存商品）", color = Color.White)
                }
            } else if (shown.isEmpty()) {
                EmptyState(
                    illustrationRes = R.drawable.ill_empty_search,
                    icon = Icons.Default.Search,
                    title = "全量检索亦无结果",
                    subtitle = "仓库与管家婆均无此货品记录"
                )
            } else {
                if (frozenMode) {
                    Text(
                        "当前为全量检索结果（包含呆滞/无库存商品）",
                        fontSize = Design.FontXs.sp,
                        color = TextWeak,
                        modifier = Modifier.padding(horizontal = Design.SpaceLg.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        // 稿（画布 05 查价）：4 张结果卡 358×97，y=210/317/424/531，节距 107 → **卡距 10**。
                        // 原用 Design.SpaceSm(=8) 偏小 2dp。此处不复用令牌：令牌是「通用间距」，
                        // 列表卡距是设计稿逐屏给定的量（本屏 10，09 我的 = 8，21 设置 = 12），不能一律化。
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = Design.SpaceLg.dp, vertical = Design.SpaceSm.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(shown, key = { "${it.id}-${it.barcode}" }) { p ->
                            val itemKey = p.sku?.takeIf { it.isNotBlank() } ?: p.barcode.orEmpty()
                            val isSelected = selectedProducts.containsKey(itemKey)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (isMultiSelect) {
                                    androidx.compose.material3.Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedProducts[itemKey] = p else selectedProducts.remove(itemKey)
                                        },
                                        colors = androidx.compose.material3.CheckboxDefaults.colors(
                                            checkedColor = Primary,
                                        ),
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    SearchResultCard(
                                        productId = p.id,
                                        photoLoader = viewModel.photoLoader,
                                        name = p.name,
                                        sellPrice = p.sellPrice,
                                        stockQty = p.stockQty,
                                        spec = p.spec,
                                        brand = p.brand,
                                        sku = p.sku,
                                        barcode = p.barcode,
                                        unit = p.unit,
                                        category = CategoryMap.refine(p.name) ?: p.category,
                                        saleStatus = p.saleStatus,
                                        highlightKeyword = query,
                                        onClick = {
                                            if (isMultiSelect) {
                                                if (isSelected) selectedProducts.remove(itemKey) else selectedProducts[itemKey] = p
                                            } else {
                                                productDetailNavKey(p.barcode, p.sku)?.let(onOpenProduct)
                                            }
                                        },
                                        onPreviewImage = { bmp ->
                                            ScanFeedback.click(context)
                                            previewImage = Triple(p.id, p.name, bmp)
                                        },
                                        // 05 设计稿右侧「昨天出 3 件」（没卖过时两值均为 null → 整行不渲染）
                                        lastSaleDay = saleFreqOf(p, saleFreq)?.day,
                                        lastSaleQty = saleFreqOf(p, saleFreq)?.qty,
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }

                    if (isMultiSelect && selectedProducts.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(Design.PillRadius.dp),
                            color = Primary,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = Design.SpaceLg.dp)
                                .sxElevation(SxElevation.L4, shape = RoundedCornerShape(Design.PillRadius.dp), background = Primary)
                                .clickable {
                                    ScanFeedback.click(context)
                                    viewModel.enqueueBatchPrint(selectedProducts.values.toList()) { count ->
                                        android.widget.Toast.makeText(context, "已将 $count 款商品加入打印队列 ✓", android.widget.Toast.LENGTH_SHORT).show()
                                        selectedProducts.clear()
                                        isMultiSelect = false
                                    }
                                },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = Design.SpaceXl.dp, vertical = 10.dp),
                            ) {
                                Icon(Icons.Default.Print, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Design.SpaceSm.dp))
                                Text(
                                    text = "加入打印队列 (${selectedProducts.size} 款)",
                                    color = Color.White,
                                    fontSize = Design.FontMd.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }

        previewImage?.let { (pid, name, bmp) ->
            ImagePreviewDialog(
                productId = pid,
                name = name,
                initialBitmap = bmp,
                photoLoader = viewModel.photoLoader,
                onDismiss = { previewImage = null },
            )
        }
    }
}

/** v2 无边框填充 chips 配色：未选=浅灰填充/灰字，选中=橙 tint/橙字加粗 */
@Composable
private fun FilledChipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    containerColor = SurfaceSubtle,
    labelColor = TextSecondary,
    selectedContainerColor = PrimaryLight,
    selectedLabelColor = Primary,
)

/**
 * 查价页私有结果卡 v2（U1）：左类目瓷片/商品主图锚点 + 中信息列 + 右价格最大字。
 * 对标 1688 商品卡语言；按压整卡弹簧缩放（M3E）；有图时展示 68dp 清晰主图，无商品图时瓷片即视觉锚。
 * 仅查价页使用；共享 ProductItemView 未动，其余屏幕零外溢。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchResultCard(
    name: String,
    sellPrice: Double,
    modifier: Modifier = Modifier,
    productId: Long? = null,
    photoLoader: com.retaildemo.v2.data.repo.ShelfPhotoLoader? = null,
    stockQty: Double? = null,
    spec: String? = null,
    brand: String? = null,
    sku: String? = null,
    barcode: String? = null,
    unit: String? = null,
    category: String? = null,
    saleStatus: String? = "active",
    highlightKeyword: String? = null,
    /** 05 设计稿右侧「昨天出 3 件」；null = 没卖过，整行不渲染。 */
    lastSaleDay: String? = null,
    lastSaleQty: Double? = null,
    onClick: () -> Unit,
    onPreviewImage: ((android.graphics.Bitmap) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Design.PressScaleDown else 1f,
        animationSpec = spring(dampingRatio = Design.SpringDampingPress, stiffness = Design.SpringStiffnessPress),
        label = "searchCardPressScale",
    )
    val thumbBmp by produceState<android.graphics.Bitmap?>(initialValue = null, productId) {
        value = if (productId != null && photoLoader != null) {
            withContext(Dispatchers.IO) {
                runCatching { photoLoader.bitmapOf(productId, maxW = 320) }.getOrNull()
            }
        } else null
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .sxElevation(SxElevation.L2)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() },
        shape = RoundedCornerShape(SEARCH_CARD_RADIUS.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bmp = thumbBmp
            // 商品配图 / 类目瓷片锚点（有图展示 50dp 真实缩略图，微边框质感；无图展示类目首字瓷片）
            // 几何真源：`_reports/canvas_05.svg` 实测 50×50 rx=14（16 处），此前是 68dp/rx=10。
            Surface(
                shape = RoundedCornerShape(SEARCH_TILE_RADIUS.dp),
                color = if (bmp != null) SurfaceSubtle else PrimaryLight,
                border = if (bmp != null) BorderStroke(0.5.dp, Border) else null,
                modifier = Modifier
                    .size(SEARCH_TILE_SIZE.dp)
                    .then(
                        if (bmp != null && onPreviewImage != null) {
                            Modifier.clickable { onPreviewImage(bmp) }
                        } else Modifier
                    ),
            ) {
                if (bmp != null) {
                    Box(Modifier.fillMaxSize()) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = (category?.takeIf { it.isNotBlank() } ?: name).firstOrNull()?.toString() ?: "货",
                            color = Primary,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
            Spacer(Modifier.width(Design.SpaceMd.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = Design.SpaceSm.dp),
            ) {
                // 品名（命中词高亮，1 行）
                Text(
                    text = buildHighlightedText(name, highlightKeyword, Primary),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 元数据行：条码/SKU + 品牌（「·」连接，与设计稿「DN20 · 69234567…1024」同范式）
                //
                // 品牌为什么从 chips 行搬到这里：chips 行要容纳 spec + category + 库存徽章，
                // 实测再加品牌会超可用宽 **6.4dp**，被 FlowRow 挤到第二行 —— 整卡因此从
                // 设计稿的 95dp 涨到 127.6dp（`scripts/ui_geometry_diff.py` 实测 ΔH +32.6dp）。
                // 搬到本行后 chips 行只用 172dp / 可用 224dp，余量 52dp，长规格也不会再挤爆。
                val codeStr = barcode?.takeIf { it.isNotBlank() } ?: sku?.takeIf { it.isNotBlank() }?.let { "SKU:$it" }
                val metaStr = listOfNotNull(
                    codeStr?.takeIf { it.isNotBlank() },
                    brand?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (metaStr.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = buildHighlightedText(metaStr, highlightKeyword, Primary),
                            color = TextWeak,
                            fontSize = Design.FontXs.sp,
                            // ⚠️ 行高必须显式给：`Text(fontSize = …)` 只覆盖字号、**不覆盖继承的行高**，
                            // 行盒会按上层 style（bodyLarge 22sp）撑到 22.2dp —— 11sp 的字占 22dp 行，
                            // 单这一行就让整卡高 8dp。这就是 UI-REDESIGN-HANDOFF §1.3 记的同一个坑。
                            lineHeight = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 标签行：规格/分类/库存 chips（fix-round1 #8：可换行不截断，66% 重名靠规格区分）
                // ⚠️ 这一行**必须能单行放下**：一旦换行，卡片就比设计稿多一行（见上「品牌搬到元数据行」的注释）。
                // 加任何新 chip 前先按 `scripts/ui_geometry_diff.py` 复测卡片高度，别凭感觉加。
                // 注意这里**不含 brand**：品牌已在元数据行，若把它算进来会出现「只渲染一个空 Spacer」的空行
                val hasAnyTag = !spec.isNullOrBlank() || !category.isNullOrBlank() || stockQty != null
                if (hasAnyTag) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (!spec.isNullOrBlank()) {
                            val isSpecMatched = highlightKeyword?.isNotBlank() == true && spec.contains(highlightKeyword.trim(), ignoreCase = true)
                            Text(
                                text = buildHighlightedText(spec, highlightKeyword, Primary),
                                color = if (isSpecMatched) Primary else TextSecondary,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                fontWeight = if (isSpecMatched) FontWeight.SemiBold else FontWeight.Medium,
                                modifier = Modifier
                                    .background(SurfaceSubtle, RoundedCornerShape(SEARCH_CHIP_RADIUS.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        if (!category.isNullOrBlank()) {
                            Text(
                                text = category,
                                color = TextSecondary,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                modifier = Modifier
                                    .background(SurfaceSubtle, RoundedCornerShape(SEARCH_CHIP_RADIUS.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        if (stockQty != null) {
                            StockBadge(
                                qty = stockQty,
                                unit = unit.orEmpty(),
                                saleStatus = saleStatus ?: "active",
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(Design.SpaceXs.dp))
            // 价格最大字 + 销售频次（05 设计稿右侧：价格下方「昨天出 3 件」）
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                PriceText(
                    price = sellPrice,
                    integerSize = 24.sp,
                    symbolSize = 12.sp,
                    decimalSize = 13.sp,
                    showUnpricedText = true,
                )
                saleFreqText(lastSaleDay, lastSaleQty)?.let { freq ->
                    Spacer(Modifier.height(4.dp))
                    Text(text = freq, color = TextWeak, fontSize = 11.sp)
                }
            }
        }
    }
}

fun marginPct(sell: Double, cost: Double): Int? {
    if (cost <= 0.0 || sell <= 0.0) return null
    return (((sell - cost) / sell) * 100).toInt()
}

// =====================================================================================
// 05 查价页几何真源（取自 `_reports/canvas_05.svg` 的矢量实测矩阵，不是眼看）
//
// 为什么用**屏内常量**而不是 Design 令牌：这几个数与令牌**故意不同**——
// 结果卡 SVG 实测 rx=16，而全仓共用的 `Design.CardRadius` 是 14（02 稿的结果卡也是 14）。
// 改令牌会波及所有屏，所以只在这里钉住查价这一屏。
// 若日后画布统一了口径，应当反过来改令牌并删掉这几个常量。
// =====================================================================================
private const val SEARCH_CARD_RADIUS = 16   // 结果卡 358×97 rx=16
private const val SEARCH_TILE_SIZE = 50     // 商品瓷片 50×50
private const val SEARCH_TILE_RADIUS = 14   // 商品瓷片 rx=14
private const val SEARCH_CHIP_RADIUS = 6    // 卡内小徽章 34×20 rx=6

/**
 * 「昨天出 3 件」的日期前缀（05 查价页设计稿右侧）。
 *
 * 按**设备本地自然日**算，不用「距今小时数 ÷ 24」——后者会把「昨晚 23 点卖的货」
 * 在第二天凌晨显示成「今天」，店员对不上账。服务端下发的是门店本地自然日（YYYY-MM-DD），
 * 这里只做日期差。
 *
 * 返回 null = 服务端没下发日期 / 日期格式坏了（脏数据只容错，不臆造）。
 */
internal fun relativeDayLabel(dayIso: String?, today: LocalDate = LocalDate.now()): String? {
    val text = dayIso?.trim().orEmpty()
    if (text.isEmpty()) return null
    val day = runCatching { LocalDate.parse(text) }.getOrNull() ?: return null
    return when (val diff = ChronoUnit.DAYS.between(day, today)) {
        in Long.MIN_VALUE..0L -> "今天"   // 当天或异常的未来日期，都归「今天」，不显示负数天
        1L -> "昨天"
        else -> "${diff} 天前"
    }
}

/**
 * 结果卡右侧的销售频次文案，如「昨天出 3 件」「3 天前出 6 件」。
 *
 * 返回 null = 不该显示（无销售记录 / 件数为 0 的脏数据 / 日期坏了）——
 * 调用方按「null 就整行不渲染」处理，绝不显示「出 0 件」。
 */
internal fun saleFreqText(
    dayIso: String?,
    qty: Double?,
    today: LocalDate = LocalDate.now(),
): String? {
    if (qty == null || qty <= 0.0) return null
    val label = relativeDayLabel(dayIso, today) ?: return null
    val n = if (qty == floor(qty)) qty.toLong().toString() else String.format(Locale.US, "%.1f", qty)
    // 必须写 ${label}：中文是 Kotlin 合法标识符字符，"$label出" 会被解析成标识符 `label出`
    return "${label}出 $n 件"
}

/**
 * 按商品找频次：**sku 优先、退条码**——与后端 `sale_stats` 摊表时同一口径
 * （账单 item 的键是「sku 优先、缺则条码」，真实库两种格式都很杂，只认一路会 miss）。
 */
internal fun saleFreqOf(p: ProductEntity, freq: Map<String, SaleFreq>): SaleFreq? =
    p.sku?.trim()?.takeIf { it.isNotEmpty() }?.let { freq[it] }
        ?: p.barcode?.trim()?.takeIf { it.isNotEmpty() }?.let { freq[it] }

/**
 * 详情页导航键（U4 回归修复）：条码非空优先，否则回落 SKU（详情页按条码/SKU 双等值解析）。
 * 上游全集 12631 款中 2781 款无条码（在售 2664），此前条码为空时点击静默无效。
 * 返回 null = 条码与 SKU 双空，调用方不跳转。
 */
internal fun productDetailNavKey(barcode: String?, sku: String?): String? =
    barcode?.trim()?.takeIf { it.isNotEmpty() } ?: sku?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 查价页轻量商品实物大图预览弹窗：
 * 点击缩略图即刻大图核对（优先展示列表现有位图，后台异步拉取 1280px 高清原图）。
 */
@Composable
private fun ImagePreviewDialog(
    productId: Long,
    name: String,
    initialBitmap: android.graphics.Bitmap,
    photoLoader: com.retaildemo.v2.data.repo.ShelfPhotoLoader,
    onDismiss: () -> Unit,
) {
    val fullBmp by produceState(initialValue = initialBitmap, productId) {
        val hq = withContext(Dispatchers.IO) {
            runCatching { photoLoader.bitmapOf(productId, maxW = 1280) }.getOrNull()
        }
        if (hq != null) value = hq
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Image(
                    bitmap = fullBmp.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(12f, fill = false)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "点击任意位置关闭",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}
