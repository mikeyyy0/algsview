package app.algsview

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlgsTest {
    private val live = JSONObject(
        """{"series":[{"id":"S1","name":"Group A vs D","status":"live","phase":{"name":"Group Stage"},"event":{"name":"Split 2 Playoffs"},
        "streams":[{"channelName":"playapex","streamType":"main"},
        {"channelName":"ALGS1_Team10","team":{"name":"ZETA DIVISION","shortName":"ZETA","logoDark":"https://x/z.png"}}]}]}"""
    )

    @Test fun channelsAreByLowerCaseLogin() {
        val channels = AlgsApi.parseChannels(live)
        assertEquals(1, channels.size)
        assertEquals("ZETA", channels["algs1_team10"]?.shortName)
    }

    @Test fun liveSet() {
        val ref = AlgsApi.parseLiveSeries(live)!!
        assertEquals("S1", ref.id)
        assertEquals("Split 2 Playoffs · Group Stage", ref.event)
    }

    @Test fun noLiveSet() = assertNull(AlgsApi.parseLiveSeries(JSONObject("""{"series":[]}""")))

    @Test fun lastSetIsTheNewestCompleted() {
        val season = JSONObject(
            """{"series":[{"id":"old","status":"completed","startsAt":"2026-06-07T17:00:00.000Z"},
            {"id":"new","status":"completed","startsAt":"2026-10-30T21:15:00.000Z"},
            {"id":"next","status":"pending","startsAt":"2026-11-01T23:00:00.000Z"}]}"""
        )
        assertEquals("new", AlgsApi.parseLastSeries(season)?.id)
    }

    @Test fun standingsAreInOrder() {
        val stats = JSONObject(
            """{"seriesStatus":"live","teams":[
            {"name":"Aurora","shortName":"AURA","position":2,"points":52,"kills":22,"placementPoints":30,"eliminated":false},
            {"name":"World Wide","shortName":"WRLD","position":1,"points":74,"kills":31,"placementPoints":43,"eliminated":true}]}"""
        )
        val s = AlgsApi.parseStandings(stats, SeriesRef("S1", "Group A vs D", "Split 2"), null)
        assertEquals(listOf("WRLD", "AURA"), s.teams.map { it.shortName })
        assertEquals(74, s.teams[0].points)
        assertEquals(true, s.teams[0].eliminated)
        assertEquals(true, s.live)
    }

    @Test fun matchLineWhileLive() = assertEquals(
        "Match 6 live, Storm Point",
        AlgsApi.matchLine(JSONObject("""{"matches":[{"matchNumber":5,"status":"completed","map":{"name":"Storm Point"}},{"matchNumber":6,"status":"live","map":{"name":"Storm Point"}}]}""")),
    )

    @Test fun matchLineBetweenMatches() = assertEquals(
        "After match 5, Storm Point",
        AlgsApi.matchLine(JSONObject("""{"matches":[{"matchNumber":5,"status":"completed","map":{"name":"Storm Point"}}]}""")),
    )
}
