package ml.melun.mangaview.ui.library

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import ml.melun.mangaview.R

/**
 * One icon language for the whole app: Material Symbols Rounded vectors (Apache 2.0), shared with
 * the reader chrome. Filled variants mark a selected or active state.
 */
internal enum class LibraryIcon(@DrawableRes val res: Int) {
    HOME(R.drawable.ic_home),
    HOME_FILLED(R.drawable.ic_home_fill1),
    SEARCH(R.drawable.ic_search),
    LIBRARY(R.drawable.ic_collections_bookmark),
    LIBRARY_FILLED(R.drawable.ic_collections_bookmark_fill1),
    PROFILE(R.drawable.ic_person),
    BACK(R.drawable.ic_arrow_back_ios_new),
    CHEVRON(R.drawable.ic_chevron_right),
    HEART(R.drawable.ic_favorite),
    HEART_FILLED(R.drawable.ic_favorite_fill1),
    DOWNLOAD(R.drawable.ic_download),
    MORE(R.drawable.ic_more_vert),
    SITE(R.drawable.ic_language),
    REFRESH(R.drawable.ic_refresh),
    STAR(R.drawable.ic_star_fill1),
    CHECK(R.drawable.ic_check),
    CHECK_CIRCLE(R.drawable.ic_check_circle_fill1),
    CLOSE(R.drawable.ic_close),
    PLAY(R.drawable.ic_play_arrow_fill1),
    BOOKMARK(R.drawable.ic_bookmark),
    INFO(R.drawable.ic_info),
    ERROR(R.drawable.ic_error_fill1),
}

@Composable
internal fun LibraryIconView(icon: LibraryIcon, color: Color, modifier: Modifier) {
    Image(
        painterResource(icon.res),
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}

/** The heart reads as a toggle: outline when off, filled when on. */
internal fun heartIcon(active: Boolean): LibraryIcon = if (active) LibraryIcon.HEART_FILLED else LibraryIcon.HEART
