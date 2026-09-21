package ml.melun.mangaview.app

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.roundToInt
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "NewxtoonClearance"

/**
 * The interstitial is localized, so its title cannot identify it; these markers are the
 * language-independent signal that the challenge is still up. The real site never has them.
 */
internal const val CLEARANCE_PROBE_SCRIPT = """
(function(){
  try{
    if(typeof window._cf_chl_opt!=="undefined"){return "challenge";}
    var selectors=["#challenge-stage","#challenge-running","#cf-challenge-running",".cf-turnstile","#turnstile-wrapper","[id*=turnstile]","[class*=turnstile]"];
    for(var i=0;i<selectors.length;i++){
      if(document.querySelector(selectors[i])){return "challenge";}
    }
    if(!document.body||document.body.innerHTML.length<2000){return "wait";}
    return "clear";
  }catch(error){return "wait";}
})()
"""

internal fun captureChallengeFrame(context: android.content.Context, webView: WebView, name: String) {
    runCatching {
        webView.invalidate()
        val bitmap = android.graphics.Bitmap.createBitmap(webView.width, webView.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        webView.draw(android.graphics.Canvas(bitmap))
        val dir = java.io.File(context.getExternalFilesDir(null), "newxtoon-search")
        dir.mkdirs()
        java.io.FileOutputStream(java.io.File(dir, name)).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
        }
        bitmap.recycle()
        Log.i(TAG, "captured $name")
    }.onFailure { Log.w(TAG, "capture failed", it) }
}

/** Detach before destroy; a renderer gone view must never be used again. */
internal fun teardownChallengeWebView(webView: WebView) {
    runCatching { webView.stopLoading() }
    runCatching { (webView.parent as? android.view.ViewGroup)?.removeView(webView) }
    runCatching { webView.destroy() }
}

/** Managed challenges render a Turnstile checkbox; a trusted tap can clear it. */
internal fun clickChallengeFrames(webView: WebView, raw: String, attempt: Int) {
    val json = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    val probe = json?.let { runCatching { JSONObject(it) }.getOrNull() }
    val dpr = (probe?.optDouble("dpr", 2.6) ?: 2.6).toFloat().coerceAtLeast(1f)
    val viewHeight = webView.height.toFloat().coerceAtLeast(1f)
    val frames = probe?.optJSONArray("frames")
    if (frames != null) {
        for (index in 0 until frames.length()) {
            val frame = frames.optJSONObject(index) ?: continue
            if (!frame.optString("src").contains("challenges.cloudflare.com")) continue
            val x = frame.optDouble("x", 0.0).toFloat() * dpr
            val y = frame.optDouble("y", 0.0).toFloat() * dpr
            val width = frame.optDouble("w", 0.0).toFloat() * dpr
            val height = frame.optDouble("h", 0.0).toFloat() * dpr
            if (width < 120f || height < 40f || height > viewHeight * 0.25f) continue
            Log.i(TAG, "tapping turnstile frame $x,$y ${width}x$height")
            tap(webView, x + minOf(20f * dpr, width / 4f), y + height / 2f)
            return
        }
    }
    val hosts = probe?.optJSONArray("hosts")
    if (hosts != null) {
        for (index in 0 until hosts.length()) {
            val host = hosts.optJSONObject(index) ?: continue
            val x = host.optDouble("x", 0.0).toFloat() * dpr
            val y = host.optDouble("y", 0.0).toFloat() * dpr
            val width = host.optDouble("w", 0.0).toFloat() * dpr
            val height = host.optDouble("h", 0.0).toFloat() * dpr
            if (width < 120f || height < 40f || height > viewHeight * 0.25f) continue
            Log.i(TAG, "tapping widget host ${host.optString("sel")} $x,$y ${width}x$height")
            tap(webView, x + minOf(20f * dpr, width / 4f), y + height / 2f)
            return
        }
    }
    // No widget was found in the light DOM: the managed challenge is running its non-interactive
    // verification, which needs no input. Tapping a guessed coordinate would inject synthetic
    // touches into a page that never asked for them, so the ladder leaves the page alone.
    Log.i(TAG, "no turnstile widget visible; leaving the verification alone attempt=$attempt")
}

private fun tap(webView: WebView, x: Float, y: Float) {
    val properties = arrayOf(MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_FINGER
    })
    fun event(action: Int, at: Long, px: Float, py: Float, pressed: Boolean): MotionEvent {
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = px
            this.y = py
            pressure = if (pressed) 1f else 0f
            size = 1f
        })
        return MotionEvent.obtain(0L, at, action, 1, properties, coordinates, 0, 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
    val downTime = SystemClock.uptimeMillis()
    fun send(action: Int, offset: Long, px: Float, py: Float, pressed: Boolean): Boolean {
        val motion = event(action, downTime + offset, px, py, pressed)
        return webView.dispatchTouchEvent(motion).also { motion.recycle() }
    }
    // An instant down/up reads as a synthetic click; the challenge also samples the
    // pointer path, so the touch drifts a pixel or two before it lifts.
    val down = send(MotionEvent.ACTION_DOWN, 0L, x, y, true)
    send(MotionEvent.ACTION_MOVE, 60L, x + 1.5f, y + 1f, true)
    send(MotionEvent.ACTION_MOVE, 130L, x - 1f, y + 1.5f, true)
    send(MotionEvent.ACTION_MOVE, 200L, x + 0.5f, y, true)
    val up = send(MotionEvent.ACTION_UP, 280L, x, y, false)
    Log.i(TAG, "tap ${x.roundToInt()},${y.roundToInt()} down=$down up=$up")
}
