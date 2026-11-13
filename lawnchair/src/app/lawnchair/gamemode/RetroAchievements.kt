package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import com.swordfish.libretrodroid.AchievementDef
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Conta do RetroAchievements: só o nome e o token ficam no aparelho; a senha nunca é guardada. */
object RaSettings {

    private fun prefs(context: Context) = context.getSharedPreferences("retroachievements", Context.MODE_PRIVATE)

    fun user(context: Context): String? = prefs(context).getString("user", null)

    fun token(context: Context): String? = prefs(context).getString("token", null)

    fun isLoggedIn(context: Context): Boolean = user(context) != null && token(context) != null

    fun save(context: Context, user: String, token: String) {
        prefs(context).edit {
            putString("user", user)
            putString("token", token)
        }
    }

    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }

    /** Conquistas desbloqueadas sem rede, guardadas como "idConquista|hashDoJogo" para reenviar depois. */
    fun pending(context: Context): Set<String> = prefs(context).getStringSet("pending", emptySet()).orEmpty()

    fun setPending(context: Context, entries: Set<String>) {
        prefs(context).edit { putStringSet("pending", entries) }
    }
}

data class RaAchievement(val id: Long, val title: String, val description: String, val points: Int, val memAddr: String)

/** Cliente mínimo da API clássica do RetroAchievements (dorequest.php). Chamadas bloqueantes. */
class RaClient {

    private val http = OkHttpClient()

    private fun call(vararg params: Pair<String, String>): JSONObject {
        val form = FormBody.Builder().apply { params.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder()
            .url("https://retroachievements.org/dorequest.php")
            .header("User-Agent", "Tandem/1.0")
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("RetroAchievements respondeu ${response.code}")
            return JSONObject(response.body.string())
        }
    }

    /** Entra com usuário e senha e devolve o token (a senha não é guardada). */
    fun login(user: String, password: String): Pair<String, String> {
        val json = call("r" to "login2", "u" to user.trim(), "p" to password)
        if (!json.optBoolean("Success", false)) {
            throw IOException(json.optString("Error").ifEmpty { "Usuário ou senha inválidos" })
        }
        return json.getString("User") to json.getString("Token")
    }

    /** Código do jogo no RetroAchievements a partir do hash da ROM; nulo se não for reconhecido. */
    fun gameIdForHash(hash: String): Long? {
        val id = call("r" to "gameid", "m" to hash).optLong("GameID", 0L)
        return id.takeIf { it > 0 }
    }

    fun achievements(user: String, token: String, gameId: Long): List<RaAchievement> {
        val json = call("r" to "patch", "u" to user, "t" to token, "g" to gameId.toString())
        val list = json.optJSONObject("PatchData")?.optJSONArray("Achievements") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }
            // Flags 3 = conquistas oficiais (5 são não oficiais).
            .filter { it.optInt("Flags", 3) == 3 }
            .map {
                RaAchievement(
                    id = it.getLong("ID"),
                    title = it.optString("Title"),
                    description = it.optString("Description"),
                    points = it.optInt("Points", 0),
                    memAddr = it.optString("MemAddr"),
                )
            }
            .filter { it.memAddr.isNotBlank() && !it.title.contains("Unknown Emulator", ignoreCase = true) }
    }

    /** Inicia a sessão de jogo e devolve os códigos das conquistas que o usuário já tem. */
    fun startSession(user: String, token: String, gameId: Long): Set<Long> {
        val json = call("r" to "startsession", "u" to user, "t" to token, "g" to gameId.toString(), "h" to "0")
        val ids = mutableSetOf<Long>()
        for (key in listOf("Unlocks", "HardcoreUnlocks")) {
            val array = json.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) ids += array.getJSONObject(i).getLong("ID")
        }
        return ids
    }

    /** Registra a conquista (modo normal). "Já tinha" conta como sucesso. */
    fun award(user: String, token: String, achievementId: Long, gameHash: String): Boolean {
        val validation = MessageDigest.getInstance("MD5")
            .digest("$achievementId${user}0".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val json = call(
            "r" to "awardachievement",
            "u" to user,
            "t" to token,
            "a" to achievementId.toString(),
            "h" to "0",
            "m" to gameHash,
            "v" to validation,
        )
        return json.optBoolean("Success", false) || json.optString("Error").contains("already", ignoreCase = true)
    }
}

/**
 * Conquistas durante uma partida (modo normal, sem hardcore): descobre o jogo pelo hash da ROM,
 * entrega as conquistas ao motor e registra no site as que forem desbloqueadas. Sem rede, as
 * conquistas ficam guardadas e são reenviadas na próxima partida.
 */
class RaSession(
    private val context: Context,
    private val view: GLRetroView,
    private val platform: GamePlatform,
    private val romPath: String,
    private val scope: CoroutineScope,
    private val onUnlock: (RaAchievement) -> Unit,
    private val onProgress: (earned: Int, total: Int) -> Unit,
) {
    private val client = RaClient()
    private var achievementsById = emptyMap<Long, RaAchievement>()
    private var gameHash: String? = null
    private var earned = 0
    private var total = 0

    /** Chamar só depois que o jogo já está rodando (o motor precisa do mapa de memória do núcleo). */
    fun start() {
        val user = RaSettings.user(context) ?: return
        val token = RaSettings.token(context) ?: return
        val consoleId = platform.raConsoleId ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                resendPending(user, token)
                val hash = LibretroDroid.computeRomHash(romPath, consoleId) ?: return@launch
                gameHash = hash
                val gameId = client.gameIdForHash(hash) ?: return@launch
                val unlocked = runCatching { client.startSession(user, token, gameId) }.getOrDefault(emptySet())
                val all = client.achievements(user, token, gameId)
                achievementsById = all.associateBy { it.id }
                total = all.size
                earned = all.count { it.id in unlocked }
                withContext(Dispatchers.Main) { onProgress(earned, total) }
                val toWatch = all.filter { it.id !in unlocked }
                if (toWatch.isNotEmpty()) {
                    LibretroDroid.initAchievements(toWatch.map { AchievementDef(it.id, it.memAddr) }.toTypedArray(), consoleId)
                    view.achievementUnlockListener = { id -> handleUnlock(id, user, token) }
                }
            }
        }
    }

    fun stop() {
        view.achievementUnlockListener = null
        runCatching { LibretroDroid.clearAchievements() }
    }

    private fun handleUnlock(id: Long, user: String, token: String) {
        val achievement = achievementsById[id] ?: return
        earned++
        scope.launch(Dispatchers.Main) {
            onUnlock(achievement)
            onProgress(earned, total)
        }
        val hash = gameHash ?: return
        scope.launch(Dispatchers.IO) {
            val sent = runCatching { client.award(user, token, id, hash) }.getOrDefault(false)
            if (!sent) RaSettings.setPending(context, RaSettings.pending(context) + "$id|$hash")
        }
    }

    private fun resendPending(user: String, token: String) {
        val pending = RaSettings.pending(context)
        if (pending.isEmpty()) return
        val remaining = pending.filterNot { entry ->
            val (id, hash) = entry.split("|").let { it[0].toLongOrNull() to it.getOrNull(1) }
            id != null && hash != null && runCatching { client.award(user, token, id, hash) }.getOrDefault(false)
        }
        RaSettings.setPending(context, remaining.toSet())
    }
}
