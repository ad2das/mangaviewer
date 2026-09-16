package ml.melun.mangaview.ui.library

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
internal fun SearchLoading(colors: LibraryColors) {
    val transition = rememberInfiniteTransition(label = "searchLoading")
    val opacity by transition.animateFloat(0.4f, 0.85f,
        infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "placeholderOpacity")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BasicText("작품을 찾는 중…", Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            }, style = hintStyle(colors, 13))
        }
        items(5) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.card)
                .padding(12.dp).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp, 96.dp).clip(RoundedCornerShape(12.dp))
                    .alpha(opacity).background(colors.outline))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).alpha(opacity), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.fillMaxWidth(0.8f).height(15.dp).clip(RoundedCornerShape(5.dp)).background(colors.outline))
                    Box(Modifier.fillMaxWidth(0.55f).height(11.dp).clip(RoundedCornerShape(5.dp)).background(colors.outline))
                    Box(Modifier.width(40.dp).height(20.dp).clip(RoundedCornerShape(5.dp)).background(colors.accentSurface))
                }
            }
        }
    }
}
