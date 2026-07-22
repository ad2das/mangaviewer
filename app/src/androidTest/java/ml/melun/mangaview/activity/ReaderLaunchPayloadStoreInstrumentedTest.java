package ml.melun.mangaview.activity;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
public class ReaderLaunchPayloadStoreInstrumentedTest {
    @Test
    public void exactPayloadIsSingleUseAndCarriesPreparedKeyAtomically() {
        Title title = title();
        Manga manga = manga(title);
        String token = ReaderLaunchPayloadStore.put(manga, title, "prepared-exact");

        ReaderLaunchPayloadStore.Entry launch = ReaderLaunchPayloadStore.take(token);

        assertNotNull(launch);
        assertEquals(manga, launch.getManga());
        assertEquals(title, launch.getTitle());
        assertEquals("prepared-exact", launch.getPreparedKey());
        assertNull(ReaderLaunchPayloadStore.take(token));
    }

    @Test
    public void discardedPayloadCannotAuthorizeActionUp() {
        Title title = title();
        String token = ReaderLaunchPayloadStore.put(manga(title), title, "prepared-cancelled");

        ReaderLaunchPayloadStore.discard(token);

        assertNull(ReaderLaunchPayloadStore.take(token));
    }

    @Test
    public void compactProcessRestoreNeverRestoresPreparedKey() {
        Title title = title();
        Manga manga = manga(title);
        Intent intent = new Intent();
        ReaderLaunchPayloadStore.attachCompactReaderPayload(intent, manga, title);

        ReaderLaunchPayloadStore.Entry restored =
                ReaderLaunchPayloadStore.restoreCompactReaderPayload(intent);

        assertNotNull(restored);
        assertNull(restored.getPreparedKey());
        ReaderLaunchPayloadStore.discard(
                intent.getStringExtra(ReaderLaunchPayloadStore.EXTRA_READER_KEY));
    }

    @Test
    public void coldExactRestoreCarriesIdentityButNoImageOrPageHint() {
        Title title = title();
        Manga manga = manga(title);
        manga.setNtkViewerPayloadHint("{\"images\":[\"https://cdn.example/page-0.jpg\"]}");
        manga.setNtkImageCount(73);
        Intent intent = new Intent();

        ReaderLaunchPayloadStore.attachColdExactReaderPayload(intent, manga, title);
        ReaderLaunchPayloadStore.Entry restored =
                ReaderLaunchPayloadStore.restoreCompactReaderPayload(intent);

        assertNotNull(restored);
        assertNull(restored.getPreparedKey());
        assertEquals("/webtoon/44/701", restored.getManga().getNtkEpisodePath());
        assertEquals("", restored.getManga().getNtkViewerPayloadHint());
        assertEquals(0, restored.getManga().getNtkImageCount());
        assertNull(intent.getStringExtra(ReaderLaunchPayloadStore.EXTRA_READER_KEY));
    }

    private static Title title() {
        Title title = new Title(
                "Atomic",
                "",
                "",
                Collections.emptyList(),
                "",
                44,
                base_webtoon);
        title.setSourceSite("ntk");
        return title;
    }

    private static Manga manga(Title title) {
        Manga manga = new Manga(701, "12화", "", base_webtoon);
        manga.setTitle(title);
        manga.setTitleId(title.getId());
        manga.setNtkEpisodePath("/webtoon/44/701");
        return manga;
    }
}
