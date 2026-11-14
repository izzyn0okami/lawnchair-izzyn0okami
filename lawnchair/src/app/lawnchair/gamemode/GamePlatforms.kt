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
    /** Jogos em disco (cue/chd/iso...): podem vir como pasta com vários arquivos. */
    val discBased: Boolean = false,
    /** Arquivos de BIOS que o núcleo usa (basta um deles). */
    val bios: List<BiosFile> = emptyList(),
    /** true = sem BIOS o jogo não roda; false = só melhora a compatibilidade. */
    val biosRequired: Boolean = false,
    /** Dentro do Tandem (núcleo libretro) ou em outro app. */
    val launch: LaunchMode = LaunchMode.LIBRETRO,
)

/** Um arquivo de BIOS esperado na pasta de sistema do núcleo. */
data class BiosFile(val name: String, val label: String)

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
        GamePlatform(
            slug = "gba",
            name = "Game Boy Advance",
            coreId = "mgba",
            extensions = setOf("gba"),
            folderAliases = setOf("gba", "gameboyadvance"),
            sortOrder = 32,
            raConsoleId = 5,
            bios = listOf(BiosFile("gba_bios.bin", "Game Boy Advance (opcional)")),
        ),
        GamePlatform("genesis", "Mega Drive / Genesis", "genesis_plus_gx", setOf("md", "gen", "smd"), setOf("genesis", "megadrive", "md", "segagenesis", "segamegadrive"), 40, 1),
        GamePlatform("sms", "Master System", "genesis_plus_gx", setOf("sms"), setOf("sms", "mastersystem", "segamastersystem"), 41, 11),
        GamePlatform("gg", "Game Gear", "genesis_plus_gx", setOf("gg"), setOf("gg", "gamegear", "segagamegear"), 42, 15),
        GamePlatform("32x", "Sega 32X", "picodrive", setOf("32x"), setOf("32x", "sega32x"), 43),
        GamePlatform(
            slug = "scd",
            name = "Sega CD",
            coreId = "genesis_plus_gx",
            extensions = setOf("cue", "chd", "iso", "m3u"),
            folderAliases = setOf("scd", "segacd", "megacd", "segamegacd"),
            sortOrder = 44,
            discBased = true,
            bios = listOf(
                BiosFile("bios_CD_U.bin", "Sega CD (EUA)"),
                BiosFile("bios_CD_E.bin", "Mega CD (Europa)"),
                BiosFile("bios_CD_J.bin", "Mega CD (Japão)"),
            ),
            biosRequired = true,
        ),
        GamePlatform("n64", "Nintendo 64", "mupen64plus_next_gles3", setOf("z64", "n64", "v64"), setOf("n64", "nintendo64"), 50),
        GamePlatform(
            slug = "psx",
            name = "PlayStation",
            coreId = "pcsx_rearmed",
            extensions = setOf("cue", "chd", "pbp", "iso", "m3u"),
            folderAliases = setOf("psx", "ps1", "playstation", "playstation1", "sonyplaystation"),
            sortOrder = 60,
            discBased = true,
            bios = listOf(
                BiosFile("scph5501.bin", "PlayStation (EUA) v3.0"),
                BiosFile("scph5500.bin", "PlayStation (Japão)"),
                BiosFile("scph5502.bin", "PlayStation (Europa)"),
                BiosFile("scph1001.bin", "PlayStation (EUA) v2.0"),
            ),
        ),
        GamePlatform("pce", "PC Engine / TurboGrafx-16", "mednafen_pce_fast", setOf("pce"), setOf("pce", "tg16", "pcengine", "turbografx16", "turbografx"), 70, 8),
        GamePlatform("atari2600", "Atari 2600", "stella", setOf("a26"), setOf("atari2600", "2600", "a26"), 80, 25),
        GamePlatform("ngp", "Neo Geo Pocket", "mednafen_ngp", setOf("ngp", "ngc"), setOf("ngp", "ngpc", "neogeopocket", "neogeopocketcolor"), 90, 14),
        GamePlatform("wswan", "WonderSwan", "mednafen_wswan", setOf("ws", "wsc"), setOf("wonderswan", "wonderswancolor", "wswan", "ws", "wsc"), 91, 53),
        GamePlatform("sg1000", "Sega SG-1000", "genesis_plus_gx", setOf("sg"), setOf("sg1000", "segasg1000"), 47, 33),
        GamePlatform(
            slug = "saturn",
            name = "Sega Saturn",
            coreId = "mednafen_saturn",
            extensions = setOf("cue", "chd", "iso", "m3u", "ccd"),
            folderAliases = setOf("saturn", "segasaturn"),
            sortOrder = 45,
            discBased = true,
            bios = listOf(
                BiosFile("sega_101.bin", "Saturn (Japão)"),
                BiosFile("mpr-17933.bin", "Saturn (EUA/Europa)"),
            ),
            biosRequired = true,
        ),
        GamePlatform(
            slug = "dreamcast",
            name = "Dreamcast",
            coreId = "flycast",
            extensions = setOf("gdi", "cdi", "chd", "cue", "m3u"),
            folderAliases = setOf("dreamcast", "dc", "segadreamcast"),
            sortOrder = 46,
            discBased = true,
            bios = listOf(
                BiosFile("dc/dc_boot.bin", "Dreamcast (boot ROM)"),
                BiosFile("dc/dc_flash.bin", "Dreamcast (flash)"),
            ),
        ),
        GamePlatform(
            slug = "nds",
            name = "Nintendo DS",
            coreId = "melonds",
            extensions = setOf("nds", "dsi"),
            folderAliases = setOf("nds", "ds", "nintendods", "dsi"),
            sortOrder = 33,
            bios = listOf(
                BiosFile("bios7.bin", "DS ARM7 (opcional)"),
                BiosFile("bios9.bin", "DS ARM9 (opcional)"),
                BiosFile("firmware.bin", "DS firmware (opcional)"),
            ),
        ),
        GamePlatform(
            slug = "psp",
            name = "PlayStation Portable",
            coreId = "ppsspp",
            extensions = setOf("iso", "cso", "pbp"),
            folderAliases = setOf("psp", "playstationportable", "sonypsp"),
            sortOrder = 61,
            discBased = true,
        ),
        GamePlatform(
            slug = "3do",
            name = "3DO",
            coreId = "opera",
            extensions = setOf("iso", "chd", "cue"),
            folderAliases = setOf("3do", "panasonic3do"),
            sortOrder = 62,
            discBased = true,
            bios = listOf(BiosFile("panafz10.bin", "3DO FZ-10")),
            biosRequired = true,
        ),
        GamePlatform("vb", "Virtual Boy", "mednafen_vb", setOf("vb", "vboy"), setOf("vb", "virtualboy", "vboy"), 92, 28),
        GamePlatform(
            slug = "atari7800",
            name = "Atari 7800",
            coreId = "prosystem",
            extensions = setOf("a78"),
            folderAliases = setOf("atari7800", "7800", "a78"),
            sortOrder = 81,
            raConsoleId = 51,
            bios = listOf(BiosFile("7800 BIOS (U).rom", "Atari 7800 (EUA, opcional)")),
        ),
        GamePlatform(
            slug = "atari5200",
            name = "Atari 5200",
            coreId = "a5200",
            extensions = setOf("a52"),
            folderAliases = setOf("atari5200", "5200", "a52"),
            sortOrder = 82,
            bios = listOf(BiosFile("5200.rom", "Atari 5200")),
            biosRequired = true,
        ),
        GamePlatform(
            slug = "lynx",
            name = "Atari Lynx",
            coreId = "handy",
            extensions = setOf("lnx"),
            folderAliases = setOf("lynx", "atarilynx", "lnx"),
            sortOrder = 83,
            raConsoleId = 13,
            bios = listOf(BiosFile("lynxboot.img", "Atari Lynx (opcional)")),
        ),
        GamePlatform("jaguar", "Atari Jaguar", "virtualjaguar", setOf("j64", "jag"), setOf("jaguar", "atarijaguar", "j64"), 84),
        GamePlatform(
            slug = "ps2",
            name = "PlayStation 2",
            coreId = "",
            extensions = setOf("iso", "chd", "cso", "zso", "gz"),
            folderAliases = setOf("ps2", "playstation2", "sonyplaystation2"),
            sortOrder = 100,
            launch = LaunchMode.EMULATOR,
        ),
        GamePlatform(
            slug = "gc",
            name = "GameCube",
            coreId = "",
            extensions = setOf("gcm", "iso", "gcz", "rvz", "ciso", "tgc"),
            folderAliases = setOf("gc", "ngc", "gamecube", "nintendogamecube"),
            sortOrder = 101,
            launch = LaunchMode.EMULATOR,
        ),
        GamePlatform(
            slug = "wii",
            name = "Wii",
            coreId = "",
            extensions = setOf("wbfs", "iso", "rvz", "wia", "wad"),
            folderAliases = setOf("wii", "nintendowii"),
            sortOrder = 102,
            launch = LaunchMode.EMULATOR,
        ),
        GamePlatform(
            slug = "switch",
            name = "Nintendo Switch",
            coreId = "",
            extensions = setOf("nsp", "xci", "nsz", "xcz"),
            folderAliases = setOf("switch", "nintendoswitch", "ns"),
            sortOrder = 103,
            launch = LaunchMode.EMULATOR,
        ),
        GamePlatform(
            slug = "3ds",
            name = "Nintendo 3DS",
            coreId = "",
            extensions = setOf("3ds", "cci", "cxi", "cia", "3dsx"),
            folderAliases = setOf("3ds", "n3ds", "nintendo3ds"),
            sortOrder = 104,
            launch = LaunchMode.EMULATOR,
        ),
        GamePlatform(
            slug = "wiiu",
            name = "Wii U",
            coreId = "",
            extensions = setOf("wua", "wud", "wux", "rpx"),
            folderAliases = setOf("wiiu", "nintendowiiu"),
            sortOrder = 105,
            launch = LaunchMode.EMULATOR,
        ),
        GamePlatform(
            slug = "pc",
            name = "PC (GameNative)",
            coreId = "",
            extensions = setOf("steam", "gog", "epic", "amazon"),
            folderAliases = setOf("pc", "windows", "gamenative", "steam", "pcgames"),
            sortOrder = 110,
            launch = LaunchMode.GAMENATIVE,
        ),
        GamePlatform(
            slug = "coleco",
            name = "ColecoVision",
            coreId = "gearcoleco",
            extensions = setOf("col"),
            folderAliases = setOf("coleco", "colecovision", "col"),
            sortOrder = 85,
            raConsoleId = 44,
            bios = listOf(BiosFile("colecovision.rom", "ColecoVision")),
            biosRequired = true,
        ),
    )

    /** Extensões genéricas: só valem quando a pasta já diz a plataforma. */
    val genericExtensions = setOf("zip", "bin")

    private fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /** Plataforma do nosso motor equivalente ao identificador de plataforma do RomM. */
    fun fromRomM(slug: String?): GamePlatform? {
        val key = normalize(slug.orEmpty())
        if (key.isEmpty()) return null
        return all.firstOrNull { key in it.folderAliases || key == normalize(it.slug) }
            ?: fuzzy(key)
    }

    /** Nomes longos que o RomM pode usar (ex.: "super-nintendo-entertainment-system"). */
    private fun fuzzy(key: String): GamePlatform? = when {
        "megadrive" in key || "genesis" in key -> bySlug("genesis")
        "gameboyadvance" in key -> bySlug("gba")
        "gameboycolor" in key -> bySlug("gbc")
        "gameboy" in key -> bySlug("gb")
        "supernintendo" in key || "superfamicom" in key || "snes" in key -> bySlug("snes")
        "nintendoentertainment" in key || "famicom" in key -> bySlug("nes")
        else -> null
    }

    fun bySlug(slug: String): GamePlatform? = all.firstOrNull { it.slug == slug }

    /** [folders] são as pastas do arquivo, da mais próxima para a mais distante da raiz. */
    fun resolve(folders: List<String>, extension: String): GamePlatform? {
        val ext = extension.lowercase()
        for (folder in folders) {
            val key = normalize(folder)
            val byFolder = all.firstOrNull { key in it.folderAliases }
            if (byFolder != null && (ext in byFolder.extensions || (!byFolder.discBased && ext in genericExtensions))) return byFolder
        }
        return all.singleOrNull { ext in it.extensions }
    }
}
