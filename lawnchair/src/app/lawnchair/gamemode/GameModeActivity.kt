package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Superfície do modo jogos (M3, passo 1): pasta de jogos configurável, varredura que reconhece
 * jogos baixados e colocados à mão, e lista simples para jogar. A interface final (estilo SteamOS)
 * vem depois dos mockups.
 */
class GameModeActivity : ComponentActivity() {

    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LawnchairTheme {
                EdgeToEdge()
                Surface(modifier = Modifier.fillMaxSize()) {
                    GameModeScreen(refreshKey = resumeTick, onExit = { finish() })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, GameModeActivity::class.java)
    }
}

@Composable
private fun GameModeScreen(refreshKey: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoEnter by remember { mutableStateOf(GameModeSettings.isAutoEnterEnabled(context)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var rootPath by remember { mutableStateOf(GameLibrarySettings.rootPath(context)) }
    var games by remember { mutableStateOf(emptyList<LocalGame>()) }
    var hasAccess by remember { mutableStateOf(StorageAccess.isGranted()) }
    var scanTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey, scanTick, rootPath) {
        hasAccess = StorageAccess.isGranted()
        games = withContext(Dispatchers.IO) { GameLibraryScanner.scan(File(rootPath)) }
    }

    fun play(corePathProvider: suspend () -> File, romPath: String, title: String) {
        scope.launch {
            busy = true
            runCatching {
                val core = corePathProvider()
                status = context.getString(R.string.game_mode_status_starting, title)
                context.startActivity(GameSessionActivity.intent(context, core.absolutePath, romPath))
            }.onFailure { status = context.getString(R.string.game_mode_status_error, it.message ?: it.toString()) }
            busy = false
        }
    }

    fun installCore(coreId: String): suspend () -> File = {
        CoreInstaller.ensureInstalled(context, coreId) { bytes ->
            status = context.getString(R.string.game_mode_status_downloading, coreId, bytes / 1024)
        }.getOrThrow()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = StorageAccess.treeUriToPath(uri)
        if (path == null) {
            status = context.getString(R.string.game_mode_status_folder_invalid)
        } else {
            GameLibrarySettings.setRootPath(context, path)
            rootPath = path
            status = ""
        }
    }

    val romPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                status = context.getString(R.string.game_mode_status_importing)
                val rom = RomLauncher.importRom(context, uri)
                val coreId = checkNotNull(RomLauncher.coreFor(rom.name)) {
                    context.getString(R.string.game_mode_status_unsupported, rom.name)
                }
                val core = installCore(coreId)()
                status = context.getString(R.string.game_mode_status_starting, rom.name)
                context.startActivity(GameSessionActivity.intent(context, core.absolutePath, rom.absolutePath))
            }.onFailure { status = context.getString(R.string.game_mode_status_error, it.message ?: it.toString()) }
            busy = false
        }
    }

    val needsAccess = !hasAccess && !GameLibrarySettings.isInsideApp(context, rootPath)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.game_mode_title),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = stringResource(R.string.game_mode_folder_label, rootPath),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { folderPicker.launch(null) }) {
                Text(text = stringResource(R.string.game_mode_choose_folder))
            }
            OutlinedButton(onClick = { scanTick++ }) {
                Text(text = stringResource(R.string.game_mode_refresh))
            }
        }
        if (needsAccess) {
            Text(
                text = stringResource(R.string.game_mode_needs_access),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = { context.startActivity(StorageAccess.settingsIntent(context)) }) {
                Text(text = stringResource(R.string.game_mode_grant_access))
            }
        }
        if (status.isNotEmpty()) {
            Text(text = status, style = MaterialTheme.typography.bodyMedium)
        }
        if (games.isEmpty()) {
            Text(
                text = stringResource(R.string.game_mode_empty_library),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            games.groupBy { it.platform }.forEach { (platform, platformGames) ->
                item(key = "header-${platform.slug}") {
                    Text(
                        text = "${platform.name} (${platformGames.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(platformGames, key = { it.path }) { game ->
                    Text(
                        text = game.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) {
                                play(installCore(game.platform.coreId), game.path, game.title)
                            }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
        OutlinedButton(
            enabled = !busy,
            onClick = { romPicker.launch(arrayOf("*/*")) },
        ) {
            Text(text = stringResource(R.string.game_mode_play_rom))
        }
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
