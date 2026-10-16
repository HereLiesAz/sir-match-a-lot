package com.hereliesaz.sirmatchalot.analysis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.hereliesaz.sirmatchalot.audio.AudioDecoder
import com.hereliesaz.sirmatchalot.data.AnalysisColumns
import com.hereliesaz.sirmatchalot.data.AnalysisQueue
import com.hereliesaz.sirmatchalot.data.AppDatabase
import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.dsp.EnergyCurve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Analyses the library in the background, reporting in a notification.
 *
 * Analysis is an FFT pass over every whole track. A folder of a hundred songs is
 * therefore minutes of work, and doing it inside the ViewModel meant it died
 * whenever the app was backgrounded — which is exactly when a user would leave
 * it to get on with it. This was asked for directly: *"If the playlist is
 * longer, then set it up to run in the background, allowing it to pause and
 * resume, reporting its progress in a notification."*
 *
 * The service reaches the database through the process-wide [AppDatabase]
 * singleton and runs its own [TrackAnalyzer], never anything held by the
 * ViewModel, because it outlives the ViewModel by design. Since the row it
 * measures can be edited while the measurement runs, it writes back only the
 * analysis columns ([com.hereliesaz.sirmatchalot.data.TrackDao.updateAnalysis]),
 * never the whole row it started from. Progress
 * travels the other way through [AnalysisProgressBus], so the library screen and
 * the notification show the same figures without either being the source of the
 * other.
 *
 * Pause takes effect **between tracks**, not inside one: abandoning a half-
 * measured track would mean re-measuring it from the start on resume, so the
 * loop finishes what it is holding and then waits.
 */
class AnalysisService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var worker: Job? = null

    /**
     * Stop pressed for the current run, recorded here as well as on the bus.
     *
     * [AnalysisProgressBus.begin] clears the bus's stop flag, and the worker
     * calls it a moment after it starts — so a Stop that arrived in between was
     * wiped and the run went ahead anyway. This flag belongs to the run and is
     * only cleared when a new one is started.
     */
    @Volatile
    private var stopThisRun = false

    private val stopped: Boolean get() = stopThisRun || AnalysisProgressBus.stopRequested

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE, ACTION_RESUME -> {
                // A stale notification button can arrive after the run ended.
                // That delivery creates the service afresh, and returning sticky
                // without ever calling `startForeground` left it running in the
                // background, doing nothing, for the system to recreate.
                if (worker?.isActive != true) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                AnalysisProgressBus.setPaused(intent.action == ACTION_PAUSE)
                notify(AnalysisProgressBus.state.value)
                return START_STICKY
            }
            ACTION_STOP -> {
                stopThisRun = true
                AnalysisProgressBus.requestStop()
                // Stopping a run that is not running has to end the service, not
                // start one. Pressing Stop while idle delivered an intent, this
                // returned START_STICKY without ever calling `startForeground`,
                // and the result was a service doing no work, in no foreground,
                // that never stopped itself — and, being sticky, one the system
                // would recreate.
                if (worker?.isActive != true) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
        }

        if (worker?.isActive == true) return START_STICKY

        // A null intent is the system restarting a sticky service after a kill,
        // and that restart is background-initiated: calling `startForeground`
        // from it throws `ForegroundServiceStartNotAllowedException` on Android
        // 12 and up. There is nothing to resume from anyway — the queue is
        // recomputed from the database when the user asks again.
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val rescan = intent.getBooleanExtra(EXTRA_RESCAN, false)
        stopThisRun = false
        startForegroundCompat(buildNotification(AnalysisState(total = 1, current = "Starting...")))
        worker = scope.launch { run(rescan) }
        return START_STICKY
    }

    private suspend fun run(rescan: Boolean = false) {
        val dao = AppDatabase.getDatabase(applicationContext).trackDao()
        val analyzer = TrackAnalyzer()

        val tracks = dao.getAllTracks()
        val plan = if (rescan) AnalysisQueue.planFullRescan(tracks) else AnalysisQueue.plan(tracks)
        if (!plan.hasWork || stopped) {
            AnalysisProgressBus.finish()
            stopSelf()
            return
        }

        AnalysisProgressBus.begin(plan.toAnalyse.size)
        // begin() clears the bus's stop flag; a Stop that landed before it is
        // still in [stopThisRun], and is put back so the UI sees it too.
        if (stopThisRun) AnalysisProgressBus.requestStop()
        var done = 0
        var failed = 0

        for (track in plan.toAnalyse) {
            if (stopped) break

            // Hold between tracks rather than inside one, so resuming does not
            // repeat work already paid for.
            while (AnalysisProgressBus.pauseRequested && !stopped) {
                delay(400)
            }
            if (stopped) break

            AnalysisProgressBus.update(done, track.title, failed)
            notify(AnalysisProgressBus.state.value)

            if (!analyse(track, dao, analyzer)) failed++
            done++
        }

        AnalysisProgressBus.update(done, "", failed)
        val wasStopped = stopped
        // Out of the foreground first, removing the progress notification, and
        // only then the summary under its own id. Posted under the foreground
        // id, as it was, the summary *was* the foreground notification, and
        // stopSelf() took it down with the service a moment after it appeared.
        stopForegroundCompat()
        notifyComplete(done, failed, wasStopped)
        AnalysisProgressBus.finish()
        stopSelf()
    }

    /** Measures one track and stores what was measured. Returns false on failure. */
    private suspend fun analyse(
        track: Track,
        dao: com.hereliesaz.sirmatchalot.data.TrackDao,
        analyzer: TrackAnalyzer,
    ): Boolean {
        val source = track.sourceUri ?: return false
        return try {
            val decoded = AudioDecoder.decode(applicationContext, Uri.parse(source)) ?: return false
            val analysis = analyzer.analyse(decoded.pcm)

            val peaksFile = File(filesDir, "peaks/${track.id}.peaks")
            peaksFile.parentFile?.mkdirs()
            peaksFile.writeBytes(analysis.peaks.toByteArray())

            val energyFile = File(filesDir, "energy/${track.id}.energy")
            energyFile.parentFile?.mkdirs()
            energyFile.writeBytes(analysis.energyCurve.toByteArray())

            // Only the measured columns, keyed by id: [track] is a snapshot
            // taken when the run began, and writing it back whole reverted any
            // edit made to the row in the minutes since.
            dao.updateAnalysis(
                track.id,
                AnalysisColumns.from(
                    analysis = analysis,
                    sampleRate = decoded.pcm.sampleRate,
                    trimmedStartFrames = decoded.trimmedStartFrames,
                    frameCount = decoded.pcm.frameCount,
                    peaksPath = peaksFile.absolutePath,
                    energyPath = energyFile.absolutePath,
                ),
            )
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            // One track too large for this device must not end a run of two
            // hundred. Decoding holds the whole file as PCM, so an hour-long
            // hi-res set is easily larger than the heap — and it is counted as a
            // failure, reported in the notification, and the loop moves on.
            System.gc()
            false
        } catch (e: Exception) {
            false
        }
    }

    // --- Notification ---

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Track analysis",
                // Low: this is progress on work the user asked for, not an
                // interruption. It should be readable, not attention-grabbing.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress while measuring tempo, key and energy" },
        )
    }

    private fun buildNotification(state: AnalysisState): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val title = if (state.paused) "Analysis paused" else "Analysing your library"
        val text = buildString {
            // "done + 1" reads as "working on item N of total" while a track
            // is in flight — but the state published right after the loop
            // finishes (done == total, just before finish() clears it) hit
            // this same formula and briefly showed "38 of 37" here while the
            // library screen's plain "done/total" correctly read "37/37".
            if (state.total > 0) append("${(state.done + 1).coerceAtMost(state.total)} of ${state.total}")
            if (state.current.isNotEmpty()) {
                if (isNotEmpty()) append(" — ")
                append(state.current)
            }
        }

        builder
            .setContentTitle(title)
            .setContentText(text.ifEmpty { "Working..." })
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOnlyAlertOnce(true)
            .setOngoing(!state.paused)
            .setProgress(state.total.coerceAtLeast(1), state.done, false)

        builder.addAction(
            if (state.paused) action(ACTION_RESUME, "Resume") else action(ACTION_PAUSE, "Pause"),
        )
        builder.addAction(action(ACTION_STOP, "Stop"))
        return builder.build()
    }

    private fun action(actionName: String, label: String): Notification.Action {
        val intent = Intent(this, AnalysisService::class.java).setAction(actionName)
        val pending = PendingIntent.getService(
            this,
            actionName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, label, pending).build()
    }

    private fun notify(state: AnalysisState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(state)) }
    }

    private fun notifyComplete(done: Int, failed: Int, stopped: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle(if (stopped) "Analysis stopped" else "Analysis finished")
            .setContentText(completionText(done, failed))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setAutoCancel(true)
            .setOngoing(false)
        runCatching { manager.notify(COMPLETE_NOTIFICATION_ID, builder.build()) }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        AnalysisProgressBus.finish()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "analysis"
        private const val NOTIFICATION_ID = 4711

        /** The end-of-run summary; separate from the foreground id so it outlives the service. */
        private const val COMPLETE_NOTIFICATION_ID = 4712

        /**
         * The summary line for a run that attempted [attempted] tracks, [failed]
         * of which could not be measured. [attempted] counts the failures too,
         * so "measured" is the difference — reporting it whole claimed every
         * failed track as measured as well.
         */
        internal fun completionText(attempted: Int, failed: Int): String {
            val measured = (attempted - failed).coerceAtLeast(0)
            return buildString {
                append("$measured ${if (measured == 1) "track" else "tracks"} measured")
                if (failed > 0) append(", $failed could not be analysed")
            }
        }

        const val ACTION_PAUSE = "com.hereliesaz.sirmatchalot.ANALYSIS_PAUSE"
        const val ACTION_RESUME = "com.hereliesaz.sirmatchalot.ANALYSIS_RESUME"
        const val ACTION_STOP = "com.hereliesaz.sirmatchalot.ANALYSIS_STOP"

        /** Set to re-measure everything, not only what has never been measured. */
        const val EXTRA_RESCAN = "com.hereliesaz.sirmatchalot.ANALYSIS_RESCAN"

        /** Starts a run, or does nothing if one is already going. */
        fun start(context: Context, rescan: Boolean = false) {
            val intent = Intent(context, AnalysisService::class.java)
                .putExtra(EXTRA_RESCAN, rescan)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, AnalysisService::class.java).setAction(action))
        }
    }
}
