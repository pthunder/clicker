package dev.clicker.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * App icons for the shortcut tiles. Nothing is bundled with the app: icons are downloaded once
 * and cached on the device.
 *
 * - Apple TV apps: looked up on Apple's public iTunes Search API by bundle id (only the bundle id
 *   of a public app is sent).
 * - LG apps: fetched from the TV itself.
 */
class Artwork(context: Context) {
    private val dir = File(context.filesDir, "artwork").apply { mkdirs() }

    fun cached(label: String): File? = file(label).takeIf { it.isFile && it.length() > 0 }

    private fun file(label: String) = File(dir, label.lowercase().replace(Regex("[^a-z0-9]+"), "_") + ".png")

    /** Downloads the icon for an Apple App Store bundle id. Returns the cached file. */
    suspend fun fromAppStore(label: String, bundleId: String): File? = withContext(Dispatchers.IO) {
        cached(label)?.let { return@withContext it }
        runCatching {
            val lookup = "https://itunes.apple.com/lookup?bundleId=" + URLEncoder.encode(bundleId, "UTF-8")
            val json = JSONObject(get(lookup).decodeToString())
            val result = json.optJSONArray("results")?.optJSONObject(0) ?: return@runCatching null
            val art = result.optString("artworkUrl512").ifEmpty { result.optString("artworkUrl100") }
            if (art.isEmpty()) null else save(label, get(art))
        }.onFailure { Log.w(TAG, "artwork for $label: $it") }.getOrNull()
    }

    /** Stores an icon fetched by the caller (for example from the LG TV, which needs its own connection). */
    suspend fun fromLoader(label: String, load: suspend () -> ByteArray): File? = withContext(Dispatchers.IO) {
        cached(label)?.let { return@withContext it }
        runCatching { save(label, load()) }.onFailure { Log.w(TAG, "artwork for $label: $it") }.getOrNull()
    }

    private fun save(label: String, bytes: ByteArray): File = file(label).apply { writeBytes(bytes) }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Clicker")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "ClickerArt"
    }
}
