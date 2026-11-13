package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sessão de jogo: roda um núcleo Libretro em tela cheia dentro do próprio APK.
 * M3: save de bateria automático, estado rápido e menu de pausa (Voltar, botão Mode ou Select + Start).
 * Roda no mesmo processo do launcher; separar em processo próprio fica para depois.
 */
class GameSessionActivity : ComponentActivity() {

    private var retroView: GLRetroView? = null
    private var saveStore: SaveStore? = null
    private var menuOpen by mutableStateOf(false)
    private var selectDown = false
    private var startDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val corePath = intent.getStringExtra(EXTRA_CORE_PATH)
        val romPath = intent.getStringExtra(EXTRA_ROM_PATH)
        if (corePath == null || romPath == null) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val coreId = File(corePath).name.removeSuffix("_libretro_android.so")
        val store = SaveStore(this, coreId, File(romPath))
        saveStore = store

        val root = File(filesDir, "libretro")
        val data = GLRetroViewData(this).apply {
            coreFilePath = corePath
            gameFilePath = romPath
            systemDirectory = File(root, "system").apply { mkdirs() }.absolutePath
            savesDirectory = File(root, "saves").apply { mkdirs() }.absolutePath
            saveRAMState = store.readSram()
        }

        val view = GLRetroView(this, data)
        retroView = view
        lifecycle.addObserver(view)
        setContentView(view)

        addContentView(
            ComposeView(this).apply {
                setContent {
                    LawnchairTheme {
                        if (menuOpen) {
                            PauseMenu(
                                canLoad = store.hasState(),
                                onResume = { setMenu(false) },
                                onSave = { saveQuickState() },
                                onLoad = { loadQuickState() },
                                onReset = {
                                    view.reset()
                                    setMenu(false)
                                },
                                onExit = { exitGame() },
                            )
                        }
                    }
                }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = setMenu(!menuOpen)
            },
        )

        lifecycleScope.launch {
            view.getGLRetroErrors().collect { code ->
                Toast.makeText(this@GameSessionActivity, getString(R.string.game_session_engine_error, code), Toast.LENGTH_LONG).show()
                finish()
            }
        }

        // Autosave do save de bateria a cada minuto enquanto o jogo está na tela.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(SRAM_AUTOSAVE_MS)
                    withContext(Dispatchers.Default) { persistSram() }
                }
            }
        }
    }

    private fun setMenu(open: Boolean) {
        menuOpen = open
        if (open) retroView?.pauseEmulation() else retroView?.resumeEmulation()
    }

    private fun persistSram() {
        val view = retroView ?: return
        val store = saveStore ?: return
        runCatching {
            val bytes = view.serializeSRAM()
            if (bytes.isNotEmpty()) store.writeSram(bytes)
        }
    }

    private fun saveQuickState() {
        val view = retroView ?: return
        val store = saveStore ?: return
        val saved = runCatching { store.writeState(view.serializeState()) }.isSuccess
        toast(if (saved) R.string.game_session_state_saved else R.string.game_session_state_save_failed)
        setMenu(false)
    }

    private fun loadQuickState() {
        val view = retroView ?: return
        val bytes = saveStore?.readState() ?: return
        val loaded = runCatching { view.unserializeState(bytes) }.getOrDefault(false)
        toast(if (loaded) R.string.game_session_state_loaded else R.string.game_session_state_load_failed)
        setMenu(false)
    }

    private fun exitGame() {
        persistSram()
        finish()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        // Antes do super: a superfície de vídeo ainda está ativa e consegue entregar o save.
        persistSram()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Controles sem botão Mode (a maioria dos genéricos): Select + Start juntos abrem o menu.
        val isDown = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectDown = isDown || (event.action != KeyEvent.ACTION_UP && selectDown)
            KeyEvent.KEYCODE_BUTTON_START -> startDown = isDown || (event.action != KeyEvent.ACTION_UP && startDown)
        }
        if (isDown && event.repeatCount == 0 && selectDown && startDown &&
            (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || event.keyCode == KeyEvent.KEYCODE_BUTTON_START)
        ) {
            setMenu(!menuOpen)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_UP) setMenu(!menuOpen)
            return true
        }
        if (menuOpen) {
            return when (event.keyCode) {
                // No menu, A confirma e B fecha, como em qualquer console.
                KeyEvent.KEYCODE_BUTTON_A -> super.dispatchKeyEvent(KeyEvent(event.action, KeyEvent.KEYCODE_DPAD_CENTER))

                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (event.action == KeyEvent.ACTION_UP) setMenu(false)
                    true
                }

                else -> super.dispatchKeyEvent(event)
            }
        }
        val view = retroView
        val isGameInput = event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD or InputDevice.SOURCE_JOYSTICK) != 0
        if (view != null && isGameInput && event.keyCode != KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> view.onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> view.onKeyUp(event.keyCode, event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!menuOpen && retroView?.onGenericMotionEvent(event) == true) return true
        return super.dispatchGenericMotionEvent(event)
    }

    companion object {
        private const val EXTRA_CORE_PATH = "core_path"
        private const val EXTRA_ROM_PATH = "rom_path"
        private const val SRAM_AUTOSAVE_MS = 60_000L

        fun intent(context: Context, corePath: String, romPath: String): Intent = Intent(context, GameSessionActivity::class.java)
            .putExtra(EXTRA_CORE_PATH, corePath)
            .putExtra(EXTRA_ROM_PATH, romPath)
    }
}

@Composable
private fun PauseMenu(
    canLoad: Boolean,
    onResume: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onReset: () -> Unit,
    onExit: () -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstItem.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.game_session_menu_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Button(onClick = onResume, modifier = Modifier.fillMaxWidth().focusRequester(firstItem)) {
                Text(text = stringResource(R.string.game_session_resume))
            }
            OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.game_session_save_state))
            }
            OutlinedButton(onClick = onLoad, enabled = canLoad, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.game_session_load_state))
            }
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.game_session_reset))
            }
            OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.game_session_exit))
            }
        }
    }
}
