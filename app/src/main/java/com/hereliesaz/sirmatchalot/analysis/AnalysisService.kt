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
 * The service owns its own database handle and analyser rather than reaching
 * back into the ViewModel, because it outlives the ViewModel by design. Progress
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                AnalysisProgressBus.setPaused(true)
                notify(AnalysisProgressBus.state.value)
                return START_STICKY
            }
            ACTION_RESUME -> {
                AnalysisProgressBus.setPaused(false)
                notify(AnalysisProgressBus.state.value)
                return START_STICKY
            }
            ACTION_STOP -> {
                AnalysisProgressBus.requestStop()
                return START_STICKY
            }
        }

        if (worker?.isActive == true) return START_STICKY

        val rescan = intent?.getBooleanExtra(EXTRA_RESCAN, false) ?: false
        startForegroundCompat(buildNotification(AnalysisState(total = 1, current = "Starting...")))
        worker = scope.launch { run(rescan) }
        return START_STICKY
    }

    private suspend fun run(rescan: Boolean = false) {
        val dao = AppDatabase.getDatabase(applicationContext).trackDao()
        val analyzer = TrackAnalyzer()

        val tracks = dao.getAllTracks()
        val plan = if (rescan) AnalysisQueue.planFullRescan(tracks) else AnalysisQueue.plan(tracks)
        if (!plan.hasWork) {
            AnalysisProgressBus.finish()
            stopSelf()
            return
        }

        AnalysisProgressBus.begin(plan.toAnalyse.size)
        var done = 0
        var failed = 0

        for (track in plan.toAnalyse) {
            if (AnalysisProgressBus.stopRequested) break

            // Hold between tracks rather than inside one, so resuming does not
            // repeat work already paid for.
            while (AnalysisProgressBus.pauseRequested && !AnalysisProgressBus.stopRequested) {
                delay(400)
            }
            if (AnalysisProgressBus.stopRequested) break

            AnalysisProgressBus.update(done, track.title, failed)
            notify(AnalysisProgressBus.state.value)

            if (!analyse(track, dao, analyzer)) failed++
            done++
        }

        AnalysisProgressBus.update(done, "", failed)
        notifyComplete(done, failed)
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

            val rate = decoded.pcm.sampleRate.toDouble()
            dao.updateTrack(
                track.copy(
                    bpm = analysis.bpm,
                    tempoConfidence = analysis.tempoConfidence,
                    firstBeatSeconds = analysis.beatGrid?.firstBeatSeconds,
                    downbeatOffset = analysis.beatGrid?.downbeatOffset ?: 0,
                    camelotKey = analysis.camelotKey,
                    keyName = analysis.keyName,
                    keyConfidence = analysis.keyConfidence,
                    energyLevel = analysis.energyLevel,
                    durationMs = (analysis.durationSeconds * 1000).toLong(),
                    sampleRate = decoded.pcm.sampleRate,
                    trimStartMs = (decoded.trimmedStartFrames / rate * 1000).toLong(),
                    trimEndMs = ((decoded.trimmedStartFrames + decoded.pcm.frameCount) / rate * 1000).toLong(),
                    peaksPath = peaksFile.absolutePath,
                    energyPath = energyFile.absolutePath,
                    analysisVersion = Track.CURRENT_ANALYSIS_VERSION,
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
            if (state.total > 0) append("${state.done + 1} of ${state.total}")
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

    private fun notifyComplete(done: Int, failed: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle("Analysis finished")
            .setContentText(
                buildString {
                    append("$done ${if (done == 1) "track" else "tracks"} measured")
                    if (failed > 0) append(", $failed could not be decoded")
                },
            )
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setAutoCancel(true)
            .setOngoing(false)
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
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
