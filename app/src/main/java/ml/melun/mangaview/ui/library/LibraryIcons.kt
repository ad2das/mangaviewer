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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

internal enum class LibraryIcon { HOME, SEARCH, LIBRARY, PROFILE, BACK, HEART, DOWNLOAD, MORE, SITE, REFRESH }

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
        }
    }
}

private val DrawScope.iconStroke: Float get() = minOf(size.width, size.height) * .105f

private fun DrawScope.drawHome(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * .12f, h * .47f); lineTo(w * .5f, h * .15f); lineTo(w * .88f, h * .47f)
        lineTo(w * .77f, h * .47f); lineTo(w * .77f, h * .84f); lineTo(w * .57f, h * .84f)
        lineTo(w * .57f, h * .61f); lineTo(w * .43f, h * .61f); lineTo(w * .43f, h * .84f)
        lineTo(w * .23f, h * .84f); lineTo(w * .23f, h * .47f); close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawSearch(color: Color) {
    val stroke = iconStroke
    drawCircle(color, size.width * .27f, Offset(size.width * .43f, size.height * .42f), style = Stroke(stroke))
    drawLine(color, Offset(size.width * .62f, size.height * .61f), Offset(size.width * .87f, size.height * .86f), stroke, StrokeCap.Round)
}

private fun DrawScope.drawLibrary(color: Color) {
    val w = size.width
    val h = size.height
    drawRect(color, Offset(w * .16f, h * .22f), Size(w * .13f, h * .6f))
    drawRect(color, Offset(w * .36f, h * .16f), Size(w * .13f, h * .66f))
    drawRoundRect(color, Offset(w * .56f, h * .24f), Size(w * .3f, h * .58f), CornerRadius(iconStroke))
    drawLine(Color.White, Offset(w * .63f, h * .4f), Offset(w * .79f, h * .4f), iconStroke * .45f)
    drawLine(Color.White, Offset(w * .63f, h * .55f), Offset(w * .79f, h * .55f), iconStroke * .45f)
}

private fun DrawScope.drawProfile(color: Color) {
    val w = size.width
    val h = size.height
    drawCircle(color.copy(alpha = .14f), w * .48f, Offset(w / 2, h / 2))
    drawCircle(color, w * .14f, Offset(w / 2, h * .39f))
    drawArc(color, 195f, 150f, true, Offset(w * .25f, h * .45f), Size(w * .5f, h * .4f))
}

private fun DrawScope.drawBack(color: Color) {
    val stroke = iconStroke
    drawLine(color, Offset(size.width * .72f, size.height * .16f), Offset(size.width * .28f, size.height * .5f), stroke, StrokeCap.Round)
    drawLine(color, Offset(size.width * .28f, size.height * .5f), Offset(size.width * .72f, size.height * .84f), stroke, StrokeCap.Round)
}

private fun DrawScope.drawHeart(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * .5f, h * .82f)
        cubicTo(w * .15f, h * .6f, w * .08f, h * .31f, w * .28f, h * .2f)
        cubicTo(w * .39f, h * .14f, w * .48f, h * .2f, w * .5f, h * .29f)
        cubicTo(w * .52f, h * .2f, w * .61f, h * .14f, w * .72f, h * .2f)
        cubicTo(w * .92f, h * .31f, w * .85f, h * .6f, w * .5f, h * .82f)
    }
    drawPath(path, color, style = Stroke(iconStroke, cap = StrokeCap.Round))
}

private fun DrawScope.drawDownload(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = iconStroke
    drawLine(color, Offset(w * .5f, h * .13f), Offset(w * .5f, h * .64f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * .28f, h * .46f), Offset(w * .5f, h * .68f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * .72f, h * .46f), Offset(w * .5f, h * .68f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * .2f, h * .84f), Offset(w * .8f, h * .84f), stroke, StrokeCap.Round)
}

private fun DrawScope.drawMore(color: Color) {
    repeat(3) { drawCircle(color, iconStroke * .65f, Offset(size.width / 2, size.height * (.28f + it * .22f))) }
}

private fun DrawScope.drawSite(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * .14f, h * .65f); lineTo(w * .45f, h * .08f); lineTo(w * .42f, h * .36f)
        lineTo(w * .84f, h * .28f); lineTo(w * .53f, h * .88f); lineTo(w * .57f, h * .56f); close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawRefresh(color: Color) {
    drawArc(color, 35f, 275f, false, style = Stroke(iconStroke, cap = StrokeCap.Round))
    val path = Path().apply {
        moveTo(size.width * .76f, size.height * .16f)
        lineTo(size.width * .9f, size.height * .37f)
        lineTo(size.width * .65f, size.height * .35f)
        close()
    }
    drawPath(path, color)
}
