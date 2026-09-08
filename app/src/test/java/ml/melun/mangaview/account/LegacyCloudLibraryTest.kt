package ml.melun.mangaview.account

import ml.melun.mangaview.core.*
import ml.melun.mangaview.source.SourceEpisode
import org.junit.Assert.*
import org.junit.Test

class LegacyCloudLibraryTest {
    @Test fun restoresOriginalRecentFavoriteAndPageBookmarkFields() {
        val title = """{"id":12,"baseMode":1,"name":"Saved series","sourceSite":"v12st","bookmarkEpisodeId":3}"""
        val result = LegacyCloudLibrary.decode(mapOf("recentJson" to "[$title]", "favoriteJson" to "[$title]",
            "bookmarkJson" to """{"wfwf.1.12":3}""", "pageBookmarkJson" to """{"wfwf.1.12.3":2,"wfwf.1.12.3.offset":85}""",
            "updatedAt" to 100L))
        assertEquals(0, result.unresolvedRecords)
        val series = CloudLibraryCodec.series(result.records.single { it.kind == "series" })
        assertFalse(series.favorite)
        assertEquals(1, result.records.count { it.kind == "favorite" && !it.deleted })
        assertEquals("wfwf", series.sourceKey)
        val progress = CloudLibraryCodec.progress(result.records.single { it.kind == "progress" })
        assertEquals("3", progress.episodeKey)
        assertEquals("p0002", progress.pageKey)
        assertEquals(85 * 1024L, progress.offsetInPageUnits)
        assertEquals(1, result.records.count { it.kind == "bookmark" })
    }

    @Test fun exactNtkResumePathIsPreservedInsteadOfNumericDisplayId() {
        val result = LegacyCloudLibrary.decode(mapOf("recentJson" to """[{"id":12,"baseMode":2,"name":"Webtoon",
            "sourceSite":"ntk","bookmarkEpisodeId":3,"resumeNtkEpisodePath":"/webtoon/12/nv-12-3"}]""",
            "pageBookmarkJson" to """{"ntk.2.12.3":7,"ntk.2.12.3.offset":123}"""))
        assertEquals("/webtoon/12/nv-12-3", CloudLibraryCodec.progress(result.records.single { it.kind == "progress" }).episodeKey)
        assertEquals(0, result.unresolvedRecords)
    }

    @Test fun pathlessNtkDisplayIdIsResolvedFromRealCatalog() {
        val fields = mapOf("recentJson" to """[{"id":12,"baseMode":1,"name":"Comic","sourceSite":"ntk","bookmarkEpisodeId":3}]""")
        val pending = LegacyCloudLibrary.decode(fields)
        assertEquals(setOf("/manhwa/12"), pending.unresolvedSeries)
        assertFalse(pending.records.any { it.kind == "progress" })
        val episode = SourceEpisode(EpisodeId(SeriesId(SourceId("ntk"), "/manhwa/12"), "/manhwa/12/34567"), "3화", sequenceNumber = 3.0)
        val resolved = LegacyCloudLibrary.decode(fields, mapOf("/manhwa/12" to listOf(episode)))
        assertEquals("/manhwa/12/34567", CloudLibraryCodec.progress(resolved.records.single { it.kind == "progress" }).episodeKey)
        assertEquals(0, resolved.unresolvedRecords)
    }

    @Test fun conflictingCatalogNumbersDoNotRestoreTheWrongEpisode() {
        val fields = mapOf("recentJson" to """[{"id":12,"baseMode":1,"name":"Comic","sourceSite":"ntk","bookmarkEpisodeId":3}]""")
        val series = SeriesId(SourceId("ntk"), "/manhwa/12")
        val catalog = listOf(SourceEpisode(EpisodeId(series, "/manhwa/12/34567"), "3화", sequenceNumber = 3.0),
            SourceEpisode(EpisodeId(series, "/manhwa/12/3"), "1화", sequenceNumber = 1.0))
        assertFalse(LegacyCloudLibrary.decode(fields, mapOf(series.remoteKey to catalog)).records.any { it.kind == "progress" })
    }

    @Test fun oldUnprefixedBookmarksAreImportedForAnUnambiguousTitle() {
        val result = LegacyCloudLibrary.decode(mapOf("recentJson" to """[{"id":12,"baseMode":1,"name":"Comic","sourceSite":"wfwf","bookmarkEpisodeId":3}]""",
            "pageBookmarkJson" to """{"1.12.3":4,"1.12.3.offset":5,"1.12.4":2}"""))
        assertEquals(2, result.records.count { it.kind == "bookmark" })
        assertEquals("p0004", CloudLibraryCodec.progress(result.records.single { it.kind == "progress" }).pageKey)
    }

    @Test fun unknownProviderIsKeptPendingInsteadOfCrossProviderImport() {
        val result = LegacyCloudLibrary.decode(mapOf("recentJson" to """[{"id":12,"baseMode":1,"name":"Comic"}]"""))
        assertTrue(result.records.isEmpty())
        assertEquals(1, result.unresolvedRecords)
    }
}
