package dev.cannoli.scorza.db

// Recently Played only lists games. Apps (everything under Manage Tools and Manage Ports) are
// never recorded, and any timestamps left from older builds are ignored by the queries.
class RecentlyPlayedRepository(private val db: CannoliDatabase) {
    data class Entry(val ref: LibraryRef, val lastPlayedAt: Long)

    fun recent(limit: Int = 10): List<Entry> = db.queryAll(
        """
        SELECT id, last_played_at FROM roms WHERE last_played_at IS NOT NULL
        ORDER BY last_played_at DESC
        LIMIT ?
        """.trimIndent(),
        limit.toLong(),
    ) { stmt -> Entry(LibraryRef.Rom(stmt.getLong(0)), stmt.getLong(1)) }

    fun record(ref: LibraryRef, timestamp: Long = System.currentTimeMillis()) {
        if (ref !is LibraryRef.Rom) return
        db.execute("UPDATE roms SET last_played_at = ? WHERE id = ?", timestamp, ref.id)
    }

    fun clear(ref: LibraryRef) {
        val sql = when (ref) {
            is LibraryRef.Rom -> "UPDATE roms SET last_played_at = NULL WHERE id = ?"
            is LibraryRef.App -> "UPDATE apps SET last_played_at = NULL WHERE id = ?"
        }
        db.execute(sql, ref.id)
    }

    fun hasAny(): Boolean = db.queryOne(
        "SELECT 1 FROM roms WHERE last_played_at IS NOT NULL LIMIT 1",
    ) { true } ?: false
}
