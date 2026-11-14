package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * BIOS dos consoles: ficam na pasta de sistema dos núcleos, com o nome exato que o núcleo espera.
 * Vêm de três lugares: a pasta "bios" dentro da pasta de jogos, o firmware guardado no RomM ou um
 * arquivo escolhido à mão.
 */
object BiosManager {

    fun systemDir(context: Context): File = File(context.filesDir, "libretro/system").apply { mkdirs() }

    /** Plataformas que usam algum BIOS. */
    fun platformsWithBios(): List<GamePlatform> = GamePlatforms.all.filter { it.bios.isNotEmpty() }

    fun has(context: Context, file: BiosFile): Boolean = File(systemDir(context), file.name).isFile

    /** O jogo pode rodar? (BIOS opcional ou ao menos um dos obrigatórios presente) */
    fun isReady(context: Context, platform: GamePlatform): Boolean = !platform.biosRequired || platform.bios.any { has(context, it) }

    private fun canonical(name: String): BiosFile? {
        val lower = name.lowercase()
        return platformsWithBios().flatMap { it.bios }.firstOrNull { it.name.lowercase() == lower }
    }

    /** Copia o arquivo para a pasta de sistema se o nome for de um BIOS conhecido; devolve o BIOS ou nulo. */
    fun import(context: Context, displayName: String, input: InputStream): BiosFile? {
        val bios = canonical(File(displayName).name) ?: return null
        val target = File(systemDir(context), bios.name)
        val temp = File(target.parentFile, "${target.name}.part")
        temp.outputStream().use { input.copyTo(it) }
        target.delete()
        return if (temp.renameTo(target)) bios else null
    }

    /** Procura BIOS na pasta "bios" da pasta de jogos e copia os que faltam. Devolve quantos copiou. */
    fun importFromFolder(context: Context, gamesRoot: File): Int {
        val dir = File(gamesRoot, "bios")
        if (!dir.isDirectory) return 0
        var count = 0
        dir.walkTopDown().maxDepth(3).filter { it.isFile }.forEach { file ->
            val bios = canonical(file.name) ?: return@forEach
            if (has(context, bios)) return@forEach
            file.inputStream().use { import(context, file.name, it) }?.let { count++ }
        }
        return count
    }

    /** Baixa do RomM os BIOS que faltam. Bloqueante. Devolve quantos baixou. */
    fun downloadFromServer(context: Context, client: RomMClient): Int {
        var count = 0
        for (firmware in client.listFirmware()) {
            val bios = canonical(firmware.fileName) ?: continue
            if (has(context, bios)) continue
            client.downloadFirmware(firmware, File(systemDir(context), bios.name))
            count++
        }
        return count
    }
}
