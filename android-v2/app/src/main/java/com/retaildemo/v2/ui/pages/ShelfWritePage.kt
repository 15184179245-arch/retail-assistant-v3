package com.retaildemo.v2.ui.pages

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.SxElevation
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
import com.retaildemo.v2.nfc.ShelfNfc
import com.retaildemo.v2.nfc.ShelfTagWriter
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.theme.Card
import com.retaildemo.v2.ui.theme.Cyan
import com.retaildemo.v2.ui.theme.CyanLight
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.DangerLight
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import com.retaildemo.v2.ui.viewmodel.ShelfViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private data class WriteTarget(
    val label: String,
    val path: String,
    val tagId: String,
)

/** 结果面板的四态配色（SEIS：状态必须一眼可辨，不靠读小字）。 */
private enum class Tone { WAIT, DOING, INFO, GOOD, WARN, BAD }

private data class WriteUi(
    val tone: Tone = Tone.WAIT,
    val place: String = "",
    val title: String = "",
    val detail: String = "",
)

/** 本次会话的写入流水 —— 「写入成功」要留下看得见、对得上的凭证。 */
private data class WriteRecord(
    val time: String,
    val place: String,
    val code: String,
    val ok: Boolean,
    val note: String,
)

private val TimeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/**
 * I20 写贴向导：**写稳定 tagId，不写名字**；老板 2026-09-14 三连反馈后重做反馈与覆盖写：
 *
 * 1. **写完必有大结果**：成功/已一致/待复核/被拒/失败 五态大字面板（配色 + 语义音效 + 震动），
 *    不再是底部一行小字；每次写入进「本次写入流水」留凭证（读回值直接展示）。
 * 2. **覆盖写入**：旧内容先归一化判定 —— 同一位置免重写，不同内容直接覆盖，
 *    只有物理锁死的贴才拒绝（且把贴内旧内容讲清楚）。引擎见 [ShelfTagWriter]。
 * 3. **先贴后建防手滑**：同一个名字只建档一次 —— 连续两次碰到贴不会悄悄建出两个货架，
 *    贴也就不会指到没人认领的那个（「写入的和读取的不一致」的另一条成因）。
 *
 * 无 NFC 硬件时全程降级为提示，不崩（NFC 采购/试贴属老板领地，代码零阻塞）。
 */
@Composable
fun ShelfWritePage(
    onBack: () -> Unit,
    viewModel: ShelfViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val adapter = remember { runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull() }
    val tree by viewModel.tree.collectAsState()

    val targets = remember(tree) {
        buildList {
            tree.forEach { shelf ->
                add(WriteTarget(shelf.name, "", shelf.tagId))
                shelf.levels.forEach { level ->
                    add(WriteTarget(level.name, shelf.name, level.tagId))
                    level.slots.forEach { slot ->
                        add(WriteTarget(slot.name, "${shelf.name} · ${level.name}", slot.tagId))
                    }
                }
            }
        }
    }

    var selected by remember { mutableStateOf<WriteTarget?>(null) }
    var createMode by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var ui by remember { mutableStateOf(WriteUi()) }
    var history by remember { mutableStateOf(listOf<WriteRecord>()) }

    val payload = when {
        !createMode -> selected?.tagId?.let { ShelfNfc.encodeTagPayload(it) }
        else -> null // 先贴后建：写入时机在贴上、名字起好之后
    }

    fun placeOf(t: WriteTarget?): String = when {
        t == null -> ""
        t.path.isEmpty() -> t.label
        else -> "${t.path} · ${t.label}"
    }

    // 面板随「选位置/切模式」回到待写状态（写入中不打断）
    LaunchedEffect(selected?.tagId, createMode) {
        val place = if (createMode) newName.trim() else placeOf(selected)
        ui = WriteUi(
            tone = Tone.WAIT,
            place = place,
            title = when {
                adapter == null -> "本机没有 NFC"
                createMode -> if (newName.isBlank()) "先起名字，再碰贴" else "碰贴：建档并写入"
                selected == null -> "先在上面选一个位置"
                else -> "碰贴即写入「${place}」"
            },
            detail = when {
                adapter == null -> "可用第三方标签工具把 SX:<码> 写进标签（码见下方列表）"
                createMode -> "名字完全自定义；同一个名字只建档一次，防手滑建重"
                else -> "旧内容会被直接覆盖；锁死的贴会当场告诉你"
            },
        )
    }

    // 防抖门禁：只有对「同一张贴 + 同一个目标位置」在 1.2 秒内重复触碰时才忽略（防手抖连发）；
    // 若换了目标位置、或写入未成功，允许立刻重碰覆盖写入。
    val gate = remember { Any() }
    val lastSuccess = remember { AtomicReference<Triple<String, String, Long>?>(null) } // uid, targetKey, timestamp
    val writing = remember { AtomicBoolean(false) }

    // ReaderMode 直回调：标签靠近即写，页面离开即关
    DisposableEffect(adapter, payload, createMode, newName) {
        // 先贴后建：本次向导会话里已为哪个名字建过档（见 KDoc 第 3 条）
        var createdFor: String? = null
        val callback = NfcAdapter.ReaderCallback { tag: Tag ->
            val uid = (tag.id ?: ByteArray(0)).joinToString("") { "%02X".format(it) }
            val now = android.os.SystemClock.elapsedRealtime()
            val targetKey = if (createMode) "CREATE:${newName.trim()}" else "EXIST:${payload.orEmpty()}"
            var proceed = false
            synchronized(gate) {
                val prev = lastSuccess.get()
                val isRecentSameSuccess = prev != null &&
                    prev.first == uid &&
                    prev.second == targetKey &&
                    (now - prev.third) < 1_200L
                if (!isRecentSameSuccess && writing.compareAndSet(false, true)) {
                    proceed = true
                }
            }
            if (!proceed) return@ReaderCallback

            val useCreate = createMode
            val name = newName.trim()
            val placeName = if (useCreate) name else placeOf(selected)
            var codeShown = payload ?: ""
            activity?.runOnUiThread {
                ui = WriteUi(Tone.DOING, placeName, "正在写贴…", "贴保持按在手机背面别动，写完自动核对")
            }

            val result: ShelfTagWriter.Result = when {
                useCreate && name.isEmpty() -> ShelfTagWriter.Result(
                    ShelfTagWriter.Status.REJECTED, "先起名字，再碰贴",
                    "在下面的输入框给这个位置起个名，再贴一次贴。", null,
                )
                useCreate && createdFor == name -> ShelfTagWriter.Result(
                    ShelfTagWriter.Status.ALREADY, "「$name」刚才已经建好写过一张了",
                    "要建下一张贴：换个名字，或切到「写已有位置」。", null,
                )
                useCreate -> {
                    val tagId = runCatching { createShelfBlocking(viewModel, name) }.getOrNull()
                    if (tagId == null) {
                        ShelfTagWriter.Result(
                            ShelfTagWriter.Status.FAILED, "建档失败",
                            "本地写入没成功，没有动这张贴 —— 重试一次，或先建好货架再用「写已有位置」。", null,
                        )
                    } else {
                        createdFor = name
                        codeShown = ShelfNfc.encodeTagPayload(tagId).orEmpty()
                        ShelfTagWriter.write(tag, ShelfNfc.encodeTagPayload(tagId))
                    }
                }
                payload != null -> ShelfTagWriter.write(tag, payload)
                else -> ShelfTagWriter.Result(
                    ShelfTagWriter.Status.REJECTED, "先在上面选一个位置",
                    "或在顶部切到「先贴后建」现场起名字。", null,
                )
            }
            writing.set(false)

            val rowOk = result.status == ShelfTagWriter.Status.OK ||
                result.status == ShelfTagWriter.Status.ALREADY
            if (rowOk) {
                lastSuccess.set(Triple(uid, targetKey, android.os.SystemClock.elapsedRealtime()))
            }
            activity?.runOnUiThread {
                ui = WriteUi(result.status.toTone(), placeName, result.title, result.detail)
                history = listOf(
                    WriteRecord(TimeFmt.format(Date()), placeName.ifBlank { "新位置" }, codeShown, rowOk, result.title),
                ) + history
                if (history.size > 6) history = history.take(6)
                ScanFeedback.play(context, result.status.toSemantics())
            }
        }
        try {
            adapter?.enableReaderMode(
                activity,
                callback,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null,
            )
        } catch (_: Exception) {
        }
        onDispose {
            try {
                adapter?.disableReaderMode(activity)
            } catch (_: Exception) {
            }
        }
    }

    Scaffold(
        topBar = {
            retaildemoTopBar(
                title = "写贴向导",
                subtitle = "写的是稳定码，改名不用重贴",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Design.SpaceLg.dp),
        ) {
            Spacer(Modifier.height(Design.SpaceSm.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
                ModeChip("写已有位置", !createMode, Modifier.weight(1f)) { createMode = false }
                ModeChip("先贴后建", createMode, Modifier.weight(1f)) { createMode = true }
            }

            Spacer(Modifier.height(Design.SpaceMd.dp))

            if (createMode) {
                Column(verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp)) {
                    Text(
                        "先给这个位置起名，然后碰贴 —— 建档和写贴一次完成。",
                        fontSize = Design.FontSm.sp,
                        color = TextSecondary,
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("位置名字（完全自定义，如「东墙那排」）") },
                    )
                    if (newName.isNotBlank()) {
                        Text(
                            "碰贴时会新建一个货架「${newName.trim()}」并把它的稳定码写进这张贴。",
                            fontSize = Design.FontXs.sp,
                            color = TextWeak,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                Text(
                    if (targets.isEmpty()) "还没有任何位置，先用「先贴后建」现场建一个。" else "选一个位置：",
                    fontSize = Design.FontSm.sp,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(Design.SpaceSm.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                ) {
                    items(targets, key = { it.tagId }) { target ->
                        val on = selected?.tagId == target.tagId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sxElevation(SxElevation.L2)
                                .clickable { selected = target },
                            colors = CardDefaults.cardColors(containerColor = if (on) PrimaryLight else Card),
                            shape = RoundedCornerShape(Design.CardRadius.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = Design.SpaceMd.dp, vertical = Design.SpaceMd.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        target.label,
                                        fontSize = Design.FontMdPlus.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextMain,
                                    )
                                    Text(
                                        listOfNotNull(
                                            target.path.ifBlank { null },
                                            "码 ${target.tagId}",
                                        ).joinToString(" · "),
                                        fontSize = Design.FontXs.sp,
                                        color = TextWeak,
                                    )
                                }
                                if (on) {
                                    Text("已选", fontSize = Design.FontSm.sp, color = Primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Design.SpaceMd.dp))

            // ============ 写贴结果面板：五态大字 + 凭证（SEIS：状态一眼可辨） ============
            val (bg, accent, glyph) = when (ui.tone) {
                Tone.WAIT -> Triple(SurfaceSubtle, TextWeak, "○")
                Tone.DOING -> Triple(WarningLight, Warning, "…")
                Tone.INFO -> Triple(CyanLight, Cyan, "≡")
                Tone.GOOD -> Triple(SuccessLight, Success, "✓")
                Tone.WARN -> Triple(WarningLight, Warning, "!")
                Tone.BAD -> Triple(DangerLight, Danger, "✕")
            }
            Surface(
                color = bg,
                shape = RoundedCornerShape(Design.CardRadius.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .padding(Design.SpaceLg.dp)
                        .let { if (ui.tone == Tone.GOOD || ui.tone == Tone.BAD) it.padding(bottom = Design.SpaceXs.dp) else it },
                    verticalArrangement = Arrangement.spacedBy(Design.SpaceXs.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.SpaceMd.dp)) {
                        Text(glyph, fontSize = Design.FontDisplay.sp, fontWeight = FontWeight.Black, color = accent)
                        Column(Modifier.weight(1f)) {
                            if (ui.place.isNotBlank()) {
                                Text(ui.place, fontSize = Design.FontXs.sp, color = TextSecondary)
                            }
                            Text(
                                ui.title,
                                fontSize = Design.FontLg.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (ui.tone == Tone.WAIT) TextSecondary else TextMain,
                            )
                        }
                    }
                    if (ui.detail.isNotBlank()) {
                        Text(ui.detail, fontSize = Design.FontSm.sp, color = TextSecondary)
                    }
                    if (!createMode && payload != null) {
                        Text("将写入：$payload", fontSize = Design.FontXs.sp, color = TextWeak)
                    }

                    if (history.isNotEmpty()) {
                        Spacer(Modifier.height(Design.SpaceXs.dp))
                        Text("本次写入流水", fontSize = Design.FontXs.sp, color = TextWeak)
                        history.take(4).forEach { rec ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Design.SpaceSm.dp),
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                Spacer(
                                    Modifier
                                        .width(8.dp)
                                        .height(8.dp)
                                        .background(if (rec.ok) Success else Danger, CircleShape),
                                )
                                Text(rec.time, fontSize = Design.FontXs.sp, color = TextWeak)
                                Text(rec.place, fontSize = Design.FontXs.sp, color = TextMain, modifier = Modifier.weight(1f))
                                Text(
                                    if (rec.ok) "写入 ${rec.code}" else rec.note,
                                    fontSize = Design.FontXs.sp,
                                    color = if (rec.ok) TextSecondary else Danger,
                                )
                            }
                        }
                    }
                }
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回货架", fontSize = Design.FontSm.sp, color = TextWeak)
            }
            Spacer(Modifier.height(Design.SpaceMd.dp))
        }
    }
}

/** 写贴结果 → 面板状态（SEIS：语义事件表口径，不借扫码音）。 */
private fun ShelfTagWriter.Status.toTone() = when (this) {
    ShelfTagWriter.Status.OK -> Tone.GOOD
    ShelfTagWriter.Status.ALREADY -> Tone.INFO
    ShelfTagWriter.Status.UNVERIFIED -> Tone.WARN
    ShelfTagWriter.Status.REJECTED -> Tone.BAD
    ShelfTagWriter.Status.FAILED -> Tone.BAD
}

/** 写贴结果 → 语义音效（铁律 14：一律 Sfx 事件；写完=提交成功、已一致=重复、待复核=警示、写不了=拒绝）。 */
private fun ShelfTagWriter.Status.toSemantics() = when (this) {
    ShelfTagWriter.Status.OK -> ScanFeedback.Sfx.COMMIT_OK
    ShelfTagWriter.Status.ALREADY -> ScanFeedback.Sfx.DUPLICATE
    ShelfTagWriter.Status.UNVERIFIED -> ScanFeedback.Sfx.WARN
    ShelfTagWriter.Status.REJECTED -> ScanFeedback.Sfx.SCAN_FAIL
    ShelfTagWriter.Status.FAILED -> ScanFeedback.Sfx.SCAN_FAIL
}

@Composable
private fun ModeChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (on) Primary else SurfaceSubtle,
        shape = RoundedCornerShape(Design.PillRadius.dp),
        modifier = modifier.height(44.dp).clickable { onClick() },
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                fontSize = Design.FontMd.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                color = if (on) Color.White else TextSecondary,
            )
        }
    }
}

/** 建档需要挂起，但 ReaderMode 回调是同步线程 —— 用 runBlocking 桥接（毫秒级本地写库）。 */
private fun createShelfBlocking(viewModel: ShelfViewModel, name: String): String? =
    kotlinx.coroutines.runBlocking {
        viewModel.createShelfAndReturnTag(name)
    }
