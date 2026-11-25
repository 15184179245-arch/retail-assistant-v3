package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.BillTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** B14 多维度报表 VM：本地账单全量 × 时间维度 → 一次聚合，UI 只读。 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val repo: BillRepo,
) : ViewModel() {

    private val _range = MutableStateFlow(ReportsRange.TODAY)
    val range: StateFlow<ReportsRange> = _range.asStateFlow()

    val report: StateFlow<ReportUi> =
        combine(repo.allBills, _range) { bills, range ->
            computeReportUi(
                bills = bills,
                itemsOf = repo::itemsOf,
                range = range,
                today = BillTime.today(),
                monthStart = BillTime.today().take(8) + "01",
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), ReportUi(range = _range.value))

    fun setRange(r: ReportsRange) {
        _range.value = r
    }
}
