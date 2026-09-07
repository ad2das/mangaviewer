package ml.melun.mangaview.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object EngineDatabaseMigration {
    val FROM_1_TO_2: Migration = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            createAnchorTables(database)
            createPublicationTables(database)
        }

        private fun createAnchorTables(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `engine_reading_anchors` (
                    `sourceKey` TEXT NOT NULL,
                    `seriesKey` TEXT NOT NULL,
                    `episodeKey` TEXT NOT NULL,
                    `pageKey` TEXT NOT NULL,
                    `sourceYQ32` INTEGER NOT NULL,
                    `viewportOffsetUnits` INTEGER NOT NULL,
                    `legacyScreenOffsetUnits` INTEGER NOT NULL,
                    `updatedAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`sourceKey`, `seriesKey`)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `engine_bookmark_anchors` (
                    `sourceKey` TEXT NOT NULL,
                    `seriesKey` TEXT NOT NULL,
                    `episodeKey` TEXT NOT NULL,
                    `pageKey` TEXT NOT NULL,
                    `sourceYQ32` INTEGER NOT NULL,
                    `viewportOffsetUnits` INTEGER NOT NULL,
                    `legacyScreenOffsetUnits` INTEGER NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`sourceKey`, `seriesKey`, `episodeKey`, `pageKey`)
                )
                """.trimIndent(),
            )
        }

        private fun createPublicationTables(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `engine_pages` (
                    `cacheKey` TEXT NOT NULL,
                    `contentRevision` TEXT NOT NULL,
                    `sourceKey` TEXT NOT NULL,
                    `seriesKey` TEXT NOT NULL,
                    `episodeKey` TEXT NOT NULL,
                    `pageKey` TEXT NOT NULL,
                    `relativePath` TEXT NOT NULL,
                    `byteCount` INTEGER NOT NULL,
                    `sha256` TEXT NOT NULL,
                    `mediaType` TEXT NOT NULL,
                    `widthPx` INTEGER NOT NULL,
                    `heightPx` INTEGER NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    `lastAccessEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`cacheKey`, `contentRevision`)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `engine_publications` (
                    `publicationId` TEXT NOT NULL,
                    `cacheKey` TEXT NOT NULL,
                    `contentRevision` TEXT NOT NULL,
                    `sourceKey` TEXT NOT NULL,
                    `seriesKey` TEXT NOT NULL,
                    `episodeKey` TEXT NOT NULL,
                    `pageKey` TEXT NOT NULL,
                    `stagingRelativePath` TEXT NOT NULL,
                    `destinationRelativePath` TEXT NOT NULL,
                    `byteCount` INTEGER NOT NULL,
                    `sha256` TEXT NOT NULL,
                    `mediaType` TEXT NOT NULL,
                    `widthPx` INTEGER NOT NULL,
                    `heightPx` INTEGER NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`publicationId`)
                )
                """.trimIndent(),
            )
        }
    }
}
