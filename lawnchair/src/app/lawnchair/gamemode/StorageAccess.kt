package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings

/** Acesso a "todos os arquivos", que o Android exige para ler e gravar numa pasta de jogos livre. */
object StorageAccess {

    fun isGranted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun settingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    /** Converte a pasta escolhida no seletor do sistema em caminho real (interno ou cartão SD). */
    fun treeUriToPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val base = when (volume) {
            "primary" -> Environment.getExternalStorageDirectory().absolutePath
            "home", "" -> return null
            else -> "/storage/$volume"
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }
}
