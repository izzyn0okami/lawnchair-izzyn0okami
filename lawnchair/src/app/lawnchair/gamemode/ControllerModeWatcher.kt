package app.lawnchair.gamemode

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

/**
 * Observa a conexão de controles e chama [onGamepadConnected] quando um gamepad
 * físico é conectado. Só deve ficar registrado enquanto o launcher está em primeiro
 * plano (start/stop), para nunca abrir o modo jogos por cima de outro app ou jogo.
 */
class ControllerModeWatcher(
    context: Context,
    private val onGamepadConnected: () -> Unit,
) : InputManager.InputDeviceListener {

    private val appContext = context.applicationContext
    private val inputManager = appContext.getSystemService(InputManager::class.java)
    private var registered = false

    fun start() {
        if (registered) return
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        registered = true
    }

    fun stop() {
        if (!registered) return
        inputManager.unregisterInputDeviceListener(this)
        registered = false
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        if (!GameModeSettings.isAutoEnterEnabled(appContext)) return
        val device = InputDevice.getDevice(deviceId) ?: return
        if (isPhysicalGamepad(device)) onGamepadConnected()
    }

    override fun onInputDeviceRemoved(deviceId: Int) = Unit

    override fun onInputDeviceChanged(deviceId: Int) = Unit

    private fun isPhysicalGamepad(device: InputDevice): Boolean {
        if (device.isVirtual) return false
        return (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    }
}
