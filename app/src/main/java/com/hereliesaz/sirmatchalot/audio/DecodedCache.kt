package com.hereliesaz.sirmatchalot.audio

/**
 * Holds decoded tracks, bounded by total audio rather than by entry count.
 *
 * Decoding is expensive — MediaCodec over the whole file, then a sample-rate
 * conversion — so a track loaded twice should not be decoded twice. The previous
 * cache was a plain `MutableMap` that nothing ever removed from, which made that
 * saving unbounded: every track ever put on a deck stayed in memory as full PCM
 * for the lifetime of the ViewModel. Five minutes of 48 kHz stereo is about
 * 58 MB, so an Automatchic Mix of ten tracks accumulated well over half a
 * gigabyte on a heap that tops out at 256 MB. That was reported as
 * `OutOfMemoryError`.
 *
 * Bounded by **bytes**, not entries, because tracks differ in length by more
 * than an order of magnitude and a count-based bound says nothing about memory.
 *
 * Bytes rather than frames, specifically, because depth now varies: a hi-res
 * source is stored as float and costs four bytes a sample where a CD rip costs
 * two. Counting frames would have made the cache believe a float track was half
 * the size it is, and the budget exists precisely to stop the heap running out.
 *
 * ## Pinning
 *
 * A buffer that a deck clip or a sampler pad is currently reading must never be
 * evicted — the render thread holds the reference and would keep playing it, but
 * a later reload would decode a second copy and the "cache" would have made
 * things worse rather than better. Eviction therefore takes the set of ids that
 * are in use, and skips them. If the pinned set alone exceeds the budget,
 * nothing is evicted: correctness first, and [overBudget] says so, so a caller
 * can decline to load yet another track rather than discovering the ceiling by
 * crashing.
 *
 * Not thread-safe; owned by the ViewModel, which touches it from one coroutine.
 */
class DecodedCache(
    /** Ceiling on total sample bytes held, summed across channels. */
    private val maxBytes: Long = defaultBudgetBytes(),
) {
    private val entries = LinkedHashMap<String, PcmBuffer>()

    /** Total sample bytes held, counting each channel at its own depth. */
    var heldBytes: Long = 0
        private set

    val size: Int get() = entries.size

    /** True when what is pinned alone exceeds the budget. */
    var overBudget: Boolean = false
        private set

    /**
     * The buffer for [id], marking it most recently used.
     */
    operator fun get(id: String): PcmBuffer? {
        val buffer = entries.remove(id) ?: return null
        entries[id] = buffer
        return buffer
    }

    operator fun contains(id: String): Boolean = id in entries

    /**
     * Stores [buffer] under [id] and evicts least-recently-used entries that are
     * not in [pinned] until the total is within budget.
     *
     * The new entry is never itself evicted, since it was just asked for.
     */
    fun put(id: String, buffer: PcmBuffer, pinned: Set<String> = emptySet()) {
        entries.remove(id)?.let { heldBytes -= it.byteCount }
        entries[id] = buffer
        heldBytes += buffer.byteCount
        trim(pinned + id)
    }

    /** Drops [id] if it is held and not pinned elsewhere. */
    fun remove(id: String) {
        entries.remove(id)?.let { heldBytes -= it.byteCount }
    }

    /**
     * Evicts everything not in [pinned], oldest first, until within budget.
     *
     * Called after a track is retired from a deck as well as on insertion, so a
     * long mix releases each track as it finishes rather than at the end.
     */
    fun trim(pinned: Set<String>) {
        if (heldBytes <= maxBytes) {
            overBudget = false
            return
        }
        val candidates = entries.keys.filterNot { it in pinned }
        for (id in candidates) {
            if (heldBytes <= maxBytes) break
            entries.remove(id)?.let { heldBytes -= it.byteCount }
        }
        overBudget = heldBytes > maxBytes
    }

    fun clear() {
        entries.clear()
        heldBytes = 0
        overBudget = false
    }

    companion object {
        /**
         * Bytes to hold, from the runtime's own heap ceiling rather than a fixed
         * number — the limit is 256 MB on some devices and several times that on
         * others, and a constant tuned for one is wrong for the other.
         *
         * A third of the heap. The rest has to cover the decoder's accumulation
         * buffers, the conversion intermediates, and Compose.
         */
        fun defaultBudgetBytes(): Long {
            val heapBytes = Runtime.getRuntime().maxMemory()
            return (heapBytes / 3).coerceAtLeast(MINIMUM_BYTES)
        }

        /**
         * Enough for roughly two five-minute 16-bit stereo tracks, whatever the
         * heap says. A pair of hi-res tracks is twice that and will not fit —
         * which is the honest answer on a small device, and `overBudget` is how
         * a caller learns it before the allocator does.
         */
        private const val MINIMUM_BYTES = 2L * 5 * 60 * 48_000 * 2 * 2
    }
}
