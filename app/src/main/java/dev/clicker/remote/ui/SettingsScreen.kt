package dev.clicker.remote.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import dev.clicker.remote.BackgroundMode
import dev.clicker.remote.LinkState
import dev.clicker.remote.RemoteController
import dev.clicker.remote.VolumeTarget
import dev.clicker.remote.ir.IrCodes

@Composable
fun SettingsScreen(controller: RemoteController, lastKey: String?, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val settings = controller.settings
    var atvHost by remember { mutableStateOf(settings.atvHost) }
    var lgHost by remember { mutableStateOf(settings.lgHost) }
    var pin by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }

        // ---- Apple TV
        Section("Apple TV", state.atv)
        if (settings.atvName.isNotEmpty()) Text(settings.atvName, fontSize = 13.sp)
        OutlinedTextField(
            value = atvHost, onValueChange = { atvHost = it }, label = { Text("Address") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::findAppleTvs) { Text("Find") }
            Button(onClick = { controller.setAppleTvAddress(atvHost); controller.startAppleTvPairing() }) { Text("Pair") }
        }
        state.atvFound.forEach { found ->
            TextButton(onClick = { atvHost = found.host; controller.chooseAppleTv(found) }) { Text("${found.name}  ${found.host}") }
        }
        Text("Pairing shows a 4-digit code on the TV. Enter it within 30 seconds.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()

        // ---- LG TV
        Section("LG TV", state.lg)
        OutlinedTextField(
            value = lgHost, onValueChange = { lgHost = it }, label = { Text("Address") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::findLgTvs) { Text("Find") }
            Button(onClick = { controller.setLgAddress(lgHost); controller.connectLg() }) { Text("Connect") }
        }
        state.lgFound.forEach { found ->
            TextButton(onClick = { lgHost = found.host; controller.setLgAddress(found.host) }) { Text("${found.name}  ${found.host}") }
        }
        Text(
            "First connection shows an \"allow\" prompt on the TV. Without Wi-Fi the remote falls back to IR." +
                (settings.lgMac?.let { "\nMAC $it" } ?: ""),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // ---- Input switching
        Text("After On, switch the TV to the Apple TV's input", fontWeight = FontWeight.SemiBold)
        var tvInput by remember { mutableStateOf(settings.atvTvInput) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("HDMI_1" to "1", "HDMI_2" to "2", "HDMI_3" to "3", "HDMI_4" to "4", "" to "Off").forEach { (id, label) ->
                RadioButton(selected = tvInput == id, onClick = { tvInput = id; controller.setAtvTvInput(id) })
                Text(label, fontSize = 13.sp)
            }
        }

        HorizontalDivider()

        // ---- Volume
        Text("Volume buttons control", fontWeight = FontWeight.SemiBold)
        VolumeTarget.entries.forEach { target ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.volumeTarget == target, onClick = { controller.setVolumeTarget(target) })
                Text(target.label, fontSize = 14.sp)
            }
        }

        HorizontalDivider()

        // ---- Background
        Text("Background", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackgroundMode.entries.forEach { mode ->
                RadioButton(selected = state.background == mode, onClick = { controller.setBackground(mode) })
                Text(mode.label, fontSize = 14.sp)
            }
        }
        Text(
            "Copy background.jpg and/or background.mp4 to\n${BackgroundFiles.dir(LocalContext.current)?.path ?: "(storage unavailable)"}",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // ---- Show list backup
        val scope = rememberCoroutineScope()
        val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                runCatching { controller.shows.restore(uri) }
                    .onSuccess { controller.notify("Restored $it shows") }
                    .onFailure { controller.notify(it.message ?: "Couldn't restore that file") }
            }
        }
        Text("Show list", fontWeight = FontWeight.SemiBold)
        Text(
            "A copy is saved after every change to Documents/Clicker/clicker-shows.json on this remote. " +
                "It survives reinstalling the app.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
            Text("Restore from a backup…")
        }
        var tmdb by remember { mutableStateOf(controller.shows.tmdbToken.orEmpty()) }
        OutlinedTextField(
            value = tmdb, onValueChange = { tmdb = it; controller.shows.tmdbToken = it },
            label = { Text("TMDB API read access token (for Trending)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Free from themoviedb.org → Settings → API. Stays on this remote.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // ---- Help alerts
        val help = controller.help
        Text("Help alerts (Pushover)", fontWeight = FontWeight.SemiBold)
        var pushToken by remember { mutableStateOf(help.appToken.orEmpty()) }
        var pushUser by remember { mutableStateOf(help.userKey.orEmpty()) }
        var sender by remember { mutableStateOf(help.sender) }
        var recipient by remember { mutableStateOf(help.recipient) }
        var requests by remember { mutableStateOf(help.requests.joinToString("\n")) }
        OutlinedTextField(pushToken, { pushToken = it; help.appToken = it }, label = { Text("Application API token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pushUser, { pushUser = it; help.userKey = it }, label = { Text("User key (whose phone)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(sender, { sender = it; help.sender = it }, label = { Text("From") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(recipient, { recipient = it; help.recipient = it }, label = { Text("To") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            requests, { requests = it; help.requests = it.lines() },
            label = { Text("Buttons, one per line") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
        )
        Text(
            "Alerts repeat every 30 seconds until acknowledged in the Pushover app. Not a medical alert system.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // ---- IR test
        Text("IR test" + if (!state.irAvailable) " (no IR emitter found)" else "", fontWeight = FontWeight.SemiBold)
        Text("Denon soundbar", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { controller.testIr(IrCodes.DenonSoundbar.power) }) { Text("Power") }
            OutlinedButton(onClick = { controller.testIr(IrCodes.DenonSoundbar.volumeDown) }) { Text("Vol−") }
            OutlinedButton(onClick = { controller.testIr(IrCodes.DenonSoundbar.volumeUp) }) { Text("Vol+") }
        }
        Text("LG TV", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { controller.testIr(IrCodes.LgTv.power) }) { Text("Power") }
            OutlinedButton(onClick = { controller.testIr(IrCodes.LgTv.volumeDown) }) { Text("Vol−") }
            OutlinedButton(onClick = { controller.testIr(IrCodes.LgTv.volumeUp) }) { Text("Vol+") }
        }

        HorizontalDivider()

        // ---- Other apps (Clicker can be the home screen, so keep a way out)
        val context = LocalContext.current
        Text("Other apps", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                context.packageManager.getLaunchIntentForPackage("com.cantata.remote")?.let { context.startActivity(it) }
            }) { Text("Haptique app") }
            OutlinedButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
            }) { Text("Android settings") }
        }

        HorizontalDivider()

        Text("Hardware key tester", fontWeight = FontWeight.SemiBold)
        Text("Press a key on the remote: ${lastKey ?: "—"}", fontSize = 13.sp)

        HorizontalDivider()
        Text(
            "Clicker 0.1.0 · open source (MIT). Apple TV support follows pyatv; LG support follows aiowebostv.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.awaitingAtvPin) {
        var secondsLeft by remember { mutableStateOf(30) }
        LaunchedEffect(state.pairingDeadline) {
            val deadline = state.pairingDeadline ?: return@LaunchedEffect
            while (true) {
                secondsLeft = ((deadline - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
                if (secondsLeft == 0) break
                delay(250)
            }
        }
        AlertDialog(
            onDismissRequest = { controller.cancelAppleTvPairing(); pin = "" },
            title = { Text("Code on the TV") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(4) }, singleLine = true,
                        label = { Text("4 digits") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    if (secondsLeft > 0) {
                        Text("Enter it within $secondsLeft s", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Time ran out. Cancel and tap Pair again.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(enabled = pin.length == 4, onClick = { controller.finishAppleTvPairing(pin); pin = "" }) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { controller.cancelAppleTvPairing(); pin = "" }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, link: LinkState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        StatusDot(link)
        Text(
            when (link) {
                LinkState.CONNECTED -> "connected"
                LinkState.CONNECTING -> "connecting…"
                LinkState.OFFLINE -> "not reachable"
                LinkState.NOT_SET_UP -> "not set up"
            },
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
