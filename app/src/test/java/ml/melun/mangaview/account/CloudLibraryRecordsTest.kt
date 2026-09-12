package ml.melun.mangaview.account

import ml.melun.mangaview.data.db.*
import org.junit.Assert.*
import org.junit.Test

class CloudLibraryRecordsTest {
    @Test fun recentTouchCannotAdvanceFavoriteRevisionOrUndoRemoteRemoval() {
        val entry = LibraryEntryEntity("wfwf", "comic:12", "Title", null, true, 10)
        val favorite = CloudLibraryCodec.favorite(entry)
        val touched = CloudLibraryCodec.favorite(entry.copy(updatedAtEpochMillis = 100))
        val pending = CloudLibraryRecords.localChanges(listOf(favorite), listOf(touched), 100)
        assertEquals(listOf(favorite), pending)
        val removed = favorite.copy(updatedAt = 20, deleted = true)
        assertEquals(listOf(removed), CloudLibraryRecords.merge(pending, listOf(removed)))
        assertEquals(listOf(removed), CloudLibraryRecords.mergeAfterDownload(
            listOf(removed), listOf(touched), listOf(touched), 101))
    }

    @Test fun favoriteRemovalSurvivesOfflineRestartAndStaleRemote() {
        val favorite = CloudLibraryCodec.favorite(LibraryEntryEntity("wfwf", "comic:12", "Title", null, true, 10))
        val removed = CloudLibraryRecords.localChanges(listOf(favorite), emptyList(), 20)
        val restarted = CloudLibraryRecords.decode(CloudLibraryRecords.encode(removed))
        assertTrue(CloudLibraryRecords.merge(
            CloudLibraryRecords.localChanges(restarted, emptyList(), 30), listOf(favorite)).single().deleted)
    }

    private fun progress(offset: Long, at: Long) = CloudLibraryCodec.progress(
        ReadingProgressEntity("ntk", "/webtoon/12", "/webtoon/12/nv-12-3", "p0002", offset, at),
        EngineReadingAnchorEntity("ntk", "/webtoon/12", "/webtoon/12/nv-12-3", "p0002",
            9_876_543_210L, 123_456L, offset, at))

    @Test fun precisePositionAndNativeAnchorSurviveCloudRoundTrip() {
        val original = progress(987654, 123456789)
        val decoded = CloudLibraryRecords.decode(CloudLibraryRecords.encode(listOf(original))).single()
        assertEquals(original, decoded)
        assertEquals(CloudLibraryCodec.progress(original), CloudLibraryCodec.progress(decoded))
        assertEquals(CloudLibraryCodec.readingAnchor(original), CloudLibraryCodec.readingAnchor(decoded))
    }

    @Test fun deletedBookmarkSurvivesOfflineRestartAndStaleRemote() {
        val bookmark = CloudLibraryCodec.bookmark(BookmarkEntity("wfwf", "comic:12", "3", "p0001", 40, 10))
        val pending = CloudLibraryRecords.localChanges(listOf(bookmark), emptyList(), 20)
        val restarted = CloudLibraryRecords.decode(CloudLibraryRecords.encode(pending))
        val stillDeleted = CloudLibraryRecords.localChanges(restarted, emptyList(), 30)
        assertTrue(CloudLibraryRecords.merge(stillDeleted, listOf(bookmark)).single().deleted)
        assertEquals(20, stillDeleted.single().updatedAt)
    }

    @Test fun clockRegressionDoesNotLoseRevisionOnNextSnapshot() {
        val old = progress(10, 100)
        val changed = progress(20, 50)
        val first = CloudLibraryRecords.localChanges(listOf(old), listOf(changed), 50)
        val second = CloudLibraryRecords.localChanges(first, listOf(changed), 50)
        assertEquals(101, first.single().updatedAt)
        assertEquals(first, second)
    }

    @Test fun editDuringDownloadWinsEvenWhenRemoteDeviceClockIsAhead() {
        val before = progress(10, 10)
        val remote = progress(40, 9000)
        val changed = progress(20, 20)
        val merged = CloudLibraryRecords.mergeAfterDownload(listOf(remote), listOf(before), listOf(changed), 21).single()
        assertEquals(20, CloudLibraryCodec.progress(merged).offsetInPageUnits)
        assertEquals(9001, merged.updatedAt)
        assertEquals(merged.updatedAt, CloudLibraryCodec.readingAnchor(merged)!!.updatedAtEpochMillis)
    }

    @Test fun removalDuringDownloadIsNotResurrected() {
        val before = progress(10, 10)
        val remote = progress(40, 9000)
        assertTrue(CloudLibraryRecords.mergeAfterDownload(listOf(remote), listOf(before), emptyList(), 20).single().deleted)
    }

    @Test fun unchangedLocalRecordAllowsNewerRemotePosition() {
        val before = progress(10, 10)
        val remote = progress(40, 20)
        assertEquals(listOf(remote), CloudLibraryRecords.mergeAfterDownload(listOf(remote), listOf(before), listOf(before), 30))
    }

    @Test fun independentRecordsAndTiesMergeDeterministically() {
        val first = progress(10, 10)
        val other = CloudLibraryCodec.series(LibraryEntryEntity("wfwf", "comic:12", "Title", null, true, 10))
        assertEquals(CloudLibraryRecords.merge(listOf(first, other)), CloudLibraryRecords.merge(listOf(other), listOf(first)))
        assertTrue(CloudLibraryRecords.merge(listOf(first, first.copy(deleted = true))).single().deleted)
    }

    @Test fun mismatchedNativeAnchorIsNotBackedUpAsExactPosition() {
        val record = progress(10, 10)
        val snapshot = CloudLibrarySnapshot(emptyList(), listOf(CloudLibraryCodec.progress(record)), emptyList(),
            listOf(CloudLibraryCodec.readingAnchor(record)!!.copy(updatedAtEpochMillis = 9)), emptyList())
        assertNull(CloudLibraryCodec.readingAnchor(CloudLibraryCodec.snapshot(snapshot).single()))
    }

    @Test fun everyRegisteredSourceSurvivesSnapshotRoundTrip() {
        val entry = LibraryEntryEntity("newxtoon", "series-1", "신작", null, true, 100)
        val reading = ReadingProgressEntity("newxtoon", "series-1", "ep-1", "p0002", 4096, 110)
        val bookmark = BookmarkEntity("newxtoon", "series-1", "ep-1", "p0003", 8192, 120)
        val snapshot = CloudLibrarySnapshot(listOf(entry), listOf(reading), listOf(bookmark), emptyList(), emptyList())
        val records = CloudLibraryCodec.snapshot(snapshot)
        assertEquals(records, CloudLibraryRecords.decode(CloudLibraryRecords.encode(records)))
        assertEquals(records.sortedBy { it.identity },
            CloudLibraryRecords.localChanges(emptyList(), records, 200).sortedBy { it.identity })
        assertEquals("newxtoon", CloudLibraryCodec.progress(records.single { it.kind == "progress" }).sourceKey)
        assertEquals("newxtoon", CloudLibraryCodec.bookmark(records.single { it.kind == "bookmark" }).sourceKey)
    }

    @Test fun corruptedCloudRecordCannotBecomeAnEmptySuccessfulRestore() {
        assertThrows(IllegalArgumentException::class.java) { CloudLibraryRecords.decode("{\"version\":2,\"records\":[]}") }
        val record = progress(10, 10)
        val wrong = record.copy(key = "another record")
        assertThrows(IllegalArgumentException::class.java) { CloudLibraryRecords.decode(CloudLibraryRecords.encode(listOf(wrong))) }
    }
}
