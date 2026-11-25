package com.retaildemo.v2.ui.pages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.retaildemo.v2.data.repo.PrinterConfig
import com.retaildemo.v2.print.LabelTemplates
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.TopBarStyle
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.BorderStrong
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.LabelPreviewInk
import com.retaildemo.v2.ui.theme.LabelPreviewInkBody
import com.retaildemo.v2.ui.theme.LabelPreviewInkStrong
import com.retaildemo.v2.ui.theme.LabelPreviewInkWeak
import com.retaildemo.v2.ui.theme.LabelPreviewPaper
import com.retaildemo.v2.ui.theme.LabelPreviewPaperEdge
import com.retaildemo.v2.ui.theme.LabelPreviewRule
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.viewmodel.PrinterManageViewModel

/** 打印机管理：蓝牙配对设备 / 网络标签机发现 / 默认打印机 / 实时价签物理模拟器。 */
@Composable
fun PrinterManagePage(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit = {},
    viewModel: PrinterManageViewModel = hiltViewModel(),
) {
    val bluetoothDevices by viewModel.bluetoothDevices.collectAsState()
    val networkPrinters by viewModel.networkPrinters.collectAsState()
    val defaultPrinter by viewModel.defaultPrinter.collectAsState()
    val secondPrinter by viewModel.secondPrinter.collectAsState()
    val message by viewModel.message.collectAsState()
    val template by viewModel.template.collectAsState()
    val context = LocalContext.current
    var subnet by remember { mutableStateOf("192.168.1") }

    // Android 12+ 读系统已配对设备必须运行时授权，不申请时列表永远为空
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    // 从系统设置（配对打印机/授予权限）返回时自动刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
        ) {
            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    val btGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    Scaffold(
        containerColor = Design.PageBg,
        topBar = {
            retaildemoTopBar(
                title = "打印机管理",
                subtitle = "蓝牙 TSPL 直发 · 局域网网络标签机",
                onBack = onBack,
                actions = {
                    // U2 浅色顶栏（白底深字）后，这里的白字会隐身——2026-09-10 真机反馈「打印队列没有入口」即此因
                    TextButton(onClick = onOpenQueue) {
                        Text("打印队列", color = Primary, fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            // 稿（画布 20 打印与价签）：卡 y=118(h72) / 238(h63) / 309(h63)。
            // 像素三点法实测（20_printer.png, dpr2）：卡白止 300.0，边框 300~301，页底 301~309，
            // 下卡白起 310 → **卡间距 8**（原 14.dp 偏大 6dp，一屏少放近一整张卡）。
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
        ) {
            // 操作提示横幅
            message?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PrimaryLight,
                    border = BorderStroke(0.5.dp, Primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Print, null, tint = Primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(msg, fontSize = 13.sp, color = Primary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // 当前默认打印机 Hero Card
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
                border = BorderStroke(1.dp, if (defaultPrinter != null) Success.copy(alpha = 0.4f) else Border),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (defaultPrinter != null) SuccessLight else SurfaceSubtle,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (defaultPrinter?.kind == "bluetooth") Icons.Default.Bluetooth else Icons.Default.Print,
                                    contentDescription = null,
                                    tint = if (defaultPrinter != null) Success else TextWeak,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Spacer(Modifier.width(10.dp))

                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (defaultPrinter != null) "已设默认打印机" else "未设置默认打印机",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMain,
                                )
                                Spacer(Modifier.width(6.dp))
                                if (defaultPrinter != null) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = SuccessLight,
                                    ) {
                                        Text(
                                            "在线就绪",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Success,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                defaultPrinter?.let {
                                    "${it.name.ifBlank { "打印设备" }} (${it.address}${if (it.kind == "network") ":${it.port}" else ""})"
                                } ?: "请在下方蓝牙或网络设备列表中指定一台默认打印机",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (defaultPrinter != null) {
                            Button(
                                onClick = { viewModel.testPrint() },
                                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(38.dp),
                            ) {
                                Text("测试打印", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 备用打印机卡片（第二打印机）
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
                border = BorderStroke(0.5.dp, if (secondPrinter != null) Primary.copy(alpha = 0.3f) else Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(14.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("备用打印机（第二打印机）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                            if (secondPrinter != null) {
                                Spacer(Modifier.width(6.dp))
                                Surface(shape = RoundedCornerShape(4.dp), color = PrimaryLight) {
                                    Text(
                                        "已就绪",
                                        color = Primary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = secondPrinter?.let { "${it.name.ifBlank { "打印设备" }} (${it.address})" }
                                ?: "未配置备用机（可在下方设备列表点击「设为备用」）",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (secondPrinter != null) {
                        TextButton(
                            onClick = {
                                val s = secondPrinter ?: return@TextButton
                                val d = defaultPrinter
                                viewModel.setDefault(s)
                                viewModel.setSecond(d)
                            },
                        ) {
                            Text("切换为主机", fontSize = 12.sp, color = Primary)
                        }
                        TextButton(onClick = { viewModel.setSecond(null) }) {
                            Text("清除", fontSize = 12.sp, color = Danger)
                        }
                    }
                }
            }

            // 便携机参数适配卡片
            if (defaultPrinter?.kind == "bluetooth" || secondPrinter?.kind == "bluetooth") {
                val currentWidth = defaultPrinter?.paperWidth ?: 40
                val currentDensity = defaultPrinter?.density ?: 8
                Card(
                    shape = RoundedCornerShape(Design.CardRadius.dp),
                    colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("便携打印机参数化适配", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        Spacer(Modifier.height(8.dp))

                        // 指令集选择（TSPL=标签机主流；CPCL=便携面单机常见）
                        Text("指令集", fontSize = 11.sp, color = TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "tspl" to "TSPL·标签机",
                                "cpcl" to "CPCL·面单机",
                            ).forEach { (cs, csLabel) ->
                                val selected = (defaultPrinter?.commandSet ?: "tspl").equals(cs, true)
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.updateCommandSet(cs) },
                                    label = { Text(csLabel, fontSize = 11.sp) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = PrimaryLight,
                                        selectedLabelColor = Primary,
                                    ),
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 纸规选择（宽×高 mm，F11 纸规自适应：竖版纵向流/横版左右分栏）
                        val currentHeight = defaultPrinter?.paperHeight ?: 0
                        Text("出签纸规 (宽×高 mm)", fontSize = 11.sp, color = TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        LabelTemplates.PRESET_SIZES.chunked(4).forEachIndexed { idx, chunk ->
                            if (idx > 0) Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                chunk.forEach { sz ->
                                    val selected = currentWidth == sz.widthMm &&
                                        (
                                            currentHeight == sz.heightMm ||
                                                (currentHeight == 0 && LabelTemplates.heightFor(sz.widthMm) == sz.heightMm)
                                            )
                                    FilterChip(
                                        selected = selected,
                                        onClick = { viewModel.updateLabelSize(sz.widthMm, sz.heightMm) },
                                        label = { Text(sz.toString(), fontSize = 11.sp) },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = PrimaryLight,
                                            selectedLabelColor = Primary,
                                        ),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 打印偏移容错（边缘防误触思路：整版平移 + 死区夹紧，配再大也不出纸）
                        val offX = defaultPrinter?.offsetXMm ?: 0.0
                        val offY = defaultPrinter?.offsetYMm ?: 0.0
                        Text("打印偏移容错 (mm，0.5 步进)", fontSize = 11.sp, color = TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("X 左右: %+.1f".format(offX), fontSize = 11.sp, color = TextMain, modifier = Modifier.width(86.dp))
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OffsetStepButton("◀") { viewModel.updateOffset(offX - 0.5, offY) }
                                OffsetStepButton("▶") { viewModel.updateOffset(offX + 0.5, offY) }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Y 上下: %+.1f".format(offY), fontSize = 11.sp, color = TextMain, modifier = Modifier.width(86.dp))
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OffsetStepButton("▲") { viewModel.updateOffset(offX, offY - 0.5) }
                                OffsetStepButton("▼") { viewModel.updateOffset(offX, offY + 0.5) }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "内容整体平移补齐机器偏差；偏移量自动夹紧到纸内安全区，永不贴边/出纸。X 正=右移，Y 正=下移。",
                            fontSize = 10.sp, color = TextWeak, lineHeight = 14.sp,
                        )

                        Spacer(Modifier.height(10.dp))

                        // 浓度调节
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("打印浓度: $currentDensity (1~15)", fontSize = 11.sp, color = TextSecondary)
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(4 to "淡", 8 to "标准", 12 to "深").forEach { (d, dLabel) ->
                                    val sel = currentDensity == d
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (sel) PrimaryLight else SurfaceSubtle,
                                        border = BorderStroke(0.5.dp, if (sel) Primary else Border),
                                        modifier = Modifier.clickable { viewModel.updateDensity(d) },
                                    ) {
                                        Text(
                                            text = "$dLabel($d)",
                                            fontSize = 11.sp,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sel) Primary else TextSecondary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 价签模板配置与实时模拟器
            Text(
                "可配置价签模板（蓝牙与网络同步生效）",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain,
            )
            val previewSize = defaultPrinter?.let {
                LabelTemplates.LabelSize(
                    it.paperWidth,
                    if (it.paperHeight > 0) it.paperHeight else LabelTemplates.heightFor(it.paperWidth),
                )
            } ?: LabelTemplates.LabelSize(40, 30)
            PriceTagTemplateEditor(viewModel = viewModel, template = template, labelSize = previewSize)

            // 蓝牙打印机
            Text(
                "蓝牙标签打印机（系统已配对）",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain,
            )
            if (bluetoothDevices.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Design.CardSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (btGranted) {
                            "未检测到系统已配对的蓝牙设备。请打开系统设置→蓝牙，配对打印机（如汉印、芝柯、佳博等）后返回本页自动刷新；所有已配对设备都会列出，疑似打印机排在前面。"
                        } else {
                            "缺少「附近的设备」蓝牙权限，读不到系统已配对的打印机。请在系统设置→应用→示例零售→权限里允许后返回本页。"
                        },
                        fontSize = 12.sp,
                        color = TextWeak,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else {
                bluetoothDevices.forEach { device ->
                    PrinterRowItem(
                        name = device.name,
                        subtitle = if (device.likelyPrinter) "疑似标签打印机 · MAC 地址: ${device.address}"
                        else "MAC 地址: ${device.address}",
                        icon = Icons.Default.Bluetooth,
                        isDefault = defaultPrinter?.address == device.address,
                        isSecond = secondPrinter?.address == device.address,
                        onSet = {
                            // BTP-P* 便携面单机=CPCL（2026-09-12 BTP-P39 PLUS 真机实证：发 TSPL 被当文本打出）
                            viewModel.setDefault(
                                PrinterConfig(
                                    "bluetooth", device.name, device.address,
                                    commandSet = LabelTemplates.defaultCommandSetFor(device.name),
                                ),
                            )
                            ScanFeedback.tick(context)
                        },
                        onSetSecond = {
                            viewModel.setSecond(
                                PrinterConfig(
                                    "bluetooth", device.name, device.address,
                                    commandSet = LabelTemplates.defaultCommandSetFor(device.name),
                                ),
                            )
                            ScanFeedback.tick(context)
                        },
                        onTest = { viewModel.testPrint() },
                    )
                }
            }

            // 网络标签机
            Text(
                "网络标签机（局域网 9100 端口）",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain,
            )
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(10.dp),
                ) {
                    OutlinedTextField(
                        value = subnet,
                        onValueChange = { subnet = it },
                        placeholder = { Text("网段，如 192.168.1", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Router, null, tint = Primary) },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = BorderStrong,
                            focusedContainerColor = Design.PageBg,
                            unfocusedContainerColor = Design.PageBg,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.discover(subnet) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("扫描发现", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (networkPrinters.isNotEmpty()) {
                networkPrinters.forEach { printer ->
                    PrinterRowItem(
                        name = "网络标签打印机",
                        subtitle = "${printer.first}:${printer.second}",
                        icon = Icons.Default.Print,
                        isDefault = defaultPrinter?.address == printer.first,
                        isSecond = secondPrinter?.address == printer.first,
                        onSet = {
                            viewModel.setDefault(PrinterConfig("network", "网络标签机 ${printer.first}", printer.first, printer.second))
                            ScanFeedback.tick(context)
                        },
                        onSetSecond = {
                            viewModel.setSecond(PrinterConfig("network", "网络标签机 ${printer.first}", printer.first, printer.second))
                            ScanFeedback.tick(context)
                        },
                        onTest = { viewModel.testPrint() },
                    )
                }
            }
        }
    }
}

/**
 * 价签模板配置面板 + 1:1 物理质感仿真预览器
 */
@Composable
private fun PriceTagTemplateEditor(
    viewModel: PrinterManageViewModel,
    template: LabelTemplates.PriceTagTemplate,
    labelSize: LabelTemplates.LabelSize,
) {
    var storeName by remember(template) { mutableStateOf(template.storeName) }
    var footer by remember(template) { mutableStateOf(template.footer) }
    var showSpec by remember(template) { mutableStateOf(template.showSpec) }
    var showSku by remember(template) { mutableStateOf(template.showSku) }
    var codeStyle by remember(template) { mutableStateOf(template.codeStyle) }
    var priceScale by remember(template) { mutableStateOf(template.priceScale) }
    val context = LocalContext.current
    val isPortrait = labelSize.heightMm > labelSize.widthMm

    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // === 1:1 物理质感仿真价签预览 ===
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "价签效果实时预览",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = SurfaceSubtle,
                ) {
                    Text(
                        "${labelSize.widthMm}×${labelSize.heightMm}mm · ${if (isPortrait) "竖版" else "横版"}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // 预览展示区：居中固定高度，按真实纸规比例呈现实体贴纸形态
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = LabelPreviewPaper, // 米白不干胶底色
                    border = BorderStroke(1.dp, LabelPreviewPaperEdge),
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(
                            labelSize.widthMm.toFloat() / labelSize.heightMm.toFloat(),
                            matchHeightConstraintsFirst = true,
                        ),
                ) {
                    if (codeStyle == "barcode") {
                        // 一维条码风格：品名规格在上方，价格+SKU居中，条码横贯底置全宽
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                if (storeName.isNotBlank()) {
                                    Text(
                                        storeName,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabelPreviewInkStrong,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    "304不锈钢外六角螺栓",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.Black,
                                    maxLines = 2,
                                    lineHeight = 14.sp,
                                )
                                if (showSpec) {
                                    Text(
                                        "M8×35 (全螺纹)",
                                        fontSize = 9.sp,
                                        color = LabelPreviewInk,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "¥ 12.50",
                                    fontSize = when (priceScale) {
                                        4 -> 22.sp
                                        2 -> 16.sp
                                        else -> 19.sp
                                    },
                                    fontWeight = FontWeight.Black,
                                    color = Color.Black,
                                )
                                if (showSku) {
                                    Text(
                                        "692048591024",
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = LabelPreviewInkBody,
                                    )
                                }
                            }
                            // 仿真一维条码：横贯底置
                            Surface(
                                color = Color.White,
                                border = BorderStroke(0.5.dp, LabelPreviewRule),
                                shape = RoundedCornerShape(2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(26.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "||||| ||| |||| | ||||| | ||||",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        letterSpacing = 2.sp,
                                        color = Color.Black,
                                    )
                                }
                            }
                        }
                    } else if (isPortrait) {
                        // 二维码风格·竖版（如 30×40）：纵向流全居中
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (storeName.isNotBlank()) {
                                    Text(
                                        storeName,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabelPreviewInkStrong,
                                        maxLines = 1,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                Text(
                                    "304不锈钢外六角螺栓",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.Black,
                                    maxLines = 2,
                                    lineHeight = 13.sp,
                                    textAlign = TextAlign.Center,
                                )
                                if (showSpec) {
                                    Text(
                                        "M8×35 (全螺纹)",
                                        fontSize = 8.sp,
                                        color = LabelPreviewInk,
                                        maxLines = 1,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                Text(
                                    "¥ 12.50",
                                    fontSize = when (priceScale) {
                                        4 -> 18.sp
                                        2 -> 14.sp
                                        else -> 16.sp
                                    },
                                    fontWeight = FontWeight.Black,
                                    color = Color.Black,
                                    textAlign = TextAlign.Center,
                                )
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = Color.Black,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                            Surface(
                                color = Color.White,
                                border = BorderStroke(0.5.dp, LabelPreviewRule),
                                shape = RoundedCornerShape(3.dp),
                                modifier = Modifier.size(46.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.QrCode,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(38.dp),
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (showSku) {
                                    Text(
                                        "692048591024",
                                        fontSize = 7.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = LabelPreviewInkBody,
                                        maxLines = 1,
                                    )
                                }
                                if (footer.isNotBlank()) {
                                    Text(footer, fontSize = 7.sp, color = LabelPreviewInkWeak, maxLines = 1)
                                }
                            }
                        }
                    } else {
                        // 二维码风格·横版（如 40×30、50×30、60×40）：左品名+大价格，右大二维码+SKU
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    if (storeName.isNotBlank()) {
                                        Text(
                                            storeName,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabelPreviewInkStrong,
                                            maxLines = 1,
                                        )
                                    }
                                    Text(
                                        "304不锈钢外六角螺栓",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Black,
                                        maxLines = 2,
                                        lineHeight = 14.sp,
                                    )
                                    if (showSpec) {
                                        Text(
                                            "M8×35 (全螺纹)",
                                            fontSize = 9.sp,
                                            color = LabelPreviewInk,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Text(
                                    "¥ 12.50",
                                    fontSize = when (priceScale) {
                                        4 -> 22.sp
                                        2 -> 16.sp
                                        else -> 19.sp
                                    },
                                    fontWeight = FontWeight.Black,
                                    color = Color.Black,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Surface(
                                    color = Color.White,
                                    border = BorderStroke(0.5.dp, LabelPreviewRule),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.size(60.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.QrCode,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(50.dp),
                                        )
                                    }
                                }
                                if (showSku) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "692048591024",
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = LabelPreviewInkBody,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // === 模板参数配置 ===
            OutlinedTextField(
                value = storeName,
                onValueChange = { storeName = it },
                label = { Text("价签店名（顶部抬头）") },
                placeholder = { Text("如：示例零售建材总汇") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = BorderStrong,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = footer,
                onValueChange = { footer = it },
                label = { Text("价签页脚（底部标注）") },
                placeholder = { Text("如：正品保障 · 凭签质保") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = BorderStrong,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示商品规格", fontSize = 13.sp, color = TextMain, modifier = Modifier.weight(1f))
                Switch(
                    checked = showSpec,
                    onCheckedChange = { showSpec = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Primary),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示商品 SKU/编码", fontSize = 13.sp, color = TextMain, modifier = Modifier.weight(1f))
                Switch(
                    checked = showSku,
                    onCheckedChange = { showSku = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Primary),
                )
            }

            Spacer(Modifier.height(6.dp))

            Text("码图样式（管家婆扫描枪直扫识别）", fontSize = 12.sp, color = TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(
                    selected = codeStyle == "qr",
                    onClick = { codeStyle = "qr" },
                    label = { Text("二维码 (QR)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryLight,
                        selectedLabelColor = Primary,
                    ),
                )
                FilterChip(
                    selected = codeStyle == "barcode",
                    onClick = { codeStyle = "barcode" },
                    label = { Text("一维条码 (Code 128)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryLight,
                        selectedLabelColor = Primary,
                    ),
                )
            }

            Spacer(Modifier.height(6.dp))

            Text("价格字号缩放比例", fontSize = 12.sp, color = TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(2 to "标准 (2×)", 3 to "放大 (3× 推荐)", 4 to "醒目特大 (4×)").forEach { (scale, label) ->
                    FilterChip(
                        selected = priceScale == scale,
                        onClick = { priceScale = scale },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryLight,
                            selectedLabelColor = Primary,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    viewModel.saveTemplate(
                        LabelTemplates.PriceTagTemplate(
                            storeName = storeName.trim(),
                            showSpec = showSpec,
                            showSku = showSku,
                            codeStyle = codeStyle,
                            priceScale = priceScale,
                            footer = footer.trim(),
                        ),
                    )
                    ScanFeedback.success(context)
                },
                shape = RoundedCornerShape(Design.ButtonRadius.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Design.MinTouchTarget.dp),
            ) {
                Text("保存价签模板配置", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun PrinterRowItem(
    name: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isDefault: Boolean,
    isSecond: Boolean = false,
    onSet: () -> Unit,
    onSetSecond: (() -> Unit)? = null,
    onTest: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
        border = BorderStroke(
            0.5.dp,
            when {
                isDefault -> Success.copy(alpha = 0.5f)
                isSecond -> Primary.copy(alpha = 0.5f)
                else -> Border
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    isDefault -> SuccessLight
                    isSecond -> PrimaryLight
                    else -> SurfaceSubtle
                },
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = when {
                            isDefault -> Success
                            isSecond -> Primary
                            else -> TextSecondary
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isDefault) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = SuccessLight) {
                            Text(
                                "默认",
                                color = Success,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    } else if (isSecond) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = PrimaryLight) {
                            Text(
                                "备用",
                                color = Primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 11.sp, color = TextWeak)
            }

            Spacer(Modifier.width(8.dp))

            if (!isDefault) {
                OutlinedButton(
                    onClick = onSet,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, BorderStrong),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text(if (isSecond) "升为主机" else "设为默认", fontSize = 11.sp, color = TextMain)
                }
                Spacer(Modifier.width(4.dp))
                if (!isSecond && onSetSecond != null) {
                    OutlinedButton(
                        onClick = onSetSecond,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.5.dp, Primary.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text("设为备用", fontSize = 11.sp, color = Primary)
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }

            Button(
                onClick = onTest,
                colors = ButtonDefaults.buttonColors(containerColor = if (isDefault) Primary else SurfaceSubtle),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text("测试", fontSize = 11.sp, color = if (isDefault) Color.White else TextMain)
            }
        }
    }
}

/** 偏移校准步进按钮（小尺寸 +/-，点击即生效并落盘到默认打印机配置）。 */
@Composable
private fun OffsetStepButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = SurfaceSubtle,
        border = BorderStroke(0.5.dp, Border),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = TextMain,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}
