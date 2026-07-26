package com.hereliesaz.sirmatchalot.sync

/**
 * What a device in a room is for.
 *
 * The request was *"link multiple devices together, each showing a different
 * screen, all acting as one instrument"*. `SyncClient.joinRoom` has always sent
 * a `role` string with the join message, and nothing has ever read it — every
 * device joined as "all" and showed the same three tabs. A role that no one acts
 * on is not a role, it is a field.
 *
 * This is the mapping from that string to the screen the device should present.
 * Kept as a pure enum in `sync` rather than in the UI package because it is part
 * of the wire protocol: a role is something one device tells another, so its
 * spelling is an API commitment, not a UI detail. See `docs/API.md`.
 */
enum class SyncRole(
    /** The value carried in a `join` message's `role` field. */
    val wireName: String,
    /** What to call it on screen. */
    val label: String,
) {
    /** Everything, on one device — the default, and what a solo device is. */
    ALL("all", "Full control"),

    /** Browsing and loading: the library, and nothing that can be mis-tapped mid-mix. */
    LIBRARY("library", "Library"),

    /** The platter: decks, crossfader, filters. The instrument proper. */
    DECKS("decks", "Decks"),

    /** Pads and one-shots. A second person playing along. */
    PADS("pads", "Pads"),
    ;

    /** True when this role should be able to reach every screen. */
    val isFullControl: Boolean get() = this == ALL

    companion object {
        /**
         * The role named by [value], or [ALL] when it is unknown.
         *
         * Unknown falls back to full control rather than to nothing: a device
         * joining a room run by a newer version, naming a role this build has
         * never heard of, is better off with the whole instrument than with a
         * blank screen. Matching is case-insensitive and tolerates the older
         * spellings — `browser` and `platter` were used in earlier prompts.
         */
        fun fromWire(value: String?): SyncRole = when (value?.trim()?.lowercase()) {
            null, "", "all", "full" -> ALL
            "library", "browser", "browse" -> LIBRARY
            "decks", "deck", "platter", "mixer" -> DECKS
            "pads", "pad", "sampler", "performance" -> PADS
            else -> ALL
        }
    }
}
