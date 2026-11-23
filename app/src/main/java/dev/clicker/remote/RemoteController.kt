package dev.clicker.remote

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.clicker.remote.appletv.AppleTvClient
import dev.clicker.remote.appletv.AppleTvException
import dev.clicker.remote.appletv.CompanionDiscovery
import dev.clicker.remote.appletv.HidCommand
import dev.clicker.remote.ir.IrBlaster
import dev.clicker.remote.ir.IrCodes
import dev.clicker.remote.ir.IrSignal
import dev.clicker.remote.lgtv.WebOsClient
import dev.clicker.remote.lgtv.WebOsDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LinkState { NOT_SET_UP, OFFLINE, CONNECTING, CONNECTED }

/**
 * A shortcut tile. [match] is matched against installed app names on the device, unless
 * [atvBundle] names the exact Apple TV app. [storeBundle] is the App Store id used for artwork
 * when it differs from the tvOS one.
 */
data class AppShortcut(
    val label: String,
    val match: String,
    val atvBundle: String? = null,
    val storeBundle: String? = null,
)

val DEFAULT_SHORTCUTS = listOf(
    AppShortcut("Apple TV", "Apple TV", atvBundle = "com.apple.TVWatchList", storeBundle = "com.apple.tv"),
    AppShortcut("Netflix", "Netflix"),
    AppShortcut("YouTube", "YouTube"),
    AppShortcut("Hulu", "Hulu"),
    AppShortcut("Peacock", "Peacock"),
    AppShortcut("Paramount+", "Paramount"),
)

data class UiState(
    val target: Target = Target.APPLE_TV,
    val volumeTarget: VolumeTarget = VolumeTarget.APPLE_TV,
    val atv: LinkState = LinkState.NOT_SET_UP,
    val lg: LinkState = LinkState.NOT_SET_UP,
    val message: String? = null,
    val awaitingAtvPin: Boolean = false,
    val busy: Boolean = false,
    val atvFound: List<CompanionDiscovery.Found> = emptyList(),
    val lgFound: List<WebOsDiscovery.Found> = emptyList(),
    val irAvailable: Boolean = false,
    val background: BackgroundMode = BackgroundMode.VIDEO,
    /** Shortcut label to cached icon file path. */
    val artwork: Map<String, String> = emptyMap(),
    /** When the Apple TV will give up waiting for the pairing code (epoch ms). */
    val pairingDeadline: Long? = null,
    /** Last button press (epoch ms): lights the "pressed" indicator. */
    val pressedAt: Long = 0,
    /** Last command a device confirmed (epoch ms): lights the "sent" indicator. */
    val sentAt: Long = 0,
)

enum class Key { UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, PLAY_PAUSE, MENU }

/**
 * Everything the screen can do. Commands run one at a time, in order, off the main thread.
 * Each device is connected on first use and reconnected after a dropped connection.
 */
class RemoteController(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val ir = IrBlaster(app)
    private val discovery = CompanionDiscovery(app)
    private val registration = app.assets.open("webos_registration.json").bufferedReader().readText()

    private var atv: AppleTvClient? = null
    private var lg: WebOsClient? = null
    private var atvApps: Map<String, String> = emptyMap()
    private var lgApps: List<WebOsClient.App> = emptyList()
    private val artwork = Artwork(app)

    private val _state = MutableStateFlow(
        UiState(
            target = settings.target,
            volumeTarget = settings.volumeTarget,
            atv = if (settings.atvCredentials != null && settings.atvHost.isNotEmpty()) LinkState.OFFLINE else LinkState.NOT_SET_UP,
            lg = if (settings.lgHost.isNotEmpty()) LinkState.OFFLINE else LinkState.NOT_SET_UP,
            irAvailable = ir.available,
            background = settings.backgroundMode,
            artwork = DEFAULT_SHORTCUTS.mapNotNull { s -> Artwork(app).cached(s.label)?.let { s.label to it.path } }.toMap(),
        ),
    )
    val state: StateFlow<UiState> = _state

    /** A queued action. [signal] marks a button press, which drives the on-screen lights. */
    private class Queued(val name: String, val signal: Boolean, val action: suspend () -> Unit)

    private val queue = Channel<Queued>(capacity = 16)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (job in queue) {
                try {
                    Log.w(TAG, "run ${job.name}")
                    job.action()
                    if (job.signal) _state.update { it.copy(sentAt = System.currentTimeMillis()) }
                } catch (e: Exception) {
                    Log.w(TAG, "${job.name} failed", e)
                    say(e.message ?: "${job.name} failed")
                }
            }
        }
        // The activity calls reconnect() as soon as it starts, so the first button press is quick.
    }

    /** Connects any configured device that isn't connected. Called whenever the remote wakes up. */
    fun reconnect() = background("reconnect") {
        if (settings.atvCredentials != null && settings.atvHost.isNotEmpty() && atv?.isConnected != true) {
            runCatching { appleTv() }.onFailure { Log.w(TAG, "Apple TV: $it") }
        }
        if (settings.lgHost.isNotEmpty() && lg?.isConnected != true) {
            runCatching { lgTv() }.onFailure { Log.w(TAG, "LG TV: $it") }
        }
    }

    private fun newAppleTv(port: Int) = AppleTvClient(settings.atvHost, port, settings.identity).apply {
        onDisconnected = { _state.update { if (it.atv == LinkState.CONNECTED) it.copy(atv = LinkState.OFFLINE) else it } }
    }

    private fun newLgTv() = WebOsClient(settings.lgHost, registration).apply {
        onDisconnected = { _state.update { if (it.lg == LinkState.CONNECTED) it.copy(lg = LinkState.OFFLINE) else it } }
    }

    /** Queues a button action; the "pressed" light comes on now, "sent" when it completes. */
    private fun run(name: String, action: suspend () -> Unit) = enqueue(name, true, action)

    /** Queues background work (connecting, setup) that shouldn't flash the lights. */
    private fun background(name: String, action: suspend () -> Unit) = enqueue(name, false, action)

    private fun enqueue(name: String, signal: Boolean, action: suspend () -> Unit) {
        if (signal) _state.update { it.copy(pressedAt = System.currentTimeMillis()) }
        if (queue.trySend(Queued(name, signal, action)).isFailure) say("Busy, try again")
    }

    private fun say(text: String?) = _state.update { it.copy(message = text) }
    fun clearMessage() = say(null)

    // ---- Connections --------------------------------------------------------------------------

    private suspend fun appleTv(): AppleTvClient {
        val creds = settings.atvCredentials ?: throw AppleTvException("Pair the Apple TV in Settings first")
        if (settings.atvHost.isEmpty()) throw AppleTvException("Set the Apple TV address in Settings")
        atv?.let { if (it.isConnected) return it }
        _state.update { it.copy(atv = LinkState.CONNECTING) }
        try {
            val client = atv ?: newAppleTv(settings.atvPort).also { atv = it }
            try {
                client.connect(creds)
            } catch (e: AppleTvException) {
                // The Companion port can change after a reboot: look it up again and retry once.
                val found = discovery.scan(3000).firstOrNull { it.host == settings.atvHost } ?: throw e
                if (found.port == settings.atvPort) throw e
                settings.atvPort = found.port
                client.close()
                atv = newAppleTv(found.port).also { it.connect(creds) }
            }
            _state.update { it.copy(atv = LinkState.CONNECTED) }
            if (_state.value.artwork.size < DEFAULT_SHORTCUTS.size) fetchArtworkLater()
            return atv!!
        } catch (e: Exception) {
            _state.update { it.copy(atv = LinkState.OFFLINE) }
            throw e
        }
    }

    private suspend fun lgTv(): WebOsClient {
        if (settings.lgHost.isEmpty()) throw IllegalStateException("Set the LG TV address in Settings")
        lg?.let { if (it.isConnected) return it }
        _state.update { it.copy(lg = LinkState.CONNECTING) }
        try {
            val client = lg ?: newLgTv().also { lg = it }
            val key = client.connect(settings.lgClientKey) { say("Accept the prompt on the TV") }
            if (key != settings.lgClientKey) settings.lgClientKey = key
            if (settings.lgMac == null) runCatching { client.macAddresses().firstOrNull()?.let { settings.lgMac = it } }
            _state.update { it.copy(lg = LinkState.CONNECTED, message = null) }
            if (_state.value.artwork.size < DEFAULT_SHORTCUTS.size) fetchArtworkLater()
            return client
        } catch (e: Exception) {
            _state.update { it.copy(lg = LinkState.OFFLINE) }
            throw e
        }
    }

    /** Runs an Apple TV command, reconnecting once if the connection dropped. */
    private suspend fun withAtv(block: suspend (AppleTvClient) -> Unit) {
        try {
            block(appleTv())
        } catch (e: AppleTvException) {
            if (atv?.isConnected == true) throw e
            block(appleTv())
        }
    }

    /** Runs an LG command over Wi-Fi; if the TV can't be reached, sends [irFallback] instead. */
    private suspend fun withLg(irFallback: IrSignal?, block: suspend (WebOsClient) -> Unit) {
        val client = try {
            lgTv()
        } catch (e: Exception) {
            if (irFallback != null && ir.available) {
                ir.send(irFallback)
                return
            }
            throw e
        }
        try {
            block(client)
        } catch (e: Exception) {
            if (irFallback != null && ir.available) ir.send(irFallback) else throw e
        }
    }

    /** Queues an icon download for shortcuts that have none yet: Apple TV first, then the LG. */
    private fun fetchArtworkLater() = background("artwork") {
        val missing = DEFAULT_SHORTCUTS.filter { it.label !in _state.value.artwork }
        if (missing.isEmpty()) return@background
        val found = mutableMapOf<String, String>()
        atv?.takeIf { it.isConnected }?.let { client ->
            if (atvApps.isEmpty()) atvApps = runCatching { client.apps() }.getOrDefault(emptyMap())
            for (s in missing) {
                val bundle = s.storeBundle ?: atvBundleFor(s) ?: continue
                artwork.fromAppStore(s.label, bundle)?.let { found[s.label] = it.path }
            }
        }
        lg?.takeIf { it.isConnected }?.let { client ->
            if (lgApps.isEmpty()) lgApps = runCatching { client.apps() }.getOrDefault(emptyList())
            for (s in missing.filter { it.label !in found }) {
                val app = lgApps.firstOrNull { it.id == pick(lgApps.map { a -> a.id to a.title }, s.match) } ?: continue
                app.icon?.let { url -> artwork.fromLoader(s.label) { client.download(url) }?.let { found[s.label] = it.path } }
            }
        }
        if (found.isNotEmpty()) _state.update { it.copy(artwork = it.artwork + found) }
    }

    // ---- Buttons -------------------------------------------------------------------------------

    fun setTarget(target: Target) {
        settings.target = target
        _state.update { it.copy(target = target) }
    }

    fun setBackground(mode: BackgroundMode) {
        settings.backgroundMode = mode
        _state.update { it.copy(background = mode) }
    }

    fun setAtvTvInput(input: String) {
        settings.atvTvInput = input
    }

    fun setVolumeTarget(target: VolumeTarget) {
        settings.volumeTarget = target
        _state.update { it.copy(volumeTarget = target) }
    }

    fun key(key: Key) = run(key.name) {
        when (_state.value.target) {
            Target.APPLE_TV -> withAtv { atv ->
                when (key) {
                    Key.UP -> atv.press(HidCommand.Up)
                    Key.DOWN -> atv.press(HidCommand.Down)
                    Key.LEFT -> atv.press(HidCommand.Left)
                    Key.RIGHT -> atv.press(HidCommand.Right)
                    Key.OK -> atv.press(HidCommand.Select)
                    Key.BACK -> atv.press(HidCommand.Menu)
                    Key.HOME -> atv.press(HidCommand.Home)
                    Key.PLAY_PAUSE -> atv.press(HidCommand.PlayPause)
                    Key.MENU -> atv.press(HidCommand.Home, holdMs = 1000) // long-press Home: Control Center
                }
            }
            Target.LG_TV -> {
                val (button, code) = when (key) {
                    Key.UP -> "UP" to IrCodes.LgTv.up
                    Key.DOWN -> "DOWN" to IrCodes.LgTv.down
                    Key.LEFT -> "LEFT" to IrCodes.LgTv.left
                    Key.RIGHT -> "RIGHT" to IrCodes.LgTv.right
                    Key.OK -> "ENTER" to IrCodes.LgTv.ok
                    Key.BACK -> "BACK" to IrCodes.LgTv.back
                    Key.HOME -> "HOME" to IrCodes.LgTv.home
                    Key.PLAY_PAUSE -> "PLAY" to null
                    Key.MENU -> "MENU" to IrCodes.LgTv.settings
                }
                withLg(code) { it.button(button) }
            }
        }
    }

    // ---- Volume ----------------------------------------------------------------------------------
    //
    // Holding a volume key must never build a backlog that keeps changing the volume after the
    // key is released. Through the Apple TV this is a real press-and-hold (button down, button
    // up), so tvOS repeats it itself and stops the moment the key is released. For the other
    // targets one step is sent at a time while held, and the next only after the previous one
    // has been delivered.

    private var volumeRepeat: Job? = null
    private var volumeWatchdog: Job? = null
    private var heldVolumeCommand: HidCommand? = null

    /** Call with down=true when a volume key or button is pressed and down=false on release. */
    fun volumeHold(direction: Int, down: Boolean) {
        when (_state.value.volumeTarget) {
            VolumeTarget.APPLE_TV -> {
                val command = if (direction > 0) HidCommand.VolumeUp else HidCommand.VolumeDown
                if (down) {
                    if (heldVolumeCommand != null) return
                    heldVolumeCommand = command
                    run("volume ${if (direction > 0) "up" else "down"}") { withAtv { it.hid(command, down = true) } }
                    // Safety net: if the release is ever lost, let go after a few seconds anyway.
                    volumeWatchdog = viewModelScope.launch { delay(4000); volumeHold(direction, down = false) }
                } else {
                    val held = heldVolumeCommand ?: return
                    heldVolumeCommand = null
                    volumeWatchdog?.cancel()
                    background("volume release") { withAtv { it.hid(held, down = false) } }
                }
            }
            VolumeTarget.LG_TV, VolumeTarget.SOUNDBAR_IR -> {
                volumeRepeat?.cancel()
                if (!down) return
                volumeRepeat = viewModelScope.launch(Dispatchers.IO) {
                    var first = true
                    while (true) {
                        _state.update { it.copy(pressedAt = System.currentTimeMillis()) }
                        runCatching { volumeStep(direction) }.onFailure { say(it.message); return@launch }
                        _state.update { it.copy(sentAt = System.currentTimeMillis()) }
                        delay(if (first) 450 else 180)
                        first = false
                    }
                }
            }
        }
    }

    private suspend fun volumeStep(direction: Int) {
        when (_state.value.volumeTarget) {
            VolumeTarget.LG_TV ->
                if (direction > 0) withLg(IrCodes.LgTv.volumeUp) { it.volumeUp() }
                else withLg(IrCodes.LgTv.volumeDown) { it.volumeDown() }
            else -> ir.send(if (direction > 0) IrCodes.DenonSoundbar.volumeUp else IrCodes.DenonSoundbar.volumeDown)
        }
    }

    /**
     * Mute. tvOS has no mute command, so with the Apple TV handling volume this sends the
     * soundbar's own IR mute.
     */
    fun mute() = run("mute") {
        when (_state.value.volumeTarget) {
            VolumeTarget.LG_TV -> withLg(IrCodes.LgTv.mute) { it.setMute(!it.isMuted()) }
            VolumeTarget.APPLE_TV, VolumeTarget.SOUNDBAR_IR -> {
                if (!ir.available) throw IllegalStateException("No IR emitter on this device")
                ir.send(IrCodes.DenonSoundbar.mute)
            }
        }
    }

    private var lastPowerOn: Boolean? = null

    /** One button for both: turns everything off if it was last turned on (or the TV answers), else on. */
    fun powerToggle() {
        val tvLooksOn = lastPowerOn ?: (_state.value.lg == LinkState.CONNECTED)
        if (tvLooksOn) powerOff() else powerOn()
    }

    /** Turns everything on: wakes the Apple TV (which wakes the TV over HDMI-CEC) and the TV itself. */
    fun powerOn() = run("power on") {
        lastPowerOn = true
        val errors = mutableListOf<String>()
        if (settings.lgHost.isNotEmpty()) {
            settings.lgMac?.let { mac -> runCatching { WebOsClient.wakeOnLan(mac) } }
            if (ir.available) ir.send(IrCodes.LgTv.powerOn)
        }
        if (settings.atvCredentials != null) {
            runCatching { withAtv { it.wake() } }.onFailure { errors += "Apple TV: ${it.message}" }
        }
        say(errors.joinToString("\n").ifEmpty { null })
        switchTvToAppleTvWhenReady()
    }

    /**
     * After power-on, waits for the TV's network control to come up (it takes a few seconds after
     * the screen lights) and then selects the Apple TV's input. Runs beside the command queue so
     * buttons keep working meanwhile.
     */
    private fun switchTvToAppleTvWhenReady() {
        val input = settings.atvTvInput
        if (input.isEmpty() || settings.lgHost.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            repeat(15) { attempt ->
                val switched = runCatching { lgTv().switchInput(input); true }.getOrDefault(false)
                if (switched) {
                    Log.i(TAG, "TV switched to $input after ${attempt + 1} tries")
                    return@launch
                }
                delay(2000)
            }
            say("Couldn't switch the TV to ${input.replace('_', ' ')}")
        }
    }

    /** Turns everything off. */
    fun powerOff() = run("power off") {
        lastPowerOn = false
        val errors = mutableListOf<String>()
        if (settings.atvCredentials != null) {
            runCatching { withAtv { it.sleep() } }.onFailure { errors += "Apple TV: ${it.message}" }
        }
        if (settings.lgHost.isNotEmpty()) {
            runCatching { withLg(IrCodes.LgTv.powerOff) { it.powerOff() } }.onFailure { errors += "TV: ${it.message}" }
        }
        say(errors.joinToString("\n").ifEmpty { null })
    }

    /** Opens the TV's input picker. The LG network API has no picker, so this always uses IR. */
    fun tvInput() = run("input") {
        if (!ir.available) throw IllegalStateException("No IR emitter on this device")
        ir.send(IrCodes.LgTv.input)
    }

    fun launch(shortcut: AppShortcut) = run("open ${shortcut.label}") {
        when (_state.value.target) {
            Target.APPLE_TV -> withAtv { atv ->
                if (atvApps.isEmpty()) atvApps = atv.apps()
                val bundle = atvBundleFor(shortcut)
                    ?: throw IllegalStateException("${shortcut.label} isn't installed on the Apple TV")
                atv.launchApp(bundle)
            }
            Target.LG_TV -> withLg(null) { tv ->
                if (lgApps.isEmpty()) lgApps = tv.apps()
                val id = pick(lgApps.map { it.id to it.title }, shortcut.match)
                    ?: throw IllegalStateException("${shortcut.label} isn't installed on the TV")
                tv.launch(id)
            }
        }
    }

    /** The Apple TV bundle id for a shortcut: its fixed id if set (and installed), else by name. */
    private fun atvBundleFor(shortcut: AppShortcut): String? =
        shortcut.atvBundle?.takeIf { atvApps.isEmpty() || it in atvApps }
            ?: pick(atvApps.entries.map { it.key to it.value }, shortcut.match)

    /** Picks the app whose name equals [match], else the shortest name containing it. */
    private fun pick(apps: List<Pair<String, String>>, match: String): String? =
        apps.firstOrNull { it.second.equals(match, true) }?.first
            ?: apps.filter { it.second.contains(match, true) }.minByOrNull { it.second.length }?.first

    // ---- Setup ---------------------------------------------------------------------------------

    fun findAppleTvs() = background("find Apple TVs") {
        _state.update { it.copy(busy = true, atvFound = emptyList()) }
        try {
            val found = discovery.scan()
            _state.update { it.copy(atvFound = found) }
            if (found.isEmpty()) say("No Apple TV found. Is it on and on the same Wi-Fi?")
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun chooseAppleTv(found: CompanionDiscovery.Found) {
        if (found.host != settings.atvHost) {
            settings.atvCredentials = null
            atv?.close(); atv = null
        }
        settings.atvHost = found.host
        settings.atvPort = found.port
        settings.atvName = found.name
        _state.update { it.copy(atv = LinkState.NOT_SET_UP, atvFound = emptyList()) }
    }

    fun setAppleTvAddress(host: String) {
        if (host.trim() == settings.atvHost) return
        settings.atvHost = host
        settings.atvCredentials = null
        atv?.close(); atv = null
        _state.update { it.copy(atv = LinkState.NOT_SET_UP) }
    }

    fun startAppleTvPairing() = background("pair Apple TV") {
        if (settings.atvHost.isEmpty()) throw IllegalStateException("Find or enter the Apple TV first")
        atv?.close()
        val client = newAppleTv(settings.atvPort)
        atv = client
        _state.update { it.copy(busy = true) }
        try {
            client.beginPairing()
            _state.update {
                it.copy(
                    awaitingAtvPin = true,
                    pairingDeadline = System.currentTimeMillis() + PAIRING_WINDOW_MS,
                    message = "Enter the code shown on the TV",
                )
            }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun finishAppleTvPairing(pin: String) = background("pair Apple TV") {
        val client = atv ?: throw IllegalStateException("Start pairing again")
        _state.update { it.copy(awaitingAtvPin = false, pairingDeadline = null, busy = true) }
        try {
            settings.atvCredentials = client.finishPairing(pin)
            atvApps = emptyMap()
            say("Apple TV paired")
            appleTv()
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun cancelAppleTvPairing() {
        atv?.close(); atv = null
        _state.update { it.copy(awaitingAtvPin = false, pairingDeadline = null, message = null) }
    }

    fun findLgTvs() = background("find LG TVs") {
        _state.update { it.copy(busy = true, lgFound = emptyList()) }
        try {
            val found = WebOsDiscovery.scan()
            _state.update { it.copy(lgFound = found) }
            if (found.isEmpty()) say("No LG TV found. Is it on and on the same Wi-Fi?")
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun setLgAddress(host: String) {
        if (host.trim() == settings.lgHost) return
        settings.lgHost = host
        settings.lgClientKey = null
        settings.lgMac = null
        lg?.close(); lg = null
        lgApps = emptyList()
        _state.update { it.copy(lg = if (host.isBlank()) LinkState.NOT_SET_UP else LinkState.OFFLINE, lgFound = emptyList()) }
    }

    fun connectLg() = background("connect LG TV") {
        _state.update { it.copy(busy = true) }
        try {
            lgTv()
            say("LG TV connected")
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun testIr(signal: IrSignal) = run("IR") { ir.send(signal) }

    override fun onCleared() {
        atv?.close()
        lg?.close()
    }

    companion object {
        private const val TAG = "Clicker"

        /** tvOS closes a pair-setup connection about 30 seconds after showing the code. */
        const val PAIRING_WINDOW_MS = 28_000L
    }
}
