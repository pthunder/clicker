package dev.clicker.remote.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.clicker.remote.RemoteController
import dev.clicker.remote.shows.Bucket
import dev.clicker.remote.shows.Show
import dev.clicker.remote.shows.ShowLogic
import dev.clicker.remote.shows.SnoozeMode
import dev.clicker.remote.shows.StreamingApps
import dev.clicker.remote.shows.TvMaze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val SECTION_TITLES = mapOf(
    Bucket.NEW to "New to watch",
    Bucket.COMING to "Coming soon",
    Bucket.WAITING to "Caught up",
    Bucket.ENDED to "Finished",
    Bucket.SNOOZED to "Snoozed",
)

/**
 * Followed shows, grouped by what's worth knowing: new episodes first, then announced dates.
 * Tapping a show opens its app on the TV; the ⋮ button holds everything else.
 */
@Composable
fun ShowsScreen(controller: RemoteController) {
    val shows by controller.shows.shows.collectAsState(initial = null)
    var searching by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Show?>(null) }
    var showSnoozed by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    // Whose list is shown; null shows everyone's shows.
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    // On by default: shows with nothing new are tucked away and come back by themselves when a new
    // episode or season airs or gets a date.
    var newOnly by rememberSaveable { mutableStateOf(true) }
    val people = shows.orEmpty().flatMap { it.ownerList }.distinctBy { it.lowercase() }.sorted()
    val scope = rememberCoroutineScope()

    // The detail screen sits over the list or the search, and Back returns to where it was opened.
    var detailId by rememberSaveable { mutableStateOf<Int?>(null) }
    detailId?.let { id ->
        ShowDetail(id, controller, person, people, onBack = { detailId = null })
        return
    }

    if (searching) {
        SearchPanel(
            controller, person,
            following = shows.orEmpty().filter { it.isFor(person) && (person == null || it.ownerList.isNotEmpty()) }.map { it.id }.toSet(),
            followingNames = shows.orEmpty().filter { it.isFor(person) }.map { it.name.lowercase() }.toSet(),
            onDetail = { detailId = it },
            onClose = { searching = false },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shows", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                scope.launch { refreshing = true; runCatching { controller.shows.refreshAll() }; refreshing = false }
            }) {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, "Check for new episodes")
            }
            FilledTonalButton(onClick = { searching = true }) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Add")
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = newOnly, onClick = { newOnly = !newOnly }, label = { Text("What's new") })
            // Trending lives on the Add screen (empty search); this chip is the short way there.
            FilterChip(selected = false, onClick = { searching = true }, label = { Text("Trending") })
            if (people.isNotEmpty()) {
                (listOf<String?>(null) + people).forEach { name ->
                    FilterChip(
                        selected = person == name,
                        onClick = { person = name },
                        label = { Text(name ?: "Everyone") },
                    )
                }
            }
        }

        val list = shows?.filter { it.isFor(person) }
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Follow the shows you watch.\nThe remote keeps track of new seasons\nand which app they're on.",
                    color = Color.White, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            else -> {
                val now = System.currentTimeMillis()
                val groups = list.groupBy { ShowLogic.bucket(it, now) }
                val shownBuckets = if (newOnly) listOf(Bucket.NEW, Bucket.COMING) else Bucket.entries
                val tucked = list.size - shownBuckets.sumOf { groups[it].orEmpty().size }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (newOnly && tucked == list.size) {
                        item(key = "quiet") {
                            Text(
                                "Nothing new right now. Shows come back here when a new episode or season is out or has a date.",
                                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                color = Color.White, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                    for (bucket in shownBuckets) {
                        val inBucket = groups[bucket].orEmpty()
                        if (inBucket.isEmpty()) continue
                        val collapsed = bucket == Bucket.SNOOZED && !showSnoozed
                        item(key = "h-$bucket") {
                            SectionHeader(
                                if (bucket == Bucket.SNOOZED) "${SECTION_TITLES[bucket]} (${inBucket.size})" else SECTION_TITLES[bucket]!!,
                                onClick = if (bucket == Bucket.SNOOZED) ({ showSnoozed = !showSnoozed }) else null,
                                trailing = if (bucket == Bucket.SNOOZED) (if (showSnoozed) "Hide" else "Show") else null,
                            )
                        }
                        if (!collapsed) {
                            items(ShowLogic.sort(inBucket, bucket), key = { it.id }) { show ->
                                ShowRow(
                                    show, controller, highlight = bucket == Bucket.NEW,
                                    onOpen = { detailId = show.id },
                                    onMore = { selected = show },
                                )
                            }
                        }
                    }
                    if (newOnly && tucked > 0) {
                        item(key = "tucked") {
                            SectionHeader(
                                "$tucked more with nothing new", onClick = { newOnly = false }, trailing = "Show all",
                            )
                        }
                    }
                    item(key = "credit") {
                        Text(
                            "Show data from TVmaze.com", Modifier.fillMaxWidth().padding(8.dp),
                            fontSize = 10.sp, color = Color(0x99FFFFFF), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    selected?.let { show ->
        // Follow the stored row so the dialog reflects changes made from it.
        shows?.firstOrNull { it.id == show.id }?.let { current ->
            ShowDialog(current, controller, people, onDismiss = { selected = null })
        }
    }
}

@Composable
private fun SectionHeader(title: String, onClick: (() -> Unit)?, trailing: String?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xCCFFFFFF), modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShowRow(show: Show, controller: RemoteController, highlight: Boolean, onOpen: () -> Unit, onMore: () -> Unit) {
    val app = StreamingApps.appFor(show)
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onOpen, onLongClick = onMore),
        shape = RoundedCornerShape(14.dp),
        color = if (highlight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(show.id, show.posterUrl) { controller.shows.poster(show) }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(show.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ShowLogic.headline(show), fontSize = 13.sp, color = if (highlight) Color(0xFFFFD54F) else Color(0xDDFFFFFF), maxLines = 1)
                if (app != null) Text(app.label, fontSize = 11.sp, color = Color(0x99FFFFFF), maxLines = 1)
            }
            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "More for ${show.name}") }
        }
    }
}

/** A small poster, loaded off the main thread and downloaded once. */
@Composable
internal fun Poster(key: Int, url: String?, width: Dp = 46.dp, height: Dp = 66.dp, load: suspend () -> File?) {
    // Keyed on the address too: a show added before its details arrived gets its poster once they do.
    val bitmap by produceState(PosterCache.get(key), key, url) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) { load()?.let { PosterCache.decode(key, it) } }
    }
    Box(Modifier.size(width = width, height = height).clip(RoundedCornerShape(6.dp)).background(Color(0x33FFFFFF))) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private enum class DialogPage { MAIN, SEASONS, APPS, PEOPLE }

@Composable
internal fun ShowDialog(show: Show, controller: RemoteController, people: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(DialogPage.MAIN) }
    val app = StreamingApps.appFor(show)
    val act = { block: suspend () -> Unit -> scope.launch { runCatching { block() }.onFailure { controller.notify(it.message ?: "That didn't work") } }; onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(show.name, maxLines = 2) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                when (page) {
                    DialogPage.MAIN -> LazyColumn {
                        item { Text(ShowLogic.headline(show), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                        if (app != null) item { DialogOption("Open in ${app.label}") { controller.openShow(show); onDismiss() } }
                        item { DialogOption("We're caught up") { act { controller.shows.caughtUp(show) } } }
                        item { DialogOption("Where we are…") { page = DialogPage.SEASONS } }
                        if (ShowLogic.isSnoozed(show)) {
                            item { DialogOption("Wake up (stop snoozing)") { act { controller.shows.wake(show) } } }
                        } else {
                            item { DialogOption("Snooze until a new season") { act { controller.shows.snooze(show, SnoozeMode.NEXT_SEASON) } } }
                            item {
                                DialogOption("Snooze for 3 months") {
                                    act { controller.shows.snooze(show, SnoozeMode.UNTIL, System.currentTimeMillis() + 91L * 24 * 3600_000) }
                                }
                            }
                            item { DialogOption("Snooze until we wake it") { act { controller.shows.snooze(show, SnoozeMode.INDEFINITE) } } }
                        }
                        item { DialogOption("Choose the app…") { page = DialogPage.APPS } }
                        if (people.isNotEmpty()) {
                            item {
                                val whose = show.ownerList.joinToString(" & ").ifEmpty { "everyone" }
                                DialogOption("Whose list… ($whose)") { page = DialogPage.PEOPLE }
                            }
                        }
                        item { DialogOption("Stop following", color = Color(0xFFEF9A9A)) { act { controller.shows.unfollow(show) } } }
                    }
                    DialogPage.PEOPLE -> LazyColumn {
                        item {
                            DialogOption("Everyone", selected = show.ownerList.isEmpty()) { act { controller.shows.setOwners(show, emptyList()) } }
                        }
                        items(people) { name ->
                            val on = show.ownerList.any { it.equals(name, true) }
                            // Toggling keeps the dialog open so a show can go on several lists.
                            DialogOption(name, selected = on) {
                                val next = if (on) show.ownerList.filterNot { it.equals(name, true) } else show.ownerList + name
                                scope.launch { controller.shows.setOwners(show, next) }
                            }
                        }
                    }
                    DialogPage.SEASONS -> SeasonPicker(show, controller) { pick -> act { pick() } }
                    DialogPage.APPS -> LazyColumn {
                        item {
                            val guessed = StreamingApps.appForNetwork(show.network)
                            DialogOption("Automatic${guessed?.let { " (${it.label})" } ?: ""}", selected = show.appOverride == null) {
                                act { controller.shows.setApp(show, null) }
                            }
                        }
                        items(StreamingApps.ALL) { choice ->
                            DialogOption(choice.label, selected = show.appOverride == choice.label) {
                                act { controller.shows.setApp(show, choice.label) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (page == DialogPage.MAIN) onDismiss() else page = DialogPage.MAIN }) {
                Text(if (page == DialogPage.MAIN) "Close" else "Back")
            }
        },
    )
}

@Composable
private fun SeasonPicker(show: Show, controller: RemoteController, onPick: (suspend () -> Unit) -> Unit) {
    val seasons by produceState<List<TvMaze.Season>?>(null, show.id) { value = controller.shows.seasons(show) }
    LazyColumn {
        item { DialogOption("Haven't started") { onPick { controller.shows.notStarted(show) } } }
        val list = seasons
        if (list == null) {
            item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            // Only seasons that have started airing can have been watched.
            val aired = list.filter { it.number in 1..maxOf(show.lastSeason, 1) }
            items(aired) { season ->
                val atThisSeason = show.watchedSeason == season.number && show.watchedEpisode == Show.WHOLE_SEASON
                DialogOption("Finished season ${season.number}", selected = atThisSeason) {
                    onPick { controller.shows.finishedSeason(show, season.number) }
                }
            }
            item { DialogOption("Caught up with everything") { onPick { controller.shows.caughtUp(show) } } }
        }
    }
}

@Composable
private fun DialogOption(label: String, selected: Boolean = false, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 16.sp, color = color, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SearchPanel(
    controller: RemoteController,
    person: String?,
    following: Set<Int>,
    followingNames: Set<String>,
    onDetail: (Int) -> Unit,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TvMaze.Found>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // No automatic keyboard: the empty search shows what's trending; tap the box to type.
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(450) // wait for a pause in typing
        loading = true
        runCatching { controller.shows.search(query) }
            .onSuccess { results = it; error = null }
            .onFailure { error = "Couldn't search. Is the remote online?" }
        loading = false
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(if (person != null) "Add to $person's shows" else "Search, or browse trending") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
            )
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Done") }
        }
        error?.let { Text(it, color = Color(0xFFEF9A9A), fontSize = 13.sp) }
        if (query.isBlank()) {
            TrendingList(controller, person, followingNames, onDetail, Modifier.weight(1f))
            return@Column
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results, key = { it.id }) { found ->
                var followed by remember(found.id) { mutableStateOf(found.id in following) }
                Surface(Modifier.fillMaxWidth().clickable { onDetail(found.id) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Poster(found.id, found.posterUrl) { controller.shows.poster(found) }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(found.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(found.year, found.network, found.status.takeIf { it == "Ended" }).joinToString(" · "),
                                fontSize = 12.sp, color = Color(0xCCFFFFFF), maxLines = 1,
                            )
                        }
                        if (followed) {
                            Icon(Icons.Default.Check, "Following", tint = Color(0xFF66BB6A), modifier = Modifier.padding(horizontal = 12.dp))
                        } else {
                            FilledTonalButton(onClick = {
                                followed = true
                                scope.launch {
                                    runCatching { controller.shows.follow(found, owner = person) }
                                        .onSuccess { controller.notify("Following ${found.name}") }
                                        .onFailure { followed = false; controller.notify("Couldn't follow ${found.name}") }
                                }
                            }) { Text("Follow") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Decoded posters kept in memory, so scrolling a long list doesn't decode the same file again.
 * Posters are decoded at half size: the rows show them at about 70 pixels wide.
 */
private object PosterCache {
    private val cache = android.util.LruCache<Int, ImageBitmap>(160)

    fun get(id: Int): ImageBitmap? = cache.get(id)

    fun decode(id: Int, file: File): ImageBitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()?.also { cache.put(id, it) }
    }
}

/** A streaming service's short name for chips and badges, e.g. "Amazon Prime Video" → "Prime Video". */
private fun serviceLabel(provider: String): String =
    StreamingApps.appForNetwork(provider)?.takeIf { it != StreamingApps.SPECTRUM }?.label ?: provider

/**
 * This week's trending shows across services (TMDB), shown when the search box is empty, with
 * chips to narrow it to one service.
 */
@Composable
private fun TrendingList(
    controller: RemoteController,
    person: String?,
    followingNames: Set<String>,
    onDetail: (Int) -> Unit,
    modifier: Modifier,
) {
    var trending by remember { mutableStateOf<List<dev.clicker.remote.shows.Tmdb.Trending>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var service by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { controller.shows.trending() }
            .onSuccess { trending = it }
            .onFailure { error = it.message ?: "Couldn't load what's trending" }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TRENDING THIS WEEK", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xCCFFFFFF))
        error?.let { Text(it, color = Color(0xFFEF9A9A), fontSize = 13.sp) }
        val list = trending
        if (list == null && error == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (list != null) {
            val services = list.flatMap { t -> t.services.map(::serviceLabel) }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = service == null, onClick = { service = null }, label = { Text("All") })
                services.forEach { name ->
                    FilterChip(selected = service == name, onClick = { service = name }, label = { Text(name) })
                }
            }
            val shown = list.filter { t -> service == null || t.services.any { serviceLabel(it) == service } }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.tmdbId }) { t ->
                    var followed by remember(t.tmdbId) { mutableStateOf(t.name.lowercase() in followingNames) }
                    Surface(Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            runCatching { controller.shows.tvmazeId(t) }
                                .onSuccess(onDetail)
                                .onFailure { controller.notify(it.message ?: "Couldn't open ${t.name}") }
                        }
                    }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Poster(-t.tmdbId, t.posterUrl) { controller.shows.poster(t) }
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(t.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(t.year, t.services.map(::serviceLabel).distinct().joinToString(", ").ifEmpty { null })
                                        .joinToString(" · ").ifEmpty { "Not streaming in the US" },
                                    fontSize = 12.sp, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (followed) {
                                Icon(Icons.Default.Check, "Following", tint = Color(0xFF66BB6A), modifier = Modifier.padding(horizontal = 12.dp))
                            } else {
                                FilledTonalButton(onClick = {
                                    followed = true
                                    scope.launch {
                                        runCatching { controller.shows.follow(t, owner = person) }
                                            .onSuccess { controller.notify("Following ${t.name}") }
                                            .onFailure { followed = false; controller.notify(it.message ?: "Couldn't follow ${t.name}") }
                                    }
                                }) { Text("Follow") }
                            }
                        }
                    }
                }
                item(key = "tmdb-credit") {
                    Text(
                        "Trending data from TMDB; where to watch from JustWatch.\nThis product uses the TMDB API but is not endorsed or certified by TMDB.",
                        Modifier.fillMaxWidth().padding(8.dp), fontSize = 10.sp, color = Color(0x99FFFFFF),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}
