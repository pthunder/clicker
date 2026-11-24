package dev.clicker.remote.shows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * TMDB (https://www.themoviedb.org): what's trending this week, and where each show streams in
 * the US (that part comes from JustWatch). Needs the household's own free API read access token,
 * which is kept on the device and never in the source code.
 *
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
class Tmdb(private val http: OkHttpClient, private val token: String) {

    data class Trending(
        val tmdbId: Int,
        val name: String,
        val year: String?,
        val posterUrl: String?,
        /** Streaming services that include it in the US (subscription, not rent or buy). */
        val services: List<String>,
    )

    /** This week's trending TV, with US streaming services, in TMDB's order. */
    suspend fun trending(): List<Trending> = withContext(Dispatchers.IO) {
        val shows = parseTrending(get("/trending/tv/week?language=en-US"))
        coroutineScope {
            shows.map { show ->
                async {
                    val services = runCatching { parseUsServices(get("/tv/${show.tmdbId}/watch/providers")) }.getOrDefault(emptyList())
                    show.copy(services = services)
                }
            }.awaitAll()
        }
    }

    /** The TVmaze id for a TMDB show, found through the IMDb or TheTVDB id they share. */
    fun tvmazeId(tmdbId: Int): Int? {
        val ids = JSONObject(get("/tv/$tmdbId/external_ids"))
        val lookups = listOfNotNull(
            ids.optString("imdb_id").takeIf { it.startsWith("tt") }?.let { "imdb=$it" },
            ids.optInt("tvdb_id").takeIf { it > 0 }?.let { "thetvdb=$it" },
        )
        for (query in lookups) {
            val request = Request.Builder().url("https://api.tvmaze.com/lookup/shows?$query")
                .header("User-Agent", "Clicker remote (open source)").build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) return JSONObject(response.body!!.string()).getInt("id")
            }
        }
        return null
    }

    private fun get(path: String): String {
        val request = Request.Builder().url("https://api.themoviedb.org/3$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw IOException("TMDB didn't accept the API token")
            if (!response.isSuccessful) throw IOException("TMDB answered ${response.code}")
            return response.body?.string() ?: throw IOException("TMDB sent nothing")
        }
    }

    companion object {
        private const val POSTERS = "https://image.tmdb.org/t/p/w185"

        fun parseTrending(json: String): List<Trending> {
            val results = JSONObject(json).getJSONArray("results")
            return (0 until results.length()).map { results.getJSONObject(it) }.map { o ->
                Trending(
                    tmdbId = o.getInt("id"),
                    name = o.optString("name").ifEmpty { o.optString("original_name") },
                    year = o.optString("first_air_date").take(4).takeIf { it.length == 4 },
                    posterUrl = o.optString("poster_path").takeIf { it.startsWith("/") }?.let { POSTERS + it },
                    services = emptyList(),
                )
            }
        }

        /** Subscription services in the US, without add-on channel duplicates ("HBO Max Amazon Channel"). */
        fun parseUsServices(json: String): List<String> {
            val us = JSONObject(json).optJSONObject("results")?.optJSONObject("US") ?: return emptyList()
            val flatrate: JSONArray = us.optJSONArray("flatrate") ?: return emptyList()
            return (0 until flatrate.length()).map { flatrate.getJSONObject(it).optString("provider_name") }
                .filter { it.isNotEmpty() && !it.contains("Channel", ignoreCase = true) && !it.endsWith("with Ads") }
                .distinct()
        }
    }
}
