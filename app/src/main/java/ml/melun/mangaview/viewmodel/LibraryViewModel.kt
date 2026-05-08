package ml.melun.mangaview.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.repository.OfflineStore
import ml.melun.mangaview.state.UiState

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val state = MutableLiveData<UiState<List<Any>>>(UiState.Idle)

    fun state(): LiveData<UiState<List<Any>>> = state

    fun loadOfflineLibrary() {
        state.value = UiState.Loading
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                OfflineStore.snapshot(getApplication())
            }
            state.value = if (snapshot.isEmpty()) UiState.Empty else UiState.Content(snapshot)
        }
    }
}
