package dev.clicker.remote.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** The battery level (0-100) and whether the device is on a charger. */
data class Charge(val level: Int, val plugged: Boolean)

/**
 * Follows the battery. [plugIns] counts the moments a charger is connected after the screen
 * started (not a remote that was already docked), which is when the animation plays.
 */
class ChargeWatch {
    var charge by mutableStateOf(Charge(0, false))
    var plugIns by mutableIntStateOf(0)
}

@Composable
fun rememberChargeWatch(): ChargeWatch {
    val context = LocalContext.current
    val watch = remember { ChargeWatch() }
    DisposableEffect(context) {
        var known = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                val wasPlugged = watch.charge.plugged
                watch.charge = Charge(if (level >= 0) level * 100 / scale else watch.charge.level, plugged)
                if (known && plugged && !wasPlugged) watch.plugIns++
                known = true
            }
        }
        // Battery broadcasts are sticky: registering delivers the current state straight away.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return watch
}

/**
 * Full-screen charging animation: a glass battery, tilting in 3D, filling with glowing liquid to
 * the current level, with rising bubbles and a pulsing bolt. Plays for a few seconds when a
 * charger is connected; a tap dismisses it.
 */
@Composable
fun ChargingOverlay(watch: ChargeWatch) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(watch.plugIns) {
        if (watch.plugIns == 0) return@LaunchedEffect
        visible = true
        delay(SHOW_MS)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(tween(350)) + scaleIn(tween(500), initialScale = 0.85f), exit = fadeOut(tween(600))) {
        Box(
            Modifier.fillMaxSize().background(Color(0xF2050A10)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { visible = false },
            contentAlignment = Alignment.Center,
        ) {
            ChargingBattery(watch.charge.level)
        }
    }
}

@Composable
private fun ChargingBattery(level: Int) {
    // A running clock in seconds drives every motion.
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1_000_000_000f }
    }
    // The liquid rises from empty to the real level when the animation starts.
    val fill = (level / 100f) * (time / 1.6f).coerceAtMost(1f).let { 1 - (1 - it) * (1 - it) * (1 - it) }
    val liquid = when {
        level < 20 -> listOf(Color(0xFFFF8A65), Color(0xFFE53935), Color(0xFF8E1B1B))
        level < 50 -> listOf(Color(0xFFFFE082), Color(0xFFFFB300), Color(0xFF8F5A00))
        else -> listOf(Color(0xFFB9F6CA), Color(0xFF00E676), Color(0xFF00702F))
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Canvas(
            Modifier.size(width = 220.dp, height = 330.dp).graphicsLayer {
                // A slow sway around the vertical axis with real perspective gives the 3D look.
                rotationY = sin(time * 0.9f) * 24f
                rotationX = 10f + sin(time * 0.6f) * 4f
                cameraDistance = 14f * density
            },
        ) {
            drawBattery(fill, liquid, time)
        }
        Text("${level}%", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text(if (level >= 100) "Charged" else "Charging", color = liquid[1], fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

private fun DrawScope.drawBattery(fill: Float, liquid: List<Color>, time: Float) {
    val w = size.width
    val h = size.height
    val bodyLeft = w * 0.16f
    val bodyRight = w * 0.84f
    val bodyWidth = bodyRight - bodyLeft
    val ellipseH = bodyWidth * 0.26f // depth of the round top and bottom
    val top = h * 0.12f
    val bottom = h - ellipseH / 2 - 4f
    val pulse = 0.5f + 0.5f * sin(time * 2.4f)

    // Glow behind the battery, breathing with the charge.
    drawCircle(
        Brush.radialGradient(listOf(liquid[1].copy(alpha = 0.32f + 0.18f * pulse), Color.Transparent), center = Offset(w / 2, h * 0.58f), radius = w * 0.95f),
        radius = w * 0.95f, center = Offset(w / 2, h * 0.58f),
    )

    // The glass cylinder, as a clip for everything inside it.
    val body = Path().apply {
        addRect(Rect(bodyLeft, top, bodyRight, bottom))
        addOval(Rect(bodyLeft, bottom - ellipseH / 2, bodyRight, bottom + ellipseH / 2))
        addOval(Rect(bodyLeft, top - ellipseH / 2, bodyRight, top + ellipseH / 2))
    }
    drawPath(body, Brush.horizontalGradient(listOf(Color(0x33FFFFFF), Color(0x0FFFFFFF), Color(0x2AFFFFFF)), bodyLeft, bodyRight))

    // Liquid: a shaded column with a moving wavy surface.
    val surface = bottom - (bottom - top) * fill
    if (fill > 0.005f) {
        clipPath(body) {
            val shade = Brush.horizontalGradient(listOf(liquid[2], liquid[1], liquid[0], liquid[1], liquid[2]), bodyLeft, bodyRight)
            drawRect(shade, topLeft = Offset(bodyLeft, surface), size = Size(bodyWidth, bottom - surface + ellipseH))
            drawOval(shade, topLeft = Offset(bodyLeft, bottom - ellipseH / 2), size = Size(bodyWidth, ellipseH))

            // The surface ellipse, rippling.
            val wave = Path()
            val steps = 48
            for (i in 0..steps) {
                val a = 2 * PI * i / steps
                val x = w / 2 + (bodyWidth / 2) * kotlin.math.cos(a).toFloat()
                val ripple = sin(a.toFloat() * 3 + time * 3.2f) * ellipseH * 0.08f
                val y = surface + (ellipseH / 2) * sin(a).toFloat() + ripple
                if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
            }
            wave.close()
            drawPath(wave, Brush.verticalGradient(listOf(liquid[0], liquid[1]), surface - ellipseH / 2, surface + ellipseH / 2))

            // Bubbles rising through the liquid.
            for (i in 0 until 14) {
                val seed = i * 0.6180339f
                val speed = 0.18f + (seed * 7 % 1f) * 0.25f
                val progress = ((time * speed + seed) % 1f)
                val y = bottom - progress * (bottom - surface)
                val x = bodyLeft + bodyWidth * (0.15f + (seed * 13 % 1f) * 0.7f) + sin(time * 2 + i) * 6f
                val r = 2.5f + (seed * 5 % 1f) * 5f
                drawCircle(Color.White.copy(alpha = 0.35f * (1 - progress)), r, Offset(x, y))
            }
        }
    }

    // Glass rims and a highlight for depth.
    drawOval(Color.White.copy(alpha = 0.55f), topLeft = Offset(bodyLeft, top - ellipseH / 2), size = Size(bodyWidth, ellipseH), style = Stroke(2.5f))
    drawArc(Color.White.copy(alpha = 0.4f), 0f, 180f, false, Offset(bodyLeft, bottom - ellipseH / 2), Size(bodyWidth, ellipseH), style = Stroke(2.5f))
    drawLine(Color.White.copy(alpha = 0.4f), Offset(bodyLeft, top), Offset(bodyLeft, bottom), 2.5f)
    drawLine(Color.White.copy(alpha = 0.4f), Offset(bodyRight, top), Offset(bodyRight, bottom), 2.5f)
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent), bodyLeft + bodyWidth * 0.12f, bodyLeft + bodyWidth * 0.26f),
        topLeft = Offset(bodyLeft + bodyWidth * 0.12f, top + ellipseH * 0.4f), size = Size(bodyWidth * 0.14f, bottom - top - ellipseH * 0.6f),
    )

    // The terminal cap: a small metal cylinder on top.
    val capW = bodyWidth * 0.36f
    val capLeft = w / 2 - capW / 2
    val capH = h * 0.05f
    val capE = capW * 0.26f
    val metal = Brush.horizontalGradient(listOf(Color(0xFF5B6470), Color(0xFFE3E8EE), Color(0xFF6B7480)), capLeft, capLeft + capW)
    drawRect(metal, Offset(capLeft, top - ellipseH / 2 - capH), Size(capW, capH))
    drawOval(metal, Offset(capLeft, top - ellipseH / 2 - capE / 2), Size(capW, capE))
    drawOval(Color(0xFFF2F5F8), Offset(capLeft, top - ellipseH / 2 - capH - capE / 2), Size(capW, capE))

    // A lightning bolt in the middle, pulsing.
    val cx = w / 2
    val cy = (top + bottom) / 2
    val s = bodyWidth * (0.30f + 0.03f * pulse)
    val bolt = Path().apply {
        moveTo(cx + s * 0.10f, cy - s * 0.62f)
        lineTo(cx - s * 0.32f, cy + s * 0.06f)
        lineTo(cx - s * 0.02f, cy + s * 0.06f)
        lineTo(cx - s * 0.12f, cy + s * 0.62f)
        lineTo(cx + s * 0.32f, cy - s * 0.08f)
        lineTo(cx + s * 0.02f, cy - s * 0.08f)
        close()
    }
    drawPath(bolt, Color.White.copy(alpha = 0.25f + 0.2f * pulse), style = Stroke(10f))
    drawPath(bolt, Color.White.copy(alpha = 0.85f + 0.15f * pulse))
}

private const val SHOW_MS = 6_000L
