package ml.melun.mangaview.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

internal enum class LibraryIcon {
    HOME, SEARCH, LIBRARY, PROFILE, BACK, HEART, DOWNLOAD, MORE, SITE, REFRESH,
    STAR, CHECK, CLOSE, PLAY, BOOKMARK
}

@Composable
internal fun LibraryIconView(icon: LibraryIcon, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        when (icon) {
            LibraryIcon.HOME -> drawHome(color)
            LibraryIcon.SEARCH -> drawSearch(color)
            LibraryIcon.LIBRARY -> drawLibrary(color)
            LibraryIcon.PROFILE -> drawProfile(color)
            LibraryIcon.BACK -> drawBack(color)
            LibraryIcon.HEART -> drawHeart(color)
            LibraryIcon.DOWNLOAD -> drawDownload(color)
            LibraryIcon.MORE -> drawMore(color)
            LibraryIcon.SITE -> drawSite(color)
            LibraryIcon.REFRESH -> drawRefresh(color)
            LibraryIcon.STAR -> drawStar(color)
            LibraryIcon.CHECK -> drawCheck(color)
            LibraryIcon.CLOSE -> drawClose(color)
            LibraryIcon.PLAY -> drawPlay(color)
            LibraryIcon.BOOKMARK -> drawBookmark(color)
        }
    }
}

private val DrawScope.iconStroke: Float get() = minOf(size.width, size.height) * 0.095f

private fun DrawScope.drawHome(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = iconStroke
    val path = Path().apply {
        moveTo(w * 0.15f, h * 0.44f)
        lineTo(w * 0.5f, h * 0.16f)
        lineTo(w * 0.85f, h * 0.44f)
        lineTo(w * 0.85f, h * 0.82f)
        lineTo(w * 0.60f, h * 0.82f)
        lineTo(w * 0.60f, h * 0.56f)
        lineTo(w * 0.40f, h * 0.56f)
        lineTo(w * 0.40f, h * 0.82f)
        lineTo(w * 0.15f, h * 0.82f)
        close()
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawSearch(color: Color) {
    val stroke = iconStroke
    val r = size.width * 0.27f
    val center = Offset(size.width * 0.42f, size.height * 0.42f)
    drawCircle(color, r, center, style = Stroke(stroke, cap = StrokeCap.Round))
    drawLine(
        color,
        Offset(size.width * 0.61f, size.height * 0.61f),
        Offset(size.width * 0.86f, size.height * 0.86f),
        stroke * 1.15f,
        StrokeCap.Round,
    )
}

private fun DrawScope.drawLibrary(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = iconStroke
    drawRoundRect(
        color,
        Offset(w * 0.16f, h * 0.20f),
        Size(w * 0.16f, h * 0.62f),
        CornerRadius(stroke * 0.6f, stroke * 0.6f),
        style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawRoundRect(
        color,
        Offset(w * 0.38f, h * 0.15f),
        Size(w * 0.16f, h * 0.67f),
        CornerRadius(stroke * 0.6f, stroke * 0.6f),
        style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    val path = Path().apply {
        moveTo(w * 0.62f, h * 0.22f)
        lineTo(w * 0.80f, h * 0.28f)
        lineTo(w * 0.64f, h * 0.82f)
        lineTo(w * 0.46f, h * 0.76f)
        close()
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawProfile(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = iconStroke
    drawCircle(color, w * 0.18f, Offset(w * 0.5f, h * 0.35f), style = Stroke(stroke))
    val body = Path().apply {
        moveTo(w * 0.20f, h * 0.80f)
        cubicTo(w * 0.20f, h * 0.62f, w * 0.34f, h * 0.58f, w * 0.50f, h * 0.58f)
        cubicTo(w * 0.66f, h * 0.58f, w * 0.80f, h * 0.62f, w * 0.80f, h * 0.80f)
    }
    drawPath(body, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawBack(color: Color) {
    val stroke = iconStroke * 1.1f
    val path = Path().apply {
        moveTo(size.width * 0.64f, size.height * 0.20f)
        lineTo(size.width * 0.34f, size.height * 0.50f)
        lineTo(size.width * 0.64f, size.height * 0.80f)
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawHeart(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = iconStroke
    val path = Path().apply {
        moveTo(w * 0.50f, h * 0.80f)
        cubicTo(w * 0.16f, h * 0.58f, w * 0.10f, h * 0.32f, w * 0.28f, h * 0.22f)
        cubicTo(w * 0.40f, h * 0.15f, w * 0.48f, h * 0.22f, w * 0.50f, h * 0.30f)
        cubicTo(w * 0.52f, h * 0.22f, w * 0.60f, h * 0.15f, w * 0.72f, h * 0.22f)
        cubicTo(w * 0.90f, h * 0.32f, w * 0.84f, h * 0.58f, w * 0.50f, h * 0.80f)
        close()
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawDownload(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = iconStroke
    drawLine(color, Offset(w * 0.50f, h * 0.16f), Offset(w * 0.50f, h * 0.62f), stroke, StrokeCap.Round)
    val arrow = Path().apply {
        moveTo(w * 0.30f, h * 0.44f)
        lineTo(w * 0.50f, h * 0.64f)
        lineTo(w * 0.70f, h * 0.44f)
    }
    drawPath(arrow, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val tray = Path().apply {
        moveTo(w * 0.20f, h * 0.74f)
        lineTo(w * 0.20f, h * 0.82f)
        lineTo(w * 0.80f, h * 0.82f)
        lineTo(w * 0.80f, h * 0.74f)
    }
    drawPath(tray, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawMore(color: Color) {
    val r = iconStroke * 0.8f
    drawCircle(color, r, Offset(size.width * 0.50f, size.height * 0.26f))
    drawCircle(color, r, Offset(size.width * 0.50f, size.height * 0.50f))
    drawCircle(color, r, Offset(size.width * 0.50f, size.height * 0.74f))
}

private fun DrawScope.drawSite(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.56f, h * 0.14f)
        lineTo(w * 0.22f, h * 0.52f)
        lineTo(w * 0.46f, h * 0.52f)
        lineTo(w * 0.44f, h * 0.86f)
        lineTo(w * 0.78f, h * 0.44f)
        lineTo(w * 0.54f, h * 0.44f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawRefresh(color: Color) {
    val stroke = iconStroke
    drawArc(color, 45f, 270f, false, style = Stroke(stroke, cap = StrokeCap.Round))
    val arrow = Path().apply {
        moveTo(size.width * 0.74f, size.height * 0.14f)
        lineTo(size.width * 0.90f, size.height * 0.34f)
        lineTo(size.width * 0.66f, size.height * 0.34f)
        close()
    }
    drawPath(arrow, color)
}

private fun DrawScope.drawStar(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.50f, h * 0.14f)
        lineTo(w * 0.61f, h * 0.38f)
        lineTo(w * 0.87f, h * 0.38f)
        lineTo(w * 0.66f, h * 0.54f)
        lineTo(w * 0.74f, h * 0.79f)
        lineTo(w * 0.50f, h * 0.63f)
        lineTo(w * 0.26f, h * 0.79f)
        lineTo(w * 0.34f, h * 0.54f)
        lineTo(w * 0.13f, h * 0.38f)
        lineTo(w * 0.39f, h * 0.38f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawCheck(color: Color) {
    val stroke = iconStroke * 1.15f
    val path = Path().apply {
        moveTo(size.width * 0.22f, size.height * 0.52f)
        lineTo(size.width * 0.42f, size.height * 0.72f)
        lineTo(size.width * 0.78f, size.height * 0.30f)
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawClose(color: Color) {
    val stroke = iconStroke * 1.1f
    drawLine(color, Offset(size.width * 0.26f, size.height * 0.26f), Offset(size.width * 0.74f, size.height * 0.74f), stroke, StrokeCap.Round)
    drawLine(color, Offset(size.width * 0.74f, size.height * 0.26f), Offset(size.width * 0.26f, size.height * 0.74f), stroke, StrokeCap.Round)
}

private fun DrawScope.drawPlay(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.30f, h * 0.22f)
        lineTo(w * 0.78f, h * 0.50f)
        lineTo(w * 0.30f, h * 0.78f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawBookmark(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = iconStroke
    val path = Path().apply {
        moveTo(w * 0.24f, h * 0.18f)
        lineTo(w * 0.76f, h * 0.18f)
        lineTo(w * 0.76f, h * 0.82f)
        lineTo(w * 0.50f, h * 0.65f)
        lineTo(w * 0.24f, h * 0.82f)
        close()
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
