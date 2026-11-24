package dev.clicker.remote

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.clicker.remote.ui.ClickerApp

class MainActivity : ComponentActivity() {
    private val controller: RemoteController by viewModels()

    /** Shown in Settings so unknown hardware keys can be identified. */
    var lastKey by mutableStateOf<String?>(null)
        private set

    var showSettings by mutableStateOf(false)

    /** Bumped to replay the intro video. Clicker is usually the home screen, so it rarely restarts. */
    private var introRun by mutableStateOf(0)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySetupExtras()
        setContent {
            ClickerApp(
                controller = controller,
                showSettings = showSettings,
                onShowSettings = { showSettings = it },
                lastKey = lastKey,
                introRun = introRun,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // The remote just woke up or came back to Clicker: get connections ready before a button is pressed.
        controller.reconnect()
        // Replay the intro when the remote is picked up again after sitting idle.
        if (stoppedAt != 0L && System.currentTimeMillis() - stoppedAt > INTRO_REPLAY_AFTER_MS) {
            showSettings = false
            introRun++
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applySetupExtras()
    }

    /**
     * Lets a developer preconfigure addresses over adb, e.g.
     * adb shell am start -n dev.clicker.remote/.MainActivity --es atv_host 192.168.1.20 --es lg_host 192.168.1.21
     */
    private fun applySetupExtras() {
        intent?.getStringExtra("atv_host")?.let { controller.setAppleTvAddress(it) }
        intent?.getStringExtra("lg_host")?.let { controller.setLgAddress(it) }
        // For testing deep links: --es open_url https://www.netflix.com/title/...
        intent?.getStringExtra("open_url")?.let { controller.openUrl(it) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = hardwareKeyAction(event.keyCode)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            lastKey = "${KeyEvent.keyCodeToString(event.keyCode)} (scan ${event.scanCode})"
            Log.w("Clicker", "key $lastKey")
        }
        // In Settings, keys behave normally (typing, Back closes Settings).
        if (showSettings) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                showSettings = false
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // Volume keys are press-and-hold: act on press and on release, never on auto-repeat.
        val volumeDirection = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> 0
        }
        if (volumeDirection != 0) {
            when {
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> controller.volumeHold(volumeDirection, down = true)
                event.action == KeyEvent.ACTION_UP -> controller.volumeHold(volumeDirection, down = false)
            }
            return true
        }
        if (action == null) return super.dispatchKeyEvent(event)
        // Act once per press; auto-repeat only for volume and arrows.
        if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || action.repeats)) action.run()
        return true
    }

    private class HwAction(val repeats: Boolean, val run: () -> Unit)

    private fun hardwareKeyAction(keyCode: Int): HwAction? = when (keyCode) {
        // The RS90's power-labelled button reports F1.
        KeyEvent.KEYCODE_F1 -> HwAction(false) { controller.powerToggle() }
        KeyEvent.KEYCODE_DPAD_UP -> HwAction(true) { controller.key(Key.UP) }
        KeyEvent.KEYCODE_DPAD_DOWN -> HwAction(true) { controller.key(Key.DOWN) }
        KeyEvent.KEYCODE_DPAD_LEFT -> HwAction(true) { controller.key(Key.LEFT) }
        KeyEvent.KEYCODE_DPAD_RIGHT -> HwAction(true) { controller.key(Key.RIGHT) }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_SELECT ->
            HwAction(false) { controller.key(Key.OK) }
        KeyEvent.KEYCODE_BACK -> HwAction(false) { controller.key(Key.BACK) }
        KeyEvent.KEYCODE_MENU -> HwAction(false) { controller.key(Key.HOME) }
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> HwAction(false) { controller.key(Key.PLAY_PAUSE) }
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> HwAction(false) { controller.mute() }
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> HwAction(true) { controller.key(Key.UP) }
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> HwAction(true) { controller.key(Key.DOWN) }
        else -> null
    }

    companion object {
        private const val INTRO_REPLAY_AFTER_MS = 3 * 60_000L
    }
}
