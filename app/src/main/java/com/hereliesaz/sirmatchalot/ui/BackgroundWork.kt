package com.hereliesaz.sirmatchalot.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One thing the app is currently doing that takes long enough to notice.
 *
 * @param id stable per operation, so an update finds the item it belongs to and
 *   two loads at once are two items rather than one flickering between names.
 * @param label what is being done, in words a performer would use — "Loading
 *   Blue Monday", not "decodeAndConform".
 * @param detail which part of it is happening now, when that changes as it runs.
 *   A track load spends most of its time decoding and then a while conforming,
 *   and "still going" is a much worse answer than "conforming to 128 BPM".
 * @param progress 0..1 where it is genuinely known, null where it is not. A
 *   fabricated bar that creeps to 90% and waits is a lie about a measurement,
 *   and the indeterminate spinner is the honest rendering of "no idea how long".
 */
data class WorkItem(
    val id: String,
    val label: String,
    val detail: String? = null,
    val progress: Float? = null,
) {
    /** The whole thing on one line, for the indicator that has room for one. */
    fun summary(): String = if (detail.isNullOrBlank()) label else "$label — $detail"
}

/**
 * What the app is doing right now, so it can say so.
 *
 * Everything slow in this app happened silently. Dropping a track on the platter
 * decodes the whole file, converts its sample rate, and then time-stretches and
 * pitch-shifts it to match the session — seconds to tens of seconds on a long
 * track — and the only sign of it was a line of text on the *Library* tab, which
 * is not the tab you drop tracks from. Analysis progress lived on the Library
 * screen, loop harvesting on the Sampler screen, and a track load nowhere at
 * all. Whichever screen you were on, the work you had just started was invisible
 * from it.
 *
 * So this is deliberately global and deliberately dumb: a registry of what is in
 * flight, owned by the ViewModel, rendered in the app bar that every tab shares.
 *
 * ## Why [track] rather than begin/end
 *
 * A spinner that never stops is worse than no spinner: it teaches you to ignore
 * the one place the app tells you anything. Every failure path has to clear its
 * item — an early return, a decode that gives up, an `OutOfMemoryError`, a
 * cancelled harvest — and remembering that at every exit of a long function is
 * exactly the kind of thing nobody remembers. [track] puts it in a `finally`,
 * once, where it cannot be forgotten.
 *
 * Not thread-safe by design; the ViewModel touches it from its own coroutines.
 */
class BackgroundWork {

    private val _items = MutableStateFlow<List<WorkItem>>(emptyList())

    /** Everything in flight, in the order it started. */
    val items: StateFlow<List<WorkItem>> = _items

    val isBusy: Boolean get() = _items.value.isNotEmpty()

    /**
     * Registers work under [id], replacing any existing item with that id.
     *
     * Replacing rather than stacking, because the same operation started twice
     * is one operation as far as anyone watching is concerned.
     */
    fun begin(id: String, label: String, detail: String? = null, progress: Float? = null) {
        val item = WorkItem(id, label, detail, progress)
        _items.value = _items.value.filterNot { it.id == id } + item
    }

    /**
     * Changes what a running item says, keeping its position in the list.
     *
     * Position matters: the indicator shows the first item, so an update that
     * moved a long-running job to the end would make the display jump to
     * whatever started most recently every time progress ticked.
     *
     * Does nothing when [id] is not running, so an update racing a completion
     * cannot resurrect a finished item as a spinner nobody can clear.
     */
    fun update(id: String, detail: String? = null, progress: Float? = null) {
        val existing = _items.value.firstOrNull { it.id == id } ?: return
        _items.value = _items.value.map {
            if (it.id == id) {
                existing.copy(
                    detail = detail ?: existing.detail,
                    progress = progress ?: existing.progress,
                )
            } else {
                it
            }
        }
    }

    fun end(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
    }

    fun clear() {
        _items.value = emptyList()
    }

    /**
     * Runs [block] with this work showing, and clears it however [block] ends.
     *
     * The `finally` is the point. Returns whatever [block] returns, and lets
     * every exception — including [kotlinx.coroutines.CancellationException],
     * which a cancelled harvest or a killed load throws — propagate unchanged.
     *
     * **Not `inline`.** It was, so that a `return@launch` inside [block] would
     * compile — the bodies this wraps are long, with early exits all through
     * them. That combination, an inline suspend function taking a suspend lambda
     * containing a non-local return, crashed the Kotlin JVM backend while
     * building the coroutine state machine: *"Couldn't transform method node"*.
     * Intermittently, which is worse than always — it compiled on a developer
     * machine and in CI, then failed on the next build with nothing relevant
     * changed.
     *
     * Call sites use `return@track` instead. Since the tracked block is the
     * whole body of the coroutine in every case, returning from the block and
     * returning from the coroutine are the same thing — and the `finally` runs
     * either way, which is what actually matters here.
     */
    suspend fun <T> track(
        id: String,
        label: String,
        detail: String? = null,
        block: suspend (Handle) -> T,
    ): T {
        begin(id, label, detail)
        return try {
            block(Handle(id))
        } finally {
            end(id)
        }
    }

    /** Lets a running block say what it is up to without repeating its own id. */
    inner class Handle(private val id: String) {
        fun detail(text: String) = update(id, detail = text)
        fun progress(fraction: Float) = update(id, progress = fraction.coerceIn(0f, 1f))
        fun progress(done: Int, total: Int) {
            if (total > 0) update(id, progress = (done.toFloat() / total).coerceIn(0f, 1f))
        }
    }

    companion object {
        /** Ids, so an update and its completion cannot disagree about the spelling. */
        fun loadId(trackId: String) = "load:$trackId"
        fun stretchId(clipId: String) = "stretch:$clipId"

        const val ANALYSIS = "analysis"
        const val HARVEST = "harvest"
        const val IMPORT = "import"
        const val SYNC = "sync"
        const val PADS = "pads"
        const val SESSION = "session"
        const val SERVICE_ANALYSIS = "service-analysis"
    }
}
