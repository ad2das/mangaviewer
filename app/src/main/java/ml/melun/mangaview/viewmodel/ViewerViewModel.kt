package ml.melun.mangaview.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.melun.mangaview.mangaview.CustomHttpClient
import ml.melun.mangaview.mangaview.Manga
import ml.melun.mangaview.repository.ViewerRepository
import ml.melun.mangaview.state.UiState

class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val imageState = MutableLiveData<UiState<List<String>>>(UiState.Idle)
    private var requestGroup: CustomHttpClient.RequestGroup? = null

    fun imageState(): LiveData<UiState<List<String>>> = imageState

    fun loadImages(manga: Manga) {
        requestGroup?.cancel()
        requestGroup = CustomHttpClient.RequestGroup()
        imageState.value = UiState.Loading
        viewModelScope.launch {
            val images = withContext(Dispatchers.IO) {
                ViewerRepository.ensureImagesLoaded(manga, requestGroup)
                ViewerRepository.images(manga, getApplication())
            }
            imageState.value = if (images.isNullOrEmpty()) UiState.Empty else UiState.Content(images)
        }
    }

    fun saveBookmark(manga: Manga, page: Int, offset: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            ViewerRepository.saveViewerBookmark(manga, page, offset)
        }
    }

    override fun onCleared() {
        requestGroup?.cancel()
        super.onCleared()
    }
}
