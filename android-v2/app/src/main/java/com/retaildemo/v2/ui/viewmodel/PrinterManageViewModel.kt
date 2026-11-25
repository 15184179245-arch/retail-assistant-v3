package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.repo.PrinterConfig
import com.retaildemo.v2.data.repo.PrintRepo
import com.retaildemo.v2.print.BluetoothPrinter
import com.retaildemo.v2.print.PairedPrinter
import com.retaildemo.v2.print.LabelTemplates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrinterManageViewModel @Inject constructor(
    private val printRepo: PrintRepo,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _bluetoothDevices = MutableStateFlow<List<PairedPrinter>>(emptyList())
    val bluetoothDevices: StateFlow<List<PairedPrinter>> = _bluetoothDevices.asStateFlow()

    private val _networkPrinters = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val networkPrinters: StateFlow<List<Pair<String, Int>>> = _networkPrinters.asStateFlow()

    private val _defaultPrinter = MutableStateFlow<PrinterConfig?>(null)
    val defaultPrinter: StateFlow<PrinterConfig?> = _defaultPrinter.asStateFlow()

    private val _secondPrinter = MutableStateFlow<PrinterConfig?>(null)
    val secondPrinter: StateFlow<PrinterConfig?> = _secondPrinter.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _template = MutableStateFlow(printRepo.priceTagTemplate())
    val template: StateFlow<LabelTemplates.PriceTagTemplate> = _template.asStateFlow()

    init {
        refresh()
    }

    fun saveTemplate(t: LabelTemplates.PriceTagTemplate) {
        printRepo.setPriceTagTemplate(t)
        _template.value = t
        _message.value = "价签模板已保存"
    }

    fun refresh() {
        _bluetoothDevices.value = BluetoothPrinter.pairedPrinters(appContext)
        _defaultPrinter.value = printRepo.defaultPrinter()
        _secondPrinter.value = printRepo.secondPrinter()
    }

    fun setDefault(config: PrinterConfig) {
        printRepo.setDefaultPrinter(config)
        _defaultPrinter.value = config
        _message.value = "已设为默认打印机：${config.name.ifBlank { config.address }}"
    }

    fun setSecond(config: PrinterConfig?) {
        printRepo.setSecondPrinter(config)
        _secondPrinter.value = config
        _message.value = if (config != null) "已设为备用打印机：${config.name.ifBlank { config.address }}" else "已清除备用打印机"
    }

    fun updatePaperWidth(width: Int) {
        val cur = _defaultPrinter.value ?: return
        val updated = cur.copy(paperWidth = width)
        setDefault(updated)
        _message.value = "便携机纸宽已设为 ${width}mm"
    }

    /** 纸规切换（宽×高 mm，F11）；paperHeight 一并持久化，版式引擎按宽×高自适应。 */
    fun updateLabelSize(widthMm: Int, heightMm: Int) {
        val cur = _defaultPrinter.value ?: return
        val updated = cur.copy(paperWidth = widthMm, paperHeight = heightMm)
        setDefault(updated)
        _message.value = "纸规已设为 ${widthMm}×${heightMm}mm"
    }

    /** 指令集切换（tspl=标签机主流 / cpcl=便携面单机常见）。 */
    fun updateCommandSet(commandSet: String) {
        val cur = _defaultPrinter.value ?: return
        val updated = cur.copy(commandSet = commandSet)
        setDefault(updated)
        _message.value = if (commandSet.equals(LabelTemplates.CMD_CPCL, true)) {
            "指令集已切换为 CPCL（面单机）"
        } else {
            "指令集已切换为 TSPL（标签机）"
        }
    }

    fun updateDensity(density: Int) {
        val cur = _defaultPrinter.value ?: return
        val updated = cur.copy(density = density)
        setDefault(updated)
        _message.value = "打印浓度已设为 $density"
    }

    /**
     * 打印偏移容错校准：X/Y 各 ±2mm、0.5mm 步进（机械偏差补偿量）。
     * 平移由版式引擎夹紧到边缘死区——配再大也不会把内容推出纸外/贴到纸边。
     */
    fun updateOffset(offsetXMm: Double, offsetYMm: Double) {
        val cur = _defaultPrinter.value ?: return
        val x = offsetXMm.coerceIn(-LabelTemplates.OFFSET_MAX_MM, LabelTemplates.OFFSET_MAX_MM)
        val y = offsetYMm.coerceIn(-LabelTemplates.OFFSET_MAX_MM, LabelTemplates.OFFSET_MAX_MM)
        setDefault(cur.copy(offsetXMm = x, offsetYMm = y))
        _message.value = "打印偏移已设为 X %.1fmm / Y %.1fmm（下次出签生效）".format(x, y)
    }

    fun discover(subnet: String) {
        viewModelScope.launch {
            _message.value = "扫描中…（约 10 秒）"
            val found = printRepo.discover(subnet.trim().removeSuffix("."))
            _networkPrinters.value = found
            _message.value = "发现 ${found.size} 台网络标签机"
        }
    }

    fun testPrint() {
        viewModelScope.launch {
            _message.value = "打印中…"
            _message.value = try {
                // 2026-09-10 真机排障：测试页从「货位标签」改为「样例价签」——
                // 覆盖中文店名/品名+价格+码图的真实场景（旧测试页纯 ASCII，掩盖了网络路径编码问题）
                // 2026-09-12 CPCL 排障：CPCL 蓝牙机附打「字体号对照条」（4/24/55 同句中文），
                // 新北洋 BTP-P39 PLUS 中文乱码时一打即知该机中文字体号
                val ok = printRepo.printTestTag()
                if (ok) "测试价签已发送 ✓" else "打印失败（服务端返回失败）"
            } catch (e: Exception) {
                "打印失败：${e.message}"
            }
        }
    }
}
