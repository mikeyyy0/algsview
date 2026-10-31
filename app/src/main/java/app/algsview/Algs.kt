package app.algsview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A team as the ALGS lists it on a channel. */
data class ChannelTeam(val name: String, val shortName: String, val logo: String?)

/** A team's line in a set's standings. */
data class TeamStanding(
    val position: Int,
    val name: String,
    val shortName: String,
    val logo: String?,
    val points: Int,
    val kills: Int,
    val placementPoints: Int,
    val eliminated: Boolean,
    val matchPointEligible: Boolean,
    val wonMatchPoint: Boolean,
)

/** One set's standings, as they stand now. */
data class Standings(val name: String, val event: String, val live: Boolean, val match: String?, val teams: List<TeamStanding>)

/** The set to show: its ID and names. */
data class SeriesRef(val id: String, val name: String, val event: String)

/**
 * The API behind the official site, algs.ea.com: no sign-in, undocumented, and it can change without
 * notice. It knows which set is on, who's on which channel, and the points as they happen.
 */
class AlgsApi {
    private val http = OkHttpClient()

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://prod-api.algstools.com/v1/$path").header("User-Agent", "Mozilla/5.0").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("the ALGS site answered ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
    }

    /** Who's on each channel in the set on now, by lower-case login. Empty between sets. */
    suspend fun channels(): Map<String, ChannelTeam> = parseChannels(get("streams/live"))

    /** The set on now, or else the last one played, with its standings. */
    suspend fun standings(): Standings {
        val ref = parseLiveSeries(get("streams/live")) ?: lastSeries()
        val stats = get("stats/series/${ref.id}")
        val matches = runCatching { get("series/${ref.id}/matches") }.getOrNull()
        return parseStandings(stats, ref, matches)
    }

    private suspend fun lastSeries(): SeriesRef {
        val seasons = get("seasons/structure").getJSONArray("seasons")
        val season = (0 until seasons.length()).map { seasons.getJSONObject(it) }.firstOrNull { it.optBoolean("isMainSeason") }
            ?: throw IOException("the ALGS site lists no season")
        return parseLastSeries(get("series/seasons/${season.getString("id")}")) ?: throw IOException("no set has been played yet")
    }

    companion object {
        private fun JSONObject.text(key: String) = optString(key).takeIf { it.isNotEmpty() && it != "null" }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

        private fun ref(series: JSONObject) = SeriesRef(
            series.getString("id"),
            series.optString("name"),
            listOfNotNull(series.optJSONObject("event")?.text("name"), series.optJSONObject("phase")?.text("name")).distinct().joinToString(" · "),
        )

        fun parseChannels(live: JSONObject): Map<String, ChannelTeam> =
            live.optJSONArray("series")?.objects().orEmpty().flatMap { it.optJSONArray("streams")?.objects().orEmpty() }.mapNotNull { stream ->
                val team = stream.optJSONObject("team") ?: return@mapNotNull null
                stream.getString("channelName").lowercase() to ChannelTeam(team.getString("name"), team.optString("shortName"), team.text("logoDark"))
            }.toMap()

        fun parseLiveSeries(live: JSONObject): SeriesRef? =
            live.optJSONArray("series")?.objects().orEmpty().firstOrNull { it.optString("status") == "live" }?.let(::ref)

        /** The most recent set played. ISO dates in UTC sort as text. */
        fun parseLastSeries(season: JSONObject): SeriesRef? =
            season.optJSONArray("series")?.objects().orEmpty().filter { it.optString("status") == "completed" }.maxByOrNull { it.optString("startsAt") }?.let(::ref)

        fun parseStandings(stats: JSONObject, ref: SeriesRef, matches: JSONObject?): Standings = Standings(
            name = ref.name,
            event = ref.event,
            live = stats.optString("seriesStatus") == "live",
            match = matches?.let(::matchLine),
            teams = stats.optJSONArray("teams")?.objects().orEmpty().map { t ->
                TeamStanding(
                    t.optInt("position"), t.optString("name"), t.optString("shortName"), t.text("logoDark"),
                    t.optInt("points"), t.optInt("kills"), t.optInt("placementPoints"),
                    t.optBoolean("eliminated"), t.optBoolean("matchPointEligible"), t.optBoolean("wonMatchPoint"),
                )
            }.sortedBy { it.position },
        )

        /** "Match 6 live, Storm Point" or "After match 5, Storm Point". */
        fun matchLine(matches: JSONObject): String? {
            val list = matches.optJSONArray("matches")?.objects().orEmpty()
            val live = list.lastOrNull { it.optString("status") == "live" }
            val at = live ?: list.lastOrNull { it.optString("status") == "completed" } ?: return null
            val map = at.optJSONObject("map")?.text("name")
            val number = at.optInt("matchNumber")
            return (if (live != null) "Match $number live" else "After match $number") + (map?.let { ", $it" } ?: "")
        }
    }
}
