package ml.melun.mangaview.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the 3 -> 4 schema step without a device by recording what the migration executes. */
class EngineDatabaseMigrationSqlTest {
    @Test
    fun versionThreeToFourAddsANullableEpisodeTitleColumn() {
        val statements = mutableListOf<String>()
        val migration = EngineDatabaseMigration.FROM_3_TO_4
        migration.migrate(recordingDatabase(statements))

        assertEquals(3, migration.startVersion)
        assertEquals(4, migration.endVersion)
        assertEquals(statements.toString(), 1, statements.size)
        val sql = statements.single().trim()
        assertTrue(
            "unexpected migration SQL: $sql",
            Regex("ALTER TABLE +`reading_progress` +ADD COLUMN +`episodeTitle` +TEXT").matches(sql),
        )
    }

    @Test
    fun earlierMigrationsStillChainIntoTheNewVersion() {
        val statements = mutableListOf<String>()
        val database = recordingDatabase(statements)
        EngineDatabaseMigration.FROM_1_TO_2.migrate(database)
        EngineDatabaseMigration.FROM_2_TO_3.migrate(database)
        EngineDatabaseMigration.FROM_3_TO_4.migrate(database)

        assertTrue(statements.any { it.contains("`read_episodes`") })
        assertTrue(statements.any { it.contains("`episodeTitle`") })
        assertEquals(4, EngineDatabaseMigration.FROM_3_TO_4.endVersion)
    }

    private fun recordingDatabase(statements: MutableList<String>): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, arguments ->
            when {
                method.name == "execSQL" -> {
                    statements += arguments!![0] as String
                    null
                }
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Int::class.javaPrimitiveType -> 0
                method.returnType == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase
}
