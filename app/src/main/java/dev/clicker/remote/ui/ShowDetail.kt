package dev.clicker.remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.clicker.remote.RemoteController
import dev.clicker.remote.shows.AppleTvLinks
import dev.clicker.remote.shows.Show
import dev.clicker.remote.shows.ShowLogic
import dev.clicker.remote.shows.StreamingApps
import dev.clicker.remote.shows.TvMaze
import kotlinx.coroutines.launch

/**
 * One show: poster, where it streams, what it's about, and its episodes. For a followed show the
 * new episodes are listed so the household can tick off what they've seen; for any other show
 * there's a Follow button.
 */
@Composable
fun ShowDetail(id: Int, controller: RemoteController, person: String?, people: List<String>, onBack: () -> Unit) {
    val shows by controller.shows.shows.collectAsState(initial = null)
    val followed = shows?.firstOrNull { it.id == id }
    var details by remember(id) { mutableStateOf<TvMaze.Details?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showAllEpisodes by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(id) {
        runCatching { controller.shows.detail(id) }
            .onSuccess { details = it }
            .onFailure { error = "Couldn't load this show. Is the remote online?" }
    }

    val d = details
    // What the screen knows about the show: the stored row if followed, else what was just fetched.
    val show = followed ?: d?.let {
        Show(id = id, name = it.found.name, posterUrl = it.found.posterUrl, network = it.found.network, status = it.found.status)
    }
    val app = show?.let { StreamingApps.appFor(it) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(
                show?.name ?: "", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (followed != null) IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
        }

        if (show == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error != null) Text(error!!, color = Color(0xFFEF9A9A)) else CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "top") {
                Row(verticalAlignment = Alignment.Top) {
                    Poster(id, show.posterUrl, width = 110.dp, height = 158.dp) { controller.shows.poster(show) }
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOfNotNull(show.network, d?.found?.year, show.status.takeIf { it.isNotEmpty() })
                            .joinToString(" · ").takeIf { it.isNotEmpty() }
                            ?.let { Text(it, fontSize = 13.sp, color = Color(0xCCFFFFFF)) }
                        d?.genres?.takeIf { it.isNotEmpty() }?.let { Text(it.take(3).joinToString(", "), fontSize = 12.sp, color = Color(0x99FFFFFF)) }
                        if (followed != null) {
                            Text(ShowLogic.headline(followed), fontSize = 14.sp, color = Color(0xFFFFD54F))
                            Text(watchedLine(followed), fontSize = 12.sp, color = Color(0xCCFFFFFF))
                            if (followed.ownerList.isNotEmpty()) {
                                Text("On ${followed.ownerList.joinToString(" & ")}'s list", fontSize = 12.sp, color = Color(0x99FFFFFF))
                            }
                        }
                    }
                }
            }
            item(key = "actions") {
                val viaTvApp = app != null && AppleTvLinks.listsShowsFrom(app)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (app != null) {
                        Button(onClick = { controller.openShow(show) }) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(if (viaTvApp) "Open show" else "Open ${app.label}")
                        }
                    }
                    if (viaTvApp && followed != null) {
                        TextButton(onClick = { controller.openShowApp(show) }) { Text("${app!!.label} app") }
                    }
                    if (followed == null) {
                        FilledTonalButton(onClick = {
                            scope.launch {
                                runCatching { controller.shows.followId(id, owner = person) }
                                    .onSuccess { controller.notify("Following ${show.name}") }
                                    .onFailure { controller.notify("Couldn't follow ${show.name}") }
                            }
                        }) { Text(if (person != null) "Add to $person's" else "Follow") }
                    }
                }
                if (viaTvApp) {
                    Text(
                        "Opens the show in the Apple TV app; Play there starts it in ${app!!.label}.",
                        fontSize = 11.sp, color = Color(0x99FFFFFF), modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            d?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                item(key = "summary") { Text(summary, fontSize = 14.sp, color = Color(0xEEFFFFFF)) }
            }

            if (d == null) {
                item(key = "loading") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else {
                val today = java.time.LocalDate.now().toString()
                val aired = d.episodes.filter { it.airdate != null && it.airdate <= today }
                val unwatched = if (followed != null) aired.filter { isAfter(it, followed) } else emptyList()
                if (unwatched.isNotEmpty()) {
                    item(key = "new-h") { DetailHeader("New to watch (${unwatched.size})", "Tap the circle once you've seen it") }
                    items(unwatched.take(30), key = { "n-${it.season}-${it.number}" }) { ep ->
                        EpisodeRow(ep, watched = false) {
                            controller.work { controller.shows.watchedUpTo(followed!!, ep.season, ep.number) }
                        }
                    }
                }
                if (d.nextAirDate != null) {
                    item(key = "next") {
                        DetailHeader("Coming up", null)
                        Text(
                            "S${d.nextSeason} E${d.nextEpisode}" + (d.nextName?.let { " · $it" } ?: "") +
                                " · " + ShowLogic.friendlyDate(d.nextAirDate),
                            fontSize = 14.sp, color = Color.White,
                        )
                    }
                }
                val earlier = aired.filterNot { it in unwatched }.reversed()
                if (earlier.isNotEmpty()) {
                    item(key = "all-h") {
                        Row(Modifier.fillMaxWidth().clickable { showAllEpisodes = !showAllEpisodes }, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (followed != null) "Watched" else "Episodes").uppercase() + " (${earlier.size})",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xCCFFFFFF), modifier = Modifier.weight(1f),
                            )
                            Text(if (showAllEpisodes) "Hide" else "Show", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (showAllEpisodes) {
                        items(earlier.take(60), key = { "e-${it.season}-${it.number}" }) { ep ->
                            EpisodeRow(ep, watched = followed != null) {
                                // Tapping a watched episode moves the place back to just before it.
                                if (followed != null) controller.work { controller.shows.watchedUpTo(followed, ep.season, ep.number - 1) }
                            }
                        }
                    }
                }
            }
            item(key = "credit") {
                Text("Show data from TVmaze.com", Modifier.fillMaxWidth().padding(8.dp), fontSize = 10.sp, color = Color(0x99FFFFFF))
            }
        }
    }

    if (showMenu && followed != null) ShowDialog(followed, controller, people, onDismiss = { showMenu = false })
}

private fun isAfter(ep: TvMaze.Episode, show: Show) =
    ep.season > show.watchedSeason || (ep.season == show.watchedSeason && ep.number > show.watchedEpisode)

private fun watchedLine(show: Show): String = when {
    show.watchedSeason == 0 && show.watchedEpisode == 0 -> "Not started"
    show.watchedEpisode == Show.WHOLE_SEASON -> "Watched through season ${show.watchedSeason}"
    show.watchedEpisode == 0 -> "Watched through season ${show.watchedSeason - 1}"
    else -> "Watched up to S${show.watchedSeason} E${show.watchedEpisode}"
}

@Composable
private fun DetailHeader(title: String, hint: String?) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xCCFFFFFF))
        if (hint != null) Text(hint, fontSize = 11.sp, color = Color(0x99FFFFFF))
    }
}

@Composable
private fun EpisodeRow(ep: TvMaze.Episode, watched: Boolean, onToggle: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle) {
                if (watched) Icon(Icons.Default.CheckCircle, "Watched", tint = Color(0xFF66BB6A))
                else Icon(Icons.Outlined.Circle, "Mark watched", tint = Color(0xCCFFFFFF))
            }
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("${ep.code}${if (ep.name.isNotBlank()) " · ${ep.name}" else ""}", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ep.airdate?.let { Text(ShowLogic.friendlyDate(it), fontSize = 11.sp, color = Color(0x99FFFFFF)) }
            }
        }
    }
}
