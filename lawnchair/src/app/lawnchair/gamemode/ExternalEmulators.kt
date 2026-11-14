package app.lawnchair.gamemode

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.android.launcher3.R
import java.io.File

/** Como o jogo de uma plataforma é aberto. */
enum class LaunchMode {
    /** Dentro do Tandem, com um núcleo libretro. */
    LIBRETRO,

    /** Em outro app emulador instalado no aparelho. */
    EMULATOR,

    /** No GameNative, a partir dos arquivos que ele exporta ("Exportar para o frontend"). */
    GAMENATIVE,
}

/** Provedor de arquivos do Tandem: entrega a ROM a outro app por um endereço content:// com permissão de leitura. */
class RomFileProvider : FileProvider()

/** O que vai em cada extra da intent do emulador. */
enum class ExtraKind { PATH, FILE_URI_STRING, CONTENT_URI_STRING }

data class ExternalEmulator(
    val id: String,
    val name: String,
    val packageName: String,
    val platforms: Set<String>,
    val activity: String? = null,
    val action: String = Intent.ACTION_VIEW,
    val extras: Map<String, ExtraKind> = emptyMap(),
)

/** Emuladores externos conhecidos e como abrir um jogo em cada um (mesmos formatos que o Argosy usa). */
object ExternalEmulators {

    private const val YUZU_ACTIVITY = "org.yuzu.yuzu_emu.activities.EmulationActivity"
    private const val CITRA_ACTIVITY = "org.citra.citra_emu.activities.EmulationActivity"
    private const val DOLPHIN_ACTIVITY = "org.dolphinemu.dolphinemu.ui.main.MainActivity"
    private const val GAMENATIVE_PACKAGE = "app.gamenative"
    private const val GAMENATIVE_ACTION = "app.gamenative.LAUNCH_GAME"

    val all = listOf(
        ExternalEmulator(
            "dolphin",
            "Dolphin",
            "org.dolphinemu.dolphinemu",
            setOf("gc", "wii"),
            DOLPHIN_ACTIVITY,
            Intent.ACTION_MAIN,
            mapOf("AutoStartFile" to ExtraKind.CONTENT_URI_STRING),
        ),
        ExternalEmulator(
            "dolphin_handheld",
            "Dolphin (handheld)",
            "org.dolphinemu.handheld",
            setOf("gc", "wii"),
            DOLPHIN_ACTIVITY,
            Intent.ACTION_MAIN,
            mapOf("AutoStartFile" to ExtraKind.CONTENT_URI_STRING),
        ),
        ExternalEmulator(
            "nethersx2",
            "NetherSX2 / AetherSX2",
            "xyz.aethersx2.android",
            setOf("ps2"),
            "xyz.aethersx2.android.EmulationActivity",
            Intent.ACTION_MAIN,
            mapOf("bootPath" to ExtraKind.FILE_URI_STRING),
        ),
        ExternalEmulator("armsx2", "ARMSX2", "com.armsx2", setOf("ps2"), "com.armsx2.MainActivity"),
        ExternalEmulator("pcsx2", "PCSX2", "net.pcsx2.emulator", setOf("ps2")),
        ExternalEmulator("psx2", "PSX2", "com.izzy2lost.psx2", setOf("ps2")),
        ExternalEmulator(
            "azahar",
            "Azahar",
            "org.azahar_emu.azahar",
            setOf("3ds"),
            CITRA_ACTIVITY,
            Intent.ACTION_VIEW,
            mapOf("SelectedGame" to ExtraKind.PATH),
        ),
        ExternalEmulator(
            "citra",
            "Citra",
            "org.citra.citra_emu",
            setOf("3ds"),
            CITRA_ACTIVITY,
            Intent.ACTION_VIEW,
            mapOf("SelectedGame" to ExtraKind.PATH),
        ),
        ExternalEmulator("eden", "Eden", "dev.eden.eden_emulator", setOf("switch"), YUZU_ACTIVITY),
        ExternalEmulator(
            "eden_duo",
            "Eden Duo",
            "dev.igawa6.edenduo",
            setOf("switch"),
            YUZU_ACTIVITY,
        ),
        ExternalEmulator("yuzu", "Yuzu", "org.yuzu.yuzu_emu", setOf("switch"), YUZU_ACTIVITY),
        ExternalEmulator(
            "citron",
            "Citron",
            "org.citron.emu",
            setOf("switch"),
            "org.citron.citron_emu.activities.EmulationActivity",
        ),
        ExternalEmulator(
            "sudachi",
            "Sudachi",
            "org.sudachi.sudachi_emu",
            setOf("switch"),
            "org.sudachi.sudachi_emu.activities.EmulationActivity",
        ),
        ExternalEmulator(
            "lemon",
            "Lemon",
            "dev.lemon.lemon_emulator",
            setOf("switch"),
            "dev.lemon.lemon_emu.activities.EmulationActivity",
        ),
        ExternalEmulator("ryujinx", "Ryujinx", "org.ryujinx.android", setOf("switch")),
        ExternalEmulator(
            "cemu",
            "Cemu",
            "info.cemu.cemu",
            setOf("wiiu"),
            "info.cemu.cemu.emulation.EmulationActivity",
        ),
    )

    fun forPlatform(slug: String): List<ExternalEmulator> = all.filter { slug in it.platforms }

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    fun gameNativeInstalled(context: Context): Boolean = isInstalled(context, GAMENATIVE_PACKAGE)

    private fun prefs(context: Context) = context.getSharedPreferences("game_mode", Context.MODE_PRIVATE)

    fun preferred(context: Context, platformSlug: String): String? = prefs(context).getString("emulator_$platformSlug", null)

    fun setPreferred(context: Context, platformSlug: String, emulatorId: String) {
        prefs(context).edit { putString("emulator_$platformSlug", emulatorId) }
    }

    /** O emulador que vai abrir o jogo: o escolhido, se estiver instalado; senão o primeiro instalado. */
    fun chosen(context: Context, platformSlug: String): ExternalEmulator? {
        val installed = forPlatform(platformSlug).filter { isInstalled(context, it.packageName) }
        return installed.firstOrNull { it.id == preferred(context, platformSlug) } ?: installed.firstOrNull()
    }

    /** Abre o jogo no emulador externo ou no GameNative. Lança [IllegalStateException] com um texto para o usuário. */
    fun launch(context: Context, game: LocalGame) {
        when (game.platform.launch) {
            LaunchMode.GAMENATIVE -> launchGameNative(context, game)
            else -> launchEmulator(context, game)
        }
    }

    private fun launchEmulator(context: Context, game: LocalGame) {
        val emulator = chosen(context, game.platform.slug) ?: error(
            context.getString(
                R.string.game_external_missing,
                game.platform.name,
                forPlatform(game.platform.slug).joinToString { it.name },
            ),
        )
        val file = File(game.path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.gamemode.roms", file)
        context.grantUriPermission(emulator.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val intent = Intent(emulator.action).apply {
            setPackage(emulator.packageName)
            emulator.activity?.let { setClassName(emulator.packageName, it) }
            if (emulator.action == Intent.ACTION_VIEW) setDataAndType(uri, "*/*")
            emulator.extras.forEach { (key, kind) ->
                putExtra(
                    key,
                    when (kind) {
                        ExtraKind.PATH -> file.absolutePath
                        ExtraKind.FILE_URI_STRING -> Uri.fromFile(file).toString()
                        ExtraKind.CONTENT_URI_STRING -> uri.toString()
                    },
                )
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        GameLog.log("externo", "abrindo ${file.name} em ${emulator.name} (${emulator.packageName})")
        start(context, intent, emulator.name)
    }

    private fun launchGameNative(context: Context, game: LocalGame) {
        check(gameNativeInstalled(context)) { context.getString(R.string.game_gamenative_missing) }
        val file = File(game.path)
        val appId = file.readText().trim().toIntOrNull()
            ?: error(context.getString(R.string.game_gamenative_bad_file, file.name))
        val intent = Intent(GAMENATIVE_ACTION).apply {
            setPackage(GAMENATIVE_PACKAGE)
            putExtra("app_id", appId)
            putExtra("game_source", file.extension.uppercase())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        GameLog.log("externo", "abrindo ${file.nameWithoutExtension} (id $appId) no GameNative")
        start(context, intent, "GameNative")
    }

    private fun start(context: Context, intent: Intent, appName: String) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            GameLog.error("externo", "$appName não aceitou a intent", e)
            error(context.getString(R.string.game_external_failed, appName))
        } catch (e: SecurityException) {
            GameLog.error("externo", "$appName recusou a intent", e)
            error(context.getString(R.string.game_external_failed, appName))
        }
    }
}
