package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.entity.TempProductEntity
import com.retaildemo.v2.data.repo.TempProductRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** B3 待补全队列：type=custom 且未回写正式码的临时商品（串货走 B4 台账，不在此列）。 */
@HiltViewModel
class TempQueueViewModel @Inject constructor(
    private val tempRepo: TempProductRepo,
) : ViewModel() {
    val items: Flow<List<TempProductEntity>> = tempRepo.observeByType("custom")

    /** 建档销账：管家婆拿到正式码后删临时记录，账单历史行保留临时码可追溯。 */
    fun resolve(code: String) {
        viewModelScope.launch { tempRepo.delete(code) }
    }
}
