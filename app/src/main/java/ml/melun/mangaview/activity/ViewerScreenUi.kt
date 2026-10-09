package ml.melun.mangaview.activity

import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import ml.melun.mangaview.ViewerApplication
import ml.melun.mangaview.data.settings.ThemeMode
import ml.melun.mangaview.data.settings.ViewerSettings
import ml.melun.mangaview.viewer.runtime.EngineViewerRuntime

/** Owns reader chrome, overlays and preferences for one screen session. */
internal class ViewerScreenUi(
    private val activity: ComponentActivity,
    private val sessionScope: CoroutineScope,
    private val actions: ViewerChromeController.Actions,
    private val retry: () -> Unit,
) : android.content.ContextWrapper(activity) {
    private val window get() = activity.window
    private lateinit var loading: ViewerLoadingOverlay
    private lateinit var failureCard: ViewerFailureCard
    private lateinit var snackbar: ViewerSnackbar
    private lateinit var episodeSheet: ViewerEpisodeSheet
    private lateinit var dimOverlay: View
    private var palette = ViewerPalette.of(dark = true)
    private lateinit var settingsPanel: ViewerReaderSettingsPanel
    private var appliedSettings: ViewerSettings? = null
    var volumeKeysEnabled = false
        private set
    private var foreground = false
    private lateinit var chrome: ViewerChromeController
    private lateinit var autoScroller: ViewerAutoScroller

    fun presentationComplete() {
        loading.complete()
        hideFailureCard()
    }

    fun refreshChrome() {
        if (::chrome.isInitialized) chrome.refresh()
    }

    fun enterForeground() {
        foreground = true
        applyKeepScreenOn(appliedSettings?.keepScreenOn == true)
        applyImmersive(appliedSettings?.immersiveMode == true)
    }

    fun enterBackground() {
        foreground = false
        if (::autoScroller.isInitialized) autoScroller.stop()
        applyImmersive(false)
        applyKeepScreenOn(false)
    }

    /** In-reader message bar; it rises above the bottom chrome while that is showing. */
    fun showMessage(text: String, tone: ViewerSnackbar.Tone = ViewerSnackbar.Tone.INFO,
        actionLabel: String? = null, action: (() -> Unit)? = null) {
        if (!::snackbar.isInitialized) return
        val lift = if (::chrome.isInitialized && chrome.visible) dp(128) else 0
        snackbar.translationY = -lift.toFloat()
        snackbar.show(text, tone, actionLabel, action)
    }

    fun showFailure(failure: Throwable) {
        loading.failed()
        failureCard.bind(viewerFailureMessage(failure))
        failureCard.animate().cancel()
        if (failureCard.visibility != View.VISIBLE) {
            failureCard.alpha = 0f
            failureCard.translationY = dp(16).toFloat()
            failureCard.visibility = View.VISIBLE
        }
        failureCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(CARD_ANIMATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun hideFailureCard() {
        if (failureCard.visibility != View.VISIBLE) return
        failureCard.animate().cancel()
        failureCard.animate()
            .alpha(0f)
            .translationY(dp(12).toFloat())
            .setDuration(CARD_ANIMATION_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                failureCard.visibility = View.GONE
                failureCard.alpha = 1f
                failureCard.translationY = 0f
            }
            .start()
    }

    fun observeReaderSettings() {
        val library = (activity.application as ViewerApplication).graph.userLibrary
        sessionScope.launch {
            library.snapshot
                .catch { failure -> android.util.Log.w("ViewerSettings", "reader settings unavailable", failure) }
                .collect { snapshot -> applyReaderPreferences(snapshot.settings) }
        }
    }

    private fun applyReaderPreferences(settings: ViewerSettings) {
        if (appliedSettings == settings) return
        val dark = settings.darkTheme(systemDark())
        if (appliedSettings?.darkTheme(systemDark()) != dark) applyPalette(ViewerPalette.of(dark))
        appliedSettings = settings
        volumeKeysEnabled = settings.volumeKeyNavigation
        if (::autoScroller.isInitialized) autoScroller.speed = settings.autoScrollSpeed
        if (::dimOverlay.isInitialized) dimOverlay.alpha = settings.readerDimPercent / 100f
        if (::chrome.isInitialized) chrome.setImmersiveActive(settings.immersiveMode)
        if (foreground) {
            applyKeepScreenOn(settings.keepScreenOn || (::autoScroller.isInitialized && autoScroller.running))
            applyImmersive(settings.immersiveMode)
        }
    }

    private fun persistSettings(transform: (ViewerSettings) -> ViewerSettings) {
        val library = (activity.application as ViewerApplication).graph.userLibrary
        sessionScope.launch {
            try {
                library.updateSettings(transform)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.e("ViewerSettings", "reader setting not saved", failure)
            }
        }
    }

    fun toggleSettingsPanel() {
        if (settingsPanel.visible) {
            settingsPanel.dismiss()
            if (::chrome.isInitialized) chrome.setAutoHidePaused(false)
        } else {
            settingsPanel.open(appliedSettings ?: ViewerSettings(), systemDark())
            if (::chrome.isInitialized) chrome.setAutoHidePaused(true)
        }
    }

    private fun applyPalette(value: ViewerPalette) {
        palette = value
        if (::chrome.isInitialized) chrome.applyPalette(value)
        if (::settingsPanel.isInitialized) settingsPanel.applyPalette(value)
        if (::failureCard.isInitialized) failureCard.applyPalette(value)
        if (::loading.isInitialized) loading.applyPalette(value)
        if (::snackbar.isInitialized) snackbar.applyPalette(value)
        if (::episodeSheet.isInitialized) episodeSheet.applyPalette(value)
    }

    fun showEpisodesLoading() {
        if (::chrome.isInitialized) chrome.hide()
        episodeSheet.showLoading()
    }

    fun showEpisodes(titles: List<String>, currentIndex: Int, pick: (Int) -> Unit) =
        episodeSheet.showEpisodes(titles, currentIndex, pick)

    fun dismissEpisodes() = episodeSheet.dismiss()

    private fun retryFromFailure() {
        hideFailureCard()
        loading.restart()
        retry()
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (enabled) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (enabled) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        }
    }

    private fun ViewerTouchRoot.installGestures(runtime: EngineViewerRuntime) {
        onSurfaceTap = {
            // Immersive reading owns plain taps: the reader asked for the chrome to stay hidden
            // until a deliberate long press requests it.
            if (::chrome.isInitialized && appliedSettings?.immersiveMode != true) chrome.toggle()
        }
        onSurfaceTapAt = { _, y -> tapPage(runtime, y) }
        onSurfaceDown = { if (::autoScroller.isInitialized) autoScroller.stop() }
        onSurfaceLongPress = {
            if (::chrome.isInitialized && appliedSettings?.immersiveMode == true) chrome.toggle()
        }
        onSurfaceDoubleTap = { x, y ->
            // Tap coordinates arrive in root space; zoom transforms are surface-local.
            val surface = runtime.surface
            surface.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            surface.toggleZoom(x - surface.left, y - surface.top)
        }
    }

    /**
     * Tap paging: with the chrome hidden, the top third steps one screen back and the bottom third
     * one screen forward; the middle third still toggles the chrome.
     */
    private fun tapPage(runtime: EngineViewerRuntime, y: Float): Boolean {
        if (appliedSettings?.tapPaging != true || (::chrome.isInitialized && chrome.visible)) return false
        val surface = runtime.surface
        val local = y - surface.top
        val zone = surface.height / 3f
        if (surface.height <= 0 || local in zone..(zone * 2)) return false
        val forward = local > zone * 2
        surface.stepViewport(if (forward) surface.height * PAGE_STEP else -surface.height * PAGE_STEP)
        return true
    }

    fun toggleAutoScroll() {
        if (::autoScroller.isInitialized) autoScroller.toggle()
    }

    private fun autoScrollChanged(running: Boolean) {
        if (::chrome.isInitialized) {
            chrome.setAutoScrollActive(running)
            if (running) chrome.hide()
        }
        if (foreground) applyKeepScreenOn(running || appliedSettings?.keepScreenOn == true)
    }

    fun content(runtime: EngineViewerRuntime): FrameLayout =
        ViewerTouchRoot(this).apply {
        installGestures(runtime)
        autoScroller = ViewerAutoScroller(resources.displayMetrics.density,
            { pixels -> runtime.surface.stepViewport(pixels) }, ::autoScrollChanged)
            .also { it.speed = appliedSettings?.autoScrollSpeed ?: it.speed }
        setBackgroundColor(Color.BLACK)
        // Chrome bars and the settings sheet extend into the system-bar insets themselves.
        clipToPadding = false
        installSystemBarInsets()
        addView(runtime.surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        dimOverlay = View(this@ViewerScreenUi).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = false
            isFocusable = false
            alpha = 0f
        }
        addView(dimOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        loading = ViewerLoadingOverlay(this@ViewerScreenUi)
        addView(loading, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        failureCard = ViewerFailureCard(this@ViewerScreenUi, close = { actions.back() }, retry = ::retryFromFailure)
        addView(failureCard, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            val margin = dp(16)
            setMargins(margin, margin, margin, margin + dp(24))
        })
        // Chrome installs before the panel so the panel and its scrim stay above the bars.
        installChrome(this, runtime)
        settingsPanel = ViewerReaderSettingsPanel(this@ViewerScreenUi).apply {
            onDimChanged = { percent -> dimOverlay.alpha = percent / 100f }
            onDimCommitted = { percent -> persistSettings { it.copy(readerDimPercent = percent) } }
            onKeepScreenOn = { enabled -> persistSettings { it.copy(keepScreenOn = enabled) } }
            onVolumeKeys = { enabled -> persistSettings { it.copy(volumeKeyNavigation = enabled) } }
            // The reader's switch is an explicit choice; the library's settings can return to "system".
            onDarkTheme = { enabled ->
                persistSettings { it.copy(themeMode = if (enabled) ThemeMode.DARK else ThemeMode.LIGHT) }
            }
            onTapPaging = { enabled -> persistSettings { it.copy(tapPaging = enabled) } }
            onAutoScrollSpeed = { speed -> persistSettings { it.copy(autoScrollSpeed = speed) } }
            onClose = { toggleSettingsPanel() }
        }
        addView(settingsPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        episodeSheet = ViewerEpisodeSheet(this@ViewerScreenUi)
        addView(episodeSheet, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        snackbar = ViewerSnackbar(this@ViewerScreenUi)
        addView(snackbar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ).apply { setMargins(dp(16), 0, dp(16), dp(20)) })
        applyPalette(palette)
        }

    private fun installChrome(root: ViewerTouchRoot, runtime: EngineViewerRuntime) {
        chrome = ViewerChromeController(
            activity = activity,
            surface = runtime.surface,
            snapshot = runtime::chromeSnapshot,
            actions = actions,
        ).also { controller ->
            controller.install(root)
            controller.setImmersiveActive(appliedSettings?.immersiveMode == true)
        }
        root.excludesSurfaceTap = { x, y ->
            loading.active || chrome.contains(x, y) || settingsPanel.visible || episodeSheet.visible ||
                snackbar.containsPoint(x, y) ||
                (failureCard.visibility == View.VISIBLE && failureCard.containsPoint(x, y))
        }
    }

    private fun FrameLayout.installSystemBarInsets() {
        setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.viewerSafeDrawingInsets()
            if (view.paddingLeft != safe.left || view.paddingTop != safe.top ||
                view.paddingRight != safe.right || view.paddingBottom != safe.bottom
            ) {
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            if (::chrome.isInitialized) chrome.applyInsets(safe)
            if (::settingsPanel.isInitialized) settingsPanel.applyInsets(safe.bottom)
            if (::episodeSheet.isInitialized) episodeSheet.applyInsets(safe.bottom)
            insets
        }
    }

    fun toggleImmersiveMode() {
        val enabled = appliedSettings?.immersiveMode != true
        persistSettings { it.copy(immersiveMode = enabled) }
        applyImmersive(enabled)
        if (::chrome.isInitialized) chrome.setImmersiveActive(enabled)
    }

    /** Lets reader-local overlays consume back before the host closes the whole session. */
    fun handleBack(): Boolean {
        if (::settingsPanel.isInitialized && settingsPanel.visible) {
            toggleSettingsPanel()
            return true
        }
        if (::episodeSheet.isInitialized && episodeSheet.visible) {
            episodeSheet.dismiss()
            return true
        }
        if (::chrome.isInitialized && chrome.visible) {
            chrome.hide()
            return true
        }
        return false
    }

    private fun systemDark(): Boolean = activity.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun View.containsPoint(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    private companion object {
        const val CARD_ANIMATION_MS = 180L
        /** A tap step keeps a sliver of the previous screen so the eye can find its place. */
        const val PAGE_STEP = 0.88
    }
}
