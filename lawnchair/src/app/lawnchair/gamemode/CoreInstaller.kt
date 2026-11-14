package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Baixa núcleos Libretro do buildbot oficial, no mesmo desenho do Argosy: zip -> arquivo .so em
 * `filesDir/libretro/cores`, conferido como ELF e mantido para sempre (só baixa se faltar).
 */
object CoreInstaller {

    private const val BASE_URL = "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a"

    fun coresDir(context: Context): File = File(context.filesDir, "libretro/cores").apply { mkdirs() }

    fun coreFile(context: Context, coreId: String): File = File(coresDir(context), "${coreId}_libretro_android.so")

    fun isInstalled(context: Context, coreId: String): Boolean {
        val file = coreFile(context, coreId)
        return file.isFile && isElf(file)
    }

    /** Devolve o núcleo já instalado ou baixa. [onProgress] recebe os bytes já gravados. */
    suspend fun ensureInstalled(
        context: Context,
        coreId: String,
        onProgress: (Long) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = coreFile(context, coreId)
            if (target.isFile && isElf(target)) return@runCatching target

            val part = File(target.parentFile, "${target.name}.part")
            val connection = URL("$BASE_URL/${target.name}.zip").openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            try {
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "HTTP ${connection.responseCode} ao baixar ${target.name}"
                }
                ZipInputStream(connection.inputStream).use { zip ->
                    val entry = zip.nextEntry
                    check(entry != null && entry.name == target.name) {
                        "Zip inesperado: esperava ${target.name}, veio ${entry?.name}"
                    }
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(written)
                        }
                    }
                }
                check(isElf(part)) { "${target.name} baixado não é uma biblioteca válida" }
                target.delete()
                check(part.renameTo(target)) { "Não consegui instalar ${target.name}" }
                target.setExecutable(true)
                target
            } finally {
                connection.disconnect()
                part.delete()
            }
        }
    }

    private fun isElf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 &&
                magic[0] == 0x7F.toByte() &&
                magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() &&
                magic[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)
}
