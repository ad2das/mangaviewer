package ml.melun.mangaview.contracts;

import org.junit.Test;

import ml.melun.mangaview.model.PageItem;
import ml.melun.mangaview.repository.MangaRepository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RefactorContractsTest {
    @Test
    public void sourceRepositoryExposesCancellableUnitOfWork() {
        SourceRepository repository = new LegacySourceRepository();

        MangaRepository.Cancellation cancellation = repository.cancellation();

        assertNotNull(cancellation);
        assertFalse(cancellation.isCancelled());
        cancellation.cancel();
        assertTrue(cancellation.isCancelled());
    }

    @Test
    public void viewerBookmarkDefaultsToExactFirstPageIdentity() {
        ViewerProgressStore.ViewerBookmark bookmark = ViewerProgressStore.ViewerBookmark.firstPage();

        assertEquals(0, bookmark.pageIndex);
        assertEquals(0, bookmark.scrollOffset);
        assertEquals(PageItem.FIRST, bookmark.side);
    }
}
