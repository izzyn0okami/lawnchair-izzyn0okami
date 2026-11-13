package app.lawnchair.gamemode

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Abre uma ROM avulsa escolhida no seletor de arquivos (fora da pasta de jogos). */
object RomLauncher {

    fun coreFor(fileName: String): String? = GamePlatforms.resolve(emptyList(), fileName.substringAfterLast('.', ""))?.coreId

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
