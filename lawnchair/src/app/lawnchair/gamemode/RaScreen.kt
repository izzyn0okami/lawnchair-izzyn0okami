package app.lawnchair.gamemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.game_ra_title), color = GameColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.game_ra_howto), color = GameColors.textDim, fontSize = 14.sp)
        val current = user
        if (current != null && RaSettings.isLoggedIn(context)) {
            Text(stringResource(R.string.game_ra_connected, current), color = GameColors.success, fontSize = 16.sp)
            var hardcore by remember { mutableStateOf(RaSettings.hardcore(context)) }
            FocusSurface(
                onClick = {
                    hardcore = !hardcore
                    RaSettings.setHardcore(context, hardcore)
                },
                modifier = Modifier.width(520.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.game_ra_hardcore), color = GameColors.text, fontSize = 16.sp)
                    Switch(checked = hardcore, onCheckedChange = null)
                }
            }
            Text(stringResource(R.string.game_ra_hardcore_note), color = GameColors.textDim, fontSize = 12.sp)
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
