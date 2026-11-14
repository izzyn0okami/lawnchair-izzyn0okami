package app.lawnchair.gamemode

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tela de BIOS: mostra o que falta por console e traz as três formas de adicionar. */
@Composable
internal fun BiosScreen(rootPath: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.count { uri ->
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    val input = context.contentResolver.openInputStream(uri)
                    name != null && input != null && input.use { BiosManager.import(context, name, it) } != null
                }
            }
            message = context.getString(R.string.game_bios_added, added, uris.size)
            tick++
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.game_bios_title), color = GameColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GameButton(stringResource(R.string.game_bios_import), onClick = { picker.launch(arrayOf("*/*")) }, compact = true, enabled = !busy)
            GameButton(
                stringResource(R.string.game_bios_scan_folder),
                compact = true,
                enabled = !busy,
                onClick = {
                    scope.launch {
                        val added = withContext(Dispatchers.IO) { BiosManager.importFromFolder(context, File(rootPath)) }
                        message = context.getString(R.string.game_bios_found, added, File(rootPath, "bios").absolutePath)
                        tick++
                    }
                },
            )
            GameButton(
                stringResource(R.string.game_bios_download),
                compact = true,
                enabled = !busy && RomMSettings.isConnected(context),
                onClick = {
                    scope.launch {
                        busy = true
                        message = context.getString(R.string.game_bios_downloading)
                        try {
                            val client = RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                            val added = withContext(Dispatchers.IO) { BiosManager.downloadFromServer(context, client) }
                            message = context.getString(R.string.game_bios_downloaded, added)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            GameLog.error("bios", "falha ao baixar BIOS do servidor", e)
                            message = context.getString(R.string.game_bios_error, e.message ?: e.toString())
                        } finally {
                            busy = false
                            tick++
                        }
                    }
                },
            )
        }
        if (message.isNotEmpty()) Text(message, color = GameColors.textDim, fontSize = 12.sp, maxLines = 2)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(BiosManager.platformsWithBios(), key = { it.slug }) { platform ->
                val ready = remember(tick) { BiosManager.isReady(context, platform) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val need = stringResource(if (platform.biosRequired) R.string.game_bios_required else R.string.game_bios_optional)
                    Text(
                        text = "${platform.name} · $need",
                        color = when {
                            ready -> GameColors.success
                            platform.biosRequired -> GameColors.danger
                            else -> GameColors.text
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    platform.bios.forEach { file ->
                        val present = remember(tick) { BiosManager.has(context, file) }
                        Text(
                            text = (if (present) "✓ " else "✗ ") + "${file.name}  —  ${file.label}",
                            color = if (present) GameColors.success else GameColors.textDim,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
