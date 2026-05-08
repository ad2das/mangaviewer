package ml.melun.mangaview.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import ml.melun.mangaview.report.CrashReporter
import ml.melun.mangaview.state.MangaFailure
import ml.melun.mangaview.state.UiState

open class BaseStateViewModel<T> : ViewModel() {
    private val state = MutableLiveData<UiState<T>>(UiState.Idle)
    private var loadJob: Job? = null

    fun state(): LiveData<UiState<T>> = state

    protected fun load(block: () -> T?) {
        loadJob?.cancel()
        state.value = UiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val value = withContext(Dispatchers.IO) { block() }
                state.value = if (value == null) UiState.Empty else UiState.Content(value)
            } catch (cancelled: CancellationException) {
                state.value = UiState.Error(MangaFailure.Cancelled(cancelled))
            } catch (throwable: Throwable) {
                CrashReporter.record(throwable)
                state.value = UiState.Error(mapFailure(throwable))
            }
        }
    }

    fun cancelActiveLoad() {
        loadJob?.cancel()
    }

    override fun onCleared() {
        cancelActiveLoad()
    }

    private fun mapFailure(throwable: Throwable): MangaFailure {
        return when (throwable) {
            is IOException -> MangaFailure.NetworkError(throwable)
            is SecurityException -> MangaFailure.StorageError(throwable)
            is IllegalArgumentException -> MangaFailure.ParseError(throwable)
            else -> MangaFailure.Unknown(throwable)
        }
    }
}
