package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R

/**
 * Superfície do modo jogos (M1: esqueleto). Roda no mesmo APK e na mesma task do
 * launcher, então Voltar fecha esta tela e devolve ao Lawnchair sem trocar de HOME.
 * No M2 o conteúdo passa a hospedar a UI do Argosy.
 */
class GameModeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LawnchairTheme {
                EdgeToEdge()
                Surface(modifier = Modifier.fillMaxSize()) {
                    GameModeScreen(onExit = { finish() })
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, GameModeActivity::class.java)
    }
}

@Composable
private fun GameModeScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    var autoEnter by remember { mutableStateOf(GameModeSettings.isAutoEnterEnabled(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.game_mode_title),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = stringResource(R.string.game_mode_placeholder),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.game_mode_auto_enter),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = autoEnter,
                onCheckedChange = {
                    autoEnter = it
                    GameModeSettings.setAutoEnterEnabled(context, it)
                },
            )
        }
        Button(onClick = onExit) {
            Text(text = stringResource(R.string.game_mode_exit))
        }
    }
}
