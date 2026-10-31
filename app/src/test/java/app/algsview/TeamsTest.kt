package app.algsview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeamsTest {
    private fun s(login: String, title: String, live: Boolean = true) = StreamInfo(login, login, title, live, 0, null)
    private val tail = " | ALGS Year 6 Group Stage | Match Day 6 | Global | Apex Legends"
    private val streams = listOf(
        s("algs1_team8", "TSMX Academy (Group D)$tail"),
        s("algs1_team1", "Alliance (Group A)$tail"),
        s("algs1_team7", "TSM (Group A)$tail"),
        s("algs1_team12", "Gen.G Citadel (Group D)$tail"),
        s("algs1_team3", "Falcons (Group A)$tail", live = false),
    )

    @Test fun teamNameComesBeforeTheGroup() = assertEquals("ZETA DIVISION", Teams.teamName("ZETA DIVISION (Group A)$tail"))
    @Test fun teamNameWithoutAGroup() = assertEquals("TSM", Teams.teamName("TSM | ALGS"))
    @Test fun wholeWordBeatsSubstring() = assertEquals("algs1_team7", Teams.find("tsm", streams)?.login)
    @Test fun substringIsTheFallback() = assertEquals("algs1_team12", Teams.find("citad", streams)?.login)
    @Test fun offlineChannelsDontMatch() = assertNull(Teams.find("Falcons", streams))
    @Test fun restOfTheTitleDoesntMatch() = assertNull(Teams.find("Global", streams))

    @Test fun staysWhenStillThere() = assertEquals(Teams.Decision.Stay, Teams.decide("TSM", "algs1_team7", streams))

    @Test fun movesWhenTheChannelChanges() {
        val moved = listOf(s("algs1_team2", "TSM (Group A)$tail"), s("algs1_team7", "Alliance (Group A)$tail"))
        assertEquals("algs1_team2", (Teams.decide("TSM", "algs1_team7", moved) as Teams.Decision.Move).to.login)
    }

    @Test fun waitsWhenGone() = assertEquals(Teams.Decision.Wait, Teams.decide("Nobody", "algs1_team7", streams))
    @Test fun keepsWaiting() = assertEquals(Teams.Decision.Stay, Teams.decide("Nobody", null, streams))

    @Test fun fillsInWhenTheTeamAppears() =
        assertEquals("algs1_team7", (Teams.decide("TSM", null, streams) as Teams.Decision.Move).to.login)

    @Test fun loginsIncludeTheMapCamAndBroadcast() =
        assertEquals(listOf("algs1_team1", "algs1_team2", "algs1_map", "playapex", "nicewigg"), Teams.logins("algs1_team", 2))
}
