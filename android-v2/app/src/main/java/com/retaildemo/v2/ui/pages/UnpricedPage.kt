package com.retaildemo.v2.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.R
import com.retaildemo.v2.data.remote.UnpricedProductItem
import com.retaildemo.v2.data.remote.UnpricedResponse
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.ProductItemView
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.theme.BorderStrong
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/** S11: 无售价商品清单页面（供店员/管理员对账与去管家婆补价参考，只读）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnpricedPage(
    onBack: () -> Unit,
    viewModel: UnpricedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var searchKw by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.loadUnpricedProducts()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        retaildemoTopBar(
            title = "未定价商品清单",
            subtitle = "管家婆未填售价货品 · 只读核查",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(10.dp))

            // 搜索框
            OutlinedTextField(
                value = searchKw,
                onValueChange = {
                    searchKw = it
                    viewModel.search(it)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜品名 / 规格 / SKU / 条码", fontSize = 13.sp, color = TextWeak) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Primary) },
                trailingIcon = {
                    if (searchKw.isNotEmpty()) {
                        IconButton(onClick = {
                            searchKw = ""
                            viewModel.search("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "清空", tint = TextWeak)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = BorderStrong,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                ),
            )

            Spacer(Modifier.height(8.dp))

            when (val state = uiState) {
                is UnpricedUiState.Loading -> {
                    // 36 三态合辑：列表加载用骨架行（行结构复制真实卡片），呼吸 1.6s；禁转圈盖全屏
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        repeat(3) { UnpricedSkeletonRow() }
                    }
                }

                is UnpricedUiState.Success -> {
                    if (state.items.isEmpty()) {
                        Spacer(Modifier.height(30.dp))
                        EmptyState(
                            illustrationRes = R.drawable.ill_empty_stock,
                            title = "暂无未定价商品",
                            subtitle = if (searchKw.isNotBlank()) "未找到匹配「$searchKw」的未定价商品" else "当前所有货品均已在上游管家婆录入售价",
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = "共 ${state.total} 款商品未录入售价",
                                fontSize = 12.sp,
                                color = TextSecondary,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "第 ${state.page} 页",
                                fontSize = 12.sp,
                                color = TextSecondary,
                            )
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.items, key = { it.id }) { item ->
                                ProductItemView(
                                    name = item.name,
                                    sellPrice = item.sell_price,
                                    stockQty = item.stock_qty,
                                    spec = item.spec,
                                    sku = item.sku,
                                    barcode = item.barcode,
                                    location = item.location,
                                    category = item.category,
                                    saleStatus = "active",
                                )
                            }

                            // 分页翻页栏
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.prevPage() },
                                        enabled = state.page > 1,
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text("上一页")
                                    }

                                    Text(
                                        text = "${state.page} / ${((state.total + state.pageSize - 1) / state.pageSize).coerceAtLeast(1)}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                    )

                                    OutlinedButton(
                                        onClick = { viewModel.nextPage() },
                                        enabled = state.page * state.pageSize < state.total,
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text("下一页")
                                    }
                                }
                            }
                        }
                    }
                }

                is UnpricedUiState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(top = 40.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(state.message, color = Danger, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.loadUnpricedProducts(searchKw) },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("重新加载")
                        }
                    }
                }
            }
        }
    }
}

/** S11: 无售价商品清单页面状态 */
sealed class UnpricedUiState {
    object Loading : UnpricedUiState()
    data class Success(val items: List<UnpricedProductItem>, val total: Int, val page: Int, val pageSize: Int) : UnpricedUiState()
    data class Error(val message: String) : UnpricedUiState()
}

/** S11: 无售价商品清单 ViewModel */
@HiltViewModel
class UnpricedViewModel @Inject constructor(
    private val api: retaildemoApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UnpricedUiState>(UnpricedUiState.Loading)
    val uiState: StateFlow<UnpricedUiState> = _uiState.asStateFlow()

    private var currentKw = ""
    private var currentPage = 1
    private val pageSize = 50
    private var totalCount = 0

    fun loadUnpricedProducts(kw: String = currentKw, page: Int = currentPage) {
        currentKw = kw
        currentPage = page
        _uiState.value = UnpricedUiState.Loading
        viewModelScope.launch {
            try {
                val response = api.getUnpricedProducts(kw.trim(), page, pageSize)
                if (response.isSuccessful && response.body() != null) {
                    val envelope = response.body()!!
                    if (envelope.success) {
                        val data = envelope.data ?: UnpricedResponse(emptyList())
                        totalCount = data.total
                        _uiState.value = UnpricedUiState.Success(data.items, data.total, data.page, data.page_size)
                    } else {
                        _uiState.value = UnpricedUiState.Error(envelope.user_message ?: envelope.error ?: "查询失败")
                    }
                } else {
                    _uiState.value = UnpricedUiState.Error("网络响应异常：${response.code()}")
                }
            } catch (e: HttpException) {
                _uiState.value = UnpricedUiState.Error("服务端错误：${e.code()}")
            } catch (e: IOException) {
                _uiState.value = UnpricedUiState.Error("连接后端失败，请确认服务已启动")
            } catch (e: Exception) {
                _uiState.value = UnpricedUiState.Error("查询失败：${e.message}")
            }
        }
    }

    fun search(kw: String) {
        currentKw = kw
        currentPage = 1
        loadUnpricedProducts(kw, 1)
    }

    fun nextPage() {
        val maxPages = (totalCount + pageSize - 1) / pageSize
        if (currentPage < maxPages) {
            currentPage++
            loadUnpricedProducts(currentKw, currentPage)
        }
    }

    fun prevPage() {
        if (currentPage > 1) {
            currentPage--
            loadUnpricedProducts(currentKw, currentPage)
        }
    }
}

/** 36 三态合辑：未定价列表骨架行（灰瓷片 44 + 两行骨条 + 未定价胶囊占位），呼吸 1.6s（M7）。 */
@Composable
private fun UnpricedSkeletonRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(SurfaceSubtle, RoundedCornerShape(12.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(13.dp)
                    .background(BorderStrong.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(10.dp)
                    .background(SurfaceSubtle, RoundedCornerShape(4.dp)),
            )
        }
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 22.dp)
                .background(SurfaceSubtle, RoundedCornerShape(8.dp)),
        )
    }
}
