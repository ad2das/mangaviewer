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
    private var loadCancel: (() -> Unit)? = null

    fun state(): LiveData<UiState<T>> = state

    protected fun load(block: () -> T?) {
        load(null, block)
    }

    protected fun load(onCancel: (() -> Unit)? = null, block: () -> T?) {
        loadJob?.cancel()
        loadCancel?.invoke()
        loadCancel = onCancel
        state.value = UiState.Loading
        var currentJob: Job? = null
        currentJob = viewModelScope.launch {
            try {
                val value = withContext(Dispatchers.IO) { block() }
                state.value = if (value == null) UiState.Empty else UiState.Content(value)
            } catch (cancelled: CancellationException) {
                // Normal lifecycle cancellation should not surface as a user-visible error.
            } catch (throwable: Throwable) {
                CrashReporter.record(throwable)
                state.value = UiState.Error(mapFailure(throwable))
            } finally {
                if(loadJob == currentJob) {
                    loadJob = null
                    loadCancel = null
                }
            }
        }
        loadJob = currentJob
    }

    fun cancelActiveLoad() {
        loadJob?.cancel()
        loadCancel?.invoke()
        loadJob = null
        loadCancel = null
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
