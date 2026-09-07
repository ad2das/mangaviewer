package ml.melun.mangaview.source.wfwf

import java.net.URI
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class WfwfAccessImagesTest {
    private val base = URI("https://redirected.example/cv?toon=7&num=12")

    @Test
    fun lazyBodyFallbackIsUsedOnlyWhenNoPrimaryContentImageExists() {
        val document = Jsoup.parse(
            """
                <main><img src="/artwork.jpg"></main>
                <div class="viewer-wrap"><img data-original="/pages/one.jpg"></div>
            """.trimIndent(),
            base.toString(),
        )

        val selected = WfwfAccessImages.select(document, base)

        assertEquals(listOf("/pages/one.jpg"), selected.single().candidates.map { it.path })
    }

    @Test
    fun lazyBodyFallbackCanSupplyAnImageWhenPrimarySelectorsAreAbsent() {
        val document = Jsoup.parse(
            "<main><img data-src='/pages/one.jpg'></main>",
            base.toString(),
        )

        val selected = WfwfAccessImages.select(document, base)

        assertEquals("img:0", selected.single().sourceRecord)
        assertEquals("/pages/one.jpg", selected.single().primary.path)
    }

    @Test
    fun sourceRecordUsesActualDocumentImageIndexEvenWhenAdsComeFirst() {
        val document = Jsoup.parse(
            """
                <img src="/assets/banner.jpg" alt="광고">
                <div class="viewer-wrap"><img data-original="/pages/one.jpg"></div>
            """.trimIndent(),
            base.toString(),
        )

        val selected = WfwfAccessImages.select(document, base)

        assertEquals("img:1", selected.single().sourceRecord)
    }
}
