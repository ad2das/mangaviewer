package ml.melun.mangaview.activity

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.ui.library.LibraryEffect
import ml.melun.mangaview.ui.library.LibraryScreen
import ml.melun.mangaview.ui.library.LibraryViewModel
import ml.melun.mangaview.ui.library.LibraryViewModelFactory
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import ml.melun.mangaview.update.AppUpdateDialog
import ml.melun.mangaview.update.AppUpdateViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var updates: AppUpdateViewModel
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val file = updates.state.value.file ?: return@registerForActivityResult
        if (packageManager.canRequestPackageInstalls()) installUpdate(file)
        else updates.installationFailure("이 앱의 설치 허용을 켠 뒤 ‘설치’를 다시 눌러 주세요.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updates = ViewModelProvider(this)[AppUpdateViewModel::class.java]
        val graph = (application as ViewerApplication).graph
        val viewModel = ViewModelProvider(
            this,
            LibraryViewModelFactory(
                graph.sources,
                graph.userLibrary,
                graph.offlineStore,
                graph.offlineDownloads,
                { graph.engine.openings },
                Dispatchers.IO,
            ),
        )[LibraryViewModel::class.java]
        showLibrary(graph, viewModel)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // A silent metadata check stays out of first-frame startup and stops when reading opens.
                delay(10_000)
                updates.checkAutomatically()
            }
        }
    }

    private fun showLibrary(graph: ml.melun.mangaview.app.AppGraph, viewModel: LibraryViewModel) {
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val account by graph.account.state.collectAsStateWithLifecycle()
            val updateState by updates.state.collectAsStateWithLifecycle()
            LaunchedEffect(updateState.file) {
                val file = updateState.file ?: return@LaunchedEffect
                lifecycle.withResumed {
                    if (updates.consumePendingInstall(file)) installUpdate(file)
                }
            }
            LaunchedEffect(state.saved.settings.darkTheme) {
                applySystemBars(state.saved.settings.darkTheme)
            }
            LaunchedEffect(viewModel) {
                viewModel.effects.collectLatest { effect ->
                    when (effect) {
                        LibraryEffect.CheckForUpdate -> updates.check()
                        LibraryEffect.AccountSignIn -> graph.account.signIn(this@MainActivity)
                        LibraryEffect.AccountSignOut -> graph.account.signOut()
                        LibraryEffect.AccountRetry -> graph.account.retry()
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
            LibraryScreen(state, graph.artworkLoader, viewModel::accept, account,
                updateState.phase == ml.melun.mangaview.update.UpdatePhase.AVAILABLE)
            AppUpdateDialog(updateState, updates::dismiss, updates::check, updates::download, ::installUpdate)
        }
    }

    private fun installUpdate(file: File) {
        runCatching {
            check(file.isFile) { "업데이트 파일이 없습니다. 다시 다운로드해 주세요." }
            if (!packageManager.canRequestPackageInstalls()) {
                installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
            } else {
                val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                updates.dismiss()
            }
        }.onFailure { updates.installationFailure(it.message ?: "앱 설치 화면을 열지 못했습니다") }
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
