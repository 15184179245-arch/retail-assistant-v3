package com.retaildemo.v2.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.SparseArray
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.retaildemo.v2.R

/**
 * 操作反馈（快递巴枪范式）：声音 + 震动成对出现，解放双眼。
 *
 * ## 音效语言（2026-09-13 起，Sonic Interaction Design）
 *
 * 音效不再是"捡来的素材按文件名硬编码"，而是**按事件语义设计**的一套语言，
 * 规格与合成参数都在 `scripts/gen_sfx.py`（改设计=改参数表重跑，可复现）。
 * 设计三原则（有实测支撑，见脚本 --list / 声学核验）：
 *   1. **音高方向 = 语义极性**：上行=正向/增加/完成，下行=负向/减少/拒绝；
 *   2. **时长 = 事件权重**：≤60ms 给一秒点十次的键位/步进，≥300ms 只给提交与里程碑
 *      （长音出现在高频操作上，连续扫码时会糊成一片噪音——Nielsen <100ms 瞬时响应档）；
 *   3. **响度 = 重要度**：键位 0.30 → 里程碑 0.85，实测峰值 −10.6dB → −1.5dB。
 *
 * 调用口径：**一律用 [Sfx] 语义事件**，不要直接引 R.raw（[Sfx] 是唯一事实源）。
 *
 * 音效源：Kenney "Interface Sounds"（CC0，kenney.nl，商用免署名）遗留的
 * fx_success/fx_error 两个扫码音**保持不变**（店里已听熟，改它需要老板试听拍板）；
 * 其余全部为按上表合成的新音效。
 *
 * 走系统流（USAGE_ASSISTANCE_SONIFICATION，快门/按键音同族）：真巴枪无条件响——
 * 手机静音/振动档不吞音（2026-09-11 老板实测「音效没响」=手机在静音档+通知流被静音吞掉；
 * 本机媒体流音量为 0 也不可用），声音开关完全由 APP 内「操作提示音」设置控制。
 */
object ScanFeedback {

    /**
     * 语义事件表——**音效与触觉成对定义**（同一次操作在两条通道上应是同一个"事件"）。
     *
     * 响度不由这里控制：阶梯已烘焙进音效资产本身（这样任何播放器拿到的都是正确层次），
     * 故这里除沿用音效外一律 1.0。触觉时长按"事件权重"给：键位 10ms → 里程碑 60ms。
     */
    enum class Sfx(val resId: Int, val volume: Float, val vibrateMs: Long) {
        /** 扫码成功（沿用既有素材，明亮"嘀"）。 */
        SCAN_OK(R.raw.fx_success, 1.0f, 40),

        /** 扫码失败（沿用既有素材，尖锐"嘀"低）。 */
        SCAN_FAIL(R.raw.fx_error, 1.0f, 160),

        /** 键位点按：机械短咔，一秒点十次也不吵。 */
        KEY(R.raw.sfx_key, 1.0f, 10),

        /** 退格：纸张回抽，比按键更闷更低（和"按下"区分开）。 */
        BACKSPACE(R.raw.sfx_back, 1.0f, 12),

        /** 数量/金额 +1：棘轮上齿两记上扬。 */
        STEP_UP(R.raw.sfx_step_up, 1.0f, 12),

        /** 数量/金额 −1：棘轮下齿两记下沉。 */
        STEP_DOWN(R.raw.sfx_step_dn, 1.0f, 12),

        /** 商品入列：东西"落进筐里"的一记弹响。 */
        ITEM_ADD(R.raw.sfx_item_add, 1.0f, 25),

        /** 绑定成功：扣上一声（两段上行）。 */
        BIND_OK(R.raw.sfx_bind_ok, 1.0f, 35),

        /** 解绑：摘下来（单记下沉）。 */
        UNBIND(R.raw.sfx_unbind, 1.0f, 20),

        /** 提交/存单成功：收银抽屉关合的确认感。 */
        COMMIT_OK(R.raw.sfx_commit_ok, 1.0f, 50),

        /** 一单全部扫完：三音上行琶音，里程碑仪式感。 */
        PIPELINE_DONE(R.raw.sfx_done, 1.0f, 60),

        /** 警告（提示但不阻断）：中频木块双击。 */
        WARN(R.raw.sfx_warn, 1.0f, 80),

        /** 危险动作（删除/作废前）：沉重双记低音。 */
        DANGER(R.raw.sfx_danger, 1.0f, 120),

        /** 重复扫码：敲两下门板——这件事已经做过了。 */
        DUPLICATE(R.raw.sfx_dup, 1.0f, 90),

        /** 空结果/空货位：带气声的下滑。 */
        EMPTY(R.raw.sfx_empty, 1.0f, 20),

        /** 清空输入（C 键）：一口气抹掉，比退格长、比警告轻。 */
        CLEAR(R.raw.sfx_clear, 1.0f, 25),
    }

    fun init(context: Context) {
        ensurePool(context.applicationContext)
    }

    /** 按语义事件播放（推荐入口）。 */
    fun play(context: Context, event: Sfx) = play(context, event.resId, event.volume, event.vibrateMs)

    // ---- 兼容层：既有 132 处调用点不动；新代码请直接用 play(context, Sfx.XXX) ----

    @Deprecated("用 play(context, Sfx.SCAN_OK)", ReplaceWith("play(context, Sfx.SCAN_OK)"))
    fun success(context: Context) = play(context, Sfx.SCAN_OK)

    @Deprecated("用 play(context, Sfx.SCAN_FAIL)", ReplaceWith("play(context, Sfx.SCAN_FAIL)"))
    fun error(context: Context) = play(context, Sfx.SCAN_FAIL)

    /** 旧的 tick 语义=键位点按，已升级为设计音效 sfx_key。 */
    fun tick(context: Context) = play(context, Sfx.KEY)

    /** 旧的 click 语义=键位点按，同上。 */
    fun click(context: Context) = play(context, Sfx.KEY)

    private val lock = Any()
    private var pool: SoundPool? = null
    private val soundIds = SparseArray<Int>()

    private fun play(context: Context, resId: Int, volume: Float, vibrateMs: Long) {
        val app = context.applicationContext
        // U6 设置页开关：音效/震动分别可关（SharedPreferences 读取走内存缓存，无 IO 负担）
        val cfg = com.retaildemo.v2.data.remote.AppConfig(app)
        if (cfg.vibrateOn) vibrate(context, vibrateMs)
        if (!cfg.soundOn) return
        val sp = ensurePool(app) ?: return
        val sid = soundIdFor(app, sp, resId) ?: return
        // 不跟踪样本解码状态：SoundPool.play 对未解码完的样本本就是无害空操作，
        // 解码一完成后续播放即正常——结构上不存在「卡住后一直哑火」的状态。
        try {
            sp.play(sid, volume, volume, 1, 0, 1f)
        } catch (_: Throwable) {
        }
    }

    private fun ensurePool(app: Context): SoundPool? = synchronized(lock) {
        pool?.let { return it }
        return try {
            val sp = SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            // 预载：高频操作音（键/步进/入列）必须零延迟——SoundPool 的解码是异步的，
            // 首次播放若还在解码会直接静音，店里连点十下键盘只有后几下有声。
            // 其余音效按需惰性加载（soundIdFor 兜底），避免启动时白解 15 个样本。
            for (e in listOf(Sfx.KEY, Sfx.BACKSPACE, Sfx.STEP_UP, Sfx.STEP_DOWN,
                             Sfx.ITEM_ADD, Sfx.SCAN_OK, Sfx.SCAN_FAIL)) {
                preload(app, sp, e.resId)
            }
            pool = sp
            sp
        } catch (_: Throwable) {
            null
        }
    }

    private fun preload(app: Context, sp: SoundPool, resId: Int) {
        try {
            val sid = sp.load(app, resId, 1)
            if (sid > 0) soundIds.put(resId, sid)
        } catch (_: Throwable) {
        }
    }

    private fun soundIdFor(app: Context, sp: SoundPool, resId: Int): Int? {
        soundIds[resId]?.let { return it }
        return synchronized(lock) {
            val existing: Int? = soundIds[resId]
            existing ?: run {
                preload(app, sp, resId)
                soundIds[resId]
            }
        }
    }

    private fun vibrate(context: Context, ms: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {
        }
    }
}

/** 骨架屏占位（列表加载态）。 */
@Composable
fun SkeletonList(rows: Int = 5) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        repeat(rows) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
            )
        }
    }
}
