package app.lawnchair.gamemode

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CARD_SIZE = 108.dp
private val CARD_GAP = 10.dp

@Composable
internal fun LibraryScreen(
    games: List<LocalGame>,
    androidGames: List<AndroidGame>,
    version: Int,
    busy: Boolean,
    onPlay: (LocalGame) -> Unit,
    onDetails: (LocalGame) -> Unit,
    onLaunchAndroid: (AndroidGame) -> Unit,
) {
    val context = LocalContext.current
    val recent = remember(version) { PlayHistory.recent(context, games).take(10) }
    var platformFilter by remember { mutableStateOf<String?>(null) }
    val platforms = remember(games) { games.map { it.platform }.distinctBy { it.slug } }
    val visible = remember(games, platformFilter) {
        if (platformFilter == null) games else games.filter { it.platform.slug == platformFilter }
    }
    val androidSorted = remember(androidGames, version) { AndroidGames.sortedByRecent(context, androidGames) }
    val showAndroid = platformFilter == null || platformFilter == ANDROID_FILTER
    val hero = recent.firstOrNull() ?: games.firstOrNull()
    val heroFocus = remember { FocusRequester() }
    LaunchedEffect(hero?.path) {
        if (hero != null) runCatching { heroFocus.requestFocus() }
    }

    if (games.isEmpty() && androidGames.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                text = stringResource(R.string.game_mode_empty_library),
                color = GameColors.textDim,
                fontSize = 16.sp,
            )
        }
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = (((maxWidth - 48.dp) + CARD_GAP) / (CARD_SIZE + CARD_GAP)).toInt().coerceAtLeast(2)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
        ) {
            if (hero != null) {
                item(key = "hero") {
                    HeroSection(
                        game = hero,
                        played = recent.isNotEmpty(),
                        busy = busy,
                        focus = heroFocus,
                        onPlay = { onPlay(hero) },
                        onDetails = { onDetails(hero) },
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.game_recent))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                            items(recent, key = { it.path }) { game ->
                                GameCard(game, onClick = { onDetails(game) })
                            }
                        }
                    }
                }
            }
            item(key = "platforms") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.game_by_platform))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            PlatformChip(stringResource(R.string.game_all), platformFilter == null) { platformFilter = null }
                        }
                        items(platforms, key = { it.slug }) { platform ->
                            PlatformChip(platform.name, platformFilter == platform.slug) { platformFilter = platform.slug }
                        }
                        if (androidGames.isNotEmpty()) {
                            item(key = ANDROID_FILTER) {
                                PlatformChip(stringResource(R.string.game_android), platformFilter == ANDROID_FILTER) { platformFilter = ANDROID_FILTER }
                            }
                        }
                    }
                }
            }
            if (showAndroid && androidSorted.isNotEmpty()) {
                item(key = "android") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.game_android))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                            items(androidSorted, key = { it.packageName }) { game ->
                                AndroidCard(game, onClick = { onLaunchAndroid(game) })
                            }
                        }
                    }
                }
            }
            if (platformFilter != ANDROID_FILTER && visible.isNotEmpty()) {
                item(key = "library-title") { SectionTitle(stringResource(R.string.game_library_section)) }
                items(visible.chunked(columns), key = { row -> row.first().path }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                        row.forEach { game -> GameCard(game, onClick = { onDetails(game) }) }
                    }
                }
            }
        }
    }
}

private const val ANDROID_FILTER = "android"

@Composable
private fun AndroidCard(game: AndroidGame, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, game.packageName) {
        value = withContext(Dispatchers.IO) { AndroidGames.icon(context, game) }
    }
    FocusSurface(onClick = onClick, modifier = Modifier.size(CARD_SIZE)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(56.dp)) }
                ?: Box(modifier = Modifier.size(56.dp))
            Text(text = game.title, color = GameColors.text, fontSize = 12.sp, maxLines = 2)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun GameCard(game: LocalGame, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.size(CARD_SIZE)) {
        CoverTile(game, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun PlatformChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        background = if (selected) GameColors.surfaceHigh else GameColors.surface,
    ) {
        Text(
            text = label,
            color = if (selected) GameColors.text else GameColors.textDim,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun HeroSection(
    game: LocalGame,
    played: Boolean,
    busy: Boolean,
    focus: FocusRequester,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
        CoverTile(game, modifier = Modifier.size(width = 112.dp, height = 150.dp).clip(RoundedCornerShape(12.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(if (played) R.string.game_continue_playing else R.string.game_featured).uppercase(),
                color = GameColors.textDim,
                fontSize = 12.sp,
            )
            Text(text = game.title, color = GameColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(text = game.platform.name, color = GameColors.textDim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                GameButton(
                    label = stringResource(if (played) R.string.game_continue else R.string.game_play),
                    onClick = onPlay,
                    primary = true,
                    enabled = !busy,
                    modifier = Modifier.width(140.dp).focusRequester(focus),
                )
                GameButton(label = stringResource(R.string.game_details), onClick = onDetails, modifier = Modifier.width(120.dp))
            }
        }
    }
}

@Composable
internal fun GameDetailScreen(
    game: LocalGame,
    busy: Boolean,
    onPlay: () -> Unit,
    onLoad: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val history = remember(game.path) { PlayHistory.get(context, game.path) }
    val hasState = remember(game.path) {
        SaveStore(context, game.platform.coreId, File(game.path)).hasState() && !RaSettings.blocksStates(context, game.platform)
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(game.path) { runCatching { playFocus.requestFocus() } }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        CoverTile(game, modifier = Modifier.size(width = 150.dp, height = 200.dp).clip(RoundedCornerShape(14.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(text = game.platform.name.uppercase(), color = GameColors.textDim, fontSize = 12.sp)
            Text(text = game.title, color = GameColors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                GameButton(
                    label = stringResource(R.string.game_play),
                    onClick = onPlay,
                    primary = true,
                    enabled = !busy,
                    modifier = Modifier.width(130.dp).focusRequester(playFocus),
                )
                GameButton(
                    label = stringResource(R.string.game_load_state_start),
                    onClick = onLoad,
                    enabled = hasState && !busy,
                    modifier = Modifier.width(170.dp),
                )
                GameButton(label = stringResource(R.string.game_hint_back), onClick = onBack, modifier = Modifier.width(100.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(top = 8.dp)) {
                Stat(stringResource(R.string.game_stat_playtime), formatPlaytime(history.playtimeMs))
                Stat(
                    stringResource(R.string.game_stat_last_played),
                    if (history.played) {
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(history.lastPlayedMs))
                    } else {
                        stringResource(R.string.game_never_played)
                    },
                )
                Stat(stringResource(R.string.game_stat_origin), stringResource(R.string.game_origin_local))
            }
            Text(text = game.path, color = GameColors.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label.uppercase(), color = GameColors.textDim, fontSize = 11.sp)
        Text(text = value, color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun formatPlaytime(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return when {
        minutes <= 0 -> stringResource(R.string.game_playtime_none)
        minutes < 60 -> stringResource(R.string.game_playtime_minutes, minutes)
        else -> stringResource(R.string.game_playtime_format, minutes / 60, minutes % 60)
    }
}

@Composable
internal fun SettingsScreen(
    rootPath: String,
    needsAccess: Boolean,
    busy: Boolean,
    onChooseFolder: () -> Unit,
    onRefresh: () -> Unit,
    onGrantAccess: () -> Unit,
    onPickRom: () -> Unit,
    onOpenBios: () -> Unit,
    onOpenEmulators: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    var autoEnter by remember { mutableStateOf(GameModeSettings.isAutoEnterEnabled(context)) }
    val toggleAuto = {
        autoEnter = !autoEnter
        GameModeSettings.setAutoEnterEnabled(context, autoEnter)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .width(520.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.game_mode_folder_label, rootPath),
            color = GameColors.textDim,
            fontSize = 14.sp,
        )
        GameButton(stringResource(R.string.game_mode_choose_folder), onChooseFolder)
        GameButton(stringResource(R.string.game_mode_refresh), onRefresh)
        if (needsAccess) {
            Text(text = stringResource(R.string.game_mode_needs_access), color = GameColors.danger, fontSize = 14.sp)
            GameButton(stringResource(R.string.game_mode_grant_access), onGrantAccess, primary = true)
        }
        GameButton(stringResource(R.string.game_mode_play_rom), onPickRom, enabled = !busy)
        GameButton(stringResource(R.string.game_bios_open), onOpenBios)
        GameButton(stringResource(R.string.game_emulators_open), onOpenEmulators)
        FocusSurface(onClick = toggleAuto, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.game_mode_auto_enter), color = GameColors.text, fontSize = 16.sp)
                Switch(checked = autoEnter, onCheckedChange = null)
            }
        }
        GameButton(stringResource(R.string.game_log_export), rememberLogExporter())
        GameButton(stringResource(R.string.game_mode_exit), onExit, danger = true)
        Box(modifier = Modifier.height(12.dp))
    }
}
