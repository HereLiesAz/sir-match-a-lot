package com.hereliesaz.sirmatchalot.crash

import android.content.Context
import com.hereliesaz.sirmatchalot.data.KeyValueStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The one pending crash report, if the app died since this was last read.
 *
 * Holds at most one on purpose: a crash loop should not fill the app's
 * preferences with a hundred near-identical reports, and the prompt shown on
 * next launch only ever has one thing to say. A new crash overwrites whatever
 * was pending, since the most recent failure is the one still reproducible.
 */
class CrashReportStore(private val store: KeyValueStore) {

    fun save(report: CrashReport) {
        store.putString(KEY, JSON.encodeToString(report))
    }

    /** The pending report, or null when there is none or it did not parse. */
    fun load(): CrashReport? =
        store.getString(KEY)?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSON.decodeFromString<CrashReport>(it) }.getOrNull()
        }

    /** Called once the person has seen the report, whichever way they answered. */
    fun clear() = store.putString(KEY, "")

    companion object {
        private const val KEY = "pending_crash_report"
        const val PREFERENCES_NAME = "crash_reports"

        private val JSON = Json {
            ignoreUnknownKeys = true
        }

        /** A store backed by the app's own preferences file. */
        fun forContext(context: Context): CrashReportStore {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            return CrashReportStore(
                object : KeyValueStore {
                    override fun getString(key: String): String? = preferences.getString(key, null)
                    override fun putString(key: String, value: String) {
                        preferences.edit().putString(key, value).apply()
                    }
                },
            )
        }
    }
}
