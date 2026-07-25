package com.hereliesaz.sirmatchalot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Executes the version 2 → 3 migration against a real SQLite engine.
 *
 * ## Why this exists
 *
 * A migration is the one piece of SQL in the app that nothing checks. It does
 * not fail to compile. It does not fail on a fresh install, because a fresh
 * install creates version 3 directly and never runs it. It fails on an
 * *upgrading* user's device, at launch, at which point the app will not open at
 * all — and the only tests that would have caught it are instrumented ones that
 * CI does not run.
 *
 * That has now happened twice on this single migration:
 *
 * 1. the primary key was written as a column constraint rather than a table
 *    constraint, diverging from the schema Room verifies its identity hash
 *    against;
 * 2. `TRIM(BOTH ',' FROM x)` — valid SQL-standard, and a syntax error to
 *    SQLite, which spells it `trim(X, Y)`.
 *
 * Both are invisible to review because both *read* correctly. Only an engine
 * catches them, so the statements now run against one here, in an ordinary JVM
 * test on an in-memory database.
 */
class MigrationSqlTest {

    private fun openVersion2Database(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { it.executeUpdate(AppDatabase.VERSION_2_TRACKS_SQL) }
        return connection
    }

    private fun Connection.insertVersion2Row(
        id: String,
        title: String = "Title",
        artist: String = "Artist",
        localPath: String? = "/music/$id.mp3",
        cues: List<Double?> = listOf(null, null, null, null),
        isUserAdded: Int = 1,
        durationMs: Long = 240_000,
    ) {
        prepareStatement(
            """
            INSERT INTO tracks (
                id, title, artist, localPath, bpm, camelotKey, progression, atmosphere,
                energy, durationMs, trimStartMs, trimEndMs, peaksPath, isUserAdded,
                cuePoint1, cuePoint2, cuePoint3, cuePoint4
            ) VALUES (?, ?, ?, ?, 128, '8A', 'I-V-vi-IV', 'Dark', 7, ?, 0, ?, NULL, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, title)
            statement.setString(3, artist)
            statement.setString(4, localPath)
            statement.setLong(5, durationMs)
            statement.setLong(6, durationMs)
            statement.setInt(7, isUserAdded)
            for (i in 0..3) {
                val cue = cues.getOrNull(i)
                if (cue == null) statement.setNull(8 + i, java.sql.Types.REAL)
                else statement.setDouble(8 + i, cue)
            }
            statement.executeUpdate()
        }
    }

    private fun Connection.migrate() {
        createStatement().use { statement ->
            for (sql in AppDatabase.MIGRATION_2_3_STATEMENTS) statement.executeUpdate(sql)
        }
    }

    // --- The thing that actually broke ---

    @Test
    fun `every migration statement compiles and runs`() {
        // The regression test for both shipped bugs. If any statement is invalid
        // SQLite, this throws here rather than on a user's device.
        openVersion2Database().use { connection ->
            connection.insertVersion2Row("a")
            connection.migrate()
        }
    }

    @Test
    fun `the migration runs on an empty library`() {
        openVersion2Database().use { it.migrate() }
    }

    // --- What the user keeps ---

    @Test
    fun `identity, titles and file locations survive`() {
        openVersion2Database().use { connection ->
            connection.insertVersion2Row("t1", title = "Blue Monday", artist = "New Order")
            connection.migrate()

            connection.createStatement().use { statement ->
                val rows = statement.executeQuery("SELECT id, title, artist, sourceUri FROM tracks")
                assertTrue(rows.next())
                assertEquals("t1", rows.getString("id"))
                assertEquals("Blue Monday", rows.getString("title"))
                assertEquals("New Order", rows.getString("artist"))
                // localPath becomes sourceUri.
                assertEquals("/music/t1.mp3", rows.getString("sourceUri"))
            }
        }
    }

    @Test
    fun `nothing is lost — every row carries over`() {
        openVersion2Database().use { connection ->
            repeat(25) { connection.insertVersion2Row("track$it") }
            connection.migrate()

            connection.createStatement().use { statement ->
                val rows = statement.executeQuery("SELECT COUNT(*) AS n FROM tracks")
                rows.next()
                assertEquals(25, rows.getInt("n"))
            }
        }
    }

    // --- What the migration deliberately drops ---

    @Test
    fun `the invented tempo and key are not carried forward`() {
        // Version 2 stored bpm and camelotKey as NOT NULL, filled from a hash of
        // the filename and a random number. Carrying them over would preserve the
        // fiction under columns that now mean "measured".
        openVersion2Database().use { connection ->
            connection.insertVersion2Row("t1")
            connection.migrate()

            connection.createStatement().use { statement ->
                val rows = statement.executeQuery(
                    "SELECT bpm, camelotKey, energyLevel, analysisVersion FROM tracks",
                )
                assertTrue(rows.next())
                rows.getDouble("bpm"); assertTrue("bpm should be null", rows.wasNull())
                assertNull(rows.getString("camelotKey"))
                rows.getInt("energyLevel"); assertTrue("energy should be null", rows.wasNull())
                // Zero marks the row as needing real analysis.
                assertEquals(0, rows.getInt("analysisVersion"))
            }
        }
    }

    // --- Cue points, four columns to one CSV ---

    private fun cuesAfterMigration(cues: List<Double?>): String? =
        openVersion2Database().use { connection ->
            connection.insertVersion2Row("t1", cues = cues)
            connection.migrate()
            connection.createStatement().use { statement ->
                val rows = statement.executeQuery("SELECT cuePointsCsv FROM tracks")
                rows.next()
                rows.getString("cuePointsCsv")
            }
        }

    @Test
    fun `four cue points become a CSV`() {
        assertEquals("1.5,2.5,3.5,4.5", cuesAfterMigration(listOf(1.5, 2.5, 3.5, 4.5)))
    }

    @Test
    fun `no cue points becomes null rather than a string of commas`() {
        assertNull(cuesAfterMigration(listOf(null, null, null, null)))
    }

    @Test
    fun `leading and trailing gaps are trimmed away`() {
        // This is the case the broken TRIM was for: without it the value would be
        // ",,3.0," and the reader would carry empty fields at both ends.
        assertEquals("3.0", cuesAfterMigration(listOf(null, null, 3.0, null)))
        assertEquals("1.0", cuesAfterMigration(listOf(1.0, null, null, null)))
        assertEquals("4.0", cuesAfterMigration(listOf(null, null, null, 4.0)))
    }

    @Test
    fun `an interior gap is harmless because the reader drops empty fields`() {
        val csv = cuesAfterMigration(listOf(1.0, null, 3.0, null))
        assertEquals("1.0,,3.0", csv)
        // What matters is what Track makes of it.
        val parsed = Track(title = "t", artist = "a", cuePointsCsv = csv).cuePoints
        assertEquals(listOf(1.0, 3.0), parsed)
    }

    @Test
    fun `the migrated CSV round trips through Track`() {
        val csv = cuesAfterMigration(listOf(1.5, 2.5, 3.5, 4.5))
        assertEquals(
            listOf(1.5, 2.5, 3.5, 4.5),
            Track(title = "t", artist = "a", cuePointsCsv = csv).cuePoints,
        )
    }

    // --- Shape of the result ---

    @Test
    fun `the migrated table has exactly the columns the entity declares`() {
        openVersion2Database().use { connection ->
            connection.migrate()
            val columns = mutableSetOf<String>()
            connection.createStatement().use { statement ->
                val rows = statement.executeQuery("PRAGMA table_info(`tracks`)")
                while (rows.next()) columns.add(rows.getString("name"))
            }
            val expected = setOf(
                "id", "title", "artist", "sourceUri", "bpm", "tempoConfidence",
                "firstBeatSeconds", "downbeatOffset", "camelotKey", "keyName",
                "keyConfidence", "energyLevel", "durationMs", "sampleRate",
                "trimStartMs", "trimEndMs", "peaksPath", "energyPath",
                "analysisVersion", "isUserAdded", "cuePointsCsv",
            )
            assertEquals(expected, columns)
        }
    }

    @Test
    fun `id remains the primary key`() {
        openVersion2Database().use { connection ->
            connection.migrate()
            connection.createStatement().use { statement ->
                val rows = statement.executeQuery("PRAGMA table_info(`tracks`)")
                var primaryKey: String? = null
                while (rows.next()) {
                    if (rows.getInt("pk") > 0) primaryKey = rows.getString("name")
                }
                assertEquals("id", primaryKey)
            }
        }
    }

    @Test
    fun `the scratch table is gone once the migration finishes`() {
        openVersion2Database().use { connection ->
            connection.migrate()
            connection.createStatement().use { statement ->
                val rows = statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='tracks_new'",
                )
                assertTrue("tracks_new was left behind", !rows.next())
            }
        }
    }
}
