package dev.clicker.remote.shows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ShowsTest {
    private val today = LocalDate.of(2026, 11, 24)
    private val now = 1_800_000_000_000L

    private fun show(
        last: Pair<Int, Int> = 2 to 8,
        watched: Pair<Int, Int> = 2 to 8,
        next: Pair<Int, Int>? = null,
        nextDate: String? = null,
        status: String = "Running",
    ) = Show(
        id = 1, name = "Example", status = status,
        lastSeason = last.first, lastEpisode = last.second, lastAirDate = "2026-01-01",
        nextSeason = next?.first ?: 0, nextEpisode = next?.second ?: 0, nextAirDate = nextDate,
        watchedSeason = watched.first, watchedEpisode = watched.second,
    )

    @Test fun caughtUpWithNoDateIsWaiting() {
        assertEquals(Bucket.WAITING, ShowLogic.bucket(show(), now))
    }

    @Test fun newSeasonIsNew() {
        val s = show(last = 3 to 2, watched = 2 to 8)
        assertEquals(Bucket.NEW, ShowLogic.bucket(s, now))
        assertEquals("Season 3 is out", ShowLogic.headline(s, today, now))
    }

    @Test fun finishedWholeSeasonCountsAsWatched() {
        val s = show(last = 2 to 8, watched = 2 to Show.WHOLE_SEASON)
        assertFalse(ShowLogic.hasUnwatched(s))
    }

    @Test fun announcedSeasonIsComingSoon() {
        val s = show(next = 3 to 1, nextDate = "2027-02-12")
        assertEquals(Bucket.COMING, ShowLogic.bucket(s, now))
        assertEquals("Season 3 starts Feb 12", ShowLogic.headline(s, today, now))
    }

    @Test fun endedAndWatchedIsFinished() {
        assertEquals(Bucket.ENDED, ShowLogic.bucket(show(status = "Ended"), now))
    }

    @Test fun snoozeUntilNextSeasonWakesWhenOneIsAnnounced() {
        val snoozed = show().copy(snoozeMode = SnoozeMode.NEXT_SEASON.name, snoozeSeason = 2)
        assertEquals(Bucket.SNOOZED, ShowLogic.bucket(snoozed, now))
        val announced = snoozed.copy(nextSeason = 3, nextEpisode = 1, nextAirDate = "2027-02-12")
        assertFalse(ShowLogic.isSnoozed(announced, now))
        assertEquals(Bucket.COMING, ShowLogic.bucket(announced, now))
    }

    @Test fun snoozeUntilDateExpires() {
        val s = show().copy(snoozeMode = SnoozeMode.UNTIL.name, snoozeUntil = now + 1000)
        assertTrue(ShowLogic.isSnoozed(s, now))
        assertFalse(ShowLogic.isSnoozed(s, now + 2000))
    }

    @Test fun friendlyDates() {
        assertEquals("today", ShowLogic.friendlyDate("2026-11-24", today))
        assertEquals("tomorrow", ShowLogic.friendlyDate("2026-11-25", today))
        assertEquals("Fri", ShowLogic.friendlyDate("2026-11-27", today))
        assertEquals("Dec 25", ShowLogic.friendlyDate("2026-12-25", today))
        assertEquals("Mar 1, 2028", ShowLogic.friendlyDate("2028-03-01", today))
    }

    @Test fun appsFromNetworks() {
        assertEquals("Prime Video", StreamingApps.appForNetwork("Prime Video")?.label)
        assertEquals("Apple TV", StreamingApps.appForNetwork("Apple TV+")?.label)
        assertEquals("HBO Max", StreamingApps.appForNetwork("HBO")?.label)
        assertEquals("Hulu", StreamingApps.appForNetwork("FX")?.label)
        assertEquals("Peacock", StreamingApps.appForNetwork("NBC")?.label)
        assertEquals("Spectrum", StreamingApps.appForNetwork("History")?.label)
        assertEquals(null, StreamingApps.appForNetwork(null))
    }

    @Test fun parsesTvMazeShowWithEpisodes() {
        val json = """
            {"id":67323,"name":"Scarpetta","status":"Running","premiered":"2026-03-11",
             "network":null,"webChannel":{"id":3,"name":"Prime Video"},
             "image":{"medium":"http://static.tvmaze.com/a.jpg","original":"http://static.tvmaze.com/b.jpg"},
             "_embedded":{"previousepisode":{"season":1,"number":8,"airdate":"2026-04-29"},
                          "nextepisode":{"season":2,"number":1,"airdate":"2027-03-10"}}}
        """
        val d = TvMaze.parseDetails(json)
        assertEquals("Scarpetta", d.found.name)
        assertEquals("Prime Video", d.found.network)
        assertEquals("https://static.tvmaze.com/a.jpg", d.found.posterUrl)
        assertEquals(1, d.lastSeason); assertEquals(8, d.lastEpisode)
        assertEquals(2, d.nextSeason); assertEquals("2027-03-10", d.nextAirDate)
    }

    @Test fun caughtUpFromLastWeek() {
        val eps = listOf(
            TvMaze.Episode(3, 9, "2026-11-10"), TvMaze.Episode(3, 10, "2026-11-17"),
            TvMaze.Episode(3, 11, "2026-11-24"), TvMaze.Episode(3, 12, null),
        )
        assertEquals(3 to 9, ShowStore.lastAiredBefore(eps, "2026-11-17"))
        assertEquals(0 to 0, ShowStore.lastAiredBefore(eps, "2026-01-01"))
    }

    @Test fun parsesTmdbTrendingAndServices() {
        val trending = Tmdb.parseTrending(
            """{"results":[{"id":84773,"name":"The Rings of Power","first_air_date":"2022-09-01","poster_path":"/p.jpg"}]}""",
        ).single()
        assertEquals("2022", trending.year)
        assertEquals("https://image.tmdb.org/t/p/w185/p.jpg", trending.posterUrl)
        val services = Tmdb.parseUsServices(
            """{"results":{"US":{"flatrate":[{"provider_name":"HBO Max Amazon Channel"},{"provider_name":"HBO Max"},
               {"provider_name":"Netflix Standard with Ads"},{"provider_name":"Netflix"}],"buy":[{"provider_name":"Apple TV Store"}]}}}""",
        )
        assertEquals(listOf("HBO Max", "Netflix"), services)
    }

    @Test fun summariesBecomePlainText() {
        assertEquals("Kay & Pete\nreturn.", TvMaze.plainText("<p><b>Kay</b> &amp; Pete</p><p>return.</p>"))
    }

    @Test fun peopleLists() {
        val everyone = show()
        val hers = show().copy(owners = Show.joinOwners(listOf("Sam")))
        val both = show().copy(owners = Show.joinOwners(listOf("sam", "Alex", "Sam")))
        assertTrue(everyone.isFor("Alex"))
        assertTrue(hers.isFor("sam"))
        assertFalse(hers.isFor("Alex"))
        assertTrue(hers.isFor(null))
        assertEquals(listOf("Alex", "sam"), both.ownerList)
        val back = ShowStore.fromBackupJson(ShowStore.toBackupJson(listOf(hers.copy(id = 9)))).single()
        assertEquals(listOf("Sam"), back.ownerList)
    }

    @Test fun backupRoundTripKeepsHouseholdFields() {
        val original = show(watched = 2 to Show.WHOLE_SEASON).copy(
            id = 42, name = "Kept", network = "AMC", snoozeMode = SnoozeMode.UNTIL.name, snoozeUntil = now, appOverride = "AMC+",
        )
        val back = ShowStore.fromBackupJson(ShowStore.toBackupJson(listOf(original))).single()
        assertEquals(42, back.id)
        assertEquals(2, back.watchedSeason); assertEquals(Show.WHOLE_SEASON, back.watchedEpisode)
        assertEquals(SnoozeMode.UNTIL.name, back.snoozeMode); assertEquals(now, back.snoozeUntil)
        assertEquals("AMC+", back.appOverride)
        val plain = ShowStore.fromBackupJson(ShowStore.toBackupJson(listOf(show().copy(id = 7)))).single()
        assertEquals(null, plain.snoozeMode); assertEquals(null, plain.appOverride); assertEquals(null, plain.snoozeUntil)
    }
}
