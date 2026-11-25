package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.CustomerEntity
import com.retaildemo.v2.data.repo.BillRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CustomerDetailUi(
    val customer: CustomerEntity,
    val bills: List<BillEntity>,
    val debt: Double,
)

@HiltViewModel
class CustomersViewModel @Inject constructor(
    private val repo: BillRepo,
) : ViewModel() {

    val customers = repo.allCustomers
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(3000), emptyList())

    /** 客户名 → 挂账欠款合计（pending 单）。 */
    val debtByCustomer: StateFlow<Map<String, Double>> = repo.allBills
        .combine(customers) { bills, _ ->
            bills.filter { it.status == BillEntity.STATUS_PENDING }
                .groupBy { it.customerName }
                .mapValues { (_, list) -> list.sumOf { it.totalAmount } }
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(3000), emptyMap())

    private val _openCustomerId = MutableStateFlow<String?>(null)
    val openCustomerId: StateFlow<String?> = _openCustomerId.asStateFlow()

    private val _currentDetail = MutableStateFlow<CustomerDetailUi?>(null)
    val currentDetail: StateFlow<CustomerDetailUi?> = _currentDetail.asStateFlow()

    fun open(id: String?) {
        _openCustomerId.value = id
        if (id == null) {
            _currentDetail.value = null
            return
        }
        viewModelScope.launch {
            repo.allCustomers.combine(repo.allBills) { custs, bills ->
                val c = custs.firstOrNull { it.id == id } ?: return@combine null
                val myBills = bills.filter { it.customerName == c.name }.sortedByDescending { it.createdAt }
                CustomerDetailUi(
                    customer = c,
                    bills = myBills,
                    debt = myBills.filter { it.status == BillEntity.STATUS_PENDING }.sumOf { it.totalAmount },
                )
            }.collect { _currentDetail.value = it }
        }
    }

    fun detailOf(customerId: String): Flow<CustomerDetailUi?> =
        combine(repo.allCustomers, repo.allBills) { custs, bills ->
            val c = custs.firstOrNull { it.id == customerId } ?: return@combine null
            val myBills = bills.filter { it.customerName == c.name }
                .sortedByDescending { it.createdAt }
            CustomerDetailUi(
                customer = c,
                bills = myBills,
                debt = myBills.filter { it.status == BillEntity.STATUS_PENDING }.sumOf { it.totalAmount },
            )
        }

    fun addCustomer(name: String, phone: String, creditLimit: Double) {
        viewModelScope.launch { repo.saveCustomerWithLimit(name, phone, creditLimit) }
    }

    /** B5 临时客户转正（管家婆建档后人工回填正式名，账单归属切换）。 */
    fun promoteCustomer(tempId: String, formalName: String) {
        viewModelScope.launch { repo.promoteCustomer(tempId, formalName) }
    }

    /** 对账单文本（微信/短信可粘贴）。 */
    fun buildStatement(): String {
        // 同步读取一次：直接由 UI 层从 detailOf 的最新值传入亦可；此处从 repo 拉首值
        return buildString {
            append("【对账单】\n")
        }
    }
}
