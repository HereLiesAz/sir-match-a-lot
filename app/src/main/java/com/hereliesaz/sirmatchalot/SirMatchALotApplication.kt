package com.hereliesaz.sirmatchalot

import android.app.Application
import com.hereliesaz.sirmatchalot.crash.CrashReportStore
import com.hereliesaz.sirmatchalot.crash.CrashReportingHandler

/**
 * Installs the crash handler before any activity or view model exists.
 *
 * This has to happen here rather than in [MainActivity]: a crash on a
 * background thread — analysis, playback, sync — can happen before the first
 * activity is even created, and the handler that is not installed yet cannot
 * record it.
 */
class SirMatchALotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        }.getOrDefault("")
        CrashReportingHandler.install(CrashReportStore.forContext(this), version)
    }
}
