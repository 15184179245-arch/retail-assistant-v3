package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.remote.TodoCreateRequest
import com.retaildemo.v2.data.remote.TodoDto
import com.retaildemo.v2.data.remote.TodoIdRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 待办清单 ViewModel：对接库存服务 todos 表，支持店员增删查改现场待办备忘。
 */
@HiltViewModel
class TodosViewModel @Inject constructor(
    private val api: retaildemoApi,
    private val config: AppConfig,
) : ViewModel() {

    private val _todos = MutableStateFlow<List<TodoDto>>(emptyList())
    val todos: StateFlow<List<TodoDto>> = _todos.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _msg = MutableStateFlow<String?>(null)
    val msg: StateFlow<String?> = _msg.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _busy.value = true
            _todos.value = runCatching {
                api.todos().body()?.data.orEmpty()
            }.getOrDefault(emptyList())
            _busy.value = false
        }
    }

    fun create(title: String, note: String = "", priority: String = "medium") {
        if (title.isBlank()) return
        viewModelScope.launch {
            runCatching {
                api.createTodo(
                    TodoCreateRequest(
                        title = title.trim(),
                        note = note.trim(),
                        priority = priority,
                        created_by = config.staffName,
                    )
                )
            }.onFailure {
                _msg.value = "添加失败：${it.message}"
            }
            load()
        }
    }

    fun toggle(id: Int) {
        viewModelScope.launch {
            runCatching {
                api.toggleTodo(TodoIdRequest(id))
            }.onFailure {
                _msg.value = "更新失败：${it.message}"
            }
            load()
        }
    }

    fun delete(id: Int) {
        viewModelScope.launch {
            runCatching {
                api.deleteTodo(TodoIdRequest(id))
            }.onFailure {
                _msg.value = "删除失败：${it.message}"
            }
            load()
        }
    }

    fun clearMsg() {
        _msg.value = null
    }
}
