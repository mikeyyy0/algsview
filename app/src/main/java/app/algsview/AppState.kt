package app.algsview

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A feed's window on the canvas. Positions and sizes are in pixels. */
class Tile(val id: Int, channel: Channel, val feed: FeedPlayer, val accent: Int) {
    var channel by mutableStateOf(channel)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var w by mutableFloatStateOf(640f)
    var h by mutableFloatStateOf(360f)
    var z by mutableFloatStateOf(0f)
    var audible by mutableStateOf(false)
    /** Pinned tiles stay put when feeds are swapped in. */
    var pinned by mutableStateOf(false)
    /** Showing, but its picture paused while the phone is too hot (sound and place kept). */
    var coolingOff by mutableStateOf(false)

    fun resize(width: Float) {
        w = width.coerceIn(MIN_WIDTH, 4000f)
        h = w * 9f / 16f
    }

    fun place(slot: Slot) {
        x = slot.x
        y = slot.y
        resize(slot.w)
    }

    companion object {
        const val MIN_WIDTH = 240f
    }
}

/**
 * Pinch zoom on a picture: 1 is the whole picture. The offset is in fractions of the picture's box,
 * so the same part stays framed at any size.
 */
class PictureZoom(zoom: Float = 1f, x: Float = 0f, y: Float = 0f) {
    var zoom by mutableFloatStateOf(zoom)
        private set
    var x by mutableFloatStateOf(x)
        private set
    var y by mutableFloatStateOf(y)
        private set

    /** Zooms by [factor] around ([cx], [cy]), measured from the box's centre, and moves by the pan, staying over the picture. */
    fun zoomBy(factor: Float, cx: Float, cy: Float, panX: Float, panY: Float, width: Float, height: Float) {
        val next = (zoom * factor).coerceIn(1f, MAX)
        val k = next / zoom
        val limit = (next - 1f) / 2f
        x = (x * k + (cx * (1f - k) + panX) / width).coerceIn(-limit, limit)
        y = (y * k + (cy * (1f - k) + panY) / height).coerceIn(-limit, limit)
        zoom = next
    }

    fun reset() {
        zoom = 1f
        x = 0f
        y = 0f
    }

    fun toText() = "$zoom,$x,$y"

    companion object {
        private const val MAX = 6f

        fun fromText(text: String?): PictureZoom {
            val v = text?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 } ?: return PictureZoom()
            return PictureZoom(v[0], v[1], v[2])
        }
    }
}

/** The map's own window. Any shape; the image is fitted inside it. */
class MapPanel {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var w by mutableFloatStateOf(600f)
    var h by mutableFloatStateOf(600f)
    /** Turn, in degrees. */
    var rotation by mutableFloatStateOf(0f)
    /** False until placed, by default or from storage. */
    var placed = false

    fun toJson(width: Float, height: Float, opacity: Float): String = JSONObject()
        .put("x", (x / width).toDouble()).put("y", (y / height).toDouble())
        .put("w", (w / width).toDouble()).put("h", (h / height).toDouble())
        .put("rotation", rotation.toDouble()).put("opacity", opacity.toDouble())
        .toString()

    /** Puts back a stored panel on a canvas of this size; returns its opacity. */
    fun restore(text: String?, width: Float, height: Float): Float? = runCatching {
        val j = JSONObject(text ?: return null)
        x = j.getDouble("x").toFloat() * width
        y = j.getDouble("y").toFloat() * height
        w = j.getDouble("w").toFloat() * width
        h = j.getDouble("h").toFloat() * height
        rotation = j.getDouble("rotation").toFloat()
        placed = true
        j.getDouble("opacity").toFloat()
    }.getOrNull()

    fun place(area: Area) {
        x = area.x
        y = area.y
        w = area.w
        h = area.h
    }

    fun scale(factor: Float) {
        val cx = x + w / 2
        val cy = y + h / 2
        w = (w * factor).coerceIn(MIN_SIZE, 5000f)
        h = (h * factor).coerceIn(MIN_SIZE, 5000f)
        x = cx - w / 2
        y = cy - h / 2
    }

    companion object {
        const val MIN_SIZE = 200f
    }
}

/** Where the browse side of the app is. The viewer sits on top whenever feeds are playing. */
sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
}

/**
 * Enough to put a viewing session back as it was: its feeds, which one was big, where the audio
 * was, and the layout. Saved to storage, so it survives a crash or a force-close. Live feeds come
 * back at the live edge; past broadcasts where they were.
 */
data class SavedWatch(
    val title: String,
    val image: String?,
    val feeds: List<Channel>,
    val bigKey: String,
    val audioKey: String,
    val layoutMode: String,
    val layout: SavedLayout? = null,
) {
    /** Sessions are the same if they hold the same feeds. */
    val id get() = feeds.map { it.key }.sorted().joinToString("|")

    fun toJsonObject(): JSONObject = JSONObject()
        .put("title", title).put("image", image ?: "")
        .put("feeds", JSONArray(feeds.map { c ->
            JSONObject().put("title", c.title).put("login", c.login ?: "").put("vodId", c.vodId ?: "")
                .put("startMs", c.startMs).put("follow", c.follow ?: "").put("image", c.image ?: "")
        }))
        .put("bigKey", bigKey).put("audioKey", audioKey).put("layoutMode", layoutMode)
        .put("layout", layout?.toJson() ?: "")

    companion object {
        /** The recently watched list, newest first. */
        fun listFromJson(text: String?): List<SavedWatch> = runCatching {
            val list = JSONArray(text ?: return emptyList())
            (0 until list.length()).mapNotNull { fromJson(list.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<SavedWatch>): String = JSONArray(list.map { it.toJsonObject() }).toString()

        private fun fromJson(j: JSONObject): SavedWatch? = runCatching {
            val feeds = j.getJSONArray("feeds")
            SavedWatch(
                title = j.getString("title"),
                image = j.optString("image").ifEmpty { null },
                feeds = (0 until feeds.length()).map { i ->
                    val c = feeds.getJSONObject(i)
                    Channel(
                        c.getString("title"), c.optString("login").ifEmpty { null }, c.optString("vodId").ifEmpty { null },
                        c.optLong("startMs"), c.optString("follow").ifEmpty { null }, c.optString("image").ifEmpty { null },
                    )
                },
                bigKey = j.getString("bigKey"),
                audioKey = j.getString("audioKey"),
                layoutMode = j.getString("layoutMode"),
                layout = SavedLayout.fromJson(j.optString("layout").ifEmpty { null }),
            )
        }.getOrNull()
    }
}

/** The sheet for choosing what to watch. */
class FeedPicker {
    /** New feeds to start. */
    val selected = mutableStateListOf<Channel>()
    /** Feeds on screen that were unpicked, to make room. */
    val removing = mutableStateListOf<Channel>()
    /** Shown under the title when a pick doesn't fit. */
    var note by mutableStateOf<String?>(null)
    var search by mutableStateOf("")
    var link by mutableStateOf("")
}

class AppState(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Opens the system photo picker; the choice comes back to [onMapPicked]. */
    private val openImagePicker: () -> Unit,
) {
    private val twitch = TwitchApi()
    private val algs = AlgsApi()
    private val prefs = context.getSharedPreferences("algsview", Context.MODE_PRIVATE)

    // Video budget: only feeds you can see decode video, each at its tile's size, less as the phone heats up.

    private val power = context.getSystemService(android.os.PowerManager::class.java)
    /** The phone's thermal status (PowerManager.THERMAL_STATUS_*). */
    var thermal by mutableIntStateOf(power?.currentThermalStatus ?: android.os.PowerManager.THERMAL_STATUS_NONE)
        private set
    private val thermalListener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
        val before = thermal
        thermal = status
        when {
            status >= android.os.PowerManager.THERMAL_STATUS_CRITICAL && before < android.os.PowerManager.THERMAL_STATUS_CRITICAL ->
                message = "The phone is very hot. Only the main feed shows video until it cools down; the others keep their sound and place."
            status >= android.os.PowerManager.THERMAL_STATUS_SEVERE && before < android.os.PowerManager.THERMAL_STATUS_SEVERE ->
                message = "The phone is running hot, so the feeds are at lower quality until it cools down."
        }
        applyVideoBudget()
    }

    init {
        power?.addThermalStatusListener(context.mainExecutor, thermalListener)
        // Feeds come and go, move, hide behind each other and off screen; twice a second is soon enough.
        // Waits first: this runs while AppState is still being built, before the tiles exist.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                applyVideoBudget()
            }
        }
    }

    fun close() {
        power?.removeThermalStatusListener(thermalListener)
    }

    /**
     * Video only for feeds that can be seen: not while browsing, not behind a full-screen feed,
     * not tucked away in laptop mode or hidden in the pop-out, not off the canvas or entirely
     * under another feed. Hidden ones keep playing their sound. The feed with the audio and the map
     * cam get the best rendition; the others too, as far as the connection allows, unless Lower
     * quality on quiet feeds is on (the size of the tile, and 480p or 360p with 3 or 4). Warm phones get less (3/4 size when hot, half when hotter, nothing forced
     * to the top), and a phone near its limit shows video in the main feed only.
     */
    fun applyVideoBudget() {
        if (tiles.isEmpty()) return
        // The map cam's picture runs shut too, for an instant peek, unless the phone needs the rest.
        // Nor while the map cam is on screen as a feed: no need to decode the same stream twice.
        mapCamFeed?.setVideo(
            !browsing && !inPip && (mapDrawerOpen || (thermal < android.os.PowerManager.THERMAL_STATUS_SEVERE && tiles.none { it.channel.isMapCam })),
            1920, 1080, source = true,
        )
        val shown = if (inPip) pipTile() else null
        val main = tiles.firstOrNull { it.id == bigTileId } ?: tiles.firstOrNull { it.audible } ?: tiles.maxByOrNull { it.w }
        val scale = when {
            thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> 0.5f
            thermal >= android.os.PowerManager.THERMAL_STATUS_MODERATE -> 0.75f
            else -> 1f
        }
        val quietCap = when {
            !settings.quietQuality -> Int.MAX_VALUE
            tiles.size >= 4 -> 360
            tiles.size == 3 -> 480
            else -> Int.MAX_VALUE
        }
        tiles.forEach { tile ->
            val visible = when {
                browsing -> false
                inPip -> tile === shown
                laptopActive -> tile.id == bigTileId || tiles.size == 1
                focusTileId != null -> tile.id == focusTileId
                else -> onCanvas(tile) && tile.y < mapDrawerTop && tiles.none { it !== tile && it.z > tile.z && covers(it, tile) }
            }
            // Laptop mode: the feed that was up top last keeps its picture going out of sight, so
            // going back to it is instant. Not once the phone is warm.
            val warm = !visible && !browsing && !inPip && laptopActive && tile.id == previousTopId &&
                thermal < android.os.PowerManager.THERMAL_STATUS_MODERATE
            if (warm) {
                tile.coolingOff = false
                tile.feed.keepVideo()
                return@forEach
            }
            val cooling = thermal >= android.os.PowerManager.THERMAL_STATUS_CRITICAL && tile !== main
            tile.coolingOff = visible && cooling
            // The pop-out stays on the rendition it has: another would stall it going in and coming back.
            if (inPip && visible && !cooling) {
                tile.feed.keepVideo()
                return@forEach
            }
            val hearing = tile.audible && !mutedAll
            // The map cam is small text and icons: it's useless below the top rendition.
            val top = hearing || tile.channel.isMapCam
            // Feeds get switched around a lot, so unless asked to save data, a small tile doesn't
            // mean a small rendition: it's already sharp when it's brought up.
            val fit = settings.quietQuality || scale < 1f
            tile.feed.setVideo(
                visible && !cooling,
                if (fit) (tile.w * scale).toInt() else FULL_W,
                if (fit) (tile.h * scale).toInt() else FULL_H,
                source = tile.channel.isMapCam || (hearing && scale == 1f),
                maxHeight = if (top) Int.MAX_VALUE else quietCap,
            )
        }
    }

    /** In laptop mode, the feed that was up top before the one there now. */
    private var previousTopId: Int? = null

    private fun onCanvas(t: Tile) =
        canvasW <= 0f || (t.x < canvasW && t.x + t.w > 0f && t.y < canvasH && t.y + t.h > 0f)

    private fun covers(a: Tile, b: Tile) =
        a.x <= b.x && a.y <= b.y && a.x + a.w >= b.x + b.w && a.y + a.h >= b.y + b.h
    val settings = Settings(context)
    val updater = Updater(context, scope)

    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    // Browse.

    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen get() = backStack.last()
    var homeError by mutableStateOf<String?>(null)
        private set
    var picker by mutableStateOf<FeedPicker?>(null)
        private set
    private val rowStates = mutableMapOf<String, LazyListState>()

    fun rowState(key: String) = rowStates.getOrPut(key) { LazyListState() }

    /** True while browsing on top of feeds that keep playing. */
    var browsing by mutableStateOf(false)

    /** Recent broadcasts on the main channel, for home. */
    val vods = mutableStateListOf<Vod>()

    /** Every polled channel, by login. */
    val streams = mutableStateMapOf<String, StreamInfo>()

    /** The live numbered team channels, in channel order. */
    val liveTeams
        get() = streams.values.filter { it.live && Teams.isTeamChannel(it.login, settings.teamPrefix) }
            .sortedBy { Regex("(\\d+)$").find(it.login)?.value?.toIntOrNull() ?: 0 }

    /** The main broadcast and map cams that are live. */
    val liveOthers
        get() = streams.values.filter { it.live && !Teams.isTeamChannel(it.login, settings.teamPrefix) }
            .sortedBy { Teams.BROADCASTS.indexOf(it.login).let { i -> if (i < 0) Teams.BROADCASTS.size else i } }

    fun channelOf(stream: StreamInfo) = Channel(labelOf(stream), login = stream.login, image = stream.image)

    /** Who's on each channel in the set on now, from the ALGS itself. */
    val channelTeams = mutableStateMapOf<String, ChannelTeam>()
    /** The current set's standings, once fetched. */
    var standings by mutableStateOf<Standings?>(null)
        private set
    /** Why the standings are missing or may be old. */
    var standingsError by mutableStateOf<String?>(null)
        private set
    var scoresOpen by mutableStateOf(false)

    suspend fun loadStandings() {
        runCatching { algs.standings() }
            .onSuccess { standings = it; standingsError = null }
            .onFailure { standingsError = "Couldn't update the standings: ${it.message}" }
    }

    /** The official team name where the ALGS lists one, else what the stream title says. */
    private fun labelOf(stream: StreamInfo) = channelTeams[stream.login]?.name ?: Teams.label(stream, settings.teamPrefix)

    /** Asks about every channel in one request; called every 30 s while the app is on screen. */
    suspend fun pollStreams() {
        runCatching { algs.channels() }.onSuccess { channelTeams.clear(); channelTeams.putAll(it) }
        val extra = tiles.mapNotNull { it.channel.login }
        val logins = (Teams.logins(settings.teamPrefix, settings.teamCount) + extra).distinct()
        val found = runCatching { twitch.streams(logins) }
        found.onFailure { homeError = "Couldn't reach Twitch: ${it.message}" }
        val list = found.getOrNull() ?: return
        homeError = null
        streams.clear()
        list.forEach { streams[it.login] = it }
        applyFollows(list)
        ensureMapCam()
        tiles.forEach { tile ->
            val login = tile.channel.login
            val stream = login?.let { streams[it] } ?: return@forEach
            val label = labelOf(stream)
            if (tile.channel.follow == null && tile.channel.title != label) tile.channel = tile.channel.copy(title = label)
        }
    }

    fun loadVods() {
        scope.launch { runCatching { twitch.recentVods(Teams.BROADCAST) }.onSuccess { vods.clear(); vods.addAll(it) } }
    }

    // Following a team from channel to channel.

    /** Swaps each following tile to where its team is now, in the same spot with the same audio. */
    private fun applyFollows(list: List<StreamInfo>) {
        val teamStreams = list.filter { Teams.isTeamChannel(it.login, settings.teamPrefix) }
        tiles.forEach { tile ->
            val name = tile.channel.follow ?: return@forEach
            val current = tile.channel.login
            when (val decision = Teams.decide(name, current, teamStreams)) {
                Teams.Decision.Stay -> Unit
                Teams.Decision.Wait -> {
                    tile.channel = waitingFor(name)
                    tile.feed.load(tile.channel)
                }
                is Teams.Decision.Move -> {
                    tile.channel = following(name, decision.to)
                    tile.feed.load(tile.channel)
                    if (current != null) message = "$name moved to ${Teams.channelLabel(decision.to.login)}"
                }
            }
        }
    }

    private fun waitingFor(name: String) = Channel("Waiting for $name", follow = name)

    private fun following(name: String, stream: StreamInfo) =
        Channel(Teams.teamName(stream.title).ifEmpty { name }, login = stream.login, follow = name, image = stream.image)

    /** A channel entry that follows [name], on its channel now if it's live. */
    fun followChannel(name: String): Channel = Teams.find(name, liveTeams)?.let { following(name, it) } ?: waitingFor(name)

    /** Follows the team on [tile]'s channel now, or stops following. */
    fun toggleFollow(tile: Tile) {
        tileMenu = null
        val name = tile.channel.follow
        if (name != null) {
            tile.channel = tile.channel.copy(follow = null)
            if (tile.channel.login == null) remove(tile)
            return
        }
        val stream = tile.channel.login?.let { streams[it] } ?: return
        val team = Teams.teamName(stream.title).ifEmpty { return }
        tile.channel = tile.channel.copy(follow = team, title = team)
    }

    /**
     * The last few sessions watched, newest first. From storage, so they survive a crash or a
     * force-close. Shown on home as Continue watching.
     */
    val recents = mutableStateListOf<SavedWatch>().apply {
        addAll(SavedWatch.listFromJson(prefs.getString(PREF_RECENTS, null)))
    }
    private var saveTicks = 0

    /** The name of what has the audio. */
    val playingTitle get() = (tiles.firstOrNull { it.audible } ?: tiles.firstOrNull())?.channel?.title.orEmpty()

    // Viewer.

    val tiles = mutableStateListOf<Tile>()
    var layoutMode by mutableStateOf(LayoutMode.FEATURED)
        private set
    /** Bumped to ask the canvas to lay the tiles out again. */
    var layoutRequest by mutableIntStateOf(0)
        private set
    private var layoutDone = 0
    private var topZ = 0f
    private var nextTileId = 0
    /** The tile whose menu is open, if any. */
    var tileMenu by mutableStateOf<Tile?>(null)
        private set
    /** The feed in the big slot of the main + strip layout; double-tap changes it. */
    // State, so what depends on which feed is up top in laptop mode redraws.
    private var bigTileId by mutableStateOf<Int?>(null)
    var layoutSheetOpen by mutableStateOf(false)
    /**
     * Moving and resizing tiles only while unlocked from the top bar, so a stray touch while
     * watching can't knock the layout around.
     */
    var arranging by mutableStateOf(false)
        private set
    /** While arranging: magnetic edges and no overlaps unless pushed hard (Snap), or Free. */
    var snapMode by mutableStateOf(prefs.getBoolean(PREF_SNAP, true))
        private set
    /** True once feeds have been moved by hand (or put back from a saved session). */
    private var handPlaced = false
    /** What a fresh stream starts in; Save layout makes it the custom one. */
    private var defaultLayoutMode =
        prefs.getString(PREF_DEFAULT_LAYOUT, null)?.let { runCatching { LayoutMode.valueOf(it) }.getOrNull() } ?: LayoutMode.FEATURED
    /** A resumed session's feed boxes by feed, applied at its first layout. */
    private var resumeLayout: Pair<Map<String, FloatArray>, SavedLayout>? = null
    /** Every feed silenced at once; the audio choice is kept for when it's turned back on. */
    var mutedAll by mutableStateOf(false)
        private set

    fun toggleArranging() {
        arranging = !arranging
        message = if (arranging) {
            if (snapMode) "Drag to move; edges snap. Push hard to overlap. Pinch or use the corner to resize. Tap the lock when done."
            else "Drag to move, pinch or use the corner to resize. Tap the lock when done."
        } else null
        if (!arranging) storeMapPanel()
        showChrome()
    }

    // Shared playback controls, driven by the leading tile.
    var chromeVisible by mutableStateOf(true)
    /** Bumped on every interaction with the viewer; the chrome hides a few seconds after. */
    var chromeTouch by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(true)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var isLive by mutableStateOf(true)
        private set

    // Browse.

    fun open(screen: Screen) {
        backStack.add(screen)
    }

    /** Returns false when there's nowhere further back to go. */
    fun back(): Boolean = when {
        picker != null -> { picker = null; true }
        backStack.size > 1 -> { backStack.removeAt(backStack.lastIndex); true }
        browsing && tiles.isNotEmpty() -> { browsing = false; true }
        else -> false
    }

    // Choosing feeds.

    /** Opens the feed sheet; feeds already on screen show as picked. */
    fun openPicker() {
        picker = FeedPicker()
        scope.launch { pollStreams() }
    }

    fun closePicker() {
        picker = null
    }

    fun isPlaying(channel: Channel) = tiles.any { it.channel.key == channel.key || (channel.login != null && it.channel.login == channel.login) }

    private fun onScreen(channel: Channel) = tiles.firstOrNull { it.channel.key == channel.key || (channel.login != null && it.channel.login == channel.login) }?.channel

    /** Feeds there would be on screen if the sheet's picks were applied. */
    fun pickedCount(sheet: FeedPicker) = tiles.size - sheet.removing.size + sheet.selected.size

    /**
     * Feeds already on screen start out picked; tapping one unpicks it. Any number can be picked.
     */
    fun togglePick(channel: Channel) {
        val sheet = picker ?: return
        sheet.note = null
        val playing = onScreen(channel)
        when {
            playing != null && sheet.removing.any { it.key == playing.key } -> sheet.removing.removeAll { it.key == playing.key }
            playing != null -> sheet.removing.add(playing)
            sheet.selected.any { it.key == channel.key } -> sheet.selected.removeAll { it.key == channel.key }
            else -> sheet.selected.add(channel)
        }
    }

    /**
     * Applies the sheet. A feed unpicked and another picked in its place take over the same tile,
     * so the layout and the audio stay put; leftovers are removed or added.
     */
    fun watchPicked() {
        val sheet = picker ?: return
        if (sheet.selected.isEmpty() && sheet.removing.isEmpty()) return
        // Silent tiles are swapped first, so the audio stays where it is.
        val freed = tiles.filter { t -> sheet.removing.any { it.key == t.channel.key } }.sortedBy { it.audible }.toMutableList()
        val adds = sheet.selected.toMutableList()
        while (freed.isNotEmpty() && adds.isNotEmpty()) switchFeed(freed.removeAt(0), adds.removeAt(0))
        freed.forEach(::remove)
        if (adds.isNotEmpty()) addFeeds(adds)
        picker = null
        browsing = false
    }

    /** From home: this stream on screen, joining whatever is already playing. */
    fun watch(channel: Channel) {
        browsing = false
        if (isPlaying(channel)) return
        addFeeds(listOf(channel))
    }

    private fun addFeeds(requested: List<Channel>) {
        val first = tiles.isEmpty()
        // A feed added while one fills the screen brings the whole layout back.
        focusTileId = null
        requested.forEach { channel ->
            val feed = FeedPlayer(context, scope, twitch)
            val used = tiles.map { it.accent }
            // Colours repeat past the first few feeds.
            val tile = Tile(nextTileId++, channel, feed, Accents.indices.firstOrNull { it !in used } ?: (tiles.size % Accents.size))
            tiles.add(tile)
            raise(tile)
            feed.load(channel)
        }
        // A fresh start uses the layout picked as default (Save layout makes it the custom one).
        if (first && resumeLayout == null) layoutMode = defaultLayoutMode
        ensureMapCam()
        // Keep the audio where it was; new feeds start muted.
        (tiles.firstOrNull { it.audible } ?: tiles.firstOrNull { !it.channel.isMapCam })?.let(::setAudio)
        // A feed more means a fresh fit of the layout in use.
        handPlaced = false
        layoutRequest++
        showChrome()
    }

    // Saving and resuming.

    /** Records what's on screen. Called every few seconds while watching, and on the way out. */
    fun saveWatch() {
        if (tiles.isEmpty()) return
        val big = tiles.firstOrNull { it.id == bigTileId } ?: tiles.first()
        val audio = tiles.firstOrNull { it.audible } ?: big
        val feeds = tiles.map { t -> if (t.channel.vodId != null) t.channel.copy(startMs = t.feed.player.currentPosition) else t.channel }
        val names = bigFirst().map { it.channel.title }
        val saved = SavedWatch(
            title = if (names.size > 1) "${names.first()} + ${names.size - 1} more" else names.first(),
            image = big.channel.image ?: tiles.firstNotNullOfOrNull { it.channel.image },
            feeds = feeds,
            bigKey = big.channel.key,
            audioKey = audio.channel.key,
            layoutMode = layoutMode.name,
            layout = if (canvasW > 0f) SavedLayout.of(tiles.map { Slot(it.x, it.y, it.w) }, canvasW, canvasH) else null,
        )
        recents.removeAll { it.id == saved.id }
        recents.add(0, saved)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
        prefs.edit().putString(PREF_RECENTS, SavedWatch.listToJson(recents)).apply()
    }

    /** Puts a saved session back: same feeds, layout, big tile and audio. */
    fun resumeWatching(saved: SavedWatch) {
        if (tiles.isNotEmpty()) stopAll()
        runCatching { layoutMode = LayoutMode.valueOf(saved.layoutMode) }
        // Feeds come back where they were, if the screen is the same shape.
        resumeLayout = saved.layout?.let { layout -> saved.feeds.map { it.key }.zip(layout.boxes).toMap() to layout }
        browsing = false
        addFeeds(saved.feeds)
        tiles.firstOrNull { it.channel.key == saved.audioKey }?.let(::setAudio)
        tiles.firstOrNull { it.channel.key == saved.bigKey }?.let { bigTileId = it.id }
    }

    // Tiles.

    fun openTileMenu(tile: Tile) {
        tileMenu = tile
    }

    fun closeTileMenu() {
        tileMenu = null
    }

    /** Puts another stream in the tile, keeping its place, size and audio. */
    fun switchFeed(tile: Tile, channel: Channel) {
        tileMenu = null
        if (channel.key == tile.channel.key) return
        val hadAudio = tile.audible
        tile.channel = channel
        tile.feed.load(channel)
        // A feed with the audio switched to the map cam hands it on; silence can't be "heard".
        if (channel.isMapCam && hadAudio) {
            tile.audible = false
            tiles.firstOrNull { !it.channel.isMapCam }?.let(::setAudio)
        }
        if (laptopActive && tile.id == bigTileId) applyVideoBudget()
    }

    /** Lays the feeds out afresh in [mode]. */
    fun chooseLayout(mode: LayoutMode) {
        layoutMode = mode
        handPlaced = false
        layoutRequest++
        showChrome()
    }

    /** The saved arrangement for [n] feeds, if there is one for a screen this shape. */
    fun savedLayout(n: Int): SavedLayout? =
        SavedLayout.fromJson(prefs.getString(layoutKey(n), null))?.takeIf { canvasW <= 0f || it.fits(canvasW, canvasH) }

    /** Saved layouts are kept per way up: sideways ones carry an L. */
    private fun layoutKey(n: Int) = PREF_LAYOUT + n + if (canvasW > canvasH) "L" else ""

    /** Upright or sideways, in words, for messages. */
    val wayUp get() = if (canvasW > canvasH) "sideways" else "upright"

    /** Makes the arrangement on screen the one every session with this many feeds starts in. */
    fun saveLayoutAsDefault() {
        if (tiles.isEmpty() || canvasW <= 0f) return
        val ordered = bigFirst()
        prefs.edit()
            .putString(layoutKey(ordered.size), SavedLayout.of(ordered.map { Slot(it.x, it.y, it.w) }, canvasW, canvasH).toJson())
            .putString(PREF_DEFAULT_LAYOUT, LayoutMode.CUSTOM.name)
            .apply()
        defaultLayoutMode = LayoutMode.CUSTOM
        layoutMode = LayoutMode.CUSTOM
        message = "Saved. Sessions with ${ordered.size} feed${if (ordered.size == 1) "" else "s"} start like this when the phone is $wayUp."
    }

    fun chooseSnap(on: Boolean) {
        snapMode = on
        prefs.edit().putBoolean(PREF_SNAP, on).apply()
    }

    /** The big feed (double-tapped in, else the one with the audio) first, then the rest. */
    private fun bigFirst(): List<Tile> {
        val big = tiles.firstOrNull { it.id == bigTileId } ?: tiles.firstOrNull { it.audible } ?: tiles.firstOrNull()
        return listOfNotNull(big) + tiles.filter { it !== big }
    }

    /**
     * Lays out only when asked: feeds added or removed, a layout picked, the phone folded or
     * unfolded. Switching a feed never comes through here, so the feed keeps its spot.
     */
    fun layoutIfRequested(width: Float, height: Float) {
        if (layoutDone == layoutRequest) return
        layoutDone = layoutRequest
        try {
            layOut(width, height)
        } finally {
            glideNextLayout = false
        }
    }

    private fun layOut(width: Float, height: Float) {
        val ordered = bigFirst()
        bigTileId = ordered.firstOrNull()?.id
        placeMaps(width, height)

        // Laptop mode: the main feed fills the screen above the hinge; the rest wait off screen,
        // still playing. The half below is the feed list and the map.
        hinge?.takeIf { laptopActive }?.let { (top, _) ->
            val main = ordered.first()
            settle(main, Layouts.place(LayoutMode.GRID, 1, width, top).first())
            raise(main)
            // The others slide off to the side, keeping their size, and slide back later.
            tiles.filter { it !== main }.forEach { settle(it, Slot(width + it.w, it.y, it.w)) }
            return
        }

        // One feed filling the screen: it gets the whole canvas, the rest wait off screen.
        // Unfolding to the big screen ends it.
        tiles.firstOrNull { it.id == focusTileId }?.takeIf { compactCanvas }?.let { focus ->
            focus.place(Layouts.place(LayoutMode.GRID, 1, width, height).first())
            raise(focus)
            tiles.filter { it !== focus }.forEach { it.place(Slot(width * 2, 0f, it.w)) }
            return
        }
        focusTileId = null

        // Coming back to a session puts its feeds back where they were.
        resumeLayout?.let { (boxes, layout) ->
            resumeLayout = null
            if (layout.fits(width, height) && ordered.all { it.channel.key in boxes }) {
                ordered.forEach { tile ->
                    val b = boxes.getValue(tile.channel.key)
                    tile.place(Slot(b[0] * width, b[1] * height, b[2] * width))
                }
                handPlaced = true
                return
            }
        }

        val custom = if (layoutMode == LayoutMode.CUSTOM) savedLayout(ordered.size)?.takeIf { it.fits(width, height) } else null
        val slots = custom?.slots(width, height) ?: Layouts.place(layoutMode, ordered.size, width, height)
        slots.zip(ordered).forEach { (slot, tile) -> settle(tile, slot) }
    }

    /** The canvas's size in pixels, for keeping things on screen. */
    val canvasSize get() = canvasW to canvasH

    // The map cam: not a feed, but a drawer of its own under the main feed.

    /** The map cam's channel, while it's live. */
    val mapCamLogin get() = streams.values.firstOrNull { it.live && it.login.endsWith("_map") }?.login
    /** Its player, running the whole time feeds are on screen, so opening the drawer is instant. */
    var mapCamFeed by mutableStateOf<FeedPlayer?>(null)
        private set
    var mapDrawerOpen by mutableStateOf(false)
        private set
    /** Where the drawer starts on screen, in pixels; feeds entirely below it have no picture to show. */
    var mapDrawerTop by mutableFloatStateOf(Float.MAX_VALUE)
    /** Remembered between openings, and between sessions. */
    val mapCamZoom = PictureZoom.fromText(prefs.getString(PREF_MAP_CAM_ZOOM, null))

    /** Starts the map cam once there are feeds to go with it and it's on air. */
    private fun ensureMapCam() {
        if (mapCamFeed != null || tiles.isEmpty()) return
        val login = mapCamLogin ?: return
        mapCamFeed = FeedPlayer(context, scope, twitch).apply {
            setAudible(false)
            setVideo(true, 1920, 1080, source = true)
            player.playWhenReady = playing
            load(Channel("Map cam", login = login))
        }
    }

    fun toggleMapDrawer() {
        mapDrawerOpen = !mapDrawerOpen
        if (!mapDrawerOpen) {
            mapDrawerTop = Float.MAX_VALUE
            storeMapCamZoom()
        }
        showChrome()
    }

    private fun stopMapCam() {
        mapCamFeed?.release()
        mapCamFeed = null
        mapDrawerOpen = false
        mapDrawerTop = Float.MAX_VALUE
        storeMapCamZoom()
    }

    fun storeMapCamZoom() {
        prefs.edit().putString(PREF_MAP_CAM_ZOOM, mapCamZoom.toText()).apply()
    }

    /** The bottom edge of the main feed, where the drawer can start without covering it. */
    fun mainFeedBottom(): Float? {
        hinge?.takeIf { laptopActive }?.let { return it.second }
        val main = tiles.firstOrNull { it.id == focusTileId } ?: tiles.firstOrNull { it.id == bigTileId && layoutMode != LayoutMode.GRID }
            ?: tiles.minWithOrNull(compareBy<Tile>({ -it.w }, { it.y }))
        return main?.let { it.y + it.h }
    }

    // The map: images the person imported, with their pins.

    val maps = mutableStateListOf<RefMap>().apply { addAll(RefMap.listFromJson(prefs.getString(PREF_MAPS, null))) }
    /** The map on screen; null hides it. */
    var currentMap by mutableStateOf(maps.firstOrNull { it.id == prefs.getString(PREF_CURRENT_MAP, null) })
        private set
    var mapSheetOpen by mutableStateOf(false)
    /** The colour new pins get, as an index into [Accents]. */
    var pinColor by mutableIntStateOf(0)
    /** An image picked and waiting for its name. */
    var mapToName by mutableStateOf<Uri?>(null)
        private set
    /** The photo picker is up: the app stopping for it isn't the person leaving. */
    var pickingImage = false
        private set
    val mapPanel = MapPanel()
    /** The map's window in laptop mode, below the hinge; placed and remembered on its own. */
    val laptopMapPanel = MapPanel()
    /** The map window on screen now. */
    val activeMapPanel get() = if (laptopActive) laptopMapPanel else mapPanel
    var mapOpacity by mutableFloatStateOf(0.9f)
        private set
    /** Keeps the map still while the feeds under it are arranged. */
    var mapLocked by mutableStateOf(prefs.getBoolean(PREF_MAP_LOCKED, false))
        private set

    fun pickMapImage() {
        pickingImage = true
        openImagePicker()
    }

    fun onMapPicked(uri: Uri?) {
        pickingImage = false
        mapToName = uri
    }

    fun cancelMapImport() {
        mapToName = null
    }

    /** Copies the picked image into the app's storage under [name], and shows it. */
    fun importMap(name: String) {
        val uri = mapToName ?: return
        mapToName = null
        val id = System.currentTimeMillis().toString()
        val file = java.io.File(context.filesDir, "maps/$id").apply { parentFile?.mkdirs() }
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        if (copied.isFailure || bounds.outWidth <= 0) {
            file.delete()
            message = "That image couldn't be read."
            return
        }
        val map = RefMap(id, name, file.path, bounds.outWidth, bounds.outHeight)
        maps.add(map)
        storeMaps()
        chooseMap(map)
    }

    fun chooseMap(map: RefMap?) {
        currentMap = map
        prefs.edit().putString(PREF_CURRENT_MAP, map?.id).apply()
        if (canvasW > 0f) placeMaps(canvasW, canvasH)
    }

    fun removeMap(map: RefMap) {
        maps.removeAll { it.id == map.id }
        java.io.File(map.file).delete()
        storeMaps()
        if (currentMap?.id == map.id) chooseMap(null)
    }

    /** A tap on the map, in fractions of the image: drops a pin, or takes away the one it hit. */
    fun tapMap(x: Float, y: Float, reach: Float) {
        val map = currentMap ?: return
        updateMap(map.tapped(x, y, reach, pinColor))
        showChrome()
    }

    fun clearPins() {
        currentMap?.let { updateMap(it.copy(pins = emptyList())) }
    }

    private fun updateMap(map: RefMap) {
        currentMap = map
        maps.replaceAll { if (it.id == map.id) map else it }
        storeMaps()
    }

    private fun storeMaps() {
        prefs.edit().putString(PREF_MAPS, RefMap.listToJson(maps)).apply()
    }

    /** Each map window's first spot: where it was last left, else its default corner. */
    private fun placeMaps(width: Float, height: Float) {
        if (!mapPanel.placed) {
            mapPanel.restore(prefs.getString(PREF_MAP_PANEL, null), width, height)?.let { mapOpacity = it }
            if (!mapPanel.placed) {
                val side = minOf(width, height) * 0.42f
                mapPanel.place(Area(width - side, height - side, side, side))
                mapPanel.placed = true
            }
        }
        val bottom = hinge?.second
        if (!laptopMapPanel.placed && bottom != null) {
            laptopMapPanel.restore(prefs.getString(PREF_LAPTOP_MAP, null), width, height)
            if (!laptopMapPanel.placed) {
                val d = context.resources.displayMetrics.density
                val gap = 12 * d
                val corner = 40 * d
                val left = width * LAPTOP_LIST_SHARE + gap
                laptopMapPanel.place(Area(left, bottom + gap, width - left - corner, height - bottom - gap - corner))
                laptopMapPanel.placed = true
            }
        }
        keepMapOnScreen()
    }

    /** Pulls the map back onto the screen, no bigger than it, after the screen changes shape. */
    private fun keepMapOnScreen() {
        if (canvasW <= 0f) return
        for (panel in listOf(mapPanel, laptopMapPanel)) {
            if (!panel.placed) continue
            panel.w = panel.w.coerceAtMost(canvasW)
            panel.h = panel.h.coerceAtMost(canvasH)
            panel.x = panel.x.coerceIn(0f, canvasW - panel.w)
            panel.y = panel.y.coerceIn(0f, canvasH - panel.h)
        }
    }

    /** Keeps the map's place, size, turn and opacity for next time. */
    fun storeMapPanel() {
        val panel = activeMapPanel
        if (canvasW <= 0f || !panel.placed) return
        prefs.edit().putString(if (laptopActive) PREF_LAPTOP_MAP else PREF_MAP_PANEL, panel.toJson(canvasW, canvasH, mapOpacity)).apply()
    }

    fun toggleMapLock() {
        mapLocked = !mapLocked
        prefs.edit().putBoolean(PREF_MAP_LOCKED, mapLocked).apply()
    }

    fun rotateMap(degrees: Float) {
        activeMapPanel.rotation = (activeMapPanel.rotation + degrees).mod(360f)
        storeMapPanel()
    }

    fun resetMapRotation() {
        activeMapPanel.rotation = 0f
        storeMapPanel()
    }

    /** Moves, resizes and turns the map, while arranging. */
    fun arrangeMap(pan: androidx.compose.ui.geometry.Offset, zoom: Float, rotation: Float) {
        val panel = activeMapPanel
        panel.x += pan.x
        panel.y += pan.y
        if (zoom != 1f) panel.scale(zoom)
        if (rotation != 0f) panel.rotation = (panel.rotation + rotation).mod(360f)
        showChrome()
    }

    fun updateMapOpacity(value: Float) {
        mapOpacity = value
    }

    // The explanation overlay.

    var helpOpen by mutableStateOf(false)
        private set
    /** Where each labelled control is on screen, filled in as they're laid out. */
    val helpAnchors = mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>()
    /** True once the overlay has been seen; before that it opens by itself. */
    val helpSeen get() = prefs.getBoolean(PREF_HELP_SEEN, false)

    fun showHelp() {
        helpOpen = true
        chromeVisible = true
        prefs.edit().putBoolean(PREF_HELP_SEEN, true).apply()
    }

    fun closeHelp() {
        helpOpen = false
        showChrome()
    }

    // Moving and resizing tiles by hand.

    private var arrangeRaw: Box16? = null
    private var arrangeOthers: List<Pair<Tile, Box16>> = emptyList()

    private fun arranger() = context.resources.displayMetrics.density.let { d ->
        Arranger(canvasW, canvasH, snap = SNAP_DP * d, breakThrough = BREAK_DP * d, minWidth = Tile.MIN_WIDTH)
    }

    /** A finger (or two) came down on [tile] to move or resize it. */
    fun beginArrange(tile: Tile) {
        raise(tile)
        arrangeRaw = Box16(tile.x, tile.y, tile.w)
        arrangeOthers = tiles.filter { it !== tile }.map { it to Box16(it.x, it.y, it.w) }
    }

    /**
     * Moves the tile by [dx], [dy] and resizes it by [dw] (from a pinch or the corner handle). Free
     * mode goes exactly where the finger says. Snap mode applies the [Arranger]'s rules.
     */
    fun arrangeBy(tile: Tile, dx: Float, dy: Float, dw: Float) {
        val raw = (arrangeRaw ?: Box16(tile.x, tile.y, tile.w)).let { it.copy(x = it.x + dx, y = it.y + dy, w = it.w + dw) }
        arrangeRaw = raw
        if (!snapMode || canvasW <= 0f) {
            tile.x = raw.x
            tile.y = raw.y
            tile.resize(raw.w)
        } else {
            val arranger = arranger()
            if (dw != 0f) {
                val (grown, pushed) = arranger.resize(Box16(tile.x, tile.y, tile.w), raw.w, arrangeOthers.map { it.second })
                tile.resize(grown.w)
                arrangeOthers.zip(pushed).forEach { (pair, box) -> pair.first.place(Slot(box.x, box.y, box.w)) }
            }
            if (dx != 0f || dy != 0f) {
                val moved = arranger.move(raw.copy(w = tile.w), tiles.filter { it !== tile }.map { Box16(it.x, it.y, it.w) })
                tile.x = moved.x
                tile.y = moved.y
            }
        }
        showChrome()
    }

    fun endArrange() {
        arrangeRaw = null
        arrangeOthers = emptyList()
        handPlaced = true
        saveWatch()
    }

    fun togglePin(tile: Tile) {
        tile.pinned = !tile.pinned
    }

    private var canvasW = 0f
    private var canvasH = 0f

    /**
     * Each screen shape's hand-placed arrangement (by the way up and the screen's size), kept on
     * leaving it so that coming back puts the feeds where they were: tile id to x, y, w as
     * fractions of the screen.
     */
    private val arrangements = mutableMapOf<String, Map<Int, FloatArray>>()

    private fun screenKey(w: Float, h: Float) = (if (w > h) "L" else "P") + (minOf(w, h) / 200f).toInt()

    /**
     * Folding, unfolding or turning the phone: lay the feeds out again for the new screen, or put
     * back how they were last arranged on a screen this shape. The inner screen is nearly square,
     * so turning it changes its size by under 4%; it counts all the same. Smaller changes (system
     * bars, a window resize) only pull tiles back on screen.
     */
    fun onCanvasResized(width: Float, height: Float) {
        val turned = canvasW > 0f && (canvasW > canvasH) != (width > height)
        val refit = canvasW > 0f && (turned || abs(width - canvasW) > canvasW * 0.1f || abs(height - canvasH) > canvasH * 0.1f)
        if (refit && handPlaced && tiles.isNotEmpty() && focusTileId == null && !laptopActive) {
            arrangements[screenKey(canvasW, canvasH)] =
                tiles.associate { it.id to floatArrayOf(it.x / canvasW, it.y / canvasH, it.w / canvasW) }
        }
        if (refit && mapPanel.placed) {
            // The map keeps its place relative to the screen.
            mapPanel.x *= width / canvasW
            mapPanel.y *= height / canvasH
            mapPanel.w *= width / canvasW
            mapPanel.h *= height / canvasH
        }
        canvasW = width
        canvasH = height
        keepMapOnScreen()
        if (!refit) {
            keepOnScreen(width, height)
            return
        }
        // Back on a screen shape these same feeds were arranged on by hand: put them back.
        val back = arrangements[screenKey(width, height)]
            ?.takeIf { saved -> saved.size == tiles.size && tiles.all { it.id in saved } }
        if (back != null && focusTileId == null && !laptopActive) {
            tiles.forEach { t ->
                val b = back.getValue(t.id)
                t.place(Slot(b[0] * width, b[1] * height, b[2] * width))
            }
            handPlaced = true
        } else {
            handPlaced = false
            layoutRequest++
        }
    }

    /** Pulls tiles back so each one is at least partly on screen. */
    fun keepOnScreen(width: Float, height: Float) {
        // Feeds tucked away behind a full-screen one stay tucked away.
        tiles.filter { if (laptopActive) it.id == bigTileId else focusTileId == null || it.id == focusTileId }.forEach { tile ->
            if (tile.w > width) tile.resize(width)
            tile.x = tile.x.coerceIn(-tile.w / 2, width - tile.w / 2)
            tile.y = tile.y.coerceIn(0f, height - tile.h / 2)
        }
    }

    fun raise(tile: Tile) {
        topZ += 1f
        tile.z = topZ
    }

    /** Moves the audio to [tile]. Choosing a feed to listen to also ends Mute all. */
    fun setAudio(tile: Tile, byUser: Boolean = false) {
        // The map cam has no sound to move to.
        if (tile.channel.isMapCam) return
        if (byUser) mutedAll = false
        tiles.forEach {
            it.audible = it === tile
            it.feed.setAudible(it.audible && !mutedAll)
        }
    }

    /** Silences every feed, keeping track of which one had the audio for when it's turned back on. */
    fun toggleMuteAll() {
        mutedAll = !mutedAll
        tiles.forEach { it.feed.setAudible(it.audible && !mutedAll) }
        showChrome()
    }

    /** The map cam as a feed zooms where it's being watched; the zoom stays when it's small again. */
    fun canZoom(tile: Tile) = tile.channel.isMapCam &&
        (isMainTile(tile) || focusTileId == tile.id || (laptopActive && tile.id == bigTileId))

    /** The big feed of main + strip (and the saved layout), or the only feed. Grid has no main feed. */
    fun isMainTile(tile: Tile): Boolean {
        if (tiles.size == 1) return true
        if (layoutMode == LayoutMode.GRID) return false
        val big = tiles.firstOrNull { it.id == bigTileId } ?: tiles.maxByOrNull { it.w }
        return tile === big
    }

    /**
     * A tap on a feed: the audio follows it. Tapping the feed already heard shows or hides the
     * controls.
     */
    fun tapFeed(tile: Tile) {
        if (tile.channel.isMapCam || (tile.audible && !mutedAll)) toggleChrome()
        else {
            setAudio(tile, byUser = true)
            message = "Listening to ${tile.channel.title}"
        }
    }

    /**
     * On a narrow screen (the cover screen, where feeds stack), a double-tap fills the screen
     * with that feed and tucks the rest away (still playing); double-tap again to bring them
     * back. On the wide screen it swaps the feed into the big spot.
     */
    fun doubleTap(tile: Tile) {
        when {
            canZoom(tile) && mapCamZoom.zoom > 1f -> {
                mapCamZoom.reset()
                storeMapCamZoom()
            }
            laptopActive -> putOnTop(tile)
            focusTileId != null -> {
                focusTileId = null
                layoutRequest++
            }
            compactCanvas && tiles.size > 1 -> {
                focusTileId = tile.id
                layoutRequest++
            }
            else -> swapIntoBig(tile)
        }
    }

    // Laptop mode: the phone half-open with the hinge across, the stream above it and the rest below.

    /** Where the hinge runs across the screen (top, bottom), while half-open like a laptop. */
    var hinge by mutableStateOf<Pair<Float, Float>?>(null)
        private set
    /** Laptop mode is wanted when the phone is in that position. Off until asked. */
    var laptopEnabled by mutableStateOf(prefs.getBoolean(PREF_LAPTOP, false))
        private set
    /** The one-time question, showing. */
    var laptopPromptOpen by mutableStateOf(false)
        private set

    /** Laptop mode is on screen now. */
    val laptopActive get() = laptopEnabled && hinge != null && tiles.isNotEmpty()

    /** From the activity: the fold's posture changed. */
    fun updatePosture(newHinge: Pair<Float, Float>?) {
        val was = laptopActive
        hinge = newHinge
        // The first time the phone is held like a laptop while watching, ask.
        if (newHinge != null && tiles.isNotEmpty() && !prefs.getBoolean(PREF_LAPTOP_ASKED, false)) laptopPromptOpen = true
        if (laptopActive != was) relayoutGliding()
        if (canvasW > 0f) placeMaps(canvasW, canvasH)
    }

    fun answerLaptopPrompt(use: Boolean) {
        laptopPromptOpen = false
        prefs.edit().putBoolean(PREF_LAPTOP_ASKED, true).apply()
        chooseLaptop(use)
    }

    fun chooseLaptop(on: Boolean) {
        val was = laptopActive
        laptopEnabled = on
        prefs.edit().putBoolean(PREF_LAPTOP, on).apply()
        if (laptopActive != was) relayoutGliding()
    }

    /** The feed in the big spot (up top in laptop mode), for highlighting it elsewhere. */
    val bigTileIdForUi get() = bigTileId

    /** The next layout moves feeds along the app's curve instead of jumping them. */
    private var glideNextLayout = false

    private fun relayoutGliding() {
        glideNextLayout = true
        layoutRequest++
    }

    /** Puts a feed in its slot: straight there, or gliding when the layout asked to. */
    private fun settle(tile: Tile, slot: Slot) {
        if (glideNextLayout) glide(tile, slot) else tile.place(slot)
    }

    /** In laptop mode, puts [tile] in the one spot above the hinge, and the audio with it. */
    fun putOnTop(tile: Tile) {
        if (bigTileId != tile.id) previousTopId = bigTileId
        bigTileId = tile.id
        setAudio(tile, byUser = true)
        layoutRequest++
        // Its picture back now, not at the next half-second check.
        applyVideoBudget()
    }

    /** The feed filling a narrow screen after a double-tap, if any. */
    var focusTileId by mutableStateOf<Int?>(null)
        private set

    /**
     * A phone-sized screen (shorter side under 600dp): the cover screen, upright or sideways. The
     * inner screen is far bigger either way.
     */
    private val compactCanvas get() =
        canvasW > 0f && minOf(canvasW, canvasH) / context.resources.displayMetrics.density < COMPACT_DP

    fun swapIntoBig(tile: Tile) {
        val big = tiles.maxByOrNull { it.w } ?: return
        if (big === tile) return
        val tileSlot = Slot(tile.x, tile.y, tile.w)
        val bigSlot = Slot(big.x, big.y, big.w)
        glide(tile, bigSlot)
        glide(big, tileSlot)
        bigTileId = tile.id
        handPlaced = true
        raise(tile)
    }

    // Scores: Battlefy in a Chrome sheet, so the feeds keep playing beside it.

    /**
     * Opens Battlefy as a Chrome sheet over part of the screen (up from the bottom, or
     * down the side when unfolded). A browser that can't do sheets opens it full screen. Never pop
     * into picture-in-picture and then open a page: Play Protect flagged Apexview for that.
     */
    fun openScores(activity: android.app.Activity) {
        // A link set in Settings wins; otherwise the event the main broadcast says it's showing.
        val url = settings.battlefyUrl.trim().let { if (it.isEmpty() || "://" in it) it else "https://$it" }
            .ifEmpty { Teams.scoresUrl(streams[Teams.BROADCAST]?.title) }
        val screen = activity.resources.displayMetrics
        val sheet = CustomTabsIntent.Builder()
            .setInitialActivityHeightPx((screen.heightPixels * SCORES_SHEET_HEIGHT).toInt(), CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE)
            .setInitialActivityWidthPx((screen.widthPixels * SCORES_SHEET_WIDTH).toInt())
            .setShowTitle(true)
            .build()
        sheet.intent.data = Uri.parse(url)
        // Chrome only makes a sheet of a tab launched for a result; there's no result to read.
        @Suppress("DEPRECATION")
        runCatching {
            activity.startActivityForResult(sheet.intent, SCORES_REQUEST)
            scoresSheetOpen = true
        }.onFailure {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { message = "That Battlefy link won't open." }
        }
    }

    /** The scores are open as a sheet over the app; on a narrow screen the feeds move up clear of it. */
    var scoresSheetOpen by mutableStateOf(false)
        private set

    fun onResumed() {
        scoresSheetOpen = false
    }

    /** The last crash's report, waiting for the person to send it or not. */
    var crashReport by mutableStateOf(CrashReports.pending(context))
        private set

    fun closeCrashReport() {
        crashReport = null
        CrashReports.clear(context)
    }

    // Pop-out (picture-in-picture) when leaving the app while watching.

    var inPip by mutableStateOf(false)
        private set

    /** Pop out only from the viewer itself, not from browsing or an open sheet. */
    val canPip get() = tiles.isNotEmpty() && !browsing && picker == null

    /** The feed the pop-out shows: the big tile in main + strip, else the top-left one. */
    fun pipTile(): Tile? =
        if (layoutMode == LayoutMode.FEATURED) tiles.firstOrNull { it.id == bigTileId } ?: tiles.maxByOrNull { it.w }
        else tiles.minWithOrNull(compareBy<Tile>({ it.y }, { it.x }))

    /** In the pop-out only the shown feed and the one with the audio keep playing, to save battery. */
    fun setPip(on: Boolean) {
        inPip = on
        val shown = pipTile()
        // The pop-out shows one feed; the map cam rests, and goes back to live on return.
        mapCamFeed?.player?.let {
            if (!on && playing) it.seekToDefaultPosition()
            it.playWhenReady = playing && !on
        }
        tiles.forEach { tile ->
            tile.feed.player.playWhenReady = playing && (!on || tile === shown || tile.audible)
        }
        if (!on) {
            // Live feeds paused meanwhile go back to the edge.
            tiles.filter { it.channel.vodId == null && it !== shown && !it.audible }.forEach { it.feed.player.seekToDefaultPosition() }
            showChrome()
        }
    }

    /** Leaving the app without a pop-out (or closing the pop-out) pauses everything. */
    fun pauseForBackground() {
        saveWatch()
        if (pickingImage) return
        if (tiles.isNotEmpty() && playing) togglePlay()
    }

    /** Moves a tile to a new place and size along the app's one easing curve. */
    private fun glide(tile: Tile, to: Slot) {
        val from = Slot(tile.x, tile.y, tile.w)
        // animate() needs the display's frame clock, which only Compose's UI dispatcher carries;
        // the plain lifecycle scope has none.
        scope.launch(AndroidUiDispatcher.Main) {
            animate(0f, 1f, animationSpec = tween(Motion.SCREEN_MS, easing = EaseOut)) { f, _ ->
                tile.x = from.x + (to.x - from.x) * f
                tile.y = from.y + (to.y - from.y) * f
                tile.resize(from.w + (to.w - from.w) * f)
            }
        }
    }

    fun remove(tile: Tile) {
        tileMenu = null
        tile.feed.release()
        tiles.remove(tile)
        if (tiles.isEmpty()) {
            stopAll()
            return
        }
        if (focusTileId == tile.id) focusTileId = null
        if (tile.audible) tiles.firstOrNull { !it.channel.isMapCam }?.let(::setAudio)
        if (bigTileId == tile.id) bigTileId = null
        // The rest close up to fill the space.
        handPlaced = false
        layoutRequest++
    }

    fun stopAll() {
        // Closing the viewer keeps the session, so Continue watching can bring it back.
        saveWatch()
        stopMapCam()
        tiles.forEach { it.feed.release() }
        tiles.clear()
        browsing = false
        tileMenu = null
        helpOpen = false
        helpAnchors.clear()
        layoutSheetOpen = false
        mapSheetOpen = false
        scoresOpen = false
        arranging = false
        mutedAll = false
        playing = true
    }

    // Chrome: the controls fade away while watching and come back on touch.

    fun showChrome() {
        chromeVisible = true
        chromeTouch++
    }

    fun toggleChrome() {
        if (chromeVisible) chromeVisible = false else showChrome()
    }

    // Shared playback. A past broadcast leads the scrubber if there is one; each stream runs on its own clock.

    private fun leaderTile() = tiles.firstOrNull { it.channel.vodId != null && it.audible } ?: tiles.firstOrNull { it.channel.vodId != null } ?: tiles.firstOrNull()

    fun togglePlay() {
        playing = !playing
        // In the pop-out, only the shown feed and the audio keep playing (see setPip).
        val shown = if (inPip) pipTile() else null
        tiles.forEach {
            // Resuming live means live, not where it paused.
            if (playing && it.channel.vodId == null) it.feed.player.seekToDefaultPosition()
            it.feed.player.playWhenReady = playing && (!inPip || it === shown || it.audible)
        }
        mapCamFeed?.player?.let {
            if (playing) it.seekToDefaultPosition()
            it.playWhenReady = playing && !inPip
        }
        showChrome()
    }

    /** From the player itself, not positionMs: in the pop-out the tick that updates it isn't running. */
    fun skip(deltaMs: Long) {
        val leader = leaderTile() ?: return
        seekTo(leader.feed.player.currentPosition + deltaMs)
    }

    fun seekTo(targetMs: Long) {
        val leader = leaderTile() ?: return
        if (leader.channel.vodId == null) return
        val duration = leader.feed.player.duration.takeIf { it > 0 } ?: durationMs.takeIf { it > 0 }
        val target = if (duration != null) targetMs.coerceIn(0L, duration) else targetMs.coerceAtLeast(0L)
        val shift = target - leader.feed.player.currentPosition
        // Every past broadcast moves by the same amount, so their offsets survive the seek.
        tiles.filter { it.channel.vodId != null }.forEach { it.feed.player.seekTo((it.feed.player.currentPosition + shift).coerceAtLeast(0L)) }
        positionMs = target
        showChrome()
    }

    fun goLive() {
        tiles.filter { it.channel.vodId == null }.forEach { it.feed.player.seekToDefaultPosition() }
        showChrome()
    }

    /** Runs twice a second: keeps the controls current and saves the session every 5 s. */
    fun tick() {
        val leader = leaderTile() ?: return
        positionMs = leader.feed.player.currentPosition
        durationMs = leader.feed.player.duration.takeIf { it > 0 } ?: 0L
        isLive = leader.channel.vodId == null
        if (leader.feed.player.playbackState != Player.STATE_READY) return
        if (++saveTicks % 10 == 0) saveWatch()
    }

    fun dismissMessage() {
        message = null
    }

    companion object {
        private const val FULL_W = 1920
        private const val FULL_H = 1080
        private const val MAX_RECENTS = 4
        private const val PREF_LAPTOP = "laptopMode"
        private const val PREF_LAPTOP_ASKED = "laptopAsked"
        private const val COMPACT_DP = 600f
        private const val PREF_HELP_SEEN = "helpSeen"
        private const val PREF_RECENTS = "recents"
        private const val PREF_LAYOUT = "layout"
        private const val PREF_DEFAULT_LAYOUT = "defaultLayout"
        private const val PREF_SNAP = "snap"
        private const val PREF_MAPS = "maps"
        private const val PREF_MAP_CAM_ZOOM = "mapCamZoom"
        private const val PREF_CURRENT_MAP = "currentMap"
        private const val PREF_MAP_PANEL = "mapPanel"
        private const val PREF_LAPTOP_MAP = "laptopMapPanel"
        private const val PREF_MAP_LOCKED = "mapLocked"
        /** How much of the width below the hinge the feed list takes in laptop mode. */
        const val LAPTOP_LIST_SHARE = 0.4f
        // Snap mode: edges pull together inside 12dp; a push past 56dp goes through.
        private const val SNAP_DP = 12f
        private const val BREAK_DP = 56f
        /** The scores sheet's share of the screen: its height from the bottom, its width at the side. */
        private const val SCORES_SHEET_HEIGHT = 0.6f
        const val SCORES_SHEET_WIDTH = 0.45f
        private const val SCORES_REQUEST = 4057
    }
}
