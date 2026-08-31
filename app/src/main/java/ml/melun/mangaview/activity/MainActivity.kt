package ml.melun.mangaview.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.ui.library.LibraryEffect
import ml.melun.mangaview.ui.library.LibraryScreen
import ml.melun.mangaview.ui.library.LibraryViewModel
import ml.melun.mangaview.ui.library.LibraryViewModelFactory
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as ViewerApplication).graph
        val viewModel = ViewModelProvider(
            this,
            LibraryViewModelFactory(graph.sources, graph.userLibrary, Dispatchers.IO),
        )[LibraryViewModel::class.java]
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(viewModel) {
                viewModel.effects.collectLatest { effect ->
                    if (effect is LibraryEffect.OpenEpisode) {
                        openEpisode(effect.episodeId, effect.position)
                    }
                }
            }
            LibraryScreen(state, viewModel::accept)
        }
    }

    private fun openEpisode(episodeId: EpisodeId, position: ReadingPosition?) {
        startActivity(Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerLaunchSpec.EXTRA_SOURCE_ID, episodeId.seriesId.sourceId.value)
            putExtra(ViewerLaunchSpec.EXTRA_SERIES_KEY, episodeId.seriesId.remoteKey)
            putExtra(ViewerLaunchSpec.EXTRA_EPISODE_KEY, episodeId.remoteKey)
            position?.let {
                putExtra(ViewerLaunchSpec.EXTRA_PAGE_KEY, it.pageId.remoteKey)
                putExtra(ViewerLaunchSpec.EXTRA_PAGE_OFFSET_UNITS, it.offsetInPageUnits)
            }
        })
    }
}
