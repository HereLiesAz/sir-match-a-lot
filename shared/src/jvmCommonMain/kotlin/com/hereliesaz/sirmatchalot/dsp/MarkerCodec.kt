package com.hereliesaz.sirmatchalot.dsp

/**
 * Storing a track's structural landmarks as one text column.
 *
 * Drops, builds, breakdowns and vocal entries are measured during analysis but
 * were never kept: every load recomputed them from a decode, so the planner could
 * only know where the drops were for a track that was already on a deck. Stored
 * as `kind:seconds:strength` records separated by `;` — no new table for a list
 * that is read whole and written whole, and readable in a database browser.
 *
 * Decoding is forgiving: a malformed record is skipped rather than failing the
 * track, since landmarks are enrichment and a bad one must cost that landmark,
 * not the row.
 */
object MarkerCodec {

    /** Null for an empty list, so "none found" and "never measured" stay distinct only by the column being null. */
    fun encode(points: List<PointOfInterest>): String? =
        points.takeIf { it.isNotEmpty() }
            ?.joinToString(";") { "${it.kind.name}:${it.timeSeconds}:${it.strength}" }

    fun decode(csv: String?): List<PointOfInterest> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(';').mapNotNull { record ->
            val parts = record.split(':')
            if (parts.size != 3) return@mapNotNull null
            val kind = PointOfInterest.Kind.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            val time = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return@mapNotNull null
            val strength = parts[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            PointOfInterest(time, kind, strength)
        }
    }
}
