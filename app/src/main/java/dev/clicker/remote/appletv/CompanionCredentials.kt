package dev.clicker.remote.appletv

/**
 * Long-term keys from a successful pair-setup.
 *
 * The string form is the same as pyatv's Companion credentials
 * (`ltpk:ltsk:atv_id:client_id`, hex), so credentials can be moved between the two.
 */
data class CompanionCredentials(
    val ltpk: ByteArray,     // Apple TV long-term public key (Ed25519)
    val ltsk: ByteArray,     // our long-term secret key seed (Ed25519)
    val atvId: ByteArray,    // Apple TV pairing identifier
    val clientId: ByteArray, // our pairing identifier
) {
    fun serialize(): String = listOf(ltpk, ltsk, atvId, clientId).joinToString(":") { it.toHex() }

    companion object {
        fun parse(text: String?): CompanionCredentials? {
            val parts = text?.trim()?.split(":") ?: return null
            if (parts.size != 4) return null
            return runCatching {
                CompanionCredentials(parts[0].hexToBytes(), parts[1].hexToBytes(), parts[2].hexToBytes(), parts[3].hexToBytes())
            }.getOrNull()
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
