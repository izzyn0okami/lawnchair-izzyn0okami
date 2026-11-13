package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit

/** Quando cada jogo foi jogado pela última vez e por quanto tempo, guardado por caminho da ROM. */
object PlayHistory {

    private const val FILE = "game_history"

    data class Entry(val lastPlayedMs: Long, val playtimeMs: Long) {
        val played: Boolean get() = lastPlayedMs > 0
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context, romPath: String): Entry {
        val prefs = prefs(context)
        return Entry(prefs.getLong("last:$romPath", 0L), prefs.getLong("time:$romPath", 0L))
    }

    /** Soma [elapsedMs] ao tempo do jogo e marca [nowMs] como a última vez. */
    fun addSession(context: Context, romPath: String, elapsedMs: Long, nowMs: Long) {
        if (elapsedMs <= 0) return
        val prefs = prefs(context)
        val total = prefs.getLong("time:$romPath", 0L) + elapsedMs
        prefs.edit {
            putLong("time:$romPath", total)
            putLong("last:$romPath", nowMs)
        }
    }

    /** Jogos já jogados, do mais recente para o mais antigo. */
    fun recent(context: Context, games: List<LocalGame>): List<LocalGame> = games
        .map { it to get(context, it.path).lastPlayedMs }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .map { it.first }
}
