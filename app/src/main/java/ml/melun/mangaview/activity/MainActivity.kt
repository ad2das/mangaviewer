package ml.melun.mangaview.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import ml.melun.mangaview.CrashLog
import ml.melun.mangaview.CrashReportDialog
import ml.melun.mangaview.CrashReportText
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.ReadingPosition
import ml.melun.mangaview.ui.library.LibraryEffect
import ml.melun.mangaview.ui.library.LibraryIntent
import ml.melun.mangaview.ui.library.LibraryScreen
import ml.melun.mangaview.ui.library.LibraryViewModel
import ml.melun.mangaview.ui.library.LibraryViewModelFactory
import ml.melun.mangaview.ui.library.LibraryColors
import ml.melun.mangaview.ui.library.rememberLibraryColors
import ml.melun.mangaview.ui.library.libraryPressIndication
import ml.melun.mangaview.ui.library.providesSelectionFeedback
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec
import ml.melun.mangaview.update.AppUpdateDialog
import ml.melun.mangaview.update.AppUpdateViewModel
import java.io.File

private const val STARTUP_PRIME_DELAY_MILLIS = 400L

class MainActivity : ComponentActivity() {
    private lateinit var updates: AppUpdateViewModel
    private lateinit var reader: MainReaderHost
    internal fun readerScreen(): EngineViewerScreen? = if (::reader.isInitialized) reader.current else null
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
                graph.homeCatalogCache,
                graph.episodeCatalogCache,
            ),
        )[LibraryViewModel::class.java]
        showLibrary(graph, viewModel)
        primeReaderAfterFirstDraw(graph, viewModel)
        reader = MainReaderHost(this)
        reader.restore(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // The flow emits its initial false on collection; the renderer is only re-warmed
                // when a reader actually closes, so startup never builds the engine graph early.
                var wasReading = false
                reader.visible.collectLatest { reading ->
                    if (!reading) {
                        applySystemBars(viewModel.state.value.saved.settings.darkTheme)
                        if (wasReading) graph.engine.renderers.warm()
                        viewModel.foreground(true)
                    }
                    wasReading = reading
                    try { kotlinx.coroutines.awaitCancellation() } finally { viewModel.foreground(false) }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                reader.visible.collectLatest { reading ->
                    if (!reading) { delay(10_000); updates.checkAutomatically() }
                }
            }
        }
    }

    // The library's first composition spans several frames on a cold start; prime the reader
    // engine and catalog caches only after the view tree has actually drawn, so renderer
    // preparation and prefetch never compete with the launch frames.
    private fun primeReaderAfterFirstDraw(
        graph: ml.melun.mangaview.app.AppGraph,
        viewModel: LibraryViewModel,
    ) {
        val decor = window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                decor.post {
                    decor.viewTreeObserver.removeOnDrawListener(this)
                    decor.postDelayed({
                        graph.primeAfterFirstFrame()
                        viewModel.activateEpisodeWarmer()
                    }, STARTUP_PRIME_DELAY_MILLIS)
                }
            }
        })
    }

    override fun onStart() { super.onStart(); if (::reader.isInitialized) reader.enterForeground() }
    override fun onStop() { if (::reader.isInitialized) reader.enterBackground(); super.onStop() }
    override fun onDestroy() { if (::reader.isInitialized) reader.destroy(); super.onDestroy() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (handleReaderVolumeKey(keyCode)) true else super.onKeyDown(keyCode, event)
    override fun onSaveInstanceState(outState: Bundle) {
        if (::reader.isInitialized) reader.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun handleReaderVolumeKey(keyCode: Int): Boolean {
        val forward = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            KeyEvent.KEYCODE_VOLUME_UP -> false
            else -> return false
        }
        return readerScreen()?.handleVolumeKey(forward) == true
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun showLibrary(graph: ml.melun.mangaview.app.AppGraph, viewModel: LibraryViewModel) {
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val account by graph.account.state.collectAsStateWithLifecycle()
            val updateState by updates.state.collectAsStateWithLifecycle()
            val reading by reader.visible.collectAsStateWithLifecycle()
            val scannedReport by CrashLog.pendingReport.collectAsStateWithLifecycle()
            var crashReport by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(scannedReport) {
                if (scannedReport != null && crashReport == null) crashReport = scannedReport
            }
            val colors = rememberLibraryColors(state.saved.settings.darkTheme)
            UpdateInstallEffect(updateState, reading, updates)
            LaunchedEffect(state.saved.settings.darkTheme) {
                if (readerScreen() == null) applySystemBars(state.saved.settings.darkTheme)
            }
            LibraryEffects(viewModel, graph)
            val haptics = LocalHapticFeedback.current
            val acceptWithFeedback: (LibraryIntent) -> Unit = remember(viewModel, haptics) {
                { intent ->
                    if (intent.providesSelectionFeedback()) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    viewModel.accept(intent)
                }
            }
            CompositionLocalProvider(
                LocalOverscrollFactory provides null,
                LocalIndication provides libraryPressIndication(),
            ) {
                LibraryScreen(state, graph.artworkLoader, acceptWithFeedback, account,
                    updateState.phase == ml.melun.mangaview.update.UpdatePhase.AVAILABLE,
                    onOpenCrashReport = { latestCrashReport()?.let { crashReport = it } })
            }
            if (!reading) {
                AppUpdateDialog(
                    updateState,
                    colors,
                    updates::dismiss,
                    updates::check,
                    updates::download,
                    ::installUpdate,
                )
                CrashReportHost(crashReport, colors) {
                    CrashLog.consumePending(this@MainActivity)
                    crashReport = null
                }
            }
        }
    }

    @Composable
    private fun LibraryEffects(viewModel: LibraryViewModel, graph: ml.melun.mangaview.app.AppGraph) {
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
    }

    /** Shows the newest crash with GitHub and clipboard actions; closing consumes the report. */
    @Composable
    private fun CrashReportHost(report: String?, colors: LibraryColors, onClose: () -> Unit) {
        if (report == null) return
        CrashReportDialog(
            report = report,
            colors = colors,
            onCopy = { copyToClipboard("crash-report", report) },
            onGitHub = {
                copyToClipboard("crash-report", report)
                openCrashIssue(report)
                onClose()
            },
            onDismiss = onClose,
        )
    }

    /** The newest report for the settings entry; toasts when nothing was ever recorded. */
    private fun latestCrashReport(): String? {
        val report = CrashLog.latestReport(this)
        if (report == null) Toast.makeText(this, "저장된 오류 리포트가 없습니다", Toast.LENGTH_SHORT).show()
        return report
    }

    /** Installs a finished background download, but never on top of an active reading session. */
    @Composable
    private fun UpdateInstallEffect(
        updateState: ml.melun.mangaview.update.AppUpdateState,
        reading: Boolean,
        updates: AppUpdateViewModel,
    ) {
        LaunchedEffect(updateState.file, reading) {
            val file = updateState.file ?: return@LaunchedEffect
            if (reading) return@LaunchedEffect
            lifecycle.withResumed {
                if (updates.consumePendingInstall(file)) installUpdate(file)
            }
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
        // Keep the window chrome on the same palette the Compose surfaces use.
        val background = Color.parseColor(if (dark) "#090A10" else "#F5F7FA")
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = if (dark) 0 else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun openEpisode(episodeId: EpisodeId, position: ReadingPosition?) {
        reader.open(ViewerLaunchSpec(episodeId.seriesId.sourceId, episodeId.seriesId, episodeId, position))
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

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "오류 내용을 클립보드에 복사했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens the prefilled new-issue form; the full report is already on the clipboard for pasting. */
    private fun openCrashIssue(report: String) {
        val url = CrashReportText.issueUrl(report)
        val opened = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
        Toast.makeText(
            this,
            if (opened) "GitHub 새 이슈 페이지를 엽니다 · 전체 오류 내용은 클립보드에 복사했습니다"
            else "GitHub 페이지를 열지 못했습니다. 오류 내용은 클립보드에 복사해 두었습니다",
            Toast.LENGTH_LONG,
        ).show()
    }
}
