package ml.melun.mangaview.activity

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
            LibraryViewModelFactory(
                graph.sources,
                graph.userLibrary,
                graph.offlineStore,
                graph.offlineDownloads,
                graph.repository,
                Dispatchers.IO,
            ),
        )[LibraryViewModel::class.java]
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.saved.settings.darkTheme) {
                applySystemBars(state.saved.settings.darkTheme)
            }
            LaunchedEffect(viewModel) {
                viewModel.effects.collectLatest { effect ->
                    when (effect) {
                        is LibraryEffect.OpenEpisode -> openEpisode(effect.episodeId, effect.position)
                        is LibraryEffect.OpenUri -> openExternalUri(effect.value)
                        is LibraryEffect.ShareText -> share(effect.title, effect.value)
                        is LibraryEffect.ShowMessage -> Toast.makeText(
                            this@MainActivity,
                            effect.value,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            LibraryScreen(state, graph.artworkLoader, viewModel::accept)
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars(dark: Boolean) {
        val background = Color.parseColor(if (dark) "#0F172A" else "#F8FAFC")
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = if (dark) 0 else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
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

    private fun openExternalUri(value: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
            .onFailure { Toast.makeText(this, "주소를 열 앱이 없습니다", Toast.LENGTH_SHORT).show() }
    }

    private fun share(title: String, value: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, value)
        }
        runCatching { startActivity(Intent.createChooser(intent, "공유")) }
            .onFailure { Toast.makeText(this, "공유할 앱이 없습니다", Toast.LENGTH_SHORT).show() }
    }
}
