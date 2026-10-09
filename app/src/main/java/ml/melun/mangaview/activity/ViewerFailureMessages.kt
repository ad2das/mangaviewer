package ml.melun.mangaview.activity

import ml.melun.mangaview.engine.content.PageHttpException

/**
 * Korean copy for the viewer failure card. Provider exceptions carry raw English text
 * ("Page request returned HTTP 403"); it must never reach the user-facing card.
 */
internal fun viewerFailureMessage(failure: Throwable): String = when (failure) {
    is PageHttpException -> "페이지를 불러오지 못했습니다 (HTTP ${failure.statusCode})"
    else -> failure.message?.takeIf { message -> message.isNotBlank() && message.any { it in '가'..'힣' } }
        ?: "페이지를 불러오지 못했습니다"
}

/**
 * Failure surface over the page: what went wrong, and the two ways forward (leave, or retry in
 * place). Reads on the black reading background in both themes; the accent follows the palette.
 */
internal class ViewerFailureCard(
    context: android.content.Context,
    private val close: () -> Unit,
    private val retry: () -> Unit,
) : android.widget.LinearLayout(context) {
    private val icon = android.widget.ImageView(context)
    private val title = android.widget.TextView(context)
    private val detail = android.widget.TextView(context)
    private val closeButton = android.widget.TextView(context)
    private val retryButton = android.widget.TextView(context)

    init {
        orientation = VERTICAL
        contentDescription = "viewer-failure"
        visibility = android.view.View.GONE
        isClickable = true
        elevation = context.dpf(10f)
        setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(16))
        val header = android.widget.LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(icon, LayoutParams(context.dp(22), context.dp(22)))
            addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(10) })
        }
        icon.setImageResource(ml.melun.mangaview.R.drawable.ic_error_fill1)
        title.text = DEFAULT_HEADING
        addView(header)
        addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(6)
            marginStart = context.dp(32)
        })
        val row = android.widget.LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(44)))
            addView(retryButton, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(44)).apply {
                marginStart = context.dp(8)
            })
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(14)
        })
        listOf(closeButton to "닫기", retryButton to "다시 시도").forEach { (button, label) ->
            button.text = label
            button.gravity = android.view.Gravity.CENTER
            button.isClickable = true
            button.isFocusable = true
            button.setPadding(context.dp(18), 0, context.dp(18), 0)
        }
        closeButton.setOnClickListener { close() }
        retryButton.setOnClickListener { retry() }
        applyPalette(ViewerPalette.of(dark = true))
    }

    fun bind(message: String, heading: String = DEFAULT_HEADING) {
        title.text = heading
        detail.text = message
    }

    private companion object {
        const val DEFAULT_HEADING = "페이지를 표시하지 못했습니다"
    }

    fun applyPalette(value: ViewerPalette) {
        background = roundedFill(0xF2181C28.toInt(), context.dpf(20f), context.dp(1), 0x1FFFFFFF)
        icon.tint(value.error)
        title.style(15f, ml.melun.mangaview.ui.AppFonts.BOLD, android.graphics.Color.WHITE)
        detail.style(13f, ml.melun.mangaview.ui.AppFonts.REGULAR, 0xB3FFFFFF.toInt())
        closeButton.style(14f, ml.melun.mangaview.ui.AppFonts.SEMIBOLD, android.graphics.Color.WHITE)
        retryButton.style(14f, ml.melun.mangaview.ui.AppFonts.BOLD, value.onAccent)
        val dark = value.copy(ripple = 0x29FFFFFF)
        closeButton.background = pressable(dark, 0x14FFFFFF, context.dpf(14f))
        retryButton.background = pressable(dark, value.accent, context.dpf(14f))
    }
}
