package dev.clicker.remote.shows

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The household's show list: follow, snooze, mark watched, keep air dates fresh, and keep a
 * backup copy outside the app so the list survives even an uninstall.
 */
class ShowStore(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val dao = ShowDatabase.get(app).shows()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val api = TvMaze(http)
    private val posters = File(app.filesDir, "posters").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("shows", Context.MODE_PRIVATE)
    private val refreshing = Mutex()

    val shows: Flow<List<Show>> = dao.all()

    // ---- Trending (TMDB) -----------------------------------------------------------------------

    /** The household's TMDB API read access token; kept on the device only. */
    var tmdbToken: String?
        get() = prefs.getString("tmdbToken", null)
        set(value) {
            prefs.edit().putString("tmdbToken", value?.trim()?.takeIf { it.isNotEmpty() }).apply()
            trendingCache = null
        }

    private var trendingCache: Pair<Long, List<Tmdb.Trending>>? = null

    /** This week's trending shows with their US streaming services, cached for a few hours. */
    suspend fun trending(force: Boolean = false): List<Tmdb.Trending> {
        val token = tmdbToken ?: throw IllegalStateException("Add a TMDB token in Settings to see what's trending")
        trendingCache?.let { (at, list) -> if (!force && System.currentTimeMillis() - at < TRENDING_FOR_MS) return list }
        return Tmdb(http, token).trending().also { trendingCache = System.currentTimeMillis() to it }
    }

    /** Follows a trending show by matching it to TVmaze, which tracks its episodes. */
    suspend fun follow(trending: Tmdb.Trending, owner: String? = null) = withContext(Dispatchers.IO) {
        val token = tmdbToken ?: throw IllegalStateException("No TMDB token")
        val id = Tmdb(http, token).tvmazeId(trending.tmdbId)
            ?: throw IllegalStateException("Couldn't find ${trending.name} on TVmaze")
        follow(api.details(id).found, owner = owner)
    }

    // ---- Search and follow ---------------------------------------------------------------------

    suspend fun search(query: String): List<TvMaze.Found> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList() else api.search(query.trim())
    }

    /** Follows a show. By default the household is taken to be caught up, the usual case. */
    suspend fun follow(found: TvMaze.Found, caughtUp: Boolean = true, owner: String? = null) = withContext(Dispatchers.IO) {
        val existing = dao.get(found.id)
        val fresh = runCatching { api.details(found.id) }.getOrNull()
        var show = (existing ?: Show(id = found.id, name = found.name)).let { s -> fresh?.let { merge(s, it) } ?: s }
        if (existing == null && caughtUp) show = show.copy(watchedSeason = show.lastSeason, watchedEpisode = show.lastEpisode)
        if (owner != null && (existing == null || existing.ownerList.isNotEmpty())) {
            show = show.copy(owners = Show.joinOwners(show.ownerList + owner))
        }
        dao.upsert(show)
        changed()
    }

    suspend fun unfollow(show: Show) = withContext(Dispatchers.IO) {
        dao.delete(show.id)
        File(posters, "${show.id}.jpg").delete()
        changed()
    }

    // ---- Household changes ---------------------------------------------------------------------

    suspend fun caughtUp(show: Show) = save(show.copy(watchedSeason = show.lastSeason, watchedEpisode = show.lastEpisode))

    suspend fun notStarted(show: Show) = save(show.copy(watchedSeason = 0, watchedEpisode = 0))

    /** Marks every episode up to the end of [season] as watched. */
    suspend fun finishedSeason(show: Show, season: Int) = save(show.copy(watchedSeason = season, watchedEpisode = Show.WHOLE_SEASON))

    suspend fun snooze(show: Show, mode: SnoozeMode, untilMs: Long? = null) = save(
        show.copy(
            snoozeMode = mode.name,
            snoozeUntil = untilMs,
            snoozeSeason = maxOf(show.lastSeason, show.nextSeason),
        ),
    )

    suspend fun wake(show: Show) = save(show.copy(snoozeMode = null, snoozeUntil = null, snoozeSeason = 0))

    suspend fun setApp(show: Show, label: String?) = save(show.copy(appOverride = label))

    /** Puts a show on these people's lists; an empty list means everyone's. */
    suspend fun setOwners(show: Show, names: Collection<String>) = save(show.copy(owners = Show.joinOwners(names)))

    /** Marks every episode up to and including this one as watched. */
    suspend fun watchedUpTo(show: Show, season: Int, episode: Int) =
        save(show.copy(watchedSeason = season, watchedEpisode = episode))

    private val detailCache = mutableMapOf<Int, Pair<Long, TvMaze.Details>>()

    /** Full details for the detail screen (summary and every episode), cached for a few minutes. */
    suspend fun detail(id: Int): TvMaze.Details = withContext(Dispatchers.IO) {
        detailCache[id]?.let { (at, d) -> if (System.currentTimeMillis() - at < 10 * 60_000L) return@withContext d }
        api.details(id, withEpisodes = true).also { detailCache[id] = System.currentTimeMillis() to it }
    }

    /** The TVmaze id behind a trending show, so its detail screen can open. */
    suspend fun tvmazeId(trending: Tmdb.Trending): Int = withContext(Dispatchers.IO) {
        val token = tmdbToken ?: throw IllegalStateException("No TMDB token")
        Tmdb(http, token).tvmazeId(trending.tmdbId) ?: throw IllegalStateException("Couldn't find ${trending.name} on TVmaze")
    }

    /** Follows a show by its TVmaze id. */
    suspend fun followId(id: Int, owner: String? = null) = withContext(Dispatchers.IO) {
        follow(detail(id).found, owner = owner)
    }

    suspend fun seasons(show: Show): List<TvMaze.Season> = withContext(Dispatchers.IO) {
        runCatching { api.seasons(show.id) }.getOrDefault(emptyList())
    }

    private suspend fun save(show: Show) = withContext(Dispatchers.IO) {
        dao.upsert(show)
        changed()
    }

    // ---- Keeping air dates fresh ---------------------------------------------------------------

    /**
     * Refreshes every show every [REFRESH_EVERY_MS], checking each hour while the app runs.
     * Clicker is usually the home screen, so it is nearly always running.
     */
    fun startAutoRefresh() = scope.launch(Dispatchers.IO) {
        while (true) {
            if (System.currentTimeMillis() - prefs.getLong("refreshedAt", 0) > REFRESH_EVERY_MS) refreshAll()
            delay(60 * 60_000L)
        }
    }

    /**
     * Refreshes now if any show is missing its details (it was added while offline) or the last
     * full refresh is overdue. Called whenever the remote wakes up.
     */
    fun refreshIfStale() = scope.launch(Dispatchers.IO) {
        // A list waiting to be imported fetches its own details; refreshing first would race it.
        if (File(app.getExternalFilesDir(null), "import-shows.json").isFile) return@launch
        val overdue = System.currentTimeMillis() - prefs.getLong("refreshedAt", 0) > REFRESH_EVERY_MS
        if (overdue || dao.list().any { it.refreshedAt == 0L }) refreshAll()
    }

    /** Fetches the latest air dates for every show, paced to stay within TVmaze's limits. */
    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        if (!refreshing.tryLock()) return@withContext
        try {
            var failures = 0
            var inARow = 0
            // Shows never fetched go first, so an interrupted refresh still fills the gaps.
            for (show in dao.list().sortedBy { it.refreshedAt }) {
                if (inARow >= 3) { failures++; continue } // offline: try again on the next wake or hour
                runCatching { api.details(show.id) }
                    .onSuccess { fresh ->
                        val current = dao.get(show.id) ?: return@onSuccess // unfollowed meanwhile
                        dao.upsert(clearMetSnooze(merge(current, fresh)))
                        inARow = 0
                    }
                    .onFailure { failures++; inARow++; Log.w(TAG, "refresh ${show.name}: $it") }
                delay(600)
            }
            if (failures == 0) prefs.edit().putLong("refreshedAt", System.currentTimeMillis()).apply()
        } finally {
            refreshing.unlock()
        }
    }

    /** Updates TVmaze facts and keeps the household's own fields. */
    private fun merge(show: Show, fresh: TvMaze.Details) = show.copy(
        name = fresh.found.name,
        posterUrl = fresh.found.posterUrl,
        network = fresh.found.network,
        status = fresh.found.status,
        premiered = fresh.found.premiered,
        lastSeason = fresh.lastSeason, lastEpisode = fresh.lastEpisode, lastAirDate = fresh.lastAirDate,
        nextSeason = fresh.nextSeason, nextEpisode = fresh.nextEpisode, nextAirDate = fresh.nextAirDate,
        refreshedAt = System.currentTimeMillis(),
    )

    /** A snooze whose condition has been met ends, so the show comes back on its own. */
    private fun clearMetSnooze(show: Show): Show =
        if (show.snoozeMode != null && !ShowLogic.isSnoozed(show)) show.copy(snoozeMode = null, snoozeUntil = null, snoozeSeason = 0)
        else show

    // ---- Posters -------------------------------------------------------------------------------

    /** The poster file for a show, downloading it the first time. */
    suspend fun poster(show: Show): File? = withContext(Dispatchers.IO) {
        posterFile(show.id, show.posterUrl)
    }

    suspend fun poster(found: TvMaze.Found): File? = withContext(Dispatchers.IO) {
        posterFile(found.id, found.posterUrl)
    }

    suspend fun poster(trending: Tmdb.Trending): File? = withContext(Dispatchers.IO) {
        posterFile("tmdb-${trending.tmdbId}", trending.posterUrl)
    }

    private fun posterFile(id: Any, url: String?): File? {
        val file = File(posters, "$id.jpg")
        if (file.isFile && file.length() > 0) return file
        url ?: return null
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val tmp = File(posters, "$id.tmp")
                response.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
                file
            }
        }.getOrNull()
    }

    // ---- Backup --------------------------------------------------------------------------------
    //
    // After every change the household's part of the list is written to
    // Documents/Clicker/clicker-shows.json on the remote. That folder is shared storage, so the
    // copy survives an uninstall; Settings can restore from it. Air dates and artwork are not
    // backed up: they are fetched again.

    private fun changed() {
        scope.launch(Dispatchers.IO) { runCatching { backup() }.onFailure { Log.w(TAG, "backup failed", it) } }
    }

    private suspend fun backup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // shared Documents needs Android 10+
        val json = toBackupJson(dao.list())
        val resolver = app.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val uri = prefs.getString("backupUri", null)?.let(Uri::parse)?.takeIf { existing ->
            runCatching { resolver.openOutputStream(existing, "wt")?.use { it.write(json.toByteArray()) } != null }.getOrDefault(false)
        }
        if (uri != null) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Clicker")
        }
        val created = resolver.insert(collection, values) ?: return
        resolver.openOutputStream(created, "wt")?.use { it.write(json.toByteArray()) }
        prefs.edit().putString("backupUri", created.toString()).apply()
    }

    /** Restores the list from a backup file chosen in Settings, then fetches fresh air dates. */
    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("Couldn't read that file")
        importText(text)
    }

    /**
     * Imports a list left in the app's folder as import-shows.json, then deletes it. This is how a
     * list prepared on a computer gets onto the remote:
     *
     *   adb push shows.json /sdcard/Android/data/dev.clicker.remote/files/import-shows.json
     *
     * It is checked whenever the app starts.
     */
    fun importPending() = scope.launch(Dispatchers.IO) {
        // A TMDB token can be dropped in the same way: tmdb-token.txt holding just the token.
        File(app.getExternalFilesDir(null) ?: return@launch, "tmdb-token.txt").takeIf { it.isFile }?.let { f ->
            tmdbToken = f.readText().trim()
            f.delete()
        }
        val file = File(app.getExternalFilesDir(null) ?: return@launch, "import-shows.json")
        if (!file.isFile) return@launch
        runCatching { importText(file.readText()) }
            .onSuccess { Log.w(TAG, "imported $it shows from ${file.name}") }
            .onFailure { Log.w(TAG, "import failed", it) }
        file.delete()
    }

    /**
     * Adds or updates the shows in a list (the backup format). A show marked "caughtUp" that
     * isn't followed yet starts at its latest aired episode instead of "not started".
     */
    private suspend fun importText(text: String): Int {
        val restored = fromBackupJson(text)
        val caughtUp = caughtUpIds(text)
        // Optional: "caught up" means up to episodes that aired before this date, so anything newer
        // shows as new. Lets a fresh list start with last week's episodes waiting.
        val before = JSONObject(text).optString("caughtUpBefore").takeIf { it.isNotEmpty() }
        for (show in restored) {
            val stored = dao.get(show.id)
            // A show imported while offline never got its details; import it again properly.
            val existing = stored?.takeIf { it.refreshedAt != 0L || show.id !in caughtUp }
            if (existing != null) {
                dao.upsert(
                    existing.copy(
                        watchedSeason = if (show.id in caughtUp) existing.watchedSeason else show.watchedSeason,
                        watchedEpisode = if (show.id in caughtUp) existing.watchedEpisode else show.watchedEpisode,
                        snoozeMode = show.snoozeMode, snoozeUntil = show.snoozeUntil, snoozeSeason = show.snoozeSeason,
                        appOverride = show.appOverride ?: existing.appOverride,
                        // A show on two people's lists is on both; one on everyone's list stays that way.
                        owners = if (existing.ownerList.isEmpty() || show.ownerList.isEmpty()) ""
                        else Show.joinOwners(existing.ownerList + show.ownerList),
                    ),
                )
                continue
            }
            var added = stored?.copy(owners = show.owners, appOverride = show.appOverride ?: stored.appOverride) ?: show
            if (show.id in caughtUp) {
                runCatching { api.details(show.id, withEpisodes = before != null) }.onSuccess { fresh ->
                    val merged = merge(added, fresh)
                    val (season, episode) = if (before != null) lastAiredBefore(fresh.episodes, before)
                    else merged.lastSeason to merged.lastEpisode
                    added = merged.copy(watchedSeason = season, watchedEpisode = episode)
                }
                delay(600) // stay within TVmaze's request limit
            }
            dao.upsert(added)
        }
        changed()
        scope.launch { refreshAll() }
        return restored.size
    }

    companion object {
        private const val TAG = "Clicker"
        private const val BACKUP_NAME = "clicker-shows.json"
        const val REFRESH_EVERY_MS = 12 * 60 * 60_000L
        private const val TRENDING_FOR_MS = 6 * 60 * 60_000L

        fun toBackupJson(shows: List<Show>): String = JSONObject()
            .put("format", "clicker-shows")
            .put("version", 1)
            .put("savedAt", System.currentTimeMillis())
            .put(
                "shows",
                JSONArray(
                    shows.sortedBy { it.name.lowercase() }.map { s ->
                        JSONObject()
                            .put("tvmazeId", s.id)
                            .put("name", s.name)
                            .put("network", s.network)
                            .put("watchedSeason", s.watchedSeason)
                            .put("watchedEpisode", s.watchedEpisode)
                            .put("snoozeMode", s.snoozeMode)
                            .put("snoozeUntil", s.snoozeUntil)
                            .put("snoozeSeason", s.snoozeSeason)
                            .put("app", s.appOverride)
                            .put("owners", JSONArray(s.ownerList))
                            .put("addedAt", s.addedAt)
                    },
                ),
            )
            .toString(2)

        /** The last episode that aired before [date] (ISO), or 0/0 if none had. */
        fun lastAiredBefore(episodes: List<TvMaze.Episode>, date: String): Pair<Int, Int> =
            episodes.filter { it.airdate != null && it.airdate < date }
                .maxWithOrNull(compareBy({ it.season }, { it.number }))
                ?.let { it.season to it.number } ?: (0 to 0)

        private fun caughtUpIds(text: String): Set<Int> {
            val array = JSONObject(text).getJSONArray("shows")
            return (0 until array.length()).map { array.getJSONObject(it) }
                .filter { it.optBoolean("caughtUp") }.map { it.getInt("tvmazeId") }.toSet()
        }

        fun fromBackupJson(text: String): List<Show> {
            val root = JSONObject(text)
            require(root.optString("format") == "clicker-shows") { "That isn't a Clicker show list" }
            val array = root.getJSONArray("shows")
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Show(
                    id = o.getInt("tvmazeId"),
                    name = o.getString("name"),
                    network = o.optString("network").takeIf { !o.isNull("network") && it.isNotEmpty() },
                    watchedSeason = o.optInt("watchedSeason"),
                    watchedEpisode = o.optInt("watchedEpisode"),
                    snoozeMode = o.optString("snoozeMode").takeIf { !o.isNull("snoozeMode") && it.isNotEmpty() },
                    snoozeUntil = if (o.isNull("snoozeUntil") || !o.has("snoozeUntil")) null else o.getLong("snoozeUntil"),
                    snoozeSeason = o.optInt("snoozeSeason"),
                    appOverride = o.optString("app").takeIf { !o.isNull("app") && it.isNotEmpty() },
                    owners = o.optJSONArray("owners")?.let { a -> Show.joinOwners((0 until a.length()).map { a.getString(it) }) } ?: "",
                    addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                )
            }
        }
    }
}
