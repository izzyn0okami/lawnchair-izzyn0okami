package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import java.io.File

data class LocalGame(
    val title: String,
    val platform: GamePlatform,
    val path: String,
)

/** Pasta de jogos configurável (mesma ideia do Argosy): padrão é a pasta de downloads do app. */
object GameLibrarySettings {

    private const val FILE = "game_mode"
    private const val KEY_ROOT = "games_root_path"

    fun defaultRoot(context: Context): File = File(context.getExternalFilesDir(null), "games")

    fun rootPath(context: Context): String = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ROOT, null)
        ?: defaultRoot(context).absolutePath

    fun setRootPath(context: Context, path: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY_ROOT, path) }
    }

    fun isInsideApp(context: Context, path: String): Boolean {
        val appRoot = context.getExternalFilesDir(null)?.absolutePath ?: return false
        return path == appRoot || path.startsWith("$appRoot/")
    }
}

/** Varre a pasta de jogos e cria um jogo local para cada ROM reconhecida, baixada ou colocada à mão. */
object GameLibraryScanner {

    private const val MAX_DEPTH = 6
    private val tags = Regex("""\s*[(\[][^)\]]*[)\]]""")

    fun scan(root: File): List<LocalGame> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") }
            .mapNotNull { file -> toGame(root, file) }
            .toList()
            .let(::dropDiscCompanions)
            .sortedWith(compareBy({ it.platform.sortOrder }, { it.title.lowercase() }))
    }

    /** Pasta de jogo em disco com uma lista .m3u: só a lista aparece (os .cue/.chd são os discos dela). */
    private fun dropDiscCompanions(games: List<LocalGame>): List<LocalGame> {
        val withPlaylist = games
            .filter { it.platform.discBased && it.path.endsWith(".m3u", ignoreCase = true) }
            .map { File(it.path).parent }
            .toSet()
        if (withPlaylist.isEmpty()) return games
        return games.filterNot { game ->
            game.platform.discBased && !game.path.endsWith(".m3u", ignoreCase = true) && File(game.path).parent in withPlaylist
        }
    }

    private fun toGame(root: File, file: File): LocalGame? {
        val folders = generateSequence(file.parentFile) { it.parentFile }
            .takeWhile { it != root.parentFile }
            .map { it.name }
            .toList()
        val platform = GamePlatforms.resolve(folders, file.extension) ?: return null
        return LocalGame(title = cleanTitle(file.nameWithoutExtension), platform = platform, path = file.absolutePath)
    }

    private fun cleanTitle(fileName: String): String {
        val cleaned = fileName.replace('_', ' ').replace(tags, "").trim()
        return cleaned.ifEmpty { fileName }
    }
}
