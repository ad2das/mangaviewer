package ml.melun.mangaview.activity

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import ml.melun.mangaview.ViewerApplication
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
    private lateinit var failureCard: LinearLayout
    private lateinit var failureText: TextView
    private lateinit var dimOverlay: View
    private lateinit var settingsPanel: ViewerReaderSettingsPanel
    private var appliedSettings: ViewerSettings? = null
    var volumeKeysEnabled = false
        private set
    private var foreground = false
    private lateinit var chrome: ViewerChromeController

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
        applyImmersive(false)
        applyKeepScreenOn(false)
    }

    fun showFailure(failure: Throwable) {
        loading.failed()
        failureText.text = failure.message?.takeIf(String::isNotBlank) ?: "페이지를 불러오지 못했습니다"
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
        appliedSettings = settings
        volumeKeysEnabled = settings.volumeKeyNavigation
        if (::dimOverlay.isInitialized) dimOverlay.alpha = settings.readerDimPercent / 100f
        if (::chrome.isInitialized) chrome.setImmersiveActive(settings.immersiveMode)
        if (foreground) {
            applyKeepScreenOn(settings.keepScreenOn)
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
            settingsPanel.open(appliedSettings ?: ViewerSettings())
            if (::chrome.isInitialized) chrome.setAutoHidePaused(true)
        }
    }

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

    fun content(runtime: EngineViewerRuntime): FrameLayout =
        ViewerTouchRoot(this).apply {
        onSurfaceTap = {
            // Immersive reading owns plain taps: the reader asked for the chrome to stay hidden
            // until a deliberate long press requests it.
            if (::chrome.isInitialized && appliedSettings?.immersiveMode != true) chrome.toggle()
        }
        onSurfaceLongPress = {
            if (::chrome.isInitialized && appliedSettings?.immersiveMode == true) chrome.toggle()
        }
        onSurfaceDoubleTap = { x, y ->
            // Tap coordinates arrive in root space; zoom transforms are surface-local.
            val surface = runtime.surface
            surface.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            surface.toggleZoom(x - surface.left, y - surface.top)
        }
        setBackgroundColor(Color.BLACK)
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
        failureCard = buildFailureCard()
        addView(failureCard, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            val margin = dp(24)
            setMargins(margin, margin, margin, margin + dp(48))
        })
        // Chrome installs before the panel so the panel and its scrim stay above the bars.
        installChrome(this, runtime)
        settingsPanel = ViewerReaderSettingsPanel(this@ViewerScreenUi).apply {
            onDimChanged = { percent -> dimOverlay.alpha = percent / 100f }
            onDimCommitted = { percent -> persistSettings { it.copy(readerDimPercent = percent) } }
            onKeepScreenOn = { enabled -> persistSettings { it.copy(keepScreenOn = enabled) } }
            onVolumeKeys = { enabled -> persistSettings { it.copy(volumeKeyNavigation = enabled) } }
            onClose = { dismiss() }
        }
        addView(settingsPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        }

    private fun buildFailureCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        contentDescription = "viewer-failure"
        val padH = dp(20)
        setPadding(padH, dp(14), padH, dp(14))
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(0xF0181A22.toInt())
            setStroke(dp(1), 0x33FFFFFF.toInt())
        }
        visibility = View.GONE
        isClickable = true
        failureText = TextView(this@ViewerScreenUi).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        addView(failureText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        row.addView(actionButton("닫기", accent = false) { actions.back() }, LinearLayout.LayoutParams(dp(72), dp(48)))
        row.addView(actionButton("다시 시도", accent = true) { retryFromFailure() },
            LinearLayout.LayoutParams(dp(96), dp(48)).apply { marginStart = dp(8) })
        addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
    }

    private fun actionButton(text: String, accent: Boolean, click: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(if (accent) 0xFF7C5CFF.toInt() else 0xFF181C26.toInt())
            setStroke(dp(1), if (accent) 0x669080FF.toInt() else 0x33FFFFFF.toInt())
        }
        setOnClickListener { click() }
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
            loading.active || chrome.contains(x, y) || settingsPanel.visible ||
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
            settingsPanel.dismiss()
            return true
        }
        if (::chrome.isInitialized && chrome.visible) {
            chrome.hide()
            return true
        }
        return false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun View.containsPoint(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    private companion object {
        const val CARD_ANIMATION_MS = 180L
    }
}
