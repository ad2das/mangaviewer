package ml.melun.mangaview.reader

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ml.melun.mangaview.mangaview.MTitle
import ml.melun.mangaview.mangaview.Manga
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderSessionAutoSplitInstrumentedTest {
    @Test
    fun widePageCreatesTwoDisplayPagesWhilePortraitRemainsSingle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wide = File(context.cacheDir, "auto-split-wide.png")
        val portrait = File(context.cacheDir, "auto-split-portrait.png")
        writeBitmap(wide, 1600, 1000)
        writeBitmap(portrait, 720, 1600)
        val manga = Manga(1, "auto-split", "", MTitle.base_comic).apply {
            mode = 1
        }
        val session = ReaderSession(
            context = context,
            manga = manga,
            title = null,
            viewerWidth = 720,
            viewerHeight = 1280,
            autoCut = true,
            reverse = false,
            preparedKey = null,
            startAtFirstPage = true,
            listener = NoOpListener,
        )

        try {
            val refs = pageRefs(session, manga, listOf(wide.absolutePath, portrait.absolutePath))
            assertEquals(3, refs.size)
            assertEquals(listOf(0, 0, 1), refs.map(::sourceIndex))
            assertEquals(listOf(0, 1, 0), refs.map(::side))
        } finally {
            session.cancel()
            wide.delete()
            portrait.delete()
        }
    }

    @Test
    fun strictSourceBoundsSplitGeneratedNtkPageBeforeFileCacheExists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = "/webtoon/auto-split-instrumented/episode-${System.nanoTime()}"
        val manga = Manga(2, "strict-auto-split", "", MTitle.base_comic).apply {
            mode = 0
            ntkEpisodePath = path
        }
        ReaderImageCache.rememberStrictSourceBounds(path, 0, 1800, 1000)
        val session = ReaderSession(
            context = context,
            manga = manga,
            title = null,
            viewerWidth = 720,
            viewerHeight = 1280,
            autoCut = true,
            reverse = false,
            preparedKey = null,
            startAtFirstPage = true,
            listener = NoOpListener,
        )

        try {
            val refs = pageRefs(
                session,
                manga,
                listOf("https://ntk.invalid/__ntk_img__/0"),
            )
            assertEquals(2, refs.size)
            assertEquals(listOf(0, 0), refs.map(::sourceIndex))
            assertEquals(listOf(0, 1), refs.map(::side))
        } finally {
            session.cancel()
        }
    }

    @Test
    fun splitCropShowsReadingSideFirstWithoutReusingSourceBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manga = Manga(3, "auto-split-pixels", "", MTitle.base_comic).apply {
            mode = 1
        }
        val session = ReaderSession(
            context = context,
            manga = manga,
            title = null,
            viewerWidth = 720,
            viewerHeight = 1280,
            autoCut = true,
            reverse = false,
            preparedKey = null,
            startAtFirstPage = true,
            listener = NoOpListener,
        )
        val firstSource = twoColorSpread()
        val secondSource = twoColorSpread()

        try {
            val first = splitBitmap(session, firstSource, 0)
            val second = splitBitmap(session, secondSource, 1)
            try {
                assertEquals(800, first.width)
                assertEquals(1000, first.height)
                assertEquals(Color.BLUE, first.getPixel(first.width / 2, first.height / 2))
                assertEquals(Color.RED, second.getPixel(second.width / 2, second.height / 2))
            } finally {
                first.recycle()
                second.recycle()
            }
        } finally {
            session.cancel()
            if (!firstSource.isRecycled) firstSource.recycle()
            if (!secondSource.isRecycled) secondSource.recycle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pageRefs(session: ReaderSession, manga: Manga, images: List<String>): List<Any> {
        val method = ReaderSession::class.java.getDeclaredMethod(
            "pageRefsForImages",
            Manga::class.java,
            List::class.java,
        )
        method.isAccessible = true
        return method.invoke(session, manga, images) as List<Any>
    }

    private fun sourceIndex(ref: Any): Int =
        ref.javaClass.getDeclaredField("sourceIndex").apply { isAccessible = true }.getInt(ref)

    private fun side(ref: Any): Int =
        ref.javaClass.getDeclaredField("side").apply { isAccessible = true }.getInt(ref)

    private fun splitBitmap(session: ReaderSession, bitmap: Bitmap, side: Int): Bitmap {
        val method = ReaderSession::class.java.getDeclaredMethod(
            "applyAutoSplit",
            Bitmap::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(session, bitmap, side, true) as Bitmap
    }

    private fun twoColorSpread(): Bitmap =
        Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            canvas.drawColor(Color.RED)
            val paint = android.graphics.Paint().apply { color = Color.BLUE }
            canvas.drawRect(800f, 0f, 1600f, 1000f, paint)
        }

    private fun writeBitmap(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private object NoOpListener : ReaderSession.Listener {
        override fun onPagesReady(count: Int) = Unit
        override fun onPagesAppended(count: Int) = Unit
        override fun onPagesPrepended(count: Int, insertedCount: Int, holdUntilReadyCount: Int) = Unit
        override fun onPagesRemoved(startIndex: Int, removedCount: Int, totalCount: Int) = Unit
        override fun onInitialPage(index: Int) = Unit
        override fun onPageLoading(index: Int) = Unit
        override fun onPageBoundsReady(index: Int, width: Int, height: Int) = Unit
        override fun onPageReady(index: Int, bitmap: Bitmap) = Unit
        override fun onPageTilesReady(
            index: Int,
            pageWidth: Int,
            pageHeight: Int,
            tiles: List<ReaderTile>,
        ) = Unit
        override fun onPageCard(index: Int, title: String) = Unit
        override fun onPageError(index: Int, message: String) = Unit
        override fun onPageCleared(index: Int) = Unit
        override fun onBoundaryAppendFinished(
            anchor: Int,
            direction: Int,
            silent: Boolean,
            suppressedCaptcha: Boolean,
        ) = Unit
        override fun onMessage(message: String) = Unit
        override fun onCaptchaRequired(manga: Manga) = Unit
    }
}
