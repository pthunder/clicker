package dev.clicker.remote.help

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Help requests ("Water please") sent as Pushover emergency alerts: the phone keeps alerting
 * every 30 seconds, even on silent, until someone taps Acknowledge, and the remote then shows
 * who saw it and when.
 *
 * Not a medical alert system: it depends on Wi-Fi, the internet and Pushover.
 */
class HelpAlerts(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("help", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    enum class Stage { SENDING, WAITING, SEEN, FAILED, CANCELLED, EXPIRED }

    data class Alert(
        val text: String,
        val stage: Stage,
        val sentAt: Long = System.currentTimeMillis(),
        val seenAt: Long? = null,
        val seenOn: String? = null,
        val error: String? = null,
        val receipt: String? = null,
    )

    private val _alert = MutableStateFlow<Alert?>(null)
    val alert: StateFlow<Alert?> = _alert
    private var watching: Job? = null

    var appToken: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v?.trim()?.takeIf { it.isNotEmpty() }).apply()
    var userKey: String?
        get() = prefs.getString("user", null)
        set(v) = prefs.edit().putString("user", v?.trim()?.takeIf { it.isNotEmpty() }).apply()
    /** Who is asking, shown as the alert's title ("Sam"). */
    var sender: String
        get() = prefs.getString("sender", "") ?: ""
        set(v) = prefs.edit().putString("sender", v.trim()).apply()
    /** Who gets the alert, shown on the remote ("Alex saw it"). */
    var recipient: String
        get() = prefs.getString("recipient", "") ?: ""
        set(v) = prefs.edit().putString("recipient", v.trim()).apply()
    /** The request buttons, one per line. A line starting with "!" is urgent (see [isUrgent]). */
    var requests: List<String>
        get() = prefs.getString("requests", null)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: DEFAULT_REQUESTS
        set(v) = prefs.edit().putString("requests", v.joinToString("\n")).apply()

    val configured: Boolean get() = appToken != null && userKey != null

    /**
     * Settings can be dropped in as help.json in the app's folder (picked up at start, then
     * deleted): {"appToken": "...", "userKey": "...", "sender": "...", "recipient": "...",
     * "requests": ["Water", "Coffee"]}
     */
    fun importPending() = scope.launch(Dispatchers.IO) {
        val file = File(app.getExternalFilesDir(null) ?: return@launch, "help.json").takeIf { it.isFile } ?: return@launch
        runCatching {
            val o = JSONObject(file.readText())
            o.optString("appToken").takeIf { it.isNotEmpty() }?.let { appToken = it }
            o.optString("userKey").takeIf { it.isNotEmpty() }?.let { userKey = it }
            o.optString("sender").takeIf { it.isNotEmpty() }?.let { sender = it }
            o.optString("recipient").takeIf { it.isNotEmpty() }?.let { recipient = it }
            o.optJSONArray("requests")?.let { a -> requests = (0 until a.length()).map { a.getString(it) } }
        }.onFailure { Log.w(TAG, "help.json: $it") }
        file.delete()
    }

    /** Sends a request. Any earlier request still alerting is replaced. */
    fun send(request: String) {
        val urgent = isUrgent(request)
        val text = label(request)
        val token = appToken
        val user = userKey
        if (token == null || user == null) {
            _alert.value = Alert(text, Stage.FAILED, error = "Help alerts aren't set up yet (Settings → Help alerts)")
            return
        }
        watching?.cancel()
        _alert.value?.receipt?.let { cancelReceipt(it) }
        _alert.value = Alert(text, Stage.SENDING)
        watching = scope.launch(Dispatchers.IO) {
            val receipt = try {
                post(token, user, text, urgent)
            } catch (e: Exception) {
                Log.w(TAG, "help alert failed", e)
                _alert.update { it?.copy(stage = Stage.FAILED, error = "Couldn't send. Is the remote on Wi-Fi?") }
                return@launch
            }
            _alert.update { it?.copy(stage = Stage.WAITING, receipt = receipt) }
            watchReceipt(token, receipt)
        }
    }

    /** Stops the phone alerting (for example, when the request was sent by mistake). */
    fun cancel() {
        watching?.cancel()
        _alert.value?.receipt?.let { cancelReceipt(it) }
        _alert.update { it?.copy(stage = Stage.CANCELLED) }
    }

    fun dismiss() {
        if (_alert.value?.stage in setOf(Stage.SENDING, Stage.WAITING)) return
        _alert.value = null
    }

    private fun post(token: String, user: String, text: String, urgent: Boolean): String {
        val from = sender.ifEmpty { "Clicker" }
        val body = FormBody.Builder()
            .add("token", token)
            .add("user", user)
            .add("title", if (urgent) "URGENT from $from" else "$from needs something")
            .add("message", text)
            .add("priority", "2") // emergency: repeats until acknowledged
            .add("retry", "30") // Pushover's shortest repeat
            .add("expire", if (urgent) "10800" else "3600")
            .add("sound", if (urgent) "siren" else "persistent")
            .build()
        val request = Request.Builder().url("$API/messages.json").post(body).build()
        http.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: "{}")
            if (!response.isSuccessful || json.optInt("status") != 1) {
                throw IOException(json.optJSONArray("errors")?.optString(0) ?: "Pushover answered ${response.code}")
            }
            return json.getString("receipt")
        }
    }

    /** Checks every 10 seconds whether the alert was acknowledged, until it is or it expires. */
    private suspend fun watchReceipt(token: String, receipt: String) {
        while (true) {
            delay(10_000)
            val status = runCatching {
                http.newCall(Request.Builder().url("$API/receipts/$receipt.json?token=$token").build()).execute().use {
                    JSONObject(it.body?.string() ?: "{}")
                }
            }.getOrNull() ?: continue
            if (status.optInt("acknowledged") == 1) {
                _alert.update {
                    it?.copy(
                        stage = Stage.SEEN,
                        seenAt = status.optLong("acknowledged_at") * 1000,
                        seenOn = status.optString("acknowledged_by_device").takeIf { d -> d.isNotEmpty() },
                    )
                }
                return
            }
            if (status.optInt("expired") == 1) {
                _alert.update { it?.copy(stage = Stage.EXPIRED) }
                return
            }
        }
    }

    private fun cancelReceipt(receipt: String) {
        val token = appToken ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder().add("token", token).build()
                http.newCall(Request.Builder().url("$API/receipts/$receipt/cancel.json").post(body).build()).execute().close()
            }
        }
    }

    companion object {
        private const val TAG = "Clicker"
        private const val API = "https://api.pushover.net/1"
        val DEFAULT_REQUESTS = listOf("!Air machine", "Water please", "Coffee please", "Can you come here?")

        /** Urgent requests ("!Air machine") get their own sound and title and a red button. */
        fun isUrgent(request: String) = request.trimStart().startsWith("!")

        /** The request as shown and sent, without the urgent mark. */
        fun label(request: String) = request.trimStart().removePrefix("!").trim()
    }
}
