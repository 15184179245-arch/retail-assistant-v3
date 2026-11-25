package com.retaildemo.v2.data.remote

import android.content.Context
import android.content.SharedPreferences

/**
 * 运行时配置（SharedPreferences）：服务器地址 / JWT / 静态令牌 / 店员名。
 * 地址默认回退 http://127.0.0.1:18767（配合 adb reverse，模拟器直连宿主机网关）。
 */
class AppConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("retaildemo_v2_config", Context.MODE_PRIVATE)

    var serverUrl: String
        get() {
            val saved = prefs.getString(KEY_SERVER_URL, null)?.trim()
            val raw = when {
                saved.isNullOrBlank() && isEmulator() -> DEFAULT_LAN_URL
                saved.isNullOrBlank() || (!isEmulator() && saved.contains("127.0.0.1")) -> DEFAULT_SERVER_URL
                else -> saved
            }
            return cleanServerUrl(raw)
        }
        set(value) {
            val cleaned = cleanServerUrl(value)
            prefs.edit().putString(KEY_SERVER_URL, cleaned).apply()
        }

    var jwtToken: String?
        get() = prefs.getString(KEY_JWT, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_JWT, value).apply()

    var gatewayToken: String?
        get() = prefs.getString(KEY_GATEWAY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_GATEWAY_TOKEN, value).apply()

    var staffName: String
        get() = prefs.getString(KEY_STAFF, "店员")?.takeIf { it.isNotBlank() } ?: "店员"
        set(value) = prefs.edit().putString(KEY_STAFF, value).apply()

    /**
     * M3 当前店员在人员名册里的员工 id（归属一等字段）。
     * 设备的身份从「手输名字」升级为「名册条目」：名字会改、会重名，id 不会。
     * 空串 = 未绑定名册（离线首启或名册不可达时的降级态）。
     */
    var staffId: String
        get() = prefs.getString(KEY_STAFF_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_STAFF_ID, value.trim()).apply()

    /**
     * I1 店员名单（本机维护，不做服务端同步）：首启引导与协同人选择的候选来源。
     * ⚠ SharedPreferences 存空 Set 读回会变 null，getter 必须兜底 emptySet()；写入前 toMutableSet() 拷贝。
     */
    var staffList: Set<String>
        get() = prefs.getStringSet(KEY_STAFF_LIST, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_STAFF_LIST, value.toMutableSet()).apply()

    /** 加入名单（去空白、Set 自动去重）；当前店员名也在名单内可查。 */
    fun addStaff(name: String) {
        val n = name.trim()
        if (n.isBlank()) return
        staffList = staffList + n
    }

    fun removeStaff(name: String) { staffList = staffList - name.trim() }

    /** I1 首启引导是否完成（配合 staffName 哨兵判断分流：仅"未绑定且还是默认名"才弹引导）。 */
    var staffBound: Boolean
        get() = prefs.getBoolean(KEY_STAFF_BOUND, false)
        set(value) = prefs.edit().putBoolean(KEY_STAFF_BOUND, value).apply()

    /** 公网隧道（SakuraFrp 等）自签证书时开启；出厂默认开启以确保公网隧道开箱即用。 */
    var trustAllTls: Boolean
        get() = prefs.getBoolean(KEY_TRUST_ALL_TLS, true)
        set(value) = prefs.edit().putBoolean(KEY_TRUST_ALL_TLS, value).apply()

    var productsSyncedAt: Long
        get() = prefs.getLong(KEY_PRODUCTS_SYNCED, 0L)
        set(value) = prefs.edit().putLong(KEY_PRODUCTS_SYNCED, value).apply()

    /**
     * S17 增量回拉水位：上次成功拉取时**服务器**给出的时间（毫秒）。
     * 0=从未全量拉过 → 下次全量。用服务器时间而非手机时间，规避设备时钟差导致的漏拉。
     */
    var productsPulledThroughMs: Long
        get() = prefs.getLong(KEY_PRODUCTS_PULLED_THROUGH, 0L)
        set(value) = prefs.edit().putLong(KEY_PRODUCTS_PULLED_THROUGH, value).apply()

    /** S17 最近一次上游同步摘要（设置页同步卡直接上屏，重启后仍在）。 */
    var lastSyncSummary: String
        get() = prefs.getString(KEY_LAST_SYNC_SUMMARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_SUMMARY, value).apply()

    /** U6 扫码反馈开关（巴枪范式）：音效/震动分别控制，默认全开不改既有行为。 */
    var soundOn: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ON, value).apply()

    var vibrateOn: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE_ON, value).apply()

    // I16 老王：会话 id 与悬浮球位置/收起态（进程外不留档，仅 SharedPreferences）
    var agentSessionId: String
        get() = prefs.getString(KEY_AGENT_SESSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AGENT_SESSION, value).apply()

    var agentBallX: Float
        get() = prefs.getFloat(KEY_AGENT_BALL_X, -1f)
        set(value) = prefs.edit().putFloat(KEY_AGENT_BALL_X, value).apply()

    var agentBallY: Float
        get() = prefs.getFloat(KEY_AGENT_BALL_Y, -1f)
        set(value) = prefs.edit().putFloat(KEY_AGENT_BALL_Y, value).apply()

    /** 2026-09-11 老板令「浮窗能关闭」：悬浮胶囊收起态持久化；「我的」工作台可恢复。 */
    var agentDockHidden: Boolean
        get() = prefs.getBoolean(KEY_AGENT_DOCK_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(KEY_AGENT_DOCK_HIDDEN, value).apply()

    /**
     * 2026-09-11 老板令「给设备发身份」：安装维度稳定设备号，首启生成一次终身复用
     * （重装/换机会变）。服务器 devices 表以此为主键记录「哪台设备绑哪个店员、谁在线」。
     */
    val deviceId: String
        get() {
            val saved = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
            if (saved != null) return saved
            val fresh = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
            return fresh
        }

    companion object {
        const val DEFAULT_WAN_URL = "https://service.example.invalid"
        const val DEFAULT_LAN_URL = "http://127.0.0.1:18767"
        const val DEFAULT_STORE_LAN_URL = "http://192.0.2.10:18767"
        const val DEFAULT_SERVER_URL = DEFAULT_WAN_URL
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_JWT = "jwt_token"
        private const val KEY_GATEWAY_TOKEN = "gateway_token"
        private const val KEY_STAFF = "staff_name"
        private const val KEY_STAFF_ID = "staff_id"
        private const val KEY_STAFF_LIST = "staff_list"
        private const val KEY_STAFF_BOUND = "staff_bound"
        private const val KEY_TRUST_ALL_TLS = "trust_all_tls"
        private const val KEY_PRODUCTS_SYNCED = "products_synced_at"
        private const val KEY_PRODUCTS_PULLED_THROUGH = "products_pulled_through_ms"
        private const val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"
        private const val KEY_SOUND_ON = "scan_sound_on"
        private const val KEY_VIBRATE_ON = "scan_vibrate_on"
        private const val KEY_AGENT_SESSION = "agent_session"
        private const val KEY_AGENT_BALL_X = "agent_ball_x"
        private const val KEY_AGENT_BALL_Y = "agent_ball_y"
        private const val KEY_AGENT_DOCK_HIDDEN = "agent_dock_hidden"
        private const val KEY_DEVICE_ID = "device_id"

        fun isEmulator(): Boolean {
            val fp = android.os.Build.FINGERPRINT ?: ""
            val model = android.os.Build.MODEL ?: ""
            val hw = android.os.Build.HARDWARE ?: ""
            return fp.startsWith("generic") || fp.startsWith("unknown")
                || model.contains("google_sdk") || model.contains("Emulator") || model.contains("Android SDK built for")
                || hw.contains("goldfish") || hw.contains("ranchu")
        }

        fun cleanServerUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return DEFAULT_SERVER_URL
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            return try {
                val uri = java.net.URI(withScheme)
                val scheme = uri.scheme?.lowercase() ?: "http"
                val host = uri.host ?: return withScheme.trimEnd('/')
                val port = if (uri.port != -1) ":${uri.port}" else ""
                "$scheme://$host$port"
            } catch (_: Exception) {
                val idx = withScheme.indexOf("://")
                val afterScheme = if (idx != -1) idx + 3 else 0
                val pathStart = withScheme.indexOf('/', afterScheme)
                val noPath = if (pathStart != -1) withScheme.substring(0, pathStart) else withScheme
                val hashIdx = noPath.indexOf('#')
                (if (hashIdx != -1) noPath.substring(0, hashIdx) else noPath).trimEnd('/')
            }
        }
    }
}
