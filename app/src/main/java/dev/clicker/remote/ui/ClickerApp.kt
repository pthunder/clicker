package dev.clicker.remote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.clicker.remote.BackgroundMode
import dev.clicker.remote.RemoteController

private val colors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1E3A),
    // Translucent so a personal photo or video background shows through the buttons.
    secondaryContainer = Color(0xC02B3442),
    surface = Color(0xFF111316),
    background = Color(0xFF111316),
    surfaceVariant = Color(0xB822262C),
)

private const val FADE_MS = 1200

@Composable
fun ClickerApp(
    controller: RemoteController,
    showSettings: Boolean,
    onShowSettings: (Boolean) -> Unit,
    lastKey: String?,
    introRun: Int = 0,
) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val video = remember { BackgroundFiles.video(context) }
    // The intro plays when the app opens and whenever [introRun] changes (not when coming back
    // from Settings).
    var introDone by rememberSaveable(introRun) { mutableStateOf(false) }
    // Triple-tapping an empty part of the background replays the intro on request.
    var requested by remember { mutableStateOf(false) }
    var taps by remember { mutableStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }
    val playIntro = video != null && !introDone && (state.background == BackgroundMode.VIDEO || requested)
    val finishIntro = { introDone = true; requested = false }

    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (showSettings) {
                SettingsScreen(controller, lastKey, onBack = { onShowSettings(false) })
            } else {
                Box(
                    Modifier.fillMaxSize().pointerInput(video) {
                        // Only taps that no button or tile used reach here.
                        detectTapGestures(onTap = {
                            val now = System.currentTimeMillis()
                            taps = if (now - lastTapAt < 600) taps + 1 else 1
                            lastTapAt = now
                            if (taps >= 3 && video != null) {
                                taps = 0
                                requested = true
                                introDone = false
                            }
                        })
                    },
                ) {
                    PhotoBackground(enabled = state.background != BackgroundMode.NONE)
                    AnimatedVisibility(!playIntro, enter = fadeIn(tween(FADE_MS)), exit = fadeOut(tween(200))) {
                        RemoteScreen(controller, onSettings = { onShowSettings(true) })
                    }
                    AnimatedVisibility(playIntro, enter = fadeIn(tween(0)), exit = fadeOut(tween(FADE_MS))) {
                        Box(
                            Modifier.fillMaxSize().clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { finishIntro() }, // tap to skip
                        ) {
                            if (video != null) IntroVideo(video) { finishIntro() }
                        }
                    }
                }
            }
        }
    }
}
