package ml.melun.mangaview.adapter;

import org.junit.Test;

import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.model.PageItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class StripAdapterTest {
    @Test
    public void imagePageRejectsMissingPageData() {
        assertFalse(StripAdapterTestAccess.isAttachableImagePage(null));
        assertFalse(StripAdapterTestAccess.isAttachableImagePage(new PageItem(0, "img", null)));
    }

    @Test
    public void imagePageAcceptsCompletePageData() {
        Manga manga = new Manga(12, "episode", "", MTitle.base_comic);

        assertTrue(StripAdapterTestAccess.isAttachableImagePage(new PageItem(0, "img", manga)));
    }

    @Test
    public void imageModelFallsBackToRawImageWithoutManga() {
        assertEquals("", StripAdapterTestAccess.imageModel(null));
        assertEquals("img", StripAdapterTestAccess.imageModel(new PageItem(0, "img", null)));
    }

    @Test
    public void imageModelRejectsBlankImageWithManga() {
        Manga manga = new Manga(12, "episode", "", MTitle.base_comic);

        assertEquals("", StripAdapterTestAccess.imageModel(new PageItem(0, "   ", manga)));
    }

    @Test
    public void displayedBitmapCacheRequiresValidKeyAndBitmap() {
        assertFalse(StripAdapterTestAccess.shouldCacheDisplayedBitmap("", true, true));
        assertFalse(StripAdapterTestAccess.shouldCacheDisplayedBitmap("page", false, true));
        assertFalse(StripAdapterTestAccess.shouldCacheDisplayedBitmap("page", true, false));
        assertTrue(StripAdapterTestAccess.shouldCacheDisplayedBitmap("page", true, true));
    }

    @Test
    public void decodedBitmapCacheRejectsSingleHugeEntries() {
        assertTrue(StripAdapterTestAccess.shouldCacheDecodedBitmap(2 * 1024, 16 * 1024));
        assertFalse(StripAdapterTestAccess.shouldCacheDecodedBitmap(8 * 1024, 16 * 1024));
    }

    @Test
    public void displayBitmapRejectsMissingBitmap() {
        assertFalse(StripAdapterTestAccess.isDisplayBitmapUsable(null));
    }

    @Test
    public void transientImageFailuresRetryThreeTimesOnlyForActivePages() {
        assertTrue(StripAdapterTestAccess.shouldRetryImageLoad(false, "page", 0));
        assertTrue(StripAdapterTestAccess.shouldRetryImageLoad(false, "page", 1));
        assertTrue(StripAdapterTestAccess.shouldRetryImageLoad(false, "page", 2));
        assertFalse(StripAdapterTestAccess.shouldRetryImageLoad(false, "page", 3));
        assertFalse(StripAdapterTestAccess.shouldRetryImageLoad(true, "page", 0));
        assertFalse(StripAdapterTestAccess.shouldRetryImageLoad(false, "", 0));
        assertEquals(220L, StripAdapterTestAccess.imageRetryDelayMs(1));
        assertEquals(650L, StripAdapterTestAccess.imageRetryDelayMs(2));
        assertEquals(1200L, StripAdapterTestAccess.imageRetryDelayMs(3));
    }

    @Test
    public void autoCutSecondPageDoesNotReserveFullHeightBeforeDecode() {
        assertEquals(1, StripAdapterTestAccess.estimatedPageHeight(true, PageItem.SECOND, 1000, 3000L, 2));
        assertEquals(1500, StripAdapterTestAccess.estimatedPageHeight(true, PageItem.FIRST, 1000, 3000L, 2));
        assertEquals(1500, StripAdapterTestAccess.estimatedPageHeight(false, PageItem.SECOND, 1000, 3000L, 2));
    }

    @Test
    public void stackTrimKeepsPreloadKeysForStillLoadedPages() {
        Set<String> loaded = new LinkedHashSet<>();
        loaded.add("episode:page1");

        assertTrue(StripAdapterTestAccess.shouldRetainTrackedPreloadForLoadedPage("episode:page1", loaded));
        assertTrue(StripAdapterTestAccess.shouldRetainTrackedPreloadForLoadedPage("decoded:episode:page1", loaded));
        assertFalse(StripAdapterTestAccess.shouldRetainTrackedPreloadForLoadedPage("episode:page2", loaded));
        assertFalse(StripAdapterTestAccess.shouldRetainTrackedPreloadForLoadedPage("decoded:episode:page2", loaded));
    }

    @Test
    public void firstVisibleMetricLogsOncePerAdapter() {
        assertTrue(StripAdapterTestAccess.shouldLogFirstVisible(false));
        assertFalse(StripAdapterTestAccess.shouldLogFirstVisible(true));
    }

    @Test
    public void preloadBudgetsCoverOneFastFling() {
        assertTrue(StripAdapterTestAccess.preloadAheadCount() <= 8);
        assertTrue(StripAdapterTestAccess.initialPreloadAheadCount() <= 6);
        assertEquals(0, StripAdapterTestAccess.decodedPreloadActiveLimit());
        assertTrue(StripAdapterTestAccess.scrollIdlePreloadDelayMs() >= 600L);
        assertTrue(StripAdapterTestAccess.scrollIdleHeightCorrectionDelayMs()
                >= StripAdapterTestAccess.scrollIdlePreloadDelayMs());
        assertTrue(StripAdapterTestAccess.previewWidth(1080) >= 360);
        assertEquals(360, StripAdapterTestAccess.previewWidth(1080));
        assertTrue(StripAdapterTestAccess.previewWidth(1080) < 1080);
    }

    @Test
    public void busyScrollPreloadIsThrottledDuringFastFling() {
        assertTrue(StripAdapterTestAccess.shouldRunBusyPreload(-1, 10, 0L));
        assertFalse(StripAdapterTestAccess.shouldRunBusyPreload(10, 11, 20L));
        assertFalse(StripAdapterTestAccess.shouldRunBusyPreload(10, 12, 20L));
        assertTrue(StripAdapterTestAccess.shouldRunBusyPreload(10, 14, 20L));
        assertFalse(StripAdapterTestAccess.shouldRunBusyPreload(10, 11, 80L));
        assertTrue(StripAdapterTestAccess.shouldRunBusyPreload(10, 11, 120L));
    }

    @Test
    public void stripAdapterStartsAheadPreloadOutsideVisibleDecodeWork() {
        assertTrue(StripAdapterTestAccess.startsPreloadFromBind());
        assertTrue(StripAdapterTestAccess.startsPreloadFromScrollAnchor());
    }

    @Test
    public void fastFlingBindPathAvoidsWritingTransformedResources() {
        assertEquals(DiskCacheStrategy.DATA, StripAdapterTestAccess.viewerDiskCacheStrategy(true));
        assertEquals(DiskCacheStrategy.DATA, StripAdapterTestAccess.viewerDiskCacheStrategy(false));
    }

    @Test
    public void fastFlingBindsPreviewOnlyUntilScrollSettles() {
        assertTrue(StripAdapterTestAccess.shouldUsePreviewOnlyBind(true, false));
        assertFalse(StripAdapterTestAccess.shouldUsePreviewOnlyBind(false, false));
        assertFalse(StripAdapterTestAccess.shouldUsePreviewOnlyBind(true, true));
    }

    @Test
    public void mangaItemBuilderCreatesAutoCutAppendWindow() {
        Manga manga = new Manga(12, "episode", "", MTitle.base_comic);
        List<Object> items = StripMangaItemBuilder.appendItems(manga, Arrays.asList("a", "b"),
                true, true, page -> { });

        assertTrue(items.get(0) instanceof InfoItem);
        assertPage(items.get(1), manga, 0, PageItem.FIRST);
        assertPage(items.get(2), manga, 0, PageItem.SECOND);
        assertPage(items.get(3), manga, 1, PageItem.FIRST);
        assertPage(items.get(4), manga, 1, PageItem.SECOND);
        assertTrue(items.get(5) instanceof InfoItem);
    }

    @Test
    public void mangaItemBuilderCreatesPrependWindowInDisplayOrder() {
        Manga manga = new Manga(13, "episode", "", MTitle.base_comic);
        List<Object> items = StripMangaItemBuilder.prependItems(manga, Arrays.asList("a", "b"),
                false, page -> { });

        assertTrue(items.get(0) instanceof InfoItem);
        assertPage(items.get(1), manga, 0, PageItem.FIRST);
        assertPage(items.get(2), manga, 1, PageItem.FIRST);
    }

    private static void assertPage(Object item, Manga manga, int index, int side) {
        assertTrue(item instanceof PageItem);
        PageItem page = (PageItem) item;
        assertSame(manga, page.manga);
        assertEquals(index, page.index);
        assertEquals(side, page.side);
    }
}
