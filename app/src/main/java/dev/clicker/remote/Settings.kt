package dev.clicker.remote

import android.content.Context
import dev.clicker.remote.appletv.ClientIdentity
import dev.clicker.remote.appletv.CompanionCredentials
import kotlin.random.Random

enum class VolumeTarget(val label: String) {
    APPLE_TV("Apple TV (HDMI-CEC)"),
    LG_TV("LG TV"),
    SOUNDBAR_IR("Denon soundbar (IR, experimental)"),
}

enum class Target { APPLE_TV, LG_TV }

enum class BackgroundMode(val label: String) { VIDEO("Intro video"), PHOTO("Photo"), NONE("None") }

/** App settings, stored in SharedPreferences. Credentials stay on the device. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("clicker", Context.MODE_PRIVATE)

    var atvHost: String
        get() = prefs.getString("atv_host", "") ?: ""
        set(v) = prefs.edit().putString("atv_host", v.trim()).apply()

    var atvPort: Int
        get() = prefs.getInt("atv_port", 49153)
        set(v) = prefs.edit().putInt("atv_port", v).apply()

    var atvName: String
        get() = prefs.getString("atv_name", "") ?: ""
        set(v) = prefs.edit().putString("atv_name", v).apply()

    var atvCredentials: CompanionCredentials?
        get() = CompanionCredentials.parse(prefs.getString("atv_credentials", null))
        set(v) = prefs.edit().putString("atv_credentials", v?.serialize()).apply()

    var lgHost: String
        get() = prefs.getString("lg_host", "") ?: ""
        set(v) = prefs.edit().putString("lg_host", v.trim()).apply()

    /** SHA-256 of the LG TV's certificate, pinned at first pairing. */
    var lgCertPin: String?
        get() = prefs.getString("lg_cert_pin", null)
        set(v) = prefs.edit().putString("lg_cert_pin", v).apply()

    var lgClientKey: String?
        get() = prefs.getString("lg_client_key", null)
        set(v) = prefs.edit().putString("lg_client_key", v).apply()

    var lgMac: String?
        get() = prefs.getString("lg_mac", null)
        set(v) = prefs.edit().putString("lg_mac", v).apply()

    var volumeTarget: VolumeTarget
        get() = runCatching { VolumeTarget.valueOf(prefs.getString("volume_target", "")!!) }.getOrDefault(VolumeTarget.APPLE_TV)
        set(v) = prefs.edit().putString("volume_target", v.name).apply()

    var target: Target
        get() = runCatching { Target.valueOf(prefs.getString("target", "")!!) }.getOrDefault(Target.APPLE_TV)
        set(v) = prefs.edit().putString("target", v.name).apply()

    /** The LG input the Apple TV is plugged into (webOS id such as "HDMI_1"); "" = don't switch. */
    var atvTvInput: String
        get() = prefs.getString("atv_tv_input", "HDMI_1") ?: "HDMI_1"
        set(v) = prefs.edit().putString("atv_tv_input", v).apply()

    var backgroundMode: BackgroundMode
        get() = runCatching { BackgroundMode.valueOf(prefs.getString("background", "")!!) }.getOrDefault(BackgroundMode.VIDEO)
        set(v) = prefs.edit().putString("background", v.name).apply()

    /** Stable identity this remote presents to the Apple TV. Generated on first use. */
    val identity: ClientIdentity
        get() {
            val rpId = prefs.getString("rp_id", null) ?: List(6) { "%02x".format(Random.nextInt(256)) }.joinToString("")
                .also { prefs.edit().putString("rp_id", it).apply() }
            val pubId = prefs.getString("pub_id", null)
                ?: (listOf(0x02) + List(5) { Random.nextInt(256) }).joinToString(":") { "%02X".format(it) }
                    .also { prefs.edit().putString("pub_id", it).apply() }
            return ClientIdentity(name = "Clicker", rpId = rpId, publicId = pubId)
        }
}
