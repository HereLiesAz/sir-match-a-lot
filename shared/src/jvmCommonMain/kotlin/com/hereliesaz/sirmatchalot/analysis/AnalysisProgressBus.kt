package com.hereliesaz.sirmatchalot.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * How far a background analysis run has got.
 *
 * @param done tracks finished.
 * @param total tracks in the run.
 * @param current title being worked on.
 * @param paused whether the run is holding.
 */
data class AnalysisState(
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val paused: Boolean = false,
    val failed: Int = 0,
) {
    val running: Boolean get() = total > 0 && done < total
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

/**
 * Shared state between the analysis service and whatever is on screen.
 *
 * The service outlives the UI — that is the point of running analysis in a
 * service — so progress cannot live in the ViewModel. A process-wide holder is
 * the simplest thing that lets the notification and the library screen show the
 * same figures without either owning the other, and it keeps the service free of
 * binder plumbing for what is a handful of integers.
 *
 * [requestPause] and [requestStop] are read by the service's loop between
 * tracks, so a pause takes effect at the next track boundary rather than
 * abandoning a half-measured one.
 *
 * Partial updates go through [MutableStateFlow.update], so a pause toggled
 * from the UI thread cannot be lost to a progress write from the service's
 * worker landing between its read and its write.
 */
object AnalysisProgressBus {

    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state

    @Volatile
    var pauseRequested: Boolean = false
        private set

    @Volatile
    var stopRequested: Boolean = false
        private set

    fun begin(total: Int) {
        pauseRequested = false
        stopRequested = false
        _state.value = AnalysisState(done = 0, total = total)
    }

    fun update(done: Int, current: String, failed: Int) {
        _state.update { it.copy(done = done, current = current, failed = failed) }
    }

    fun setPaused(paused: Boolean) {
        pauseRequested = paused
        _state.update { it.copy(paused = paused) }
    }

    fun requestStop() {
        stopRequested = true
    }

    fun finish() {
        pauseRequested = false
        stopRequested = false
        _state.value = AnalysisState()
    }
}
