package app.algsview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.random.Random

/**
 * What a tile plays: a live channel ([login]), a past broadcast ([vodId], from [startMs]), or
 * nothing yet while it waits for a followed team. [follow] is the team the tile follows.
 */
data class Channel(
    val title: String,
    val login: String? = null,
    val vodId: String? = null,
    val startMs: Long = 0L,
    val follow: String? = null,
    val image: String? = null,
) {
    /** Identifies the stream, whatever its label says this match. */
    /** The map cam has no sound, and it's small text and icons. */
    val isMapCam get() = login?.endsWith("_map") == true

    val key get() = follow?.let { "follow:$it" } ?: vodId?.let { "vod:$it" } ?: "live:$login"
}

/** A channel's state from the last poll. */
data class StreamInfo(val login: String, val displayName: String, val title: String, val live: Boolean, val viewers: Int, val image: String?)

/** A past broadcast. */
data class Vod(val id: String, val title: String, val seconds: Int, val image: String?, val publishedAt: String)

/**
 * Twitch's private web API, the one its own site uses: it can change without notice. No sign-in;
 * the client ID is the public one in Twitch's web player. Streamlink's Twitch plugin is the
 * reference for the shape of these calls.
 */
class TwitchApi {
    private val http = OkHttpClient()

    private suspend fun gql(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://gql.twitch.tv/gql")
            .header("Client-ID", CLIENT_ID)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Twitch answered ${r.code}")
            val json = JSONObject(r.body?.string().orEmpty())
            if (json.isNull("data")) throw IOException(json.optJSONArray("errors")?.optJSONObject(0)?.optString("message") ?: "Twitch refused")
            json.getJSONObject("data")
        }
    }

    /** Titles and live status of many channels in one request. Channels that don't exist are left out. */
    suspend fun streams(logins: List<String>): List<StreamInfo> {
        val query = "query(\$l:[String!]){users(logins:\$l){login displayName broadcastSettings{title} " +
            "stream{id viewersCount previewImageURL(width:640,height:360)}}}"
        val users = gql(JSONObject().put("query", query).put("variables", JSONObject().put("l", JSONArray(logins)))).getJSONArray("users")
        return (0 until users.length()).mapNotNull { i ->
            val u = users.optJSONObject(i) ?: return@mapNotNull null
            val stream = u.optJSONObject("stream")
            StreamInfo(
                login = u.getString("login"),
                displayName = u.optString("displayName"),
                title = u.optJSONObject("broadcastSettings")?.optString("title").orEmpty(),
                live = stream != null,
                viewers = stream?.optInt("viewersCount") ?: 0,
                image = stream?.optString("previewImageURL")?.ifEmpty { null },
            )
        }
    }

    suspend fun recentVods(login: String, count: Int = 12): List<Vod> {
        val query = "query(\$l:String!,\$n:Int!){user(login:\$l){videos(first:\$n,type:ARCHIVE){edges{node{id title lengthSeconds publishedAt " +
            "previewThumbnailURL(width:640,height:360)}}}}}"
        val data = gql(JSONObject().put("query", query).put("variables", JSONObject().put("l", login).put("n", count)))
        val edges = data.optJSONObject("user")?.optJSONObject("videos")?.optJSONArray("edges") ?: return emptyList()
        return (0 until edges.length()).map { i ->
            val n = edges.getJSONObject(i).getJSONObject("node")
            Vod(n.getString("id"), n.optString("title"), n.optInt("lengthSeconds"), n.optString("previewThumbnailURL").ifEmpty { null }, n.optString("publishedAt"))
        }
    }

    /** A fresh playable URL for the channel. Tokens expire, so this is asked for on every (re)load. */
    suspend fun playbackUrl(channel: Channel): String {
        val isVod = channel.vodId != null
        val login = channel.login.orEmpty()
        val data = gql(
            JSONObject().put("operationName", "PlaybackAccessToken").put("query", TOKEN_QUERY).put(
                "variables",
                JSONObject().put("isLive", !isVod).put("login", login).put("isVod", isVod)
                    // Measured Oct 31, 2026: of 15 player types tried, only "embed" starts a live
                    // stream with no preroll, at full quality. Mid-stream ads are still filtered.
                    .put("vodID", channel.vodId.orEmpty()).put("playerType", if (isVod) "site" else "embed"),
            )
        )
        val token = data.optJSONObject(if (isVod) "videoPlaybackAccessToken" else "streamPlaybackAccessToken")
            ?: throw IOException("Twitch has no stream here")
        val query = "sig=${enc(token.getString("signature"))}&token=${enc(token.getString("value"))}&allow_source=true&supported_codecs=avc1"
        return if (isVod) "https://usher.ttvnw.net/vod/${channel.vodId}.m3u8?$query"
        else "https://usher.ttvnw.net/api/channel/hls/$login.m3u8?$query&fast_bread=true&p=${Random.nextInt(1_000_000)}"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        private const val TOKEN_QUERY = "query PlaybackAccessToken(\$login:String!,\$isLive:Boolean!,\$vodID:ID!,\$isVod:Boolean!,\$playerType:String!){" +
            "streamPlaybackAccessToken(channelName:\$login,params:{platform:\"web\",playerBackend:\"mediaplayer\",playerType:\$playerType})@include(if:\$isLive){value signature} " +
            "videoPlaybackAccessToken(id:\$vodID,params:{platform:\"web\",playerBackend:\"mediaplayer\",playerType:\$playerType})@include(if:\$isVod){value signature}}"
    }
}

/**
 * What someone typed into Add channel or link: a login, a twitch.tv channel link, or a
 * twitch.tv/videos/… link, with its ?t= start. Null if it isn't any of those.
 */
fun parseTwitchTarget(input: String): Channel? {
    val text = input.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("m.")
    if (text.isEmpty()) return null
    val path = text.removePrefix("twitch.tv/").substringBefore('?').trim('/')
    Regex("videos/(\\d+)").matchEntire(path)?.let { m ->
        val t = text.substringAfter("?", "").split('&').firstOrNull { it.startsWith("t=") }?.removePrefix("t=")
        return Channel("Video ${m.groupValues[1]}", vodId = m.groupValues[1], startMs = t?.let(::parseTwitchTime) ?: 0L)
    }
    Regex("v?(\\d{6,})").matchEntire(path)?.let { return Channel("Video ${it.groupValues[1]}", vodId = it.groupValues[1]) }
    return if (Regex("[A-Za-z0-9_]{2,40}").matches(path)) Channel(path.lowercase(), login = path.lowercase()) else null
}

/** 1h2m3s to milliseconds. */
fun parseTwitchTime(t: String): Long =
    Regex("(\\d+)([hms])").findAll(t).sumOf { m ->
        m.groupValues[1].toLong() * when (m.groupValues[2]) { "h" -> 3_600_000L; "m" -> 60_000L; else -> 1_000L }
    }
