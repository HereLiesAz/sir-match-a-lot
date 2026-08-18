package com.hereliesaz.sirmatchalot.data

/**
 * The engine's sample rate, as a choice rather than a constant.
 *
 * Rate is the single biggest lever on both memory and power, and neither of the
 * two things it trades against is universally right:
 *
 * - **Memory.** A decoded track costs `rate × channels × depth` bytes a second
 *   and nothing else. Running at 22.05 kHz instead of 48 kHz makes every buffer
 *   in the app — the decode accumulator, the converted copy, the stretched copy,
 *   everything the cache holds — 46% of the size. On a device whose heap tops
 *   out at 256 MB that is the difference between two tracks and five.
 * - **Power.** Every stage of the render graph is per sample: the interpolating
 *   read per clip, the deck EQ, the mixer sum, the master filter, the limiter,
 *   the meter. Halving the rate halves all of it.
 * - **Quality.** Against that, [DEVICE] is the only setting where AudioFlinger
 *   does no resampling of its own on the way out, and 22.05 kHz has a Nyquist
 *   limit of 11 kHz — the top octave of the music is simply gone.
 *
 * So it is the user's call, and the labels say what each one costs.
 */
enum class SampleRateOption(val label: String, val hz: Int?) {

    /**
     * Whatever the device's own mixer runs at.
     *
     * The best-sounding choice and the default: it is the only rate at which the
     * platform mixer is not resampling every block on the way out.
     */
    DEVICE("Device native", null),

    HZ_48000("48 kHz", 48_000),
    HZ_44100("44.1 kHz — CD", 44_100),
    HZ_32000("32 kHz — less memory", 32_000),
    HZ_22050("22.05 kHz — least memory", 22_050),
    ;

    /** The rate to actually run at, given what the device reports. */
    fun resolve(deviceRate: Int): Int = hz ?: deviceRate

    /**
     * Megabytes one minute of decoded stereo 16-bit audio costs at this rate.
     *
     * Shown next to the choice, because "22.05 kHz" means nothing to most people
     * and "5 MB a minute instead of 11" means quite a lot.
     */
    fun megabytesPerMinute(deviceRate: Int): Double =
        resolve(deviceRate).toDouble() * CHANNELS * BYTES_PER_SAMPLE * 60.0 / (1024 * 1024)

    companion object {
        private const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2

        fun fromKey(key: String?): SampleRateOption =
            entries.firstOrNull { it.name == key } ?: DEVICE
    }
}

/**
 * How often the platter and the light show redraw.
 *
 * The visuals were the app's second-largest power draw after the render thread:
 * a full-screen Canvas of additively blended radial gradients, redrawn on every
 * display frame whether or not anything had moved. Nothing about a playhead
 * sweeping a five-minute revolution needs 60 fps — it advances a third of a
 * degree a frame — so this exists to let someone spend that budget or not.
 */
enum class VisualRefresh(val label: String, val fps: Int) {
    HIGH("Smooth — 60 fps", 60),
    BALANCED("Balanced — 30 fps", 30),
    BATTERY("Battery — 15 fps", 15),
    ;

    /** Milliseconds between redraws. */
    val frameMillis: Long get() = (1000L / fps).coerceAtLeast(1L)

    companion object {
        fun fromKey(key: String?): VisualRefresh =
            entries.firstOrNull { it.name == key } ?: BALANCED
    }
}

/**
 * How much of the heap decoded audio may occupy.
 *
 * A fraction rather than a number of megabytes, because the ceiling is 256 MB on
 * some devices and several times that on others, and a constant tuned for one is
 * wrong for the other.
 */
enum class MemoryBudget(val label: String, val heapDivisor: Int) {
    SMALL("Careful — a sixth of the heap", 6),
    BALANCED("Balanced — a third of the heap", 3),
    GENEROUS("Generous — half the heap", 2),
    ;

    /** Bytes of decoded audio to allow, given a heap ceiling of [heapBytes]. */
    fun bytesFor(heapBytes: Long): Long = heapBytes / heapDivisor

    companion object {
        fun fromKey(key: String?): MemoryBudget =
            entries.firstOrNull { it.name == key } ?: BALANCED
    }
}

/**
 * How much disk local copies of imported audio may use.
 *
 * Copies exist so the app owns what it plays: a cloud source otherwise refetches
 * over the network on every read, and a lapsed grant or a moved file takes the
 * audio away from a library row that survives. See
 * [com.hereliesaz.sirmatchalot.data.AudioFileCache].
 *
 * Encoded audio, so the numbers are ordinary: roughly a megabyte a minute, which
 * is about 30 hours to the gigabyte.
 */
enum class LocalCopyBudget(val label: String, val maxBytes: Long?) {
    OFF("Off — play from the original every time", 0L),
    GB_2("Up to 2 GB — around 30 hours", 2L * 1024 * 1024 * 1024),
    GB_8("Up to 8 GB — around 130 hours", 8L * 1024 * 1024 * 1024),
    UNLIMITED("No limit", null),
    ;

    val isEnabled: Boolean get() = this != OFF

    /** The ceiling to trim against; [Long.MAX_VALUE] when there is none. */
    fun ceiling(): Long = maxBytes ?: Long.MAX_VALUE

    companion object {
        fun fromKey(key: String?): LocalCopyBudget =
            entries.firstOrNull { it.name == key } ?: GB_2
    }
}

/**
 * Everything the user can set about how the engine spends memory and power.
 *
 * A plain immutable value, with no Android in it, so the defaults and the
 * encoding are ordinary unit-testable code.
 */
data class EngineSettings(
    val sampleRate: SampleRateOption = SampleRateOption.DEVICE,
    val visualRefresh: VisualRefresh = VisualRefresh.BALANCED,
    val memoryBudget: MemoryBudget = MemoryBudget.BALANCED,

    /**
     * Whether the background light show is drawn at all.
     *
     * The most expensive thing on screen: six full-screen radial gradients
     * composited additively. Beautiful, and the first thing to turn off when a
     * set has to last the night.
     */
    val lightShow: Boolean = true,

    /**
     * Whether the audio thread stands down when nothing is sounding.
     *
     * On by default. The render graph used to run continuously from the moment
     * the app opened — mixing, filtering, limiting and metering silence at the
     * device's native rate, forever, with `AudioTrack` holding the output path
     * open the whole time. That is a constant drain for no sound.
     *
     * Off is for someone who would rather have the first sample after a pause be
     * absolutely immediate than have the battery back.
     */
    val idleShutdown: Boolean = true,

    /**
     * How much disk local copies of imported audio may take.
     *
     * On by default, and deliberately: the alternative is an app whose library
     * stops working when a grant lapses, a file moves, or the network does, and
     * every one of those has happened.
     */
    val localCopies: LocalCopyBudget = LocalCopyBudget.GB_2,
) {
    /** Whether a change from [other] requires rebuilding the audio engine. */
    fun requiresEngineRebuild(other: EngineSettings): Boolean = sampleRate != other.sampleRate
}

/** Reads and writes [EngineSettings] as strings. */
class SettingsStore(private val store: KeyValueStore) {

    fun load(): EngineSettings = EngineSettings(
        localCopies = LocalCopyBudget.fromKey(store.getString(KEY_LOCAL_COPIES)),
        sampleRate = SampleRateOption.fromKey(store.getString(KEY_SAMPLE_RATE)),
        visualRefresh = VisualRefresh.fromKey(store.getString(KEY_VISUAL_REFRESH)),
        memoryBudget = MemoryBudget.fromKey(store.getString(KEY_MEMORY_BUDGET)),
        lightShow = store.getString(KEY_LIGHT_SHOW)?.toBooleanStrictOrNull() ?: true,
        idleShutdown = store.getString(KEY_IDLE_SHUTDOWN)?.toBooleanStrictOrNull() ?: true,
    )

    /**
     * The learned transition model, as an opaque string.
     *
     * Kept out of [EngineSettings] on purpose. Everything in there is something
     * the user chose and can see themselves choosing; this is a few dozen
     * doubles that change on their own every time a set runs. Threading it
     * through the same immutable value would mean a settings write on every
     * transition, and a `requiresEngineRebuild` comparison against a field that
     * has nothing to do with the engine.
     */
    fun loadTaste(): String? = store.getString(KEY_TASTE)

    fun saveTaste(encoded: String) = store.putString(KEY_TASTE, encoded)

    fun save(settings: EngineSettings) {
        store.putString(KEY_SAMPLE_RATE, settings.sampleRate.name)
        store.putString(KEY_VISUAL_REFRESH, settings.visualRefresh.name)
        store.putString(KEY_MEMORY_BUDGET, settings.memoryBudget.name)
        store.putString(KEY_LIGHT_SHOW, settings.lightShow.toString())
        store.putString(KEY_IDLE_SHUTDOWN, settings.idleShutdown.toString())
        store.putString(KEY_LOCAL_COPIES, settings.localCopies.name)
    }

    companion object {
        const val PREFERENCES_NAME = "engine_settings"

        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_VISUAL_REFRESH = "visual_refresh"
        private const val KEY_MEMORY_BUDGET = "memory_budget"
        private const val KEY_LIGHT_SHOW = "light_show"
        private const val KEY_IDLE_SHUTDOWN = "idle_shutdown"
        private const val KEY_LOCAL_COPIES = "local_copies"
        private const val KEY_TASTE = "transition_taste"

        /**
         * The raw store behind [forContext].
         *
         * Exposed because the sync package keeps its own things in it — a
         * long-term identity key and the list of paired devices — and neither
         * belongs in [EngineSettings], which is entirely things a user chose and
         * can see themselves choosing.
         */
        fun keyValueFor(context: android.content.Context): KeyValueStore {
            val preferences = context.getSharedPreferences(
                PREFERENCES_NAME,
                android.content.Context.MODE_PRIVATE,
            )
            return object : KeyValueStore {
                override fun getString(key: String): String? = preferences.getString(key, null)

                override fun putString(key: String, value: String) {
                    preferences.edit().putString(key, value).apply()
                }
            }
        }

        /** A store backed by the app's own preferences file. */
        fun forContext(context: android.content.Context): SettingsStore {
            val preferences = context.getSharedPreferences(
                PREFERENCES_NAME,
                android.content.Context.MODE_PRIVATE,
            )
            return SettingsStore(
                object : KeyValueStore {
                    override fun getString(key: String): String? =
                        preferences.getString(key, null)

                    override fun putString(key: String, value: String) {
                        preferences.edit().putString(key, value).apply()
                    }
                },
            )
        }
    }
}
