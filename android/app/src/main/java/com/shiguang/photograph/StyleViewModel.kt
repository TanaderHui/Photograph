package com.shiguang.photograph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.http.GET

data class Style(val id: String, val name: String)

internal interface StyleApi {
    @GET("api/v1/styles")
    suspend fun listStyles(): List<Style>
}

sealed interface StylesState {
    data object Loading : StylesState
    data object Unconfigured : StylesState
    data object Failed : StylesState
    data class Loaded(val styles: List<Style>) : StylesState
}

class StyleViewModel : ViewModel() {
    private val api = Network.api(StyleApi::class.java)
    private val mutableState = MutableStateFlow<StylesState>(StylesState.Loading)
    val state = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        val styleApi = api ?: run {
            mutableState.value = StylesState.Unconfigured
            return
        }
        mutableState.value = StylesState.Loading
        viewModelScope.launch {
            mutableState.value = try {
                StylesState.Loaded(styleApi.listStyles())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                StylesState.Failed
            }
        }
    }
}
