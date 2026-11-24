package dev.clicker.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.clicker.remote.RemoteController
import dev.clicker.remote.help.HelpAlerts
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Big buttons that send a request to someone's phone. One tap starts a short countdown with a
 * Cancel button, so a brush against the screen can be undone; then it sends by itself.
 */
@Composable
fun HelpScreen(controller: RemoteController) {
    val help = controller.help
    val alert by help.alert.collectAsState()
    // The request counting down to send, and the seconds left.
    var armed by remember { mutableStateOf<String?>(null) }
    var secondsLeft by remember { mutableStateOf(0) }
    var custom by remember { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val who = help.recipient.ifEmpty { "them" }

    LaunchedEffect(armed) {
        val request = armed ?: return@LaunchedEffect
        for (s in COUNTDOWN_SECONDS downTo 1) { secondsLeft = s; delay(1000) }
        armed = null
        help.send(request)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (help.recipient.isNotEmpty()) "Ask ${help.recipient}" else "Ask for help",
            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
        )
        if (!help.configured) {
            Text(
                "Help alerts aren't set up yet. Add the Pushover keys in Settings → Help alerts.",
                color = Color(0xFFFFD54F), fontSize = 14.sp,
            )
        }

        armed?.let { request ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xFFE65100)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Sending “${HelpAlerts.label(request)}” in $secondsLeft…", Modifier.weight(1f),
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = { armed = null }) { Text("Cancel", color = Color.White, fontSize = 16.sp) }
                }
            }
        } ?: alert?.let { a -> StatusCard(a, who, onCancel = help::cancel, onDismiss = help::dismiss) }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Urgent requests come first and span the full width.
            val ordered = help.requests.sortedByDescending { HelpAlerts.isUrgent(it) }
            items(ordered, span = { if (HelpAlerts.isUrgent(it)) GridItemSpan(2) else GridItemSpan(1) }) { request ->
                val urgent = HelpAlerts.isUrgent(request)
                val isArmed = armed == request
                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        armed = request
                    },
                    modifier = Modifier.fillMaxWidth().height(104.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = when {
                        isArmed -> Color(0xFFE65100)
                        urgent -> Color(0xFFC62828)
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    },
                ) {
                    Column(
                        Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(iconFor(request), null, Modifier.size(if (urgent) 40.dp else 34.dp), tint = Color.White)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            HelpAlerts.label(request),
                            fontSize = if (urgent) 20.sp else 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                            textAlign = TextAlign.Center, maxLines = 2,
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = custom, onValueChange = { custom = it },
                modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Something else…") },
            )
            IconButton(onClick = {
                if (custom.isNotBlank()) { help.send(custom.trim()); custom = ""; keyboard?.hide() }
            }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}

@Composable
private fun StatusCard(alert: HelpAlerts.Alert, who: String, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val time = { ms: Long -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms)) }
    val (color, icon, line) = when (alert.stage) {
        HelpAlerts.Stage.SENDING -> Triple(Color(0xFF455A64), null, "Sending “${alert.text}”…")
        HelpAlerts.Stage.WAITING -> Triple(Color(0xFF1565C0), Icons.Default.NotificationsActive,
            "Sent “${alert.text}” at ${time(alert.sentAt)}. Alerting $who until it's seen.")
        HelpAlerts.Stage.SEEN -> Triple(Color(0xFF2E7D32), Icons.Default.CheckCircle,
            "${who.replaceFirstChar { it.uppercase() }} saw “${alert.text}” at ${time(alert.seenAt ?: alert.sentAt)}.")
        HelpAlerts.Stage.FAILED -> Triple(Color(0xFFB71C1C), Icons.Default.ErrorOutline, alert.error ?: "Couldn't send.")
        HelpAlerts.Stage.CANCELLED -> Triple(Color(0xFF455A64), null, "Cancelled “${alert.text}”.")
        HelpAlerts.Stage.EXPIRED -> Triple(Color(0xFFB71C1C), Icons.Default.ErrorOutline,
            "No one answered “${alert.text}” within an hour.")
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = color) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(icon, null, tint = Color.White)
                else if (alert.stage == HelpAlerts.Stage.SENDING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            }
            Text(line, Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White, fontSize = 14.sp)
            if (alert.stage == HelpAlerts.Stage.WAITING) TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
            else if (alert.stage != HelpAlerts.Stage.SENDING) TextButton(onClick = onDismiss) { Text("OK", color = Color.White) }
        }
    }
}

private const val COUNTDOWN_SECONDS = 3

private fun iconFor(request: String): ImageVector {
    val r = request.lowercase()
    return when {
        "water" in r || "drink" in r -> Icons.Default.LocalDrink
        "coffee" in r || "tea" in r -> Icons.Default.Coffee
        "air" in r || "oxygen" in r || "machine" in r -> Icons.Default.Air
        "food" in r || "eat" in r || "snack" in r -> Icons.Default.Restaurant
        "come" in r || "here" in r -> Icons.Default.Person
        else -> Icons.AutoMirrored.Filled.Chat
    }
}
