package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Modo jogos: biblioteca (pasta configurável, jogos baixados e colocados à mão), destaque de
 * "continuar jogando", recentes, filtro por plataforma, detalhe do jogo e ajustes.
 * Visual segue os mockups aprovados (estilo SteamOS), navegação só por controle físico.
 */
class GameModeActivity : ComponentActivity() {

    private var resumeTick by mutableIntStateOf(0)
    private var padLost by mutableStateOf(false)
    private lateinit var lostWatcher: ControllerLostWatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameLog.init(applicationContext)
        // Activity só possui Context válido depois que o Android conclui a anexação.
        // Inicializar o watcher aqui evita acessar applicationContext durante a construção.
        lostWatcher = ControllerLostWatcher(applicationContext) {
            if (GameModeSettings.isAutoEnterEnabled(applicationContext)) padLost = true
        }
        enableEdgeToEdge()
        setContent {
            LawnchairTheme {
                EdgeToEdge()
                Box {
                    GameModeRoot(refreshKey = resumeTick, onExit = { finish() })
                    if (padLost) {
                        PadLostDialog(onStay = { padLost = false }, onLeave = { finish() })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        lostWatcher.start()
    }

    override fun onPause() {
        lostWatcher.stop()
        super.onPause()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, GameModeActivity::class.java)
    }
}

private const val SYNC_TIMEOUT_MS = 15_000L

private enum class GameTab(val label: Int) {
    Library(R.string.game_tab_library),
    Server(R.string.game_tab_server),
    Achievements(R.string.game_tab_achievements),
    Settings(R.string.game_tab_settings),
}

@Composable
private fun GameModeRoot(refreshKey: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(GameTab.Library) }
    var detail by remember { mutableStateOf<LocalGame?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var rootPath by remember { mutableStateOf(GameLibrarySettings.rootPath(context)) }
    var games by remember { mutableStateOf(emptyList<LocalGame>()) }
    var androidGames by remember { mutableStateOf(emptyList<AndroidGame>()) }
    var hasAccess by remember { mutableStateOf(StorageAccess.isGranted()) }
    var scanTick by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey, scanTick, rootPath) {
        hasAccess = StorageAccess.isGranted()
        games = withContext(Dispatchers.IO) { GameLibraryScanner.scan(File(rootPath)) }
        androidGames = withContext(Dispatchers.IO) { AndroidGames.list(context) }
        version++
    }

    BackHandler(enabled = detail != null) { detail = null }

    suspend fun startGame(coreId: String, romPath: String, title: String, loadState: Boolean, platform: GamePlatform?) {
        val core = CoreInstaller.ensureInstalled(context, coreId) { bytes ->
            status = context.getString(R.string.game_mode_status_downloading, coreId, bytes / 1024)
        }.getOrThrow()
        if (RomMSettings.isConnected(context)) {
            status = context.getString(R.string.game_mode_status_syncing)
            withTimeoutOrNull(SYNC_TIMEOUT_MS) { withContext(Dispatchers.IO) { SaveSync.pull(context, romPath, coreId) } }
        }
        status = context.getString(R.string.game_mode_status_starting, title)
        context.startActivity(GameSessionActivity.intent(context, core.absolutePath, romPath, loadState, platform?.slug))
    }

    fun play(game: LocalGame, loadState: Boolean = false) {
        scope.launch {
            busy = true
            runCatching { startGame(game.platform.coreId, game.path, game.title, loadState, game.platform) }
                .onFailure { status = context.getString(R.string.game_mode_status_error, it.message ?: it.toString()) }
            busy = false
        }
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
                startGame(coreId, rom.absolutePath, rom.name, false, GamePlatforms.resolve(emptyList(), rom.extension))
            }.onFailure { status = context.getString(R.string.game_mode_status_error, it.message ?: it.toString()) }
            busy = false
        }
    }

    val needsAccess = !hasAccess && !GameLibrarySettings.isInsideApp(context, rootPath)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GameColors.backgroundTop, GameColors.background)))
            .systemBarsPadding(),
    ) {
        val current = detail
        if (current == null) {
            TopBar(selected = tab, onSelect = { tab = it })
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                current != null -> GameDetailScreen(
                    game = current,
                    busy = busy,
                    onPlay = { play(current) },
                    onLoad = { play(current, loadState = true) },
                    onBack = { detail = null },
                )

                tab == GameTab.Library -> LibraryScreen(
                    games = games,
                    androidGames = androidGames,
                    version = version,
                    busy = busy,
                    onPlay = { play(it) },
                    onDetails = { detail = it },
                    onLaunchAndroid = { game ->
                        if (!AndroidGames.launch(context, game)) {
                            status = context.getString(R.string.game_mode_status_error, game.title)
                        }
                    },
                )

                tab == GameTab.Settings -> SettingsScreen(
                    rootPath = rootPath,
                    needsAccess = needsAccess,
                    busy = busy,
                    onChooseFolder = { folderPicker.launch(null) },
                    onRefresh = { scanTick++ },
                    onGrantAccess = { context.startActivity(StorageAccess.settingsIntent(context)) },
                    onPickRom = { romPicker.launch(arrayOf("*/*")) },
                    onExit = onExit,
                )

                tab == GameTab.Server -> ServerScreen(rootPath = rootPath, onLibraryChange = { scanTick++ })

                else -> AchievementsScreen()
            }
        }
        if (status.isNotEmpty()) {
            Text(
                text = status,
                color = GameColors.textDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        HintBar(inDetail = current != null)
    }
}

@Composable
private fun PadLostDialog(onStay: () -> Unit, onLeave: () -> Unit) {
    val stayFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { stayFocus.requestFocus() } }
    Box(
        modifier = Modifier.fillMaxSize().background(GameColors.scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(GameColors.backgroundTop, RoundedCornerShape(14.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.game_pad_disconnected_title), color = GameColors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.game_pad_disconnected_body), color = GameColors.textDim, fontSize = 15.sp)
            GameButton(stringResource(R.string.game_pad_stay), onStay, primary = true, modifier = Modifier.focusRequester(stayFocus))
            GameButton(stringResource(R.string.game_mode_exit), onLeave)
        }
    }
}

@Composable
private fun TopBar(selected: GameTab, onSelect: (GameTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Tandem",
            color = GameColors.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 12.dp),
        )
        GameTab.entries.forEach { entry ->
            FocusSurface(
                onClick = { onSelect(entry) },
                background = if (entry == selected) GameColors.surfaceHigh else GameColors.background.copy(alpha = 0f),
            ) {
                Text(
                    text = stringResource(entry.label),
                    color = if (entry == selected) GameColors.text else GameColors.textDim,
                    fontSize = 14.sp,
                    fontWeight = if (entry == selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun HintBar(inDetail: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.bar)
            .padding(horizontal = 24.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Hint("A", stringResource(if (inDetail) R.string.game_play else R.string.game_hint_select))
        Hint("B", stringResource(R.string.game_hint_back))
    }
}

@Composable
private fun Hint(button: String, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = button, color = GameColors.text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(text = label, color = GameColors.textDim, fontSize = 12.sp)
    }
}
