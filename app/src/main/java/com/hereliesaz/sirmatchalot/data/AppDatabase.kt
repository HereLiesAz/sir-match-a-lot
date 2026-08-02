package com.hereliesaz.sirmatchalot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Track::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Rebuilds `tracks` around measured analysis — the statements
         * [MIGRATION_2_3] runs, in order.
         *
         * The old schema stored `bpm INTEGER NOT NULL` and
         * `camelotKey TEXT NOT NULL`, which had no way to express "not
         * measured" — so those columns were filled with values derived from
         * filenames and random numbers. Carrying them forward would preserve
         * the fiction, so the migration keeps what a user actually supplied
         * (identity, titles, file locations, cue points) and drops the invented
         * tempo, key, progression, atmosphere, and energy values, leaving them
         * null so the tracks are re-analysed from the audio.
         *
         * Cue points move from four nullable columns into one CSV column, since
         * the count is no longer fixed at four.
         *
         * Exposed as data, not hidden inside `migrate`, so a plain JVM test can
         * execute them against a real SQLite engine. That matters because
         * invalid migration SQL does not fail to compile, does not fail any test
         * that merely constructs the migration, and does not fail on a fresh
         * install — it fails when an *upgrading* user opens the app, at which
         * point they cannot open it again. This is the second bug of that shape
         * in this migration; the first was a primary key declared as a column
         * constraint rather than a table constraint.
         */
        val MIGRATION_2_3_STATEMENTS: List<String> = listOf(
            // Copied verbatim from Room's exported schema
            // (app/schemas/…AppDatabase/3.json, `createSql`) with only the
            // table name changed. Room verifies an identity hash derived
            // from the real table structure when it opens the database, so
            // any divergence here risks crashing upgrading users at launch.
            // Taking Room's own SQL removes the guesswork.
            "CREATE TABLE IF NOT EXISTS `tracks_new` (" +
                "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                "`sourceUri` TEXT, `bpm` REAL, `tempoConfidence` REAL NOT NULL DEFAULT 0, " +
                "`firstBeatSeconds` REAL, `downbeatOffset` INTEGER NOT NULL DEFAULT 0, " +
                "`camelotKey` TEXT, `keyName` TEXT, `keyConfidence` REAL NOT NULL DEFAULT 0, " +
                "`energyLevel` INTEGER, `durationMs` INTEGER NOT NULL DEFAULT 0, " +
                "`sampleRate` INTEGER NOT NULL DEFAULT 0, `trimStartMs` INTEGER NOT NULL DEFAULT 0, " +
                "`trimEndMs` INTEGER NOT NULL DEFAULT 0, `peaksPath` TEXT, `energyPath` TEXT, " +
                "`analysisVersion` INTEGER NOT NULL DEFAULT 0, " +
                "`isUserAdded` INTEGER NOT NULL DEFAULT 0, `cuePointsCsv` TEXT, " +
                "PRIMARY KEY(`id`))",

            // analysisVersion stays 0 so every carried-over row is treated as
            // unanalysed and gets measured properly on next import scan.
            //
            // Note TRIM(X, Y): SQLite has no TRIM(BOTH ',' FROM x). That
            // SQL-standard form parses fine to the eye and is a syntax error to
            // SQLite, which is exactly why these statements are now executed by
            // a test rather than only read.
            """
            INSERT INTO `tracks_new` (
                id, title, artist, sourceUri,
                durationMs, trimStartMs, trimEndMs,
                peaksPath, analysisVersion, isUserAdded, cuePointsCsv
            )
            SELECT
                id, title, artist, localPath,
                durationMs, trimStartMs, trimEndMs,
                peaksPath, 0, isUserAdded,
                NULLIF(
                    TRIM(
                        COALESCE(CAST(cuePoint1 AS TEXT), '') || ',' ||
                        COALESCE(CAST(cuePoint2 AS TEXT), '') || ',' ||
                        COALESCE(CAST(cuePoint3 AS TEXT), '') || ',' ||
                        COALESCE(CAST(cuePoint4 AS TEXT), ''),
                        ','
                    ),
                    ''
                )
            FROM tracks
            """.trimIndent(),

            "DROP TABLE `tracks`",
            "ALTER TABLE `tracks_new` RENAME TO `tracks`",
        )

        /**
         * The original schema, brought forward instead of destroyed.
         *
         * Version 1 shipped with `fallbackToDestructiveMigration()`, so a device
         * that upgraded while that was still in the builder had its library
         * wiped and landed on version 2. A device that did *not* upgrade in that
         * window — one that has sat on an old build — now meets a builder with
         * no fallback and no path from 1, and Room throws
         * `IllegalStateException: A migration from 1 to 4 was required but not
         * found` on the first query. That is not a wiped library; it is an app
         * that cannot be opened at all, ever again, without clearing its data.
         *
         * What version 1 stored of any value is identity, titles, file
         * locations and cue points. Its `bpm`, `camelotKey`, `progression`,
         * `atmosphere` and `energyLevel` were derived from filenames and random
         * numbers — see docs/AUDIT.md — which is why [MIGRATION_2_3] drops them
         * a step later. They are carried across here anyway rather than
         * special-cased, because the next migration already knows how to throw
         * them away, and a migration that quietly does two jobs is how the first
         * one gets forgotten.
         *
         * `mixTips`, `youtubeId` and version 1's `keyName` have no column in
         * version 2 and are dropped here, which is what version 2 did to them.
         */
        val MIGRATION_1_2_STATEMENTS: List<String> = listOf(
            "ALTER TABLE `tracks` RENAME TO `tracks_v1`",
            VERSION_2_TRACKS_SQL,
            """
            INSERT INTO `tracks` (
                `id`, `title`, `artist`, `localPath`, `bpm`, `camelotKey`,
                `progression`, `atmosphere`, `energy`, `durationMs`,
                `trimStartMs`, `trimEndMs`, `peaksPath`, `isUserAdded`,
                `cuePoint1`, `cuePoint2`, `cuePoint3`, `cuePoint4`
            )
            SELECT
                `id`, `title`, `artist`, `localPath`, `bpm`, `camelotKey`,
                `progression`, `atmosphere`, `energyLevel`, 0,
                0, 0, NULL, `isUserAdded`,
                `cuePoint1`, `cuePoint2`, `cuePoint3`, `cuePoint4`
            FROM `tracks_v1`
            """.trimIndent(),
            "DROP TABLE `tracks_v1`",
        )

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_STATEMENTS.forEach(db::execSQL)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_STATEMENTS.forEach(db::execSQL)
            }
        }

        /**
         * Adds the local copy of the audio file.
         *
         * One nullable column, so it is an `ALTER TABLE` rather than the
         * table rebuild version 3 needed. Existing rows get null and make their
         * copy the next time they are loaded or analysed.
         */
        val MIGRATION_3_4_STATEMENTS: List<String> = listOf(
            "ALTER TABLE `tracks` ADD COLUMN `cachedPath` TEXT",
        )

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_STATEMENTS.forEach(db::execSQL)
            }
        }

        /**
         * The version 2 `tracks` table, as shipped.
         *
         * Kept beside the migration so the test can build the database the
         * migration actually has to read, rather than a guess at it.
         */
        /**
         * The version 1 `tracks` table, as shipped.
         *
         * Reconstructed from the entity at the commit that carried
         * `@Database(version = 1)`, and kept beside [MIGRATION_1_2] for the same
         * reason [VERSION_2_TRACKS_SQL] is kept beside the next one: a migration
         * can only be tested against the table it actually has to read.
         */
        const val VERSION_1_TRACKS_SQL: String =
            "CREATE TABLE IF NOT EXISTS `tracks` (" +
                "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                "`bpm` INTEGER NOT NULL, `keyName` TEXT NOT NULL, `camelotKey` TEXT NOT NULL, " +
                "`progression` TEXT NOT NULL, `atmosphere` TEXT NOT NULL, " +
                "`energyLevel` INTEGER NOT NULL, `mixTips` TEXT NOT NULL, " +
                "`youtubeId` TEXT, `localPath` TEXT, `isUserAdded` INTEGER NOT NULL, " +
                "`cuePoint1` REAL, `cuePoint2` REAL, `cuePoint3` REAL, `cuePoint4` REAL, " +
                "PRIMARY KEY(`id`))"

        const val VERSION_2_TRACKS_SQL: String =
            "CREATE TABLE IF NOT EXISTS `tracks` (" +
                "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                "`localPath` TEXT, `bpm` INTEGER NOT NULL, `camelotKey` TEXT NOT NULL, " +
                "`progression` TEXT NOT NULL, `atmosphere` TEXT NOT NULL, " +
                "`energy` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`trimStartMs` INTEGER NOT NULL, `trimEndMs` INTEGER NOT NULL, " +
                "`peaksPath` TEXT, `isUserAdded` INTEGER NOT NULL, " +
                "`cuePoint1` REAL, `cuePoint2` REAL, `cuePoint3` REAL, `cuePoint4` REAL, " +
                "PRIMARY KEY(`id`))"

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sir_match_a_lot_database",
                )
                    // A real migration, not fallbackToDestructiveMigration().
                    // The previous builder wiped the user's entire library on
                    // every schema change.
                    //
                    // From 1, not from 2. The chain started at 2 while the
                    // schema was at 4, so a device still holding a version 1
                    // database — a build that shipped, from a workflow that
                    // publishes an installable APK on every push — hit "a
                    // migration from 1 to 4 was required but not found" at first
                    // query, and could not open the app again.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
