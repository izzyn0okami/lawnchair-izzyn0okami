package app.lawnchair.gamemode

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro local do modo jogo (RomM, saves, erros), para o usuário exportar e enviar quando algo
 * não funciona. Nunca grava senhas nem tokens.
 */
object GameLog {

    private const val FILE = "game_log.txt"
    private const val MAX_BYTES = 256 * 1024

    @Volatile
    private var dir: File? = null

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        dir = context.applicationContext.filesDir
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val file = dir?.let { File(it, FILE) } ?: return
        runCatching {
            if (file.length() > MAX_BYTES) {
                file.writeText(file.readText().takeLast(MAX_BYTES / 2))
            }
            file.appendText("${stamp.format(Date())} [$tag] $message\n")
        }
    }

    fun error(tag: String, message: String, error: Throwable) {
        log(tag, "$message: ${error.javaClass.simpleName}: ${error.message}")
    }

    /** Texto completo para exportar: cabeçalho do aparelho + registro. */
    @Synchronized
    fun export(context: Context): String {
        val file = File(context.applicationContext.filesDir, FILE)
        val header = buildString {
            appendLine("Tandem – registro do modo jogo")
            appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Servidor RomM: ${RomMSettings.url(context) ?: "não conectado"}")
            appendLine("Rede local permitida: ${localNetworkGranted(context)}")
            appendLine("----")
        }
        return header + (runCatching { file.readText() }.getOrDefault("(registro vazio)\n"))
    }
}

/** Devolve uma ação que abre o seletor "salvar como" e grava o registro no arquivo escolhido. */
@Composable
internal fun rememberLogExporter(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(GameLog.export(context).toByteArray()) }
        }
    }
    return remember(launcher) { { launcher.launch("tandem-log.txt") } }
}
