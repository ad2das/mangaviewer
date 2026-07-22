package ml.melun.mangaview.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Minimal status overlay that avoids TextView/JIT/Autofill work on the reader cold path. */
class ReaderStatusOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffcccccc.toInt()
        textSize = 14f * resources.displayMetrics.scaledDensity
    }

    var text: CharSequence = ""
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var textSize: Float
        get() = textPaint.textSize / resources.displayMetrics.scaledDensity
        set(value) {
            textPaint.textSize = value * resources.displayMetrics.scaledDensity
            requestLayout()
            invalidate()
        }

    fun setTextColor(color: Int) {
        textPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (text.isEmpty()) return
        canvas.drawText(
            text,
            0,
            text.length,
            paddingLeft.toFloat(),
            paddingTop - textPaint.fontMetrics.top,
            textPaint
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = paddingLeft + paddingRight + textPaint.measureText(text.toString()).toInt()
        val desiredHeight = paddingTop + paddingBottom +
            (textPaint.fontMetrics.bottom - textPaint.fontMetrics.top).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }
}
