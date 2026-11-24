package dev.clicker.remote.shows

import dev.clicker.remote.AppShortcut

/**
 * Which app to open for a show. Streaming originals open their own service; broadcast and
 * cable shows open the service that carries them (FX and ABC on Hulu, NBC on Peacock, and so
 * on), or the cable provider's app for live channels. US services; any show can be pointed at
 * a different app by hand.
 */
object StreamingApps {
    val APPLE_TV = AppShortcut("Apple TV", "Apple TV", atvBundle = "com.apple.TVWatchList", storeBundle = "com.apple.tv")
    val NETFLIX = AppShortcut("Netflix", "Netflix")
    val PRIME = AppShortcut("Prime Video", "Prime Video")
    val DISNEY = AppShortcut("Disney+", "Disney")
    val HULU = AppShortcut("Hulu", "Hulu")
    val HBO_MAX = AppShortcut("HBO Max", "Max")
    val PEACOCK = AppShortcut("Peacock", "Peacock")
    val PARAMOUNT = AppShortcut("Paramount+", "Paramount")
    val YOUTUBE = AppShortcut("YouTube", "YouTube")
    val AMC = AppShortcut("AMC+", "AMC")
    val STARZ = AppShortcut("Starz", "Starz")
    val MGM = AppShortcut("MGM+", "MGM")
    val WOW = AppShortcut("WOW Presents Plus", "WOW")
    val SPECTRUM = AppShortcut("Spectrum", "Spectrum")

    /** Choices offered when picking an app by hand. */
    val ALL = listOf(APPLE_TV, NETFLIX, PRIME, DISNEY, HULU, HBO_MAX, PEACOCK, PARAMOUNT, YOUTUBE, AMC, STARZ, MGM, WOW, SPECTRUM)

    private val byNetwork = listOf(
        "apple tv" to APPLE_TV,
        "netflix" to NETFLIX,
        "prime video" to PRIME, "amazon" to PRIME, "freevee" to PRIME,
        "disney" to DISNEY, "national geographic" to DISNEY,
        "hulu" to HULU, "fx" to HULU, "abc" to HULU, "fox" to HULU,
        "hbo" to HBO_MAX, "max" to HBO_MAX, "cinemax" to HBO_MAX, "tbs" to HBO_MAX, "tnt" to HBO_MAX,
        "discovery" to HBO_MAX, "hgtv" to HBO_MAX, "food network" to HBO_MAX, "tlc" to HBO_MAX,
        "peacock" to PEACOCK, "nbc" to PEACOCK, "bravo" to PEACOCK, "usa network" to PEACOCK, "syfy" to PEACOCK,
        "paramount" to PARAMOUNT, "cbs" to PARAMOUNT, "showtime" to PARAMOUNT, "comedy central" to PARAMOUNT,
        "mtv" to PARAMOUNT, "vh1" to PARAMOUNT, "logo" to PARAMOUNT, "nickelodeon" to PARAMOUNT,
        "youtube" to YOUTUBE,
        "amc" to AMC,
        "starz" to STARZ,
        "mgm" to MGM, "epix" to MGM,
        "wow presents" to WOW,
    )

    /** The app for a show: the one chosen by hand, else a guess from its network. */
    fun appFor(show: Show): AppShortcut? =
        show.appOverride?.let { label -> ALL.firstOrNull { it.label == label } } ?: appForNetwork(show.network)

    /** Known services by name; any other network is taken to be a cable channel. */
    fun appForNetwork(network: String?): AppShortcut? {
        val name = network?.lowercase()?.trim() ?: return null
        return byNetwork.firstOrNull { (key, _) -> name == key || name.startsWith("$key ") || name.startsWith("$key+") }?.second
            ?: SPECTRUM
    }
}
