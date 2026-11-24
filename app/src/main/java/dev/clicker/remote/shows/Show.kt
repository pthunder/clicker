package dev.clicker.remote.shows

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * A followed show. Air dates and artwork come from TVmaze and are refreshed regularly; the
 * watched position, snooze and app choice are the household's own and are never overwritten
 * by a refresh.
 *
 * Episode positions are (season, episode). [WHOLE_SEASON] as the episode means "all of that
 * season", for when someone sets the position by season only.
 */
@Entity(tableName = "shows")
data class Show(
    /** TVmaze show id. */
    @PrimaryKey val id: Int,
    val name: String,
    val posterUrl: String? = null,
    /** The network or streaming service, as TVmaze names it ("Netflix", "HBO", "AMC"). */
    val network: String? = null,
    /** TVmaze status: Running, Ended, To Be Determined, In Development. */
    val status: String = "",
    val premiered: String? = null,

    /** The latest episode that has aired. 0 when nothing has aired yet. */
    val lastSeason: Int = 0,
    val lastEpisode: Int = 0,
    val lastAirDate: String? = null,
    /** The next announced episode. 0 when none is announced. */
    val nextSeason: Int = 0,
    val nextEpisode: Int = 0,
    val nextAirDate: String? = null,

    /** Where the household is: the last episode watched. 0/0 means not started. */
    val watchedSeason: Int = 0,
    val watchedEpisode: Int = 0,

    /** Set while snoozed; see [SnoozeMode]. */
    val snoozeMode: String? = null,
    /** For [SnoozeMode.UNTIL]: when it wakes (epoch ms). */
    val snoozeUntil: Long? = null,
    /** For [SnoozeMode.NEXT_SEASON]: wakes once a season after this one is announced or airs. */
    val snoozeSeason: Int = 0,

    /** Whose list the show is on: comma-separated names. Empty means everyone's. */
    @ColumnInfo(defaultValue = "") val owners: String = "",

    /** App chosen by hand, overriding the one guessed from [network]. */
    val appOverride: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val refreshedAt: Long = 0,
) {
    val ownerList: List<String> get() = owners.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** True when the show belongs on [person]'s list (shows with no owner are on everyone's). */
    fun isFor(person: String?): Boolean = person == null || ownerList.isEmpty() || ownerList.any { it.equals(person, true) }

    companion object {
        const val WHOLE_SEASON = 1000

        fun joinOwners(names: Collection<String>): String =
            names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sorted().joinToString(",")
    }
}

enum class SnoozeMode {
    /** Until a new season is announced or starts airing. */
    NEXT_SEASON,
    /** Until a date. */
    UNTIL,
    /** Until someone wakes it by hand. */
    INDEFINITE,
}

/** Where a show appears on the Shows page, in display order. */
enum class Bucket { NEW, COMING, WAITING, ENDED, SNOOZED }

object ShowLogic {
    private fun after(s1: Int, e1: Int, s2: Int, e2: Int) = s1 > s2 || (s1 == s2 && e1 > e2)

    /** True when an aired episode is newer than the one last watched. */
    fun hasUnwatched(show: Show): Boolean =
        show.lastSeason > 0 && after(show.lastSeason, show.lastEpisode, show.watchedSeason, show.watchedEpisode)

    /** True while a snooze is in effect (it may have expired or been met since it was set). */
    fun isSnoozed(show: Show, now: Long = System.currentTimeMillis()): Boolean =
        when (show.snoozeMode?.let { runCatching { SnoozeMode.valueOf(it) }.getOrNull() }) {
            null -> false
            SnoozeMode.INDEFINITE -> true
            SnoozeMode.UNTIL -> (show.snoozeUntil ?: 0) > now
            SnoozeMode.NEXT_SEASON -> show.lastSeason <= show.snoozeSeason && show.nextSeason <= show.snoozeSeason
        }

    fun bucket(show: Show, now: Long = System.currentTimeMillis()): Bucket = when {
        isSnoozed(show, now) -> Bucket.SNOOZED
        hasUnwatched(show) -> Bucket.NEW
        show.nextAirDate != null -> Bucket.COMING
        show.status == "Ended" -> Bucket.ENDED
        else -> Bucket.WAITING
    }

    /** One short line for the list, e.g. "Season 3 is out" or "Season 2 starts Jun 24". */
    fun headline(show: Show, today: LocalDate = LocalDate.now(), now: Long = System.currentTimeMillis()): String {
        val notStarted = show.watchedSeason == 0 && show.watchedEpisode == 0
        return when (bucket(show, now)) {
            Bucket.NEW -> when {
                notStarted -> if (show.lastSeason > 1) "Not started · ${show.lastSeason} seasons" else "Not started"
                show.lastSeason > show.watchedSeason ->
                    if (show.lastSeason == show.watchedSeason + 1) "Season ${show.lastSeason} is out"
                    else "Seasons ${show.watchedSeason + 1}–${show.lastSeason} are out"
                else -> "New episodes · up to S${show.lastSeason} E${show.lastEpisode}"
            }
            Bucket.COMING -> {
                val date = show.nextAirDate?.let { friendlyDate(it, today) } ?: ""
                if (show.nextEpisode == 1) "Season ${show.nextSeason} starts $date"
                else "S${show.nextSeason} E${show.nextEpisode} · $date"
            }
            Bucket.WAITING -> when (show.status) {
                "In Development" -> "In development"
                "To Be Determined" -> "Caught up · no word yet"
                else -> "Caught up · no date yet"
            }
            Bucket.ENDED -> "Ended · all watched"
            Bucket.SNOOZED -> when (SnoozeMode.valueOf(show.snoozeMode!!)) {
                SnoozeMode.NEXT_SEASON -> "Snoozed until a new season"
                SnoozeMode.UNTIL -> {
                    val wakes = java.time.Instant.ofEpochMilli(show.snoozeUntil ?: 0).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    "Snoozed until ${friendlyDate(wakes.toString(), today)}"
                }
                SnoozeMode.INDEFINITE -> "Snoozed"
            }
        }
    }

    /** "Today", "Tomorrow", "Fri", "Jun 24" or "Jun 24, 2027". */
    fun friendlyDate(iso: String, today: LocalDate = LocalDate.now()): String {
        val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
        val month = date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
        return when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days in 2..6 -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
            date.year == today.year || days in 0..300 -> "$month ${date.dayOfMonth}"
            else -> "$month ${date.dayOfMonth}, ${date.year}"
        }
    }

    /** Order inside a bucket: soonest date first for Coming, newest first for New, else by name. */
    fun sort(shows: List<Show>, bucket: Bucket): List<Show> = when (bucket) {
        Bucket.COMING -> shows.sortedBy { it.nextAirDate }
        Bucket.NEW -> shows.sortedByDescending { it.lastAirDate ?: "" }
        else -> shows.sortedBy { it.name.lowercase() }
    }
}
