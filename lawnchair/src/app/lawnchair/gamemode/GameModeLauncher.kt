package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent

/**
 * Abre o Argosy como modo jogos. Procura primeiro o build de release e depois o de debug,
 * que usa o sufixo .debug no applicationId.
 */
object GameModeLauncher {

    private val ARGOSY_PACKAGES = listOf("com.nendo.argosy", "com.nendo.argosy.debug")

    fun launchIntent(context: Context): Intent? = ARGOSY_PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }

    fun launch(context: Context): Boolean {
        val intent = launchIntent(context) ?: return false
        context.startActivity(intent)
        return true
    }
}
