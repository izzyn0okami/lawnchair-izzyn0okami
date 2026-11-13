package app.lawnchair.gamemode

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ROMM_CARD = 140.dp
private val ROMM_GAP = 14.dp

/** Aba do servidor RomM: conectar por código de pareamento, listar jogos e baixar para a pasta de jogos. */
@Composable
internal fun ServerScreen(rootPath: String, onLibraryChange: () -> Unit) {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(RomMSettings.isConnected(context)) }
    if (!connected) {
        ConnectForm(onConnect = { connected = true })
    } else {
        ServerLibrary(
            rootPath = rootPath,
            onLibraryChange = onLibraryChange,
            onDisconnect = {
                RomMSettings.clear(context)
                connected = false
            },
        )
    }
}

@Composable
private fun ConnectForm(onConnect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.game_romm_title), color = GameColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.game_romm_howto), color = GameColors.textDim, fontSize = 14.sp)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.game_romm_address)) },
            singleLine = true,
            modifier = Modifier.width(520.dp),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.game_romm_code)) },
            singleLine = true,
            modifier = Modifier.width(520.dp),
        )
        GameButton(
            label = stringResource(R.string.game_romm_connect),
            enabled = !busy && address.isNotBlank() && code.isNotBlank(),
            primary = true,
            modifier = Modifier.width(220.dp),
            onClick = {
                scope.launch {
                    busy = true
                    message = context.getString(R.string.game_romm_connecting)
                    val result = withContext(Dispatchers.IO) { connect(address, code) }
                    result.onSuccess { (url, token) ->
                        RomMSettings.save(context, url, token)
                        onConnect()
                    }.onFailure { message = it.message ?: it.toString() }
                    busy = false
                }
            },
        )
        if (message.isNotEmpty()) Text(message, color = GameColors.textDim, fontSize = 14.sp)
    }
}

/** Testa https e depois http quando o endereço vem sem protocolo; devolve (endereço, token). */
private fun connect(address: String, code: String): Result<Pair<String, String>> = runCatching {
    val typed = address.trim().trimEnd('/')
    val candidates = if ("://" in typed) listOf(typed) else listOf("https://$typed", "http://$typed")
    var lastError: Throwable? = null
    for (candidate in candidates) {
        try {
            val client = RomMClient(candidate, null)
            if (!client.heartbeat()) {
                lastError = IllegalStateException("Não consegui falar com o servidor em $candidate")
                continue
            }
            return@runCatching candidate to client.exchangePairingCode(code)
        } catch (e: Exception) {
            lastError = e
        }
    }
    throw lastError ?: IllegalStateException("Falha ao conectar")
}

@Composable
private fun ServerLibrary(rootPath: String, onLibraryChange: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val url = remember { RomMSettings.url(context).orEmpty() }
    val client = remember { RomMClient(url, RomMSettings.token(context)) }
    var games by remember { mutableStateOf<List<RemoteGame>?>(null) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var diskTick by remember { mutableIntStateOf(0) }
    val progress: SnapshotStateMap<Long, Long> = remember { mutableStateMapOf() }
    val root = remember(rootPath) { File(rootPath) }

    LaunchedEffect(reload) {
        games = null
        error = ""
        runCatching { withContext(Dispatchers.IO) { client.listGames() } }
            .onSuccess { games = it }
            .onFailure {
                games = emptyList()
                error = context.getString(R.string.game_romm_list_error, it.message ?: it.toString())
            }
    }

    fun download(game: RemoteGame) {
        if (progress.containsKey(game.id)) return
        scope.launch {
            progress[game.id] = 0L
            runCatching {
                withContext(Dispatchers.IO) {
                    client.download(game, game.destination(root)) { bytes -> progress[game.id] = bytes }
                }
            }.onFailure { error = context.getString(R.string.game_romm_download_error, game.title, it.message ?: it.toString()) }
            progress.remove(game.id)
            diskTick++
            onLibraryChange()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.game_romm_connected, url),
                color = GameColors.textDim,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            GameButton(stringResource(R.string.game_mode_refresh), onClick = { reload++ }, modifier = Modifier.width(200.dp))
            GameButton(stringResource(R.string.game_romm_disconnect), onClick = onDisconnect, danger = true, modifier = Modifier.width(150.dp))
        }
        if (error.isNotEmpty()) Text(error, color = GameColors.danger, fontSize = 14.sp)
        val list = games
        when {
            list == null -> Text(stringResource(R.string.game_romm_loading), color = GameColors.textDim, fontSize = 16.sp)

            list.isEmpty() && error.isEmpty() -> Text(stringResource(R.string.game_romm_empty), color = GameColors.textDim, fontSize = 16.sp)

            else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = (((maxWidth - 80.dp) + ROMM_GAP) / (ROMM_CARD + ROMM_GAP)).toInt().coerceAtLeast(2)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(ROMM_GAP),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(list.chunked(columns), key = { row -> row.first().id }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(ROMM_GAP)) {
                            row.forEach { game ->
                                val downloaded = remember(game.id, diskTick) { game.destination(root).isFile }
                                RemoteCard(
                                    game = game,
                                    client = client,
                                    downloaded = downloaded,
                                    downloadedBytes = progress[game.id],
                                    onClick = { if (!downloaded) download(game) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteCard(game: RemoteGame, client: RomMClient, downloaded: Boolean, downloadedBytes: Long?, onClick: () -> Unit) {
    val context = LocalContext.current
    val cover by produceState<ImageBitmap?>(null, game.id) {
        value = withContext(Dispatchers.IO) {
            client.cover(context, game)?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
        }
    }
    val statusText = when {
        downloadedBytes != null -> {
            val percent = if (game.sizeBytes > 0) (downloadedBytes * 100 / game.sizeBytes).toInt().coerceIn(0, 100) else 0
            stringResource(R.string.game_romm_downloading, percent)
        }

        downloaded -> stringResource(R.string.game_romm_downloaded)

        else -> stringResource(R.string.game_romm_download)
    }
    FocusSurface(onClick = onClick, modifier = Modifier.size(ROMM_CARD)) {
        val image = cover
        if (image != null) {
            Image(bitmap = image, contentDescription = game.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(game.title, color = GameColors.text, fontSize = 13.sp, maxLines = 3, modifier = Modifier.padding(10.dp))
        }
        Text(
            text = statusText,
            color = if (downloaded) GameColors.success else GameColors.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(6.dp),
        )
    }
}
