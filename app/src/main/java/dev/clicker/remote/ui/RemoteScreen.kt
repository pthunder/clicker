package dev.clicker.remote.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.clicker.remote.AppShortcut
import dev.clicker.remote.DEFAULT_SHORTCUTS
import dev.clicker.remote.Key
import dev.clicker.remote.LinkState
import dev.clicker.remote.RemoteController
import dev.clicker.remote.Target
import kotlinx.coroutines.delay

/**
 * Main screen. Apps come first; the D-pad, Back/Home/Play and volume are folded away behind
 * "Controls" because the remote's physical keys already do those jobs.
 */
@Composable
fun RemoteScreen(controller: RemoteController, onSettings: () -> Unit) {
    val state by controller.state.collectAsState()
    var showControls by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(5000)
            controller.clearMessage()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Which device the controls and app tiles drive
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                listOf(Target.APPLE_TV to "Apple TV", Target.LG_TV to "LG TV").forEachIndexed { i, (target, label) ->
                    val link = if (target == Target.APPLE_TV) state.atv else state.lg
                    SegmentedButton(
                        selected = state.target == target,
                        onClick = { controller.setTarget(target) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = { StatusDot(link) },
                    ) { Text(label, fontSize = 14.sp) }
                }
            }
            SignalLights(state.pressedAt, state.sentAt)
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("On", Icons.Default.PowerSettingsNew, Modifier.weight(1f), tint = Color(0xFF66BB6A)) { controller.powerOn() }
            PillButton("Off", Icons.Default.PowerSettingsNew, Modifier.weight(1f), tint = Color(0xFFEF9A9A)) { controller.powerOff() }
            PillButton("Input", Icons.Default.Input, Modifier.weight(1f)) { controller.tvInput() }
        }

        AnimatedContent(showControls, Modifier.weight(1f), label = "main") { controls ->
            if (controls) {
                Controls(controller)
            } else {
                AppGrid(state.artwork, onLaunch = controller::launch)
            }
        }

        if (state.message != null) {
            Text(
                state.message ?: "",
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xAA000000)).padding(6.dp),
                color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3,
            )
        }

        PillButton(
            if (showControls) "Apps" else "Controls",
            if (showControls) Icons.Default.Apps else Icons.Default.Gamepad,
            Modifier.fillMaxWidth(0.6f),
            height = 40.dp,
        ) { showControls = !showControls }
    }
}

@Composable
private fun AppGrid(artwork: Map<String, String>, onLaunch: (AppShortcut) -> Unit) {
    // Two columns for up to four apps, three columns beyond that.
    val columns = if (DEFAULT_SHORTCUTS.size <= 4) 2 else 3
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        DEFAULT_SHORTCUTS.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { app -> AppTile(app, artwork[app.label], Modifier.weight(1f)) { onLaunch(app) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppTile(app: AppShortcut, iconPath: String?, modifier: Modifier, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val bitmap = remember(iconPath) { iconPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shadowElevation = 6.dp,
        ) {
            if (bitmap != null) {
                Image(bitmap, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(app.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }
        Text(
            app.label, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = Color.White,
            fontWeight = FontWeight.Medium, maxLines = 1,
        )
    }
}

@Composable
private fun Controls(controller: RemoteController) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        DPad(onKey = controller::key)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Back", Icons.AutoMirrored.Filled.ArrowBack, Modifier.weight(1f)) { controller.key(Key.BACK) }
            PillButton("Home", Icons.Default.Home, Modifier.weight(1f)) { controller.key(Key.HOME) }
            PillButton("Play", Icons.Default.PlayArrow, Modifier.weight(1f)) { controller.key(Key.PLAY_PAUSE) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldButton("Vol", Icons.Default.Remove, Modifier.weight(1f)) { down -> controller.volumeHold(-1, down) }
            PillButton("Mute", Icons.AutoMirrored.Filled.VolumeOff, Modifier.weight(0.8f), height = 52.dp) { controller.mute() }
            HoldButton("Vol", Icons.Default.Add, Modifier.weight(1f)) { down -> controller.volumeHold(+1, down) }
        }
    }
}

@Composable
fun StatusDot(state: LinkState) {
    val color = when (state) {
        LinkState.CONNECTED -> Color(0xFF4CAF50)
        LinkState.CONNECTING -> Color(0xFFFFC107)
        LinkState.OFFLINE -> Color(0xFFE57373)
        LinkState.NOT_SET_UP -> Color.Gray
    }
    Box(Modifier.requiredSize(9.dp).clip(CircleShape).background(color))
}

/**
 * Two small lights: amber the moment a button is pressed, green when the device confirms the
 * command. A gap between them shows network lag.
 */
@Composable
private fun SignalLights(pressedAt: Long, sentAt: Long) {
    var pressedLit by remember { mutableStateOf(false) }
    var sentLit by remember { mutableStateOf(false) }
    LaunchedEffect(pressedAt) {
        if (pressedAt == 0L) return@LaunchedEffect
        pressedLit = true
        delay(250)
        pressedLit = false
    }
    LaunchedEffect(sentAt) {
        if (sentAt == 0L) return@LaunchedEffect
        sentLit = true
        delay(250)
        sentLit = false
    }
    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Light(pressedLit, Color(0xFFFFB300))
        Light(sentLit, Color(0xFF66BB6A))
    }
}

@Composable
private fun Light(on: Boolean, color: Color) {
    Box(
        Modifier.requiredSize(10.dp).clip(CircleShape)
            .background(if (on) color else color.copy(alpha = 0.18f)),
    )
}

/** A button that reports press and release separately, for press-and-hold volume. */
@Composable
private fun HoldButton(label: String, icon: ImageVector, modifier: Modifier, onHold: (down: Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.height(52.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                pressed = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onHold(true)
                tryAwaitRelease()
                onHold(false)
                pressed = false
            })
        },
        shape = RoundedCornerShape(22.dp),
        color = if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(4.dp))
            Text(label, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        modifier = modifier.height(height),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(4.dp))
            Text(label, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DPad(onKey: (Key) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val press = { key: Key -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); onKey(key) }
    Surface(Modifier.size(210.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            ArrowButton(Icons.Default.KeyboardArrowUp, "Up") { press(Key.UP) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArrowButton(Icons.Default.KeyboardArrowLeft, "Left") { press(Key.LEFT) }
                Surface(
                    onClick = { press(Key.OK) },
                    modifier = Modifier.size(78.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("OK", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                ArrowButton(Icons.Default.KeyboardArrowRight, "Right") { press(Key.RIGHT) }
            }
            ArrowButton(Icons.Default.KeyboardArrowDown, "Down") { press(Key.DOWN) }
        }
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(66.dp)) {
        Icon(icon, label, Modifier.size(40.dp))
    }
}
