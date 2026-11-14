package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Jogo nativo do Android instalado no aparelho. */
data class AndroidGame(val packageName: String, val title: String)

/** Detecta e lança jogos Android: apps que o próprio sistema marca como jogo. */
object AndroidGames {

    fun list(context: Context): List<AndroidGame> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        return launcherApps.getActivityList(null, Process.myUserHandle())
            .filter { it.applicationInfo.packageName != context.packageName && isGame(it.applicationInfo) }
            .map { AndroidGame(it.applicationInfo.packageName, it.label.toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.title.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun isGame(info: ApplicationInfo): Boolean = info.category == ApplicationInfo.CATEGORY_GAME || (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    /** Jogos já abertos pelo Tandem vêm primeiro, do mais recente para o mais antigo. */
    fun sortedByRecent(context: Context, games: List<AndroidGame>): List<AndroidGame> = games.sortedByDescending { PlayHistory.get(context, key(it)).lastPlayedMs }

    fun key(game: AndroidGame) = "android:${game.packageName}"

    fun launch(context: Context, game: AndroidGame): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(game.packageName) ?: return false
        PlayHistory.markPlayed(context, key(game), System.currentTimeMillis())
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun icon(context: Context, game: AndroidGame): ImageBitmap? = runCatching {
        context.packageManager.getApplicationIcon(game.packageName).toBitmap(ICON_SIZE, ICON_SIZE).asImageBitmap()
    }.getOrNull()

    private const val ICON_SIZE = 144
}
