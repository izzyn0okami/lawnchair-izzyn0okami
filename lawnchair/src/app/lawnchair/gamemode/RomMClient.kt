package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Jogo que existe no servidor RomM (ainda não necessariamente baixado). */
data class RemoteGame(
    val id: Long,
    val title: String,
    val platform: GamePlatform,
    val fileName: String,
    val sizeBytes: Long,
    val coverPath: String?,
    /** Nome do jogo no servidor (pasta ou arquivo), usado na URL de download. */
    val serverName: String = fileName,
    /** Arquivo dentro da pasta do jogo, quando o jogo é uma pasta com um único arquivo jogável. */
    val fileId: Long? = null,
    /** Jogo de vários arquivos (ex.: cue + bin): baixa a pasta inteira e abre [fileName]. */
    val folder: Boolean = false,
) {
    /** Destino dentro da pasta de jogos: uma subpasta por plataforma (e uma pasta por jogo, se for de vários arquivos). */
    fun destination(root: File): File {
        val platformDir = File(root, platform.slug)
        return if (folder) File(File(platformDir, serverName), fileName) else File(platformDir, fileName)
    }
}

/** Arquivo de firmware (BIOS) guardado no servidor. */
data class RemoteFirmware(val id: Long, val fileName: String)

enum class RemoteFileKind(val path: String, val part: String) {
    Save("saves", "saveFile"),
    State("states", "stateFile"),
}

data class RemoteFile(val id: Long, val emulator: String?, val updatedAtMs: Long)

/** Pedido de aprovação do aparelho: o usuário confirma o código no RomM, pelo navegador. */
data class DevicePairing(
    val baseUrl: String,
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

sealed class DevicePoll {
    data object Pending : DevicePoll()
    data object SlowDown : DevicePoll()
    data object Denied : DevicePoll()
    data object Expired : DevicePoll()
    data class Approved(val token: String) : DevicePoll()
    data class Failed(val message: String) : DevicePoll()
}

/** Endereço do servidor e token, guardados só neste aparelho. */
object RomMSettings {

    private const val FILE = "romm"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun url(context: Context): String? = prefs(context).getString("url", null)

    fun token(context: Context): String? = prefs(context).getString("token", null)

    fun isConnected(context: Context): Boolean = url(context) != null && token(context) != null

    fun save(context: Context, url: String, token: String) {
        prefs(context).edit {
            putString("url", url)
            putString("token", token)
        }
    }

    fun clear(context: Context) {
        prefs(context).edit { clear() }
        RomMCache.clear(context)
    }
}

/**
 * Cliente mínimo da API do RomM: conecta por código de pareamento (ou token), lista jogos e baixa
 * arquivos. Chamadas são bloqueantes: rodar em Dispatchers.IO.
 */
class RomMClient(private val baseUrl: String, private val token: String?) {

    val baseAddress: String get() = baseUrl.trimEnd('/')

    // Mesmos prazos do Argosy: servidor remoto ou atrás de túnel pode demorar a responder.
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Listar a biblioteca inteira e baixar jogos grandes podem ficar parados por bastante tempo. */
    private val longHttp = http.newBuilder()
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()
    private val base: HttpUrl = baseUrl.trimEnd('/').plus("/").toHttpUrl()

    private fun request(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        // O token só vai para o próprio servidor, nunca para endereços de fora.
        if (token != null && url.host == base.host) builder.header("Authorization", "Bearer $token")
        return builder
    }

    fun heartbeat(): Boolean = checkServer() == null

    /** Testa se o servidor responde; devolve nulo se está tudo bem ou o motivo da falha em palavras. */
    fun checkServer(): String? = try {
        http.newCall(request(base.resolve("api/heartbeat")!!).build()).execute().use { response ->
            if (response.isSuccessful) null else "o servidor respondeu ${response.code}"
        }
    } catch (e: IOException) {
        e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName
    }

    /** Troca o código de pareamento gerado no RomM por um token de cliente. */
    fun exchangePairingCode(code: String): String {
        val body = JSONObject().put("code", code.trim()).toString().toRequestBody("application/json".toMediaType())
        val request = request(base.resolve("api/client-tokens/exchange")!!).post(body).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        404 -> "Código inválido ou expirado"
                        429 -> "Muitas tentativas, tente de novo daqui a pouco"
                        else -> "Falha na troca do código (${response.code})"
                    },
                )
            }
            return JSONObject(response.body.string()).getString("raw_token")
        }
    }

    /** Pede ao servidor um código de aprovação (fluxo de dispositivo, como TV): o login é feito no navegador. */
    fun beginDevicePairing(clientId: String, deviceName: String, version: String): DevicePairing {
        val body = JSONObject()
            .put("client_device_identifier", clientId)
            .put("name", deviceName)
            .put("client", "tandem")
            .put("platform", "Android")
            .put("client_version", version)
            .put("requested_scopes", org.json.JSONArray(DEVICE_SCOPES))
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = request(base.resolve("api/auth/device/init")!!).post(body).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    if (response.code == 404) "Esta versão do RomM não permite aprovar pelo aparelho; use o código de pareamento" else "Falha ao iniciar o pareamento (${response.code})",
                )
            }
            val json = JSONObject(response.body.string())
            val path = json.getString("verification_path_complete")
            val link = if (path.startsWith("http")) path else base.resolve(path.trimStart('/'))?.toString() ?: path
            return DevicePairing(
                baseUrl = baseUrl.trimEnd('/'),
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUrl = link,
                expiresInSeconds = json.optInt("expires_in", 600),
                intervalSeconds = json.optInt("interval", 5),
            )
        }
    }

    /** Pergunta se o usuário já aprovou. Erros de rede passageiros contam como "ainda pendente". */
    fun pollDevicePairing(deviceCode: String): DevicePoll = try {
        val body = JSONObject().put("device_code", deviceCode).toString().toRequestBody("application/json".toMediaType())
        http.newCall(request(base.resolve("api/auth/device/token")!!).post(body).build()).execute().use { response ->
            val text = response.body.string()
            if (response.isSuccessful) {
                DevicePoll.Approved(JSONObject(text).getString("access_token"))
            } else {
                when (runCatching { JSONObject(text).optString("detail") }.getOrDefault("")) {
                    "authorization_pending" -> DevicePoll.Pending
                    "slow_down" -> DevicePoll.SlowDown
                    "access_denied" -> DevicePoll.Denied
                    "expired_token" -> DevicePoll.Expired
                    else -> DevicePoll.Failed("Pareamento falhou (${response.code})")
                }
            }
        }
    } catch (_: IOException) {
        DevicePoll.Pending
    }

    // Resumo do que o servidor tem e do que foi ignorado, para explicar uma lista vazia.
    @Volatile
    var listReport: String = ""
        private set

    private var serverTotal = 0
    private val skippedPlatforms = sortedMapOf<String, Int>()
    private var skippedMulti = 0
    private var skippedExtension = 0

    /** Todos os jogos do servidor cuja plataforma o motor sabe jogar e que são um arquivo só. */
    fun listGames(): List<RemoteGame> {
        serverTotal = 0
        skippedPlatforms.clear()
        skippedMulti = 0
        skippedExtension = 0
        val games = mutableListOf<RemoteGame>()
        var offset = 0
        while (true) {
            val url = base.resolve("api/roms")!!.newBuilder()
                .addQueryParameter("order_by", "name")
                .addQueryParameter("order_dir", "asc")
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("with_char_index", "false")
                .addQueryParameter("with_filter_values", "false")
                .addQueryParameter("with_files", "true")
                .build()
            val json = longHttp.newCall(request(url).build()).execute().use { response ->
                GameLog.log("romm", "GET api/roms offset=$offset -> ${response.code}")
                if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
                val text = response.body.string()
                if (offset == 0) GameLog.log("romm", "início da resposta: ${text.take(600)}")
                JSONObject(text)
            }
            val items = json.optJSONArray("items")
            if (items == null) {
                GameLog.log("romm", "resposta sem 'items'; chaves: ${json.keys().asSequence().toList()}")
                break
            }
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                parseGame(items.getJSONObject(i))?.let(games::add)
            }
            offset += items.length()
            serverTotal = json.optInt("total", offset)
            if (offset >= serverTotal) break
        }
        listReport = buildString {
            append("O servidor tem $serverTotal jogo(s); $offset lido(s); ${games.size} compatível(is).")
            if (skippedMulti > 0) append(" Ignorados por terem vários arquivos: $skippedMulti.")
            if (skippedExtension > 0) append(" Ignorados por extensão não suportada: $skippedExtension.")
            if (skippedPlatforms.isNotEmpty()) {
                append(" Plataformas ainda não suportadas: ")
                append(skippedPlatforms.entries.joinToString { "${it.key} (${it.value})" })
                append(".")
            }
        }
        return games
    }

    private fun parseGame(item: JSONObject): RemoteGame? {
        val slug = item.optString("platform_slug")
        val platform = GamePlatforms.fromRomM(slug)
        if (platform == null) {
            if (skippedPlatforms[slug.ifEmpty { "?" }] == null) GameLog.log("romm", "plataforma ignorada: '$slug'")
            skippedPlatforms[slug.ifEmpty { "?" }] = (skippedPlatforms[slug.ifEmpty { "?" }] ?: 0) + 1
            return null
        }
        val serverName = item.optString("fs_name").ifEmpty { return null }
        val allowed = platform.extensions + if (platform.discBased) emptySet() else GamePlatforms.genericExtensions
        val flat = File(serverName).extension.lowercase() in allowed &&
            !item.optBoolean("has_multiple_files", false) &&
            !item.optBoolean("has_nested_single_file", false)
        var fileName = serverName
        var fileId: Long? = null
        var folder = false
        if (!flat) {
            val files = item.optJSONArray("files")
            val playable = (0 until (files?.length() ?: 0)).map { files!!.getJSONObject(it) }.filter {
                val name = it.optString("file_name")
                !name.startsWith(".") && File(name).extension.lowercase() in allowed
            }
            if (playable.isEmpty()) {
                skippedExtension++
                return null
            }
            val referencing = playable.filter { File(it.getString("file_name")).extension.lowercase() in setOf("cue", "m3u", "gdi") }
            if (platform.discBased && (referencing.isNotEmpty() || playable.size > 1)) {
                // Disco com várias partes (cue + bin, vários discos): baixa a pasta toda e abre a lista ou o primeiro cue.
                folder = true
                val names = playable.map { it.getString("file_name") }.sorted()
                fileName = names.firstOrNull { it.endsWith(".m3u", ignoreCase = true) }
                    ?: names.singleOrNull()
                    ?: "${File(serverName).nameWithoutExtension}.m3u"
            } else if (playable.size > 1) {
                skippedMulti++
                return null
            } else {
                fileName = playable.single().getString("file_name")
                fileId = playable.single().getLong("id")
            }
        }
        return RemoteGame(
            id = item.getLong("id"),
            title = item.optString("name").ifEmpty { File(fileName).nameWithoutExtension },
            platform = platform,
            fileName = fileName,
            sizeBytes = item.optLong("fs_size_bytes", 0L),
            coverPath = item.optString("path_cover_small").ifEmpty { null },
            serverName = serverName,
            fileId = fileId,
            folder = folder,
        )
    }

    /** Baixa para um arquivo temporário e só renomeia no fim; [onProgress] recebe bytes baixados. */
    fun download(game: RemoteGame, target: File, onProgress: (Long) -> Unit) {
        if (game.folder) {
            downloadFolder(game, target, onProgress)
            return
        }
        val url = base.resolve("api/roms/${game.id}/content/")!!.newBuilder()
            .addPathSegment(game.serverName)
            .apply { game.fileId?.let { addQueryParameter("file_ids", it.toString()) } }
            .build()
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        longHttp.newCall(request(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
            var total = 0L
            response.body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total)
                    }
                }
            }
        }
        if (game.fileId != null && isZip(temp) && target.extension.lowercase() != "zip") unzipSingle(temp, target)
        target.delete()
        check(temp.renameTo(target)) { "Não consegui gravar ${target.name}" }
    }

    /** Baixa a pasta do jogo (o servidor entrega um zip) e extrai tudo; cria a lista .m3u se faltar. */
    private fun downloadFolder(game: RemoteGame, target: File, onProgress: (Long) -> Unit) {
        val dir = checkNotNull(target.parentFile)
        val url = base.resolve("api/roms/${game.id}/content/")!!.newBuilder().addPathSegment(game.serverName).build()
        val temp = File(dir.parentFile, "${dir.name}.part")
        temp.deleteRecursively()
        temp.mkdirs()
        longHttp.newCall(request(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
            var total = 0L
            val counting = object : java.io.FilterInputStream(response.body.byteStream()) {
                override fun read(): Int = super.read().also { if (it >= 0) onProgress(++total) }

                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
                    if (it > 0) {
                        total += it
                        onProgress(total)
                    }
                }
            }
            java.util.zip.ZipInputStream(counting).use { zip ->
                val canonicalTemp = temp.canonicalPath
                var entry = zip.nextEntry
                checkNotNull(entry) { "O servidor não devolveu um pacote para ${game.title}" }
                while (entry != null) {
                    val out = File(temp, entry.name)
                    // Protege contra caminhos como ../ dentro do pacote.
                    check(out.canonicalPath.startsWith(canonicalTemp + File.separator)) { "Pacote inválido" }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        // O pacote pode trazer tudo dentro de uma pasta única: sobe o conteúdo um nível.
        val onlyChild = temp.listFiles()?.singleOrNull()
        val content = if (onlyChild != null && onlyChild.isDirectory) onlyChild else temp
        if (!File(content, game.fileName).isFile && game.fileName.endsWith(".m3u", ignoreCase = true)) {
            val discs = content.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in setOf("cue", "gdi", "chd", "iso", "pbp") }
            val cues = discs.filter { it.extension.lowercase() in setOf("cue", "gdi") }
            val list = (cues.ifEmpty { discs }).map { it.name }.sorted()
            check(list.isNotEmpty()) { "Nenhum disco encontrado em ${game.title}" }
            File(content, game.fileName).writeText(list.joinToString("\n") + "\n")
        }
        dir.deleteRecursively()
        check(content.renameTo(dir)) { "Não consegui gravar ${dir.name}" }
        temp.deleteRecursively()
    }

    /** Arquivos de firmware (BIOS) que o servidor guarda, de todas as plataformas. */
    fun listFirmware(): List<RemoteFirmware> {
        val text = http.newCall(request(base.resolve("api/platforms")!!).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
            response.body.string()
        }
        val platforms = org.json.JSONArray(text)
        val result = mutableListOf<RemoteFirmware>()
        for (i in 0 until platforms.length()) {
            val firmware = platforms.getJSONObject(i).optJSONArray("firmware") ?: continue
            for (j in 0 until firmware.length()) {
                val item = firmware.getJSONObject(j)
                if (item.optBoolean("missing_from_fs", false)) continue
                result += RemoteFirmware(item.getLong("id"), item.getString("file_name"))
            }
        }
        return result
    }

    fun downloadFirmware(firmware: RemoteFirmware, target: File) {
        val url = base.resolve("api/firmware/${firmware.id}/content/")!!.newBuilder().addPathSegment(firmware.fileName).build()
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        longHttp.newCall(request(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
            temp.outputStream().use { response.body.byteStream().copyTo(it) }
        }
        target.delete()
        check(temp.renameTo(target)) { "Não consegui gravar ${target.name}" }
    }

    private fun isZip(file: File): Boolean = file.inputStream().use {
        val head = ByteArray(4)
        it.read(head) == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte()
    }

    /** O servidor às vezes empacota a pasta num zip: tira de dentro o arquivo do jogo. */
    private fun unzipSingle(zip: File, target: File) {
        val out = File(zip.parentFile, "${zip.name}.out")
        java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
            var entry = input.nextEntry
            while (entry != null && (entry.isDirectory || File(entry.name).name != target.name)) entry = input.nextEntry
            checkNotNull(entry) { "O pacote do servidor não contém ${target.name}" }
            out.outputStream().use { input.copyTo(it) }
        }
        zip.delete()
        check(out.renameTo(zip)) { "Não consegui extrair ${target.name}" }
    }

    /** Baixa a capa para o cache e devolve o arquivo (ou nulo se falhar). */
    fun cover(context: Context, game: RemoteGame): File? {
        val path = game.coverPath ?: return null
        val file = File(File(context.cacheDir, "romm_covers").apply { mkdirs() }, "${game.id}.img")
        if (file.isFile && file.length() > 0) return file
        val url = if (path.startsWith("http")) path.toHttpUrl() else base.resolve(path.trimStart('/')) ?: return null
        // Grava num arquivo temporário e só então renomeia: outra tela nunca lê uma capa pela metade.
        val temp = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.part")
        return runCatching {
            http.newCall(request(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                temp.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            if (temp.renameTo(file)) file else null
        }.also { temp.delete() }.getOrNull()
    }

    /** Saves (bateria) e estados guardados no servidor para um jogo. */
    fun listRemoteFiles(kind: RemoteFileKind, romId: Long): List<RemoteFile> {
        val url = base.resolve("api/${kind.path}")!!.newBuilder().addQueryParameter("rom_id", romId.toString()).build()
        val text = http.newCall(request(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
            response.body.string()
        }
        val array = org.json.JSONArray(text)
        return (0 until array.length()).map { parseRemoteFile(array.getJSONObject(it)) }
    }

    private fun parseRemoteFile(json: JSONObject) = RemoteFile(
        id = json.getLong("id"),
        emulator = json.optString("emulator").ifEmpty { null },
        updatedAtMs = parseTime(json.optString("updated_at")),
    )

    private fun parseTime(text: String): Long = runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(text).toEpochMilli() }
        .recoverCatching { java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
        .getOrDefault(0L)

    fun downloadRemoteFile(kind: RemoteFileKind, id: Long, target: File) {
        val url = base.resolve("api/${kind.path}/$id/content")!!
        http.newCall(request(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
            target.outputStream().use { response.body.byteStream().copyTo(it) }
        }
    }

    /** Envia o arquivo: atualiza o existente ([existingId]) ou cria um novo; devolve o registro no servidor. */
    fun uploadRemoteFile(kind: RemoteFileKind, romId: Long, emulator: String, existingId: Long?, file: File, uploadName: String): RemoteFile {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(kind.part, uploadName, file.readBytes().toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val builder = if (existingId != null) {
            request(base.resolve("api/${kind.path}/$existingId")!!).put(body)
        } else {
            val url = base.resolve("api/${kind.path}")!!.newBuilder()
                .addQueryParameter("rom_id", romId.toString())
                .addQueryParameter("emulator", emulator)
                .build()
            request(url).post(body)
        }
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
            return parseRemoteFile(JSONObject(response.body.string()))
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        val DEVICE_SCOPES = listOf(
            "me.read",
            "platforms.read",
            "roms.read",
            "roms.user.read",
            "roms.user.write",
            "assets.read",
            "assets.write",
            "firmware.read",
            "devices.read",
            "devices.write",
        )
    }
}
