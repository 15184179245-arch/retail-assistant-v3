package com.retaildemo.v2.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.dao.SearchHistoryDao
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.ProductRepo
import com.retaildemo.v2.data.repo.syncPercentOf
import com.retaildemo.v2.data.repo.syncProgressText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val config: AppConfig,
    private val api: retaildemoApi,
    private val productRepo: ProductRepo,
    private val billRepo: com.retaildemo.v2.data.repo.BillRepo,
    private val historyDao: SearchHistoryDao,
    val updateManager: com.retaildemo.v2.util.AppUpdateManager,
    private val deviceRegistry: com.retaildemo.v2.data.repo.DeviceRegistry,
) : ViewModel() {

    val serverUrl = MutableStateFlow(config.serverUrl)
    val staffName = MutableStateFlow(config.staffName)
    val trustAllTls = MutableStateFlow(config.trustAllTls)

    fun checkForUpdateManual() {
        updateManager.checkForUpdate(isManual = true)
    }

    /** I1 店员名单（本机维护，首启引导/协同人候选来源）。 */
    private val _staffList = MutableStateFlow(config.staffList.sorted())
    val staffList: StateFlow<List<String>> = _staffList.asStateFlow()

    /** U6 名册绑定态上屏：空串=未绑名册（手输名字），非空=名册工号。 */
    val staffId = MutableStateFlow(config.staffId)

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _productsSyncing = MutableStateFlow(false)
    val productsSyncing: StateFlow<Boolean> = _productsSyncing.asStateFlow()

    /** 规范化：剥除 path/fragment，自动补 scheme。 */
    private fun normalizeUrl(raw: String): String {
        return AppConfig.cleanServerUrl(raw)
    }

    fun saveServerUrl(url: String) {
        val cleaned = normalizeUrl(url)
        config.serverUrl = cleaned
        serverUrl.value = config.serverUrl
        _testResult.value = "已保存，请测试连接"
    }

    fun setPresetWan() {
        config.serverUrl = AppConfig.DEFAULT_WAN_URL
        config.trustAllTls = true
        serverUrl.value = config.serverUrl
        trustAllTls.value = true
        _testResult.value = "已切换为外网穿透通道（${AppConfig.DEFAULT_WAN_URL}），已开启TLS信任"
    }

    fun setPresetStoreLan() {
        config.serverUrl = AppConfig.DEFAULT_STORE_LAN_URL
        serverUrl.value = config.serverUrl
        _testResult.value = "已切换为店面Wi-Fi局域网（${AppConfig.DEFAULT_STORE_LAN_URL}）"
    }

    fun setPresetLan() {
        val target = if (AppConfig.isEmulator()) AppConfig.DEFAULT_LAN_URL else AppConfig.DEFAULT_STORE_LAN_URL
        config.serverUrl = target
        serverUrl.value = config.serverUrl
        _testResult.value = "已切换为局域网（$target）"
    }

    fun setTrustAllTls(enabled: Boolean) {
        config.trustAllTls = enabled
        trustAllTls.value = enabled
        _testResult.value = if (enabled) {
            "已开启信任自签证书；服务器地址保存后生效"
        } else {
            "已关闭信任自签证书"
        }
    }

    fun saveStaffName(name: String) {
        config.staffName = name.trim()
        staffName.value = config.staffName
        // I1 改绑：新名字入名单 + 视为已绑定（历史账单 staff 不回填，只影响之后新数据）
        if (name.isNotBlank()) {
            config.addStaff(name)
            _staffList.value = config.staffList.sorted()
        }
        config.staffBound = true
    }

    /** I1 名单增员（不改变当前绑定人）。 */
    fun addStaff(name: String) {
        config.addStaff(name)
        _staffList.value = config.staffList.sorted()
    }

    fun removeStaff(name: String) {
        config.removeStaff(name)
        _staffList.value = config.staffList.sorted()
    }

    /** I2 管理员登录态展示名（null=未登录）；token 在 SharedPreferences，重启凭 token 恢复登录态。 */
    private val _adminName = MutableStateFlow(if (!config.jwtToken.isNullOrBlank()) "管理员" else null)
    val adminName: StateFlow<String?> = _adminName.asStateFlow()

    /** I2 管理员 JWT 登录（网关 /api/auth/login）：成功存 token 点亮管理区；账号绝不硬编码。 */
    fun login(user: String, pw: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = try {
                val resp = api.login(com.retaildemo.v2.data.remote.LoginRequest(user.trim(), pw))
                val body = resp.body()
                val token = body?.data?.token
                if (resp.isSuccessful && body?.success == true && !token.isNullOrBlank()) {
                    config.jwtToken = token
                    true to body.data.user.display_name.ifBlank { "管理员" }
                } else {
                    false to (body?.user_message ?: "登录失败（HTTP ${resp.code()}）")
                }
            } catch (e: Exception) {
                false to ("网络异常：${e.message ?: "无法连接网关"}")
            }
            if (result.first) _adminName.value = result.second
            onResult(result.first, result.second)
        }
    }

    /** I2 退出管理登录：只清 jwtToken，店员绑定（staffName/staffBound）保留。 */
    fun logout() {
        config.jwtToken = null
        _adminName.value = null
    }

    /**
     * 连接测试：打 /api/search 判断连通性，带精准诊断提示。
     * M2（2026-09-11）：**通了就顺手把账单要回来**——重装/换机的店员配好地址、
     * 正在等数据回来，这一刻同步最自然（不必等 15 分钟周期，也不必再点一次恢复）。
     */
    fun testConnection() {
        viewModelScope.launch {
            _testResult.value = "测试中…"
            _testResult.value = try {
                val resp = api.search("测试")
                if (resp.isSuccessful) {
                    val back = billRepo.restoreFromServer()
                    when {
                        !back.ok -> "连接正常 ✓ (${config.serverUrl})；账单同步失败：${back.error ?: "未知原因"}"
                        back.restored == 0 -> "连接正常 ✓ (${config.serverUrl})；服务器暂无账单"
                        else -> "连接正常 ✓ (${config.serverUrl})；已同步 ${back.restored} 条账单（本地 ${back.localTotal} 条）"
                    }
                } else {
                    "网关响应异常：HTTP ${resp.code()}"
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                if (msg.contains("127.0.0.1") && !AppConfig.isEmulator()) {
                    "连接失败：真机无法访问 127.0.0.1，请点击上方【店面Wi-Fi】或【外网穿透】"
                } else if (msg.contains("SSL") || msg.contains("Cert") || msg.contains("trust") || msg.contains("Handshake", ignoreCase = true)) {
                    "连接失败：TLS证书校验异常，请确认已开启下方【信任自签证书】($msg)"
                } else if (msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true)) {
                    "连接超时：无法访问 ${config.serverUrl}，请检查是否在同一Wi-Fi下或穿透服务是否运行"
                } else {
                    "连接失败：$msg"
                }
            }
        }
    }

    /** S17 同步阶段（按钮文案跟手：登录管家婆→拉取→写入→回拉）。空串=不在同步中。 */
    private val _syncPhase = MutableStateFlow("")
    val syncPhase: StateFlow<String> = _syncPhase.asStateFlow()

    /** S17 进度条：0..100（拉取阶段=真实页数插值）；null=不确定态（失败/未知）。 */
    private val _syncPercent = MutableStateFlow<Int?>(null)
    val syncPercent: StateFlow<Int?> = _syncPercent.asStateFlow()

    /** S17 进度条文案（含「拉取商品 7/26 页」）。 */
    private val _syncText = MutableStateFlow("")
    val syncText: StateFlow<String> = _syncText.asStateFlow()

    /**
     * S17 点击同步＝**真实上游同步**：服务端登录管家婆拉当场最新 → 比对 → 仅变更落库，
     * 完成后手机端只回拉变更行（首次全量）。旧服务器自动退回全量拉取，功能不中断。
     */
    fun pullProducts() {
        if (_productsSyncing.value) return
        _productsSyncing.value = true
        _syncPhase.value = "fetch_products"
        _syncText.value = "登录管家婆 · 拉取最新商品…"
        _syncPercent.value = 1
        viewModelScope.launch {
            val outcome = productRepo.syncFromUpstream { st ->
                _syncPhase.value = st.phase ?: ""
                _syncText.value = syncProgressText(st)
                _syncPercent.value = syncPercentOf(st)
            }
            _productsSyncing.value = false
            _syncPhase.value = ""
            _syncPercent.value = null
            _syncText.value = ""
            if (outcome.ok) {
                // S18：客户档案随手动同步顺带回拉（best-effort，失败不推翻商品同步结果）。
                val cust = runCatching { billRepo.syncCustomersFromServer() }.getOrNull()
                val custNote = cust?.let { c ->
                    if (c.upserted + c.linked > 0) "\n客户档案：更新 ${c.upserted + c.linked} 条" else ""
                } ?: ""
                refreshLastSync()
                _lastSyncText.value = formatLastSync(config.productsSyncedAt) + "\n" + outcome.message + custNote
            }
            _testResult.value = outcome.message
        }
    }

    fun clearTestResult() { _testResult.value = null }

    // ---------------- M2 从服务器恢复账单 ----------------

    private val _restoring = MutableStateFlow(false)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    /**
     * M2 重装/换机后的「救命按钮」：显式全量拉回服务器账单。
     * 成败与条数都经 [_testResult] 回显；**取不到时报错，绝不显示「0 条」**——
     * 否则店员会以为账本来就没了（这正是老板反馈的「重装后什么都没了」的观感）。
     */
    fun restoreBills() {
        if (_restoring.value) return
        _restoring.value = true
        viewModelScope.launch {
            val r = billRepo.restoreFromServer()
            _restoring.value = false
            _testResult.value = when {
                !r.ok -> "恢复失败：${r.error ?: "未知原因"}（本地 ${r.localTotal} 条未受影响）"
                r.restored == 0 -> "服务器上没有账单（本地共 ${r.localTotal} 条）"
                else -> "已从服务器恢复 ${r.restored} 条，本地共 ${r.localTotal} 条 ✓"
            }
        }
    }

    // ---------------- M3 人员名册 ----------------

    private val _roster = MutableStateFlow<List<com.retaildemo.v2.data.remote.RosterItemDto>>(emptyList())
    val roster: StateFlow<List<com.retaildemo.v2.data.remote.RosterItemDto>> = _roster.asStateFlow()

    /** M3 拉取人员名册；失败返回空列表（UI 回退本机名单，不阻塞首启）。 */
    suspend fun loadRoster(): List<com.retaildemo.v2.data.remote.RosterItemDto> {
        val list = try {
            val resp = api.roster()
            if (resp.isSuccessful) resp.body()?.data.orEmpty() else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        _roster.value = list
        return list
    }

    /** M3 绑定当前店员身份：名册 id（归属一等字段）+ 显示名。id 为空=未绑定名册。
     *  2026-09-11 老板令：绑定后立即向服务器报到，设备↔店员绑定即时生效（失败静默，心跳兜底）。 */
    fun saveStaffIdentity(id: String, name: String) {
        config.staffId = id
        saveStaffName(name)
        staffId.value = config.staffId
        viewModelScope.launch { runCatching { deviceRegistry.checkin() } }
    }

    // ---------------- U6 设置页功能补全（2026-09-11） ----------------

    /** 扫码反馈开关（巴枪范式音效+震动，默认全开保持既有行为）。 */
    val soundOn = MutableStateFlow(config.soundOn)
    val vibrateOn = MutableStateFlow(config.vibrateOn)

    fun setSoundOn(on: Boolean) {
        config.soundOn = on
        soundOn.value = on
    }

    fun setVibrateOn(on: Boolean) {
        config.vibrateOn = on
        vibrateOn.value = on
    }

    private val _lastSyncText = MutableStateFlow(formatLastSync(config.productsSyncedAt))
    val lastSyncText: StateFlow<String> = _lastSyncText.asStateFlow()

    private fun refreshLastSync() {
        _lastSyncText.value = formatLastSync(config.productsSyncedAt)
    }

    /** 清理操作结果就近回显在「数据清理」卡内，不与服务器连接测试消息混流。 */
    private val _cleanupResult = MutableStateFlow<String?>(null)
    val cleanupResult: StateFlow<String?> = _cleanupResult.asStateFlow()

    /** 清空搜索历史（查价/记账搜索框的历史记录，可随时重建）。 */
    fun clearSearchHistory() {
        viewModelScope.launch {
            _cleanupResult.value = try {
                historyDao.clear()
                cleanupMessage(KIND_HISTORY, ok = true)
            } catch (e: Exception) {
                cleanupMessage(KIND_HISTORY, ok = false, detail = e.message ?: "未知错误")
            }
        }
    }

    /** 清理应用文件缓存（cacheDir 整体清空，图片/网络缓存可再生）。 */
    fun clearAppCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val freedMb = try {
                deleteDirContents(appContext.cacheDir) / (1024.0 * 1024.0)
            } catch (_: Exception) {
                0.0
            }
            withContext(Dispatchers.Main) {
                _cleanupResult.value = cleanupMessage(KIND_CACHE, ok = true, detail = "释放 %.1f MB".format(freedMb))
            }
        }
    }

    /**
     * 重建离线商品库：仓库侧「先拉成功再清再灌」，失败零删除。
     * 用于增量同步把本地库搞乱后的兜底自救，二次确认在 UI 层。
     */
    fun rebuildProductDb() {
        if (_productsSyncing.value) return
        _productsSyncing.value = true
        viewModelScope.launch {
            val ok = productRepo.rebuildFromServer()
            _productsSyncing.value = false
            _cleanupResult.value = if (ok) {
                refreshLastSync()
                cleanupMessage(KIND_REBUILD, ok = true)
            } else {
                cleanupMessage(KIND_REBUILD, ok = false, detail = "拉取失败或服务器无数据，本地商品库未动")
            }
        }
    }

    companion object {
        const val KIND_HISTORY = "history"
        const val KIND_CACHE = "cache"
        const val KIND_REBUILD = "rebuild"

        /** 上次商品库同步时间文案（纯函数便于单测）：0=从未同步给引导文案。 */
        fun formatLastSync(at: Long): String {
            if (at <= 0L) return "从未同步 · 点「立即同步最新商品库」建立本地离线库"
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            return "上次同步：${fmt.format(java.util.Date(at))}"
        }

        /** 清理操作结果文案（纯函数便于单测）。 */
        fun cleanupMessage(kind: String, ok: Boolean, detail: String = ""): String {
            val tail = if (detail.isBlank()) "" else "（$detail）"
            return when (kind) {
                KIND_HISTORY -> if (ok) "搜索历史已清空 ✓" else "搜索历史清理失败$tail"
                KIND_CACHE -> if (ok) "缓存已清理 ✓$tail" else "缓存清理失败$tail"
                KIND_REBUILD -> if (ok) "离线商品库重建完成 ✓" else "重建中止，本地商品库未动$tail"
                else -> if (ok) "完成 ✓" else "失败$tail"
            }
        }

        /** 递归删除目录内容（不删目录本身），返回释放字节数；纯 java.io 可 JVM 单测。 */
        fun deleteDirContents(dir: File?): Long {
            if (dir == null || !dir.isDirectory) return 0L
            var freed = 0L
            dir.listFiles()?.forEach { f ->
                freed += if (f.isDirectory) {
                    val sub = deleteDirContents(f)
                    f.delete() // 清空后顺手删空壳目录；删不掉不影响释放量
                    sub
                } else {
                    val len = f.length()
                    if (f.delete()) len else 0L
                }
            }
            return freed
        }
    }
}
