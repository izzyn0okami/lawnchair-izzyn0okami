package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.io.IOException
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
) {
    /** Pasta de destino dentro da pasta de jogos: uma subpasta por plataforma. */
    fun destination(root: File): File = File(File(root, platform.slug), fileName)
}

enum class RemoteFileKind(val path: String, val part: String) {
    Save("saves", "saveFile"),
    State("states", "stateFile"),
}

data class RemoteFile(val id: Long, val emulator: String?, val updatedAtMs: Long)

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
    }
}

/**
 * Cliente mínimo da API do RomM: conecta por código de pareamento (ou token), lista jogos e baixa
 * arquivos. Chamadas são bloqueantes: rodar em Dispatchers.IO.
 */
class RomMClient(private val baseUrl: String, private val token: String?) {

    private val http = OkHttpClient()
    private val base: HttpUrl = baseUrl.trimEnd('/').plus("/").toHttpUrl()

    private fun request(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        // O token só vai para o próprio servidor, nunca para endereços de fora.
        if (token != null && url.host == base.host) builder.header("Authorization", "Bearer $token")
        return builder
    }

    fun heartbeat(): Boolean = runCatching {
        http.newCall(request(base.resolve("api/heartbeat")!!).build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

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

    /** Todos os jogos do servidor cuja plataforma o motor sabe jogar e que são um arquivo só. */
    fun listGames(): List<RemoteGame> {
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
                .build()
            val json = http.newCall(request(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
                JSONObject(response.body.string())
            }
            val items = json.optJSONArray("items") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                parseGame(items.getJSONObject(i))?.let(games::add)
            }
            offset += items.length()
            if (offset >= json.optInt("total", offset)) break
        }
        return games
    }

    private fun parseGame(item: JSONObject): RemoteGame? {
        if (item.optBoolean("has_multiple_files", false)) return null
        val platform = GamePlatforms.fromRomM(item.optString("platform_slug")) ?: return null
        val fileName = item.optString("fs_name").ifEmpty { return null }
        if (File(fileName).extension.lowercase() !in platform.extensions + GamePlatforms.genericExtensions) return null
        return RemoteGame(
            id = item.getLong("id"),
            title = item.optString("name").ifEmpty { File(fileName).nameWithoutExtension },
            platform = platform,
            fileName = fileName,
            sizeBytes = item.optLong("fs_size_bytes", 0L),
            coverPath = item.optString("path_cover_small").ifEmpty { null },
        )
    }

    /** Baixa para um arquivo temporário e só renomeia no fim; [onProgress] recebe bytes baixados. */
    fun download(game: RemoteGame, target: File, onProgress: (Long) -> Unit) {
        val url = base.resolve("api/roms/${game.id}/content/")!!.newBuilder()
            .addPathSegment(game.fileName)
            .build()
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        http.newCall(request(url).build()).execute().use { response ->
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
        target.delete()
        check(temp.renameTo(target)) { "Não consegui gravar ${target.name}" }
    }

    /** Baixa a capa para o cache e devolve o arquivo (ou nulo se falhar). */
    fun cover(context: Context, game: RemoteGame): File? {
        val path = game.coverPath ?: return null
        val file = File(File(context.cacheDir, "romm_covers").apply { mkdirs() }, "${game.id}.img")
        if (file.isFile && file.length() > 0) return file
        val url = if (path.startsWith("http")) path.toHttpUrl() else base.resolve(path.trimStart('/')) ?: return null
        return runCatching {
            http.newCall(request(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                file.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            file
        }.getOrNull()
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
    }
}
