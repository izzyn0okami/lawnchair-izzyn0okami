package app.lawnchair.gamemode

data class GamePlatform(
    val slug: String,
    val name: String,
    val coreId: String,
    val extensions: Set<String>,
    val folderAliases: Set<String>,
    val sortOrder: Int,
    /** Identificador do console no RetroAchievements (null = sem conquistas). */
    val raConsoleId: Int? = null,
)

/**
 * Plataformas que o motor atual (M3) sabe jogar. A decisão segue a lógica do Argosy: o nome da
 * pasta manda quando existe; sem pasta reconhecida, vale a extensão do arquivo (se for única).
 */
object GamePlatforms {

    val all = listOf(
        GamePlatform("nes", "Nintendo Entertainment System", "fceumm", setOf("nes", "unf", "unif", "fds"), setOf("nes", "famicom", "fc"), 10, 7),
        GamePlatform("snes", "Super Nintendo", "snes9x", setOf("sfc", "smc", "swc", "fig"), setOf("snes", "sfc", "superfamicom", "supernintendo"), 20, 3),
        GamePlatform("gb", "Game Boy", "mgba", setOf("gb"), setOf("gb", "gameboy"), 30, 4),
        GamePlatform("gbc", "Game Boy Color", "mgba", setOf("gbc"), setOf("gbc", "gameboycolor"), 31, 6),
        GamePlatform("gba", "Game Boy Advance", "mgba", setOf("gba"), setOf("gba", "gameboyadvance"), 32, 5),
        GamePlatform("genesis", "Mega Drive / Genesis", "genesis_plus_gx", setOf("md", "gen", "smd"), setOf("genesis", "megadrive", "md", "segagenesis", "segamegadrive"), 40, 1),
    )

    /** Extensões genéricas: só valem quando a pasta já diz a plataforma. */
    val genericExtensions = setOf("zip", "bin")

    private fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /** Plataforma do nosso motor equivalente ao identificador de plataforma do RomM. */
    fun fromRomM(slug: String?): GamePlatform? {
        val key = normalize(slug.orEmpty())
        if (key.isEmpty()) return null
        return all.firstOrNull { key in it.folderAliases || key == normalize(it.slug) }
            ?: if ("megadrive" in key || "genesis" in key) bySlug("genesis") else null
    }

    fun bySlug(slug: String): GamePlatform? = all.firstOrNull { it.slug == slug }

    /** [folders] são as pastas do arquivo, da mais próxima para a mais distante da raiz. */
    fun resolve(folders: List<String>, extension: String): GamePlatform? {
        val ext = extension.lowercase()
        for (folder in folders) {
            val key = normalize(folder)
            val byFolder = all.firstOrNull { key in it.folderAliases }
            if (byFolder != null && (ext in byFolder.extensions || ext in genericExtensions)) return byFolder
        }
        return all.singleOrNull { ext in it.extensions }
    }
}
