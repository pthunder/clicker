package dev.clicker.remote.shows

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * TVmaze (https://www.tvmaze.com/api): free show data with air dates, no account or key.
 * Data is CC BY-SA; the Shows page credits it. The service allows about 20 requests every
 * 10 seconds per address, so bulk refreshes are paced by the caller.
 */
class TvMaze(private val http: OkHttpClient) {

    data class Found(
        val id: Int,
        val name: String,
        val premiered: String?,
        val network: String?,
        val status: String,
        val posterUrl: String?,
    ) {
        val year: String? get() = premiered?.take(4)
    }

    data class Details(
        val found: Found,
        val lastSeason: Int, val lastEpisode: Int, val lastAirDate: String?,
        val nextSeason: Int, val nextEpisode: Int, val nextAirDate: String?,
        /** Every regular episode, when asked for (specials are left out). */
        val episodes: List<Episode> = emptyList(),
        /** A short description, as plain text. */
        val summary: String? = null,
        val genres: List<String> = emptyList(),
        val nextName: String? = null,
    )

    data class Season(val number: Int, val episodes: Int?)

    data class Episode(val season: Int, val number: Int, val airdate: String?, val name: String = "") {
        val code: String get() = "S$season E$number"
    }

    fun search(query: String): List<Found> {
        val url = BASE.toHttpUrl().newBuilder().addPathSegments("search/shows").addQueryParameter("q", query).build()
        return parseSearch(get(url.toString()))
    }

    fun details(id: Int, withEpisodes: Boolean = false): Details {
        val url = BASE.toHttpUrl().newBuilder().addPathSegments("shows/$id")
            .addQueryParameter("embed[]", "previousepisode")
            .addQueryParameter("embed[]", "nextepisode")
            .apply { if (withEpisodes) addQueryParameter("embed[]", "episodes") }
            .build()
        return parseDetails(get(url.toString()))
    }

    fun seasons(id: Int): List<Season> = parseSeasons(get("$BASE/shows/$id/seasons"))

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Clicker remote (open source)").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TVmaze answered ${response.code}")
            return response.body?.string() ?: throw IOException("TVmaze sent nothing")
        }
    }

    companion object {
        private const val BASE = "https://api.tvmaze.com"

        fun parseSearch(json: String): List<Found> {
            val array = JSONArray(json)
            return (0 until array.length()).map { parseShow(array.getJSONObject(it).getJSONObject("show")) }
        }

        fun parseDetails(json: String): Details {
            val o = JSONObject(json)
            val embedded = o.optJSONObject("_embedded")
            val prev = embedded?.optJSONObject("previousepisode")
            val next = embedded?.optJSONObject("nextepisode")
            return Details(
                found = parseShow(o),
                lastSeason = prev?.optInt("season") ?: 0,
                lastEpisode = prev?.optIntOrNull("number") ?: 0,
                lastAirDate = prev?.optStringOrNull("airdate"),
                nextSeason = next?.optInt("season") ?: 0,
                nextEpisode = next?.optIntOrNull("number") ?: 0,
                nextAirDate = next?.optStringOrNull("airdate"),
                nextName = next?.optStringOrNull("name"),
                summary = o.optStringOrNull("summary")?.let(::plainText),
                genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty(),
                episodes = embedded?.optJSONArray("episodes")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }
                        .filter { !it.isNull("number") }
                        .map { Episode(it.optInt("season"), it.optInt("number"), it.optStringOrNull("airdate"), it.optString("name")) }
                }.orEmpty(),
            )
        }

        fun parseSeasons(json: String): List<Season> {
            val array = JSONArray(json)
            return (0 until array.length()).map {
                val s = array.getJSONObject(it)
                Season(s.getInt("number"), s.optIntOrNull("episodeOrder"))
            }
        }

        private fun parseShow(o: JSONObject) = Found(
            id = o.getInt("id"),
            name = o.getString("name"),
            premiered = o.optStringOrNull("premiered"),
            // Streaming originals have a web channel; broadcast and cable shows have a network.
            network = (o.optJSONObject("webChannel") ?: o.optJSONObject("network"))?.optStringOrNull("name"),
            status = o.optString("status"),
            posterUrl = o.optJSONObject("image")?.let { it.optStringOrNull("medium") ?: it.optStringOrNull("original") }
                ?.replace("http://", "https://"),
        )

        /** TVmaze summaries are small HTML snippets: keep the words. */
        fun plainText(html: String): String = html
            .replace(Regex("<br\\s*/?>|</p>\\s*<p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .trim()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
    }
}
