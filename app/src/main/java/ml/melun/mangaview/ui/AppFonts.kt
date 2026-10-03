package ml.melun.mangaview.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import ml.melun.mangaview.R

/**
 * Pretendard is one variable font file; every weight is a `wght` axis position over the same
 * memory-mapped resource (the build stores .ttf uncompressed), so extra weights cost no memory.
 */
internal object AppFonts {
    private val weights = listOf(
        FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold,
        FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black,
    )

    @OptIn(ExperimentalTextApi::class)
    val family: FontFamily = FontFamily(
        weights.map { weight ->
            Font(
                R.font.pretendard_variable,
                weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
            )
        },
    )

    private val views = java.util.concurrent.ConcurrentHashMap<Int, Typeface>()

    /**
     * A platform typeface pinned at one `wght` position, built once per weight. TextView's own
     * fontVariationSettings is not used: it skips a repeated value even after setTypeface has
     * reset the paint, which silently drops the weight on a restyle.
     */
    fun typeface(context: Context, weight: Int): Typeface = views.getOrPut(weight) {
        runCatching {
            val font = android.graphics.fonts.Font.Builder(context.resources, R.font.pretendard_variable)
                .setWeight(weight)
                .setFontVariationSettings("'wght' $weight")
                .build()
            Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(font).build())
                .setSystemFallback("sans-serif")
                .build()
        }.getOrElse { Typeface.create(Typeface.DEFAULT, weight, false) }
    }

    /** Applies Pretendard at an exact weight to a platform TextView. */
    fun apply(view: TextView, weight: Int) {
        val face = typeface(view.context, weight)
        if (view.typeface !== face) view.typeface = face
    }

    const val REGULAR = 400
    const val MEDIUM = 500
    const val SEMIBOLD = 600
    const val BOLD = 700
    const val EXTRABOLD = 800
}
