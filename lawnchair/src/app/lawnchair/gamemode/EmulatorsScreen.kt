package app.lawnchair.gamemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R

/** Emuladores externos: mostra quais estão instalados e deixa escolher qual abre cada console. */
@Composable
internal fun EmulatorsScreen() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val platforms = remember { GamePlatforms.all.filter { it.launch == LaunchMode.EMULATOR } }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.game_emulators_title), color = GameColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.game_emulators_note), color = GameColors.textDim, fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(platforms, key = { it.slug }) { platform ->
                val chosen = remember(tick) { ExternalEmulators.chosen(context, platform.slug)?.id }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(platform.name, color = GameColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    ExternalEmulators.forPlatform(platform.slug).forEach { emulator ->
                        val installed = remember(tick) { ExternalEmulators.isInstalled(context, emulator.packageName) }
                        FocusSurface(
                            onClick = {
                                if (installed) {
                                    ExternalEmulators.setPreferred(context, platform.slug, emulator.id)
                                    tick++
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(emulator.name, color = if (installed) GameColors.text else GameColors.textDim, fontSize = 13.sp)
                                Text(
                                    text = when {
                                        chosen == emulator.id -> stringResource(R.string.game_emulators_in_use)
                                        installed -> stringResource(R.string.game_emulators_installed)
                                        else -> stringResource(R.string.game_emulators_not_installed)
                                    },
                                    color = if (chosen == emulator.id) GameColors.success else GameColors.textDim,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
            item(key = "gamenative") {
                val installed = remember(tick) { ExternalEmulators.gameNativeInstalled(context) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.game_gamenative_title), color = GameColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(if (installed) R.string.game_emulators_installed else R.string.game_emulators_not_installed),
                        color = if (installed) GameColors.success else GameColors.textDim,
                        fontSize = 12.sp,
                    )
                    Text(stringResource(R.string.game_gamenative_note), color = GameColors.textDim, fontSize = 12.sp)
                }
            }
        }
    }
}
