package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit

/**
 * Preferências mínimas do modo jogos (M1). Ficam em SharedPreferences simples
 * para não depender do DataStore do Lawnchair enquanto o modo ainda é um esqueleto.
 */
object GameModeSettings {

    private const val FILE = "game_mode"
    private const val KEY_AUTO_ENTER = "auto_enter_on_controller"

    fun isAutoEnterEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_ENTER, true)

    fun setAutoEnterEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_AUTO_ENTER, enabled)
        }
    }
}
