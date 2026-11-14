package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.android.launcher3.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ROMM_CARD = 140.dp
private val ROMM_GAP = 14.dp
private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** Do Android 17 em diante, apps novos precisam da permissão de rede local para falar com aparelhos da casa. */
private fun localNetworkGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
    ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

/** Aba do servidor RomM: conectar por aprovação no navegador, listar jogos e baixar para a pasta de jogos. */
@Composable
internal fun ServerScreen(rootPath: String, onLibraryChange: () -> Unit) {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(RomMSettings.isConnected(context)) }
    var allowed by remember { mutableStateOf(localNetworkGranted(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) {
        if (!allowed) runCatching { request.launch(LOCAL_NETWORK_PERMISSION) }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (!allowed) {
            Column(
                modifier = Modifier.padding(horizontal = 40.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.game_local_network_info), color = GameColors.danger, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GameButton(
                        label = stringResource(R.string.game_local_network_allow),
                        onClick = { runCatching { request.launch(LOCAL_NETWORK_PERMISSION) } },
                        primary = true,
                        modifier = Modifier.weight(1f),
                    )
                    GameButton(
                        label = stringResource(R.string.game_local_network_settings),
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
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
    }
}

@Composable
private fun ConnectForm(onConnect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<DevicePairing?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val pending = pairing
    if (pending != null) {
        PairingWait(
            pairing = pending,
            onApprove = { token ->
                RomMSettings.save(context, pending.baseUrl, token)
                onConnect()
            },
            onFail = { reason ->
                pairing = null
                message = reason
            },
            onCancel = { pairing = null },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.game_romm_title), color = GameColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(if (manual) R.string.game_romm_howto else R.string.game_romm_howto_device),
            color = GameColors.textDim,
            fontSize = 14.sp,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.game_romm_address)) },
            singleLine = true,
            modifier = Modifier.width(520.dp),
        )
        if (manual) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text(stringResource(R.string.game_romm_code)) },
                singleLine = true,
                modifier = Modifier.width(520.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GameButton(
                label = stringResource(R.string.game_romm_connect),
                enabled = !busy && address.isNotBlank() && (!manual || code.isNotBlank()),
                primary = true,
                modifier = Modifier.width(220.dp),
                onClick = {
                    scope.launch {
                        busy = true
                        message = context.getString(R.string.game_romm_connecting)
                        if (manual) {
                            val result = withContext(Dispatchers.IO) { connect(address, code) }
                            result.onSuccess { (url, token) ->
                                RomMSettings.save(context, url, token)
                                onConnect()
                            }.onFailure { message = it.message ?: it.toString() }
                        } else {
                            val result = withContext(Dispatchers.IO) { beginPairing(context, address) }
                            result.onSuccess {
                                pairing = it
                                message = ""
                            }.onFailure { message = it.message ?: it.toString() }
                        }
                        busy = false
                    }
                },
            )
            GameButton(
                label = stringResource(if (manual) R.string.game_romm_use_device else R.string.game_romm_use_code),
                modifier = Modifier.width(300.dp),
                onClick = { manual = !manual },
            )
        }
        if (message.isNotEmpty()) Text(message, color = GameColors.textDim, fontSize = 14.sp)
    }
}

/** Mostra o código e espera a aprovação no navegador; avisa quando aprovado, negado ou expirado. */
@Composable
private fun PairingWait(pairing: DevicePairing, onApprove: (String) -> Unit, onFail: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val latestApprove by rememberUpdatedState(onApprove)
    val latestFail by rememberUpdatedState(onFail)
    LaunchedEffect(pairing.deviceCode) {
        val client = RomMClient(pairing.baseUrl, null)
        var intervalMs = pairing.intervalSeconds.coerceAtLeast(1) * 1000L
        val deadline = System.currentTimeMillis() + pairing.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            when (val result = withContext(Dispatchers.IO) { client.pollDevicePairing(pairing.deviceCode) }) {
                is DevicePoll.Approved -> return@LaunchedEffect latestApprove(result.token)
                DevicePoll.Denied -> return@LaunchedEffect latestFail(context.getString(R.string.game_romm_pair_denied))
                DevicePoll.Expired -> return@LaunchedEffect latestFail(context.getString(R.string.game_romm_pair_expired))
                is DevicePoll.Failed -> return@LaunchedEffect latestFail(result.message)
                DevicePoll.SlowDown -> intervalMs = (intervalMs + 5_000L).coerceAtMost(30_000L)
                DevicePoll.Pending -> Unit
            }
        }
        latestFail(context.getString(R.string.game_romm_pair_expired))
    }
    val openFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { openFocus.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.game_romm_title), color = GameColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.game_romm_pair_code, pairing.userCode), color = GameColors.accent, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.game_romm_pair_steps), color = GameColors.textDim, fontSize = 14.sp)
        Text(pairing.verificationUrl, color = GameColors.textDim, fontSize = 12.sp)
        GameButton(
            label = stringResource(R.string.game_romm_open_browser),
            primary = true,
            modifier = Modifier.width(320.dp).focusRequester(openFocus),
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pairing.verificationUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        )
        Text(stringResource(R.string.game_romm_pair_wait), color = GameColors.textDim, fontSize = 14.sp)
        GameButton(label = stringResource(R.string.game_romm_pair_cancel), onClick = onCancel, modifier = Modifier.width(220.dp))
    }
}

/** Endereços a testar: com protocolo digitado só ele; sem protocolo, http primeiro para IP/rede local, https primeiro para nomes. */
private fun candidateUrls(address: String): List<String> {
    val typed = address.trim().trimEnd('/')
    if ("://" in typed) return listOf(typed)
    val host = typed.substringBefore('/').substringBefore(':')
    val local = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || host == "localhost" || host.endsWith(".local") || '.' !in host
    return if (local) listOf("http://$typed", "https://$typed") else listOf("https://$typed", "http://$typed")
}

/** Primeiro endereço que responde; se nenhum responder, o erro lista o motivo de cada tentativa. */
private fun reachable(address: String): RomMClient {
    val problems = mutableListOf<String>()
    for (candidate in candidateUrls(address)) {
        val client = runCatching { RomMClient(candidate, null) }.getOrElse {
            problems += "$candidate: endereço inválido"
            continue
        }
        val problem = client.checkServer() ?: return client
        problems += "$candidate: $problem"
    }
    throw IllegalStateException("Não consegui falar com o servidor. ${problems.joinToString("; ")}")
}

/** Acha o servidor e pede o código de aprovação. */
private fun beginPairing(context: Context, address: String): Result<DevicePairing> = runCatching {
    val client = reachable(address)

    @Suppress("HardwareIds")
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    val clientId = "tandem-${androidId ?: java.util.UUID.randomUUID()}"
    val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    client.beginDevicePairing(clientId, name, "1.0")
}

/** Acha o servidor e troca o código de pareamento por um token; devolve (endereço, token). */
private fun connect(address: String, code: String): Result<Pair<String, String>> = runCatching {
    val client = reachable(address)
    client.baseAddress to client.exchangePairingCode(code)
}

@Composable
private fun ServerLibrary(rootPath: String, onLibraryChange: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val url = remember { RomMSettings.url(context).orEmpty() }
    val client = remember { RomMClient(url, RomMSettings.token(context)) }
    var games by remember { mutableStateOf(RomMCache.load(context, url)) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var report by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var diskTick by remember { mutableIntStateOf(0) }
    val progress: SnapshotStateMap<Long, Long> = remember { mutableStateMapOf() }
    val root = remember(rootPath) { File(rootPath) }

    LaunchedEffect(reload) {
        val fresh = System.currentTimeMillis() - RomMCache.lastRefreshMs < REFRESH_INTERVAL_MS
        if (reload == 0 && games != null && fresh) return@LaunchedEffect
        error = ""
        refreshing = true
        runCatching { withContext(Dispatchers.IO) { client.listGames() } }
            .onSuccess {
                games = it
                report = client.listReport
                RomMCache.save(context, url, it)
            }
            .onFailure {
                if (games == null) games = emptyList()
                error = context.getString(R.string.game_romm_list_error, it.message ?: it.toString())
            }
        refreshing = false
    }

    fun download(game: RemoteGame) {
        if (progress.containsKey(game.id)) return
        scope.launch {
            progress[game.id] = 0L
            runCatching {
                withContext(Dispatchers.IO) {
                    val target = game.destination(root)
                    client.download(game, target) { bytes -> progress[game.id] = bytes }
                    SaveSync.link(context, target.absolutePath, game.id)
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
            if (refreshing && games != null) {
                Text(stringResource(R.string.game_romm_updating), color = GameColors.textDim, fontSize = 13.sp)
            }
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

            list.isEmpty() && error.isEmpty() -> Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.game_romm_empty), color = GameColors.textDim, fontSize = 16.sp)
                if (report.isNotEmpty()) Text(report, color = GameColors.textDim, fontSize = 14.sp)
            }

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
