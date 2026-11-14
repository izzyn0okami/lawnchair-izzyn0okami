package app.lawnchair.gamemode

import android.content.Context
import java.io.File

/**
 * Saves por jogo, dentro do app: `filesDir/libretro/saves/<núcleo>/<jogo>/` com `sram.srm`
 * (save de bateria, gravado sozinho) e `state1.state` (estado rápido, gravado pelo menu).
 */
class SaveStore(context: Context, coreId: String, romFile: File) {

    private val dir = File(
        File(context.filesDir, "libretro/saves/$coreId"),
        romFile.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._ -]"), "_"),
    ).apply { mkdirs() }

    val sramFile = File(dir, "sram.srm")
    val stateFile = File(dir, "state1.state")

    /** Cópias de segurança de saves substituídos durante a sincronização. */
    val backupDir = File(dir, "backups")

    fun readSram(): ByteArray? = sramFile.takeIf { it.isFile && it.length() > 0 }?.readBytes()

    fun writeSram(bytes: ByteArray) = write(sramFile, bytes)

    fun hasState(): Boolean = stateFile.isFile && stateFile.length() > 0

    fun readState(): ByteArray? = stateFile.takeIf { hasState() }?.readBytes()

    fun writeState(bytes: ByteArray) = write(stateFile, bytes)

    /** Grava por arquivo temporário e renomeia, para um corte de energia não estragar o save. */
    private fun write(target: File, bytes: ByteArray) {
        val temp = File(dir, "${target.name}.tmp")
        temp.writeBytes(bytes)
        target.delete()
        check(temp.renameTo(target)) { "Não consegui gravar ${target.name}" }
    }
}
