package com.nikhil.ridetogether.data.model

/**
 * Riders show up on the map as characters rather than identical blue dots.
 *
 * These are emoji glyphs drawn into the marker bitmap at runtime instead of
 * bundled PNGs: no drawable assets, no density buckets, nothing to scale badly,
 * and roughly 200 KB less APK. Every glyph here has been in the Unicode emoji
 * set since well before Android 8, so nothing renders as a blank box on the old
 * phones this app targets.
 */
data class RiderCharacter(
    val id: Int,
    val glyph: String,
    val label: String,
    /** ARGB, used for the marker pill, the route colour and the roster chip. */
    val color: Int
)

object Characters {

    val ALL: List<RiderCharacter> = listOf(
        RiderCharacter(0, "🐯", "Tiger", 0xFFE8590C.toInt()),
        RiderCharacter(1, "🦅", "Eagle", 0xFF1971C2.toInt()),
        RiderCharacter(2, "🦊", "Fox", 0xFFD9480F.toInt()),
        RiderCharacter(3, "🐺", "Wolf", 0xFF495057.toInt()),
        RiderCharacter(4, "🦁", "Lion", 0xFFF08C00.toInt()),
        RiderCharacter(5, "🐻", "Bear", 0xFF845EF7.toInt()),
        RiderCharacter(6, "🐳", "Whale", 0xFF0C8599.toInt()),
        RiderCharacter(7, "🐢", "Turtle", 0xFF2F9E44.toInt())
    )

    /** Total number of distinct characters available. */
    val COUNT: Int = ALL.size

    /** Safe for any int, including negatives and ids from a future version. */
    fun byId(id: Int): RiderCharacter = ALL[((id % COUNT) + COUNT) % COUNT]

    /**
     * Picks the lowest-numbered character nobody in the ride is using yet, so
     * the first two riders are always visually distinct. Falls back to wrapping
     * once the ride is bigger than the character set.
     */
    fun firstFree(taken: Collection<Int>): Int {
        val takenNormalised = taken.map { ((it % COUNT) + COUNT) % COUNT }.toSet()
        return (0 until COUNT).firstOrNull { it !in takenNormalised } ?: (taken.size % COUNT)
    }
}
