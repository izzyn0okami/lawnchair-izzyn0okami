package app.lawnchair.gamemode

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Núcleo Libretro escolhido para cada extensão de ROM (teste do M2; a biblioteca real vem no M3). */
object RomLauncher {

    private val coreByExtension = mapOf(
        "gba" to "mgba",
        "gb" to "mgba",
        "gbc" to "mgba",
        "sfc" to "snes9x",
        "smc" to "snes9x",
        "nes" to "fceumm",
        "md" to "genesis_plus_gx",
        "gen" to "genesis_plus_gx",
        "smd" to "genesis_plus_gx",
    )

    fun coreFor(fileName: String): String? = coreByExtension[fileName.substringAfterLast('.', "").lowercase()]

    /** Copia a ROM escolhida pelo seletor de arquivos para a pasta do app e devolve o arquivo. */
    suspend fun importRom(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "rom.bin"
        val target = File(File(context.filesDir, "libretro/roms").apply { mkdirs() }, name)
        requireNotNull(context.contentResolver.openInputStream(uri)) { "Não consegui abrir a ROM" }.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target
    }
}
