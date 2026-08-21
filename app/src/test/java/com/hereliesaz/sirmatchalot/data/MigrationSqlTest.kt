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

    private fun openVersion1Database(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { it.executeUpdate(AppDatabase.VERSION_1_TRACKS_SQL) }
        return connection
    }

    private fun Connection.insertVersion1Row(
        id: String,
        title: String = "Title",
        artist: String = "Artist",
        localPath: String? = "/music/$id.mp3",
        cue: Double? = null,
    ) {
        prepareStatement(
            """
            INSERT INTO tracks (
                id, title, artist, bpm, keyName, camelotKey, progression, atmosphere,
                energyLevel, mixTips, youtubeId, localPath, isUserAdded,
                cuePoint1, cuePoint2, cuePoint3, cuePoint4
            ) VALUES (?, ?, ?, 128, 'A minor', '8A', 'I-V-vi-IV', 'Dark', 7, 'Tips', NULL, ?, 1, ?, NULL, NULL, NULL)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, title)
            statement.setString(3, artist)
            if (localPath == null) statement.setNull(4, java.sql.Types.VARCHAR)
            else statement.setString(4, localPath)
            if (cue == null) statement.setNull(5, java.sql.Types.REAL)
            else statement.setDouble(5, cue)
            statement.executeUpdate()
        }
    }

    /** Every migration, from the oldest schema that ever shipped. */
    private fun Connection.migrateFromVersion1() {
        createStatement().use { statement ->
            for (sql in AppDatabase.MIGRATION_1_2_STATEMENTS) statement.executeUpdate(sql)
        }
        migrateAll()
    }

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

    /** Everything from version 2 through to the current schema. */
    private fun Connection.migrateAll() {
        migrate()
        createStatement().use { statement ->
            for (sql in AppDatabase.MIGRATION_3_4_STATEMENTS) statement.executeUpdate(sql)
        }
    }

    /**
     * Column names from the schema Room exported for [version].
     *
     * The working directory differs between running from Gradle and from an
     * IDE, so both are tried rather than assuming one.
     */
    private fun exportedColumnNames(version: Int): Set<String> {
        val relative = "schemas/com.hereliesaz.sirmatchalot.data.AppDatabase/$version.json"
        val file = listOf(java.io.File(relative), java.io.File("app/$relative"))
            .firstOrNull { it.isFile }
            ?: return emptySet()

        // Deliberately not a JSON library: the shape needed is one array of
        // objects with a `columnName`, and the point of the test is the column
        // set, not the parser.
        return Regex("\"columnName\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun Connection.columnNames(): Set<String> =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`tracks`)").use { rows ->
                buildSet { while (rows.next()) add(rows.getString("name")) }
            }
        }

    /**
     * Every column of `tracks` as SQLite itself describes it.
     *
     * Name, declared type, nullability, default and primary-key position — which
     * is what Room's own `TableInfo` compares when it validates the live
     * database against the schema it exported. Comparing names alone, as this
     * test used to, passes a migration that declares `bpm INTEGER` where the
     * entity says `REAL`, or that drops `NOT NULL DEFAULT 0` from
     * `tempoConfidence`, and both of those are `IllegalStateException:
     * Migration didn't properly handle tracks` at open time on an upgrading
     * device — the precise failure this test says it exists to prevent.
     */
    private fun Connection.columnDefinitions(table: String = "tracks"): Map<String, String> =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
                buildMap {
                    while (rows.next()) {
                        put(
                            rows.getString("name"),
                            listOf(
                                rows.getString("type").uppercase(),
                                "notnull=" + rows.getInt("notnull"),
                                "default=" + (rows.getString("dflt_value") ?: "none"),
                                "pk=" + rows.getInt("pk"),
                            ).joinToString(" "),
                        )
                    }
                }
            }
        }

    /**
     * The table Room would create for [version], described the same way.
     *
     * Built by running Room's own exported `createSql` on a scratch database,
     * so the comparison is engine against engine rather than regex against SQL.
     */
    private fun exportedColumnDefinitions(version: Int): Map<String, String> {
        val relative = "schemas/com.hereliesaz.sirmatchalot.data.AppDatabase/$version.json"
        val file = listOf(java.io.File(relative), java.io.File("app/$relative"))
            .firstOrNull { it.isFile }
            ?: return emptyMap()

        val createSql = Regex("\"createSql\"\\s*:\\s*\"(.+?)\"(?=,\\s*\")")
            .find(file.readText())
            ?.groupValues
            ?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", " ")
            ?.replace("\${TABLE_NAME}", "tracks")
            ?: return emptyMap()

        return DriverManager.getConnection("jdbc:sqlite::memory:").use { reference ->
            reference.createStatement().use { it.executeUpdate(createSql) }
            reference.columnDefinitions()
        }
    }

    // --- Version 3 to 4: the local copy ---

    @Test
    fun `the local copy migration compiles and runs`() {
        openVersion2Database().use { connection ->
            connection.insertVersion2Row("a")
            connection.migrateAll()
        }
    }

    @Test
    fun `the local copy column exists after migrating`() {
        openVersion2Database().use { connection ->
            connection.migrateAll()
            assertTrue("cachedPath" in connection.columnNames())
        }
    }

    @Test
    fun `an existing row survives with no local copy yet`() {
        // Nullable and unset, so every track already in a library keeps working
        // and makes its copy the next time it is loaded or analysed. A migration
        // that demanded a value would have had to invent one.
        openVersion2Database().use { connection ->
            connection.insertVersion2Row("a", title = "Blue Monday")
            connection.migrateAll()

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT `title`, `cachedPath` FROM `tracks`").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("Blue Monday", rows.getString("title"))
                    assertNull(rows.getString("cachedPath"))
                }
            }
        }
    }

    @Test
    fun `the migrated table matches Room's own exported schema`() {
        // The test that catches the mistake that ships.
        //
        // Room validates the live table against the schema it exported for the
        // current version, and throws at open time when they differ — so adding
        // a field to the entity and forgetting the migration is a crash on
        // launch for everyone upgrading, and nothing whatsoever for a fresh
        // install. Which means it passes every test on a developer's machine
        // and fails only on devices that already had the app.
        //
        // Reading the exported JSON makes the entity itself the expectation, so
        // the next column added has to appear in a migration or this fails here.
        val expected = exportedColumnDefinitions(version = 4)
        assertTrue("no exported schema found for version 4", expected.isNotEmpty())

        openVersion2Database().use { connection ->
            connection.insertVersion2Row("a")
            connection.migrateAll()
            assertEquals(
                "the migrated table does not match the entity Room will validate against",
                expected,
                connection.columnDefinitions(),
            )
        }
    }

    @Test
    fun `a version 1 database migrates all the way rather than refusing to open`() {
        // The chain started at 2 while the schema was at 4, so a device still
        // holding a version 1 database met "a migration from 1 to 4 was
        // required but not found" on its first query — and, with no destructive
        // fallback in the builder either, could not open the app again at all.
        // Version 1 shipped: `build-and-release.yml` publishes an installable
        // APK on every push.
        openVersion1Database().use { connection ->
            connection.insertVersion1Row("a", title = "Blue Monday", cue = 12.5)
            connection.migrateFromVersion1()

            assertEquals(
                "a version 1 upgrade must land on exactly the schema Room validates",
                exportedColumnDefinitions(version = 4),
                connection.columnDefinitions(),
            )
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT `title`, `sourceUri`, `cuePointsCsv`, `bpm` FROM `tracks`")
                    .use { rows ->
                        assertTrue("the library must survive the upgrade", rows.next())
                        assertEquals("Blue Monday", rows.getString("title"))
                        assertEquals("/music/a.mp3", rows.getString("sourceUri"))
                        assertEquals("12.5", rows.getString("cuePointsCsv"))
                        // Version 1's tempo was derived from the filename, and
                        // 2 -> 3 exists to throw exactly that away.
                        rows.getDouble("bpm")
                        assertTrue("invented analysis must not survive", rows.wasNull())
                    }
            }
        }
    }

    @Test
    fun `the migrated table still matches the entity`() {
        // The version-3 test asserts this for its own columns; adding one to the
        // entity without adding it to the migration is exactly the mistake that
        // ships and then crashes Room at open time on an upgrading device.
        openVersion2Database().use { connection ->
            connection.migrateAll()
            val columns = connection.columnNames()
            for (expected in listOf("id", "title", "artist", "sourceUri", "cachedPath", "peaksPath", "energyPath")) {
                assertTrue("the migrated table is missing `$expected`", expected in columns)
            }
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
    fun `an interior gap keeps its position rather than compacting`() {
        val csv = cuesAfterMigration(listOf(1.0, null, 3.0, null))
        assertEquals("1.0,,3.0", csv)
        // Cue 2 must come back empty, not have cue 3 slide into its slot.
        val parsed = Track(title = "t", artist = "a", cuePointsCsv = csv).cuePoints
        assertEquals(listOf(1.0, null, 3.0), parsed)
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
