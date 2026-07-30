package ml.melun.mangaview.mangaview;

import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.repository.MangaRepository;

public class NtkTitleMetadataInstrumentedTest {
    private static final String AD_THUMB =
            "https://aws-cdn1.site/board_uploads/2026/06/24/065250_fdaad08ea68b.png";

    @Test
    public void comicDetailPageKeepsWorkMetadataInsteadOfSiteHeadingAndAdThumbnail() {
        LiveNetworkAssume.assumeEnabled();
        MainApplication.p.setNtkSitePreset(CustomHttpClient.NTK_WEBTOON_URL);
        MainApplication.p.setBaseMode(base_comic);
        MainApplication.getHttpClient().clearPageCache();

        Title title = new Title(
                "뉴토끼 - 웹툰 미리보기",
                AD_THUMB,
                "",
                null,
                "",
                23632,
                base_comic);
        title.setSourceSite("ntk");
        title.setPath("/manhwa/23632");

        int result = MangaRepository.fetchEpisodesBackground(title);

        assertEquals(Title.LOAD_OK, result);
        assertTrue(title.getName().trim().length() > 0);
        assertFalse(MTitle.isGenericNtkSiteTitle(title.getName()));
        assertTrue(title.getThumb().trim().length() > 0);
        assertFalse(AD_THUMB.equals(title.getThumb()));
    }
}
