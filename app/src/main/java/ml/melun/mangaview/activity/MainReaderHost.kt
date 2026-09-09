package ml.melun.mangaview.activity

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ml.melun.mangaview.viewer.runtime.ViewerLaunchSpec

/** Retains the library's window and composition while a reader occupies its content area. */
internal class MainReaderHost(private val activity: ComponentActivity) {
    @Volatile var current: EngineViewerScreen? = null
        private set
    private val showing = MutableStateFlow(false)
    val visible = showing.asStateFlow()
    private var root: FrameLayout? = null
    private var library = emptyList<Pair<View, Int>>()
    private var windowState: ReaderWindowState? = null
    private var transition: Job? = null
    private var retiring: EngineViewerScreen? = null
    private var pendingSpec: ViewerLaunchSpec? = null
    private var generation = 0L
    private var destroyed = false
    private val libraryLifecycle = LibraryReaderLifecycle(activity)
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeReader()
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, back)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        for (index in 0 until content.childCount) content.getChildAt(index).setViewTreeLifecycleOwner(libraryLifecycle)
    }

    fun open(spec: ViewerLaunchSpec) {
        if (destroyed) return
        val ticket = ++generation
        pendingSpec = spec
        transition?.cancel()
        val previous = current ?: retiring
        current = null
        retiring = previous
        previous?.enterBackground()
        previous?.close()
        showing.value = true
        libraryLifecycle.show(false)
        back.remove()
        activity.onBackPressedDispatcher.addCallback(activity, back)
        back.isEnabled = true
        transition = activity.lifecycleScope.launch {
            try { previous?.awaitEngineClosed() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (retiring === previous) retiring = null
                if (ticket == generation && !destroyed) closeReader()
                reportCloseFailure(failure)
                return@launch
            }
            if (retiring === previous) retiring = null
            if (ticket == generation && !destroyed) attach(spec)
        }
    }

    private fun attach(spec: ViewerLaunchSpec) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (windowState == null) {
            windowState = ReaderWindowState(activity)
            library = (0 until content.childCount).map { content.getChildAt(it).let { child -> child to child.visibility } }
        }
        val reader = EngineViewerScreen(activity, spec, ::closeReader, { episode ->
            open(ViewerLaunchSpec(episode.seriesId.sourceId, episode.seriesId, episode))
        })
        current = reader
        pendingSpec = null
        try {
            val next = reader.create()
            windowState?.enter()
            root?.let(content::removeView)
            library.forEach { it.first.visibility = View.GONE }
            root = next
            content.addView(next, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            next.requestApplyInsets()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) reader.enterForeground()
            reader.open()
        } catch (failure: Exception) {
            closeReader()
            android.util.Log.e("MainReaderHost", "reader launch failed", failure)
            Toast.makeText(activity, failure.message ?: "뷰어를 열지 못했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    fun closeReader() {
        generation++
        pendingSpec = null
        transition?.cancel()
        transition = null
        current?.let {
            retiring = it
            it.enterBackground()
            it.close()
            activity.lifecycleScope.launch {
                try { it.awaitEngineClosed() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { reportCloseFailure(failure) }
                finally { if (retiring === it) retiring = null }
            }
        }
        current = null
        root?.let { (it.parent as? ViewGroup)?.removeView(it) }
        root = null
        library.forEach { (view, visibility) -> view.visibility = visibility }
        library = emptyList()
        windowState?.restore()
        windowState = null
        back.isEnabled = false
        showing.value = false
        libraryLifecycle.show(true)
    }

    fun enterForeground() { current?.enterForeground() }
    private fun reportCloseFailure(failure: Exception) {
        android.util.Log.e("MainReaderHost", "reader cleanup failed", failure)
        if (!destroyed) Toast.makeText(activity, "뷰어를 닫는 중 오류가 발생했습니다", Toast.LENGTH_SHORT).show()
    }
    fun enterBackground() { current?.enterBackground() }
    fun destroy() { destroyed = true; closeReader() }
    fun saveState(out: Bundle) {
        (current?.restorationSpec() ?: pendingSpec)?.let { out.putBundle(STATE, ViewerScreenState.write(it)) }
    }
    fun restore(state: Bundle?) { state?.getBundle(STATE)?.let { open(ViewerScreenState.read(it)) } }

    private companion object { const val STATE = "main.reader.session" }
}

/** Hidden library collectors pause while their composition and remembered scroll positions survive. */
private class LibraryReaderLifecycle(private val activity: ComponentActivity) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private var visible = true
    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, _ -> update() })
        update()
    }
    fun show(value: Boolean) { visible = value; update() }
    private fun update() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        val host = activity.lifecycle.currentState
        registry.currentState = if (visible || host < Lifecycle.State.STARTED) host else Lifecycle.State.CREATED
    }
}

@Suppress("DEPRECATION")
private class ReaderWindowState(private val activity: ComponentActivity) {
    private val window = activity.window
    private val statusColor = window.statusBarColor
    private val navigationColor = window.navigationBarColor
    private val systemUi = window.decorView.systemUiVisibility
    private val softInput = window.attributes.softInputMode

    fun enter() {
        activity.currentFocus?.let { focused ->
            activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.insetsController?.hide(WindowInsets.Type.ime())
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
    }

    fun restore() {
        window.statusBarColor = statusColor
        window.navigationBarColor = navigationColor
        window.setSoftInputMode(softInput)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(true)
        window.decorView.systemUiVisibility = systemUi
        window.decorView.requestApplyInsets()
    }
}
