package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sincroniza saves de bateria e o estado rápido com o RomM, só para jogos baixados do servidor.
 *
 * Regra: antes de jogar, baixa o do servidor se ele mudou e o local não; ao sair, envia o local
 * se mudou. Se os dois mudaram, vence o mais recente e o outro vai para uma pasta de backup.
 * Falhas de rede nunca atrapalham o jogo.
 */
object SaveSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private fun prefs(context: Context) = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)

    /** Liga uma ROM baixada ao jogo correspondente no servidor. */
    fun link(context: Context, romPath: String, romId: Long) {
        prefs(context).edit { putLong("rom:$romPath", romId) }
    }

    private fun romId(context: Context, romPath: String): Long? = prefs(context).getLong("rom:$romPath", -1L).takeIf { it >= 0 }

    private fun client(context: Context): RomMClient? {
        val url = RomMSettings.url(context) ?: return null
        return RomMClient(url, RomMSettings.token(context))
    }

    private fun emulatorTag(coreId: String) = "tandem_$coreId"

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-1").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun files(store: SaveStore) = listOf(
        RemoteFileKind.Save to store.sramFile,
        RemoteFileKind.State to store.stateFile,
    )

    /** Antes de jogar: traz do servidor o que for mais novo. Bloqueante. */
    fun pull(context: Context, romPath: String, coreId: String) {
        val romId = romId(context, romPath) ?: return
        val client = client(context) ?: return
        val store = SaveStore(context, coreId, File(romPath))
        runCatching {
            for ((kind, file) in files(store)) {
                val remote = client.listRemoteFiles(kind, romId)
                    .filter { it.emulator == emulatorTag(coreId) }
                    .maxByOrNull { it.updatedAtMs } ?: continue
                pullOne(context, client, store, kind, file, remote, romPath)
            }
        }.onFailure { GameLog.error("saves", "falha ao buscar saves de ${File(romPath).name}", it) }
    }

    private fun pullOne(context: Context, client: RomMClient, store: SaveStore, kind: RemoteFileKind, file: File, remote: RemoteFile, romPath: String) {
        val prefs = prefs(context)
        val key = "${kind.path}:$romPath"
        val lastHash = prefs.getString("$key:hash", null)
        val lastRemoteMs = prefs.getLong("$key:remote", 0L)
        val local = file.takeIf { it.isFile && it.length() > 0 }
        val remoteChanged = remote.updatedAtMs != lastRemoteMs
        if (!remoteChanged) return
        if (local != null) {
            val localChanged = hash(local) != lastHash
            if (localChanged && local.lastModified() >= remote.updatedAtMs) {
                // Os dois mudaram e o local é mais novo: o local vence e será enviado ao sair.
                prefs.edit {
                    putLong("$key:remote", remote.updatedAtMs)
                    putLong("$key:id", remote.id)
                }
                return
            }
            if (localChanged) backup(store, file)
        }
        val temp = File(file.parentFile, "${file.name}.remote")
        client.downloadRemoteFile(kind, remote.id, temp)
        if (temp.length() == 0L) {
            temp.delete()
            return
        }
        file.delete()
        check(temp.renameTo(file))
        prefs.edit {
            putString("$key:hash", hash(file))
            putLong("$key:remote", remote.updatedAtMs)
            putLong("$key:id", remote.id)
        }
    }

    private fun backup(store: SaveStore, file: File) {
        store.backupDir.mkdirs()
        file.copyTo(File(store.backupDir, "${System.currentTimeMillis()}-${file.name}"), overwrite = true)
    }

    /** Ao sair do jogo: envia em segundo plano o que mudou. */
    fun pushAsync(context: Context, romPath: String, coreId: String) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                runCatching { push(app, romPath, coreId) }
                    .onFailure { GameLog.error("saves", "falha ao enviar saves de ${File(romPath).name}", it) }
            }
        }
    }

    private fun push(context: Context, romPath: String, coreId: String) {
        val romId = romId(context, romPath) ?: return
        val client = client(context) ?: return
        val store = SaveStore(context, coreId, File(romPath))
        val prefs = prefs(context)
        for ((kind, file) in files(store)) {
            if (!file.isFile || file.length() == 0L) continue
            val key = "${kind.path}:$romPath"
            val current = hash(file)
            if (current == prefs.getString("$key:hash", null)) continue
            val existing = prefs.getLong("$key:id", -1L).takeIf { it >= 0 }
            val extension = if (kind == RemoteFileKind.Save) "srm" else "state"
            val uploaded = runCatching {
                client.uploadRemoteFile(kind, romId, emulatorTag(coreId), existing, file, "${File(romPath).nameWithoutExtension}.$extension")
            }.getOrElse {
                // O registro antigo pode ter sumido do servidor: tenta criar um novo.
                if (existing == null) throw it
                client.uploadRemoteFile(kind, romId, emulatorTag(coreId), null, file, "${File(romPath).nameWithoutExtension}.$extension")
            }
            prefs.edit {
                putString("$key:hash", current)
                putLong("$key:remote", uploaded.updatedAtMs)
                putLong("$key:id", uploaded.id)
            }
        }
    }
}
