package ml.melun.mangaview.ui.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import ml.melun.mangaview.app.SourceRegistry
import ml.melun.mangaview.app.SearchMode
import ml.melun.mangaview.source.SearchField
import ml.melun.mangaview.source.SeriesKind
import ml.melun.mangaview.source.SourceSearchQuery
import ml.melun.mangaview.source.SourceSeries

/** Search owns its result set even while a detail screen or another destination is visible. */
internal class LibrarySearchController(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val sources: SourceRegistry,
    private val current: () -> LibraryState,
    private val update: ((LibraryState) -> LibraryState) -> Unit,
    private val rememberQuery: (String) -> Unit,
    private val started: () -> Unit,
    private val loaded: (List<SourceSeries>) -> Unit,
) {
    private var enrichedItems: List<SourceSeries>? = null
    private val pager = SearchResultsPager(scope, dispatcher, "검색에 실패했습니다. 다시 시도해 주세요") { content ->
        update { state ->
            state.copy(
                searchContent = content,
                content = if (state.activeSeries == null && state.destination == MainDestination.SEARCH) {
                    content
                } else state.content,
            )
        }
        if (content is LibraryContent.Series && enrichedItems !== content.items && current().activeSeries == null) {
            enrichedItems = content.items
            loaded(content.items)
        }
    }

    fun queryChanged(value: String) {
        update { it.copy(query = value) }
        if (value.isBlank()) reset()
    }

    fun selectKind(value: SeriesKind?) {
        if (current().searchKind == value) return
        update { it.copy(searchKind = value) }
        if (current().submittedQuery.isNotEmpty()) submit()
    }

    fun selectField(value: SearchField) {
        if (current().searchField == value) return
        update { it.copy(searchField = value) }
        if (current().submittedQuery.isNotEmpty()) submit()
    }

    fun reset() {
        enrichedItems = null
        pager.reset()
        update { state ->
            state.copy(
                searchContent = LibraryContent.Empty,
                submittedQuery = "",
                content = if (state.activeSeries == null && state.destination == MainDestination.SEARCH) {
                    LibraryContent.Empty
                } else state.content,
            )
        }
    }

    fun submit() {
        val snapshot = current()
        val query = snapshot.query.trim()
        if (query.isEmpty()) { reset(); return }
        val source = sources.require(snapshot.selectedSourceId)
        val option = snapshot.sources.firstOrNull { it.id == snapshot.selectedSourceId }
        val kind = snapshot.searchKind.takeIf { option?.distinguishesKinds != false }
        val field = if (option?.searchMode == SearchMode.FIELDS) snapshot.searchField else SearchField.TITLE
        started()
        update { it.copy(query = query, submittedQuery = query, searchRevision = it.searchRevision + 1L) }
        rememberQuery(query)
        pager.start { cursor -> source.search(SourceSearchQuery(query, kind, field, cursor)) }
    }

    fun next() = pager.next()
    fun pause() = pager.pause()
    fun resume() = pager.resume()
}
