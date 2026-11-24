package dev.clicker.remote.shows

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Links that open a show's page in the Apple TV app, whose Play button then hands off to the
 * service that has it (Hulu, Peacock, Prime Video, …). Streaming apps themselves mostly ignore
 * links sent from outside (Netflix opens its home screen), but the Apple TV app honours them.
 *
 * A show's Apple TV id comes from Wikidata (property P9751), looked up by its IMDb id. When
 * Wikidata doesn't know it, the Apple TV app's search for the show's name is the next best thing.
 */
class AppleTvLinks(context: Context, private val http: OkHttpClient) {
    private val prefs = context.applicationContext.getSharedPreferences("appletv-links", Context.MODE_PRIVATE)

    fun imdbFor(tvmazeId: Int): String? = prefs.getString("imdb:$tvmazeId", null)

    fun rememberImdb(tvmazeId: Int, imdb: String?) {
        if (imdb != null && imdb != imdbFor(tvmazeId)) prefs.edit().putString("imdb:$tvmazeId", imdb).apply()
    }

    /** The cached Apple TV id: null if never looked up, "" if Wikidata had none. */
    private fun cached(tvmazeId: Int): String? {
        val at = prefs.getLong("at:$tvmazeId", 0)
        if (System.currentTimeMillis() - at > RECHECK_MS) return null
        return prefs.getString("atv:$tvmazeId", null)
    }

    /** Looks up Apple TV ids for every show given (TVmaze id to IMDb id) that isn't cached. */
    fun prefetch(imdbByShow: Map<Int, String>) {
        val missing = imdbByShow.filterKeys { cached(it) == null }
        if (missing.isEmpty()) return
        val found = runCatching { query(missing.values.toSet()) }.getOrNull() ?: return
        val edit = prefs.edit()
        for ((show, imdb) in missing) {
            edit.putString("atv:$show", found[imdb] ?: "").putLong("at:$show", System.currentTimeMillis())
        }
        edit.apply()
    }

    /** The link for a show: its Apple TV page if known, else an Apple TV search for its name. */
    fun linkFor(tvmazeId: Int, name: String): String {
        val imdb = imdbFor(tvmazeId)
        if (imdb != null && cached(tvmazeId) == null) prefetch(mapOf(tvmazeId to imdb))
        val id = cached(tvmazeId)?.takeIf { it.isNotEmpty() }
        return if (id != null) showUrl(id, name) else searchUrl(name)
    }

    private fun query(imdbIds: Set<String>): Map<String, String> {
        val values = imdbIds.filter { IMDB.matches(it) }.joinToString(" ") { "\"$it\"" }
        if (values.isEmpty()) return emptyMap()
        val sparql = "SELECT ?imdb ?atv WHERE { VALUES ?imdb { $values } ?s wdt:P345 ?imdb . ?s wdt:P9751 ?atv . }"
        val url = "https://query.wikidata.org/sparql".toHttpUrl().newBuilder().addQueryParameter("query", sparql).build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/sparql-results+json")
            .header("User-Agent", "Clicker remote (open source)")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyMap()
            val rows = JSONObject(response.body!!.string()).getJSONObject("results").getJSONArray("bindings")
            return (0 until rows.length()).map { rows.getJSONObject(it) }.associate {
                it.getJSONObject("imdb").getString("value") to it.getJSONObject("atv").getString("value")
            }
        }
    }

    companion object {
        private val IMDB = Regex("tt\\d{5,10}")
        private const val RECHECK_MS = 14L * 24 * 60 * 60_000

        fun showUrl(id: String, name: String): String = "https://tv.apple.com/us/show/${slug(name)}/$id"

        fun searchUrl(name: String): String = "https://tv.apple.com/search?term=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

        fun slug(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "show" }

        /** Services whose shows appear in the Apple TV app (Netflix, YouTube and others don't). */
        fun listsShowsFrom(app: dev.clicker.remote.AppShortcut): Boolean = app.label !in setOf(
            StreamingApps.NETFLIX.label, StreamingApps.YOUTUBE.label, StreamingApps.WOW.label, StreamingApps.SPECTRUM.label,
        )
    }
}
