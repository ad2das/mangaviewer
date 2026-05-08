package ml.melun.mangaview.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ml.melun.mangaview.model.PageItem

@Composable
fun UnifiedViewerScreen(
    state: ViewerUiState,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(
                    items = state.pages,
                    key = { page -> pageKey(page) },
                ) { page ->
                    GlidePageImage(
                        url = page.img,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black),
                        contentDescription = page.manga?.name,
                    )
                }
            }
            if (state.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.error?.let { error ->
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xCC111111))
                        .padding(16.dp),
                ) {
                    Text(text = errorMessage(error), color = Color.White)
                }
            }
        }
    }
}

private fun pageKey(page: PageItem): String {
    val manga = page.manga
    return "${manga?.baseMode ?: 0}:${manga?.titleId ?: 0}:${manga?.id ?: 0}:${page.index}:${page.side}:${page.img}"
}

private fun errorMessage(error: ViewerError): String = when (error) {
    ViewerError.Cancelled -> "Load cancelled"
    ViewerError.EmptyEpisode -> "No pages"
    is ViewerError.Network -> error.message ?: "Network error"
    is ViewerError.Parse -> error.message ?: "Parse error"
    is ViewerError.Unknown -> error.message ?: "Unknown error"
}
