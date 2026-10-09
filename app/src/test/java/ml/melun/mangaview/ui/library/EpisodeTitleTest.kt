package ml.melun.mangaview.ui.library

import ml.melun.mangaview.activity.withoutSharedSeriesName
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeTitleTest {
    @Test fun dropsTheSeriesNameProvidersRepeat() {
        assertEquals("289화", shortEpisodeTitle("S급 나무로 레벨업", "S급 나무로 레벨업 289화"))
    }

    @Test fun matchesASeriesNameWrittenWithoutSpacesOrPunctuation() {
        assertEquals("프롤로그", shortEpisodeTitle("엘피스 전기:더 라스트", "엘피스전기더라스트프롤로그"))
        assertEquals("12화", shortEpisodeTitle("Solo Leveling", "solo-leveling - 12화"))
    }

    @Test fun keepsTitlesThatDoNotStartWithTheSeriesName() {
        assertEquals("외전 1화", shortEpisodeTitle("S급 나무로 레벨업", "외전 1화"))
        assertEquals("S급 나무로 레벨업", shortEpisodeTitle("S급 나무로 레벨업", "S급 나무로 레벨업"))
        assertEquals("1화", shortEpisodeTitle("", "1화"))
    }

    @Test fun keepsAnOpeningBracketThatBelongsToTheEpisode() {
        assertEquals("(완결) 100화", shortEpisodeTitle("작품", "작품 (완결) 100화"))
    }

    @Test fun sheetStripsTheWordAlignedPrefixMostTitlesShare() {
        assertEquals(
            listOf("289화", "288화", "공지", "1화"),
            withoutSharedSeriesName(listOf("레벨업 289화", "레벨업 288화", "공지", "레벨업 1화")),
        )
    }

    @Test fun sheetNeverCutsIntoANumberAndLeavesUnsharedListsAlone() {
        assertEquals(listOf("21화", "22화"), withoutSharedSeriesName(listOf("21화", "22화")))
        assertEquals(listOf("가 1화", "나 2화"), withoutSharedSeriesName(listOf("가 1화", "나 2화")))
        assertEquals(listOf("단편"), withoutSharedSeriesName(listOf("단편")))
    }
}
