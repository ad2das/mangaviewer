package ml.melun.mangaview.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ml.melun.mangaview.MainApplication
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.MTitle.base_auto

class MainViewModel(
    private val repository: MainRepository = MainRepository(),
) : ViewModel() {
    private val _home = MutableStateFlow<LoadState<HomeContent>>(LoadState.Loading)
    val home: StateFlow<LoadState<HomeContent>> = _home.asStateFlow()

    private val _search = MutableStateFlow(SearchContent())
    val search: StateFlow<SearchContent> = _search.asStateFlow()

    private val _library = MutableStateFlow<Pair<List<MTitle>, List<MTitle>>>(emptyList<MTitle>() to emptyList())
    val library: StateFlow<Pair<List<MTitle>, List<MTitle>>> = _library.asStateFlow()

    private var searchJob: Job? = null

    init {
        refreshHome()
        refreshLibrary()
    }

    fun refreshHome() {
        _home.value = LoadState.Loading
        viewModelScope.launch {
            val baseMode = MainApplication.p.baseMode.takeUnless { it == base_auto }
                ?: ml.melun.mangaview.mangaview.MTitle.base_webtoon
            repository.loadHome(baseMode)
                .onSuccess { content ->
                    _home.value = if (content.sections.isEmpty() && content.recent.isEmpty() && content.favorites.isEmpty()) {
                        LoadState.Empty("표시할 항목이 없습니다")
                    } else {
                        LoadState.Content(content)
                    }
                }
                .onFailure { _home.value = LoadState.Error(it.message ?: "홈을 불러오지 못했습니다") }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            repository.loadLibrary()
                .onSuccess { _library.value = it }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        _search.value = _search.value.copy(query = query, searching = query.isNotBlank(), message = "")
        if (query.isBlank()) {
            _search.value = SearchContent()
            return
        }
        searchJob = viewModelScope.launch {
            repository.search(query, MainApplication.p.baseMode)
                .onSuccess { results ->
                    _search.value = SearchContent(
                        query = query,
                        results = results,
                        searching = false,
                        message = if (results.isEmpty()) "검색 결과가 없습니다" else "",
                    )
                }
                .onFailure {
                    _search.value = SearchContent(query = query, searching = false, message = it.message ?: "검색 실패")
                }
        }
    }
}
