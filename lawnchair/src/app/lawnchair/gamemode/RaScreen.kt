package app.lawnchair.gamemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aba de conquistas: entrar e sair da conta do RetroAchievements. */
@Composable
internal fun AchievementsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(RaSettings.user(context)) }
    var typedUser by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.game_ra_title), color = GameColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.game_ra_howto), color = GameColors.textDim, fontSize = 14.sp)
        val current = user
        if (current != null && RaSettings.isLoggedIn(context)) {
            Text(stringResource(R.string.game_ra_connected, current), color = GameColors.success, fontSize = 16.sp)
            GameButton(
                label = stringResource(R.string.game_ra_logout),
                danger = true,
                modifier = Modifier.width(220.dp),
                onClick = {
                    RaSettings.clear(context)
                    user = null
                },
            )
        } else {
            OutlinedTextField(
                value = typedUser,
                onValueChange = { typedUser = it },
                label = { Text(stringResource(R.string.game_ra_user)) },
                singleLine = true,
                modifier = Modifier.width(420.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.game_ra_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.width(420.dp),
            )
            GameButton(
                label = stringResource(R.string.game_ra_login),
                enabled = !busy && typedUser.isNotBlank() && password.isNotEmpty(),
                primary = true,
                modifier = Modifier.width(220.dp),
                onClick = {
                    scope.launch {
                        busy = true
                        message = context.getString(R.string.game_ra_logging_in)
                        val result = withContext(Dispatchers.IO) { runCatching { RaClient().login(typedUser, password) } }
                        result.onSuccess { (name, token) ->
                            RaSettings.save(context, name, token)
                            user = name
                            password = ""
                            message = ""
                        }.onFailure { message = it.message ?: it.toString() }
                        busy = false
                    }
                },
            )
            if (message.isNotEmpty()) Text(message, color = GameColors.textDim, fontSize = 14.sp)
        }
    }
}
