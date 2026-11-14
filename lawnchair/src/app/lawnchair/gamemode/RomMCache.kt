package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cópia local da lista de jogos do servidor: a aba Servidor abre na hora com ela e atualiza em
 * segundo plano.
 */
object RomMCache {

    private const val FILE = "romm_list.json"

    /** Momento (do processo) da última atualização bem-sucedida; evita refazer a lista a cada visita. */
    @Volatile
    var lastRefreshMs: Long = 0L

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context, url: String): List<RemoteGame>? = runCatching<List<RemoteGame>?> {
        val root = JSONObject(file(context).readText())
        if (root.optString("url") != url) return@runCatching null
        val items = root.getJSONArray("games")
        val result = mutableListOf<RemoteGame>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val platform = GamePlatforms.bySlug(o.getString("platform"))
            if (platform != null) {
                result.add(
                    RemoteGame(
                        id = o.getLong("id"),
                        title = o.getString("title"),
                        platform = platform,
                        fileName = o.getString("file"),
                        sizeBytes = o.optLong("size", 0L),
                        coverPath = o.optString("cover").ifEmpty { null },
                        serverName = o.optString("server").ifEmpty { o.getString("file") },
                        fileId = if (o.has("fileId")) o.getLong("fileId") else null,
                        folder = o.optBoolean("folder", false),
                    ),
                )
            }
        }
        result
    }.getOrNull()

    fun save(context: Context, url: String, games: List<RemoteGame>) {
        runCatching {
            val items = JSONArray()
            games.forEach {
                items.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("platform", it.platform.slug)
                        .put("file", it.fileName)
                        .put("size", it.sizeBytes)
                        .put("cover", it.coverPath.orEmpty())
                        .put("server", it.serverName)
                        .put("folder", it.folder)
                        .apply { it.fileId?.let { id -> put("fileId", id) } },
                )
            }
            file(context).writeText(JSONObject().put("url", url).put("games", items).toString())
            lastRefreshMs = System.currentTimeMillis()
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        lastRefreshMs = 0L
    }
}
