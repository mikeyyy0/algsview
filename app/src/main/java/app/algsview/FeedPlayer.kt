package app.algsview

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * One feed: its own ExoPlayer, playing whatever Twitch stream it's told to. [load] changes the
 * stream in the same player, so a tile that follows a team keeps its surface and its volume.
 * [status] is the diagnostics line shown on the tile.
 */
@OptIn(UnstableApi::class)
class FeedPlayer(
    context: Context,
    private val scope: CoroutineScope,
    private val twitch: TwitchApi,
) {
    var status by mutableStateOf("Starting")
        private set

    val player: ExoPlayer

    private var state = "Starting"
    private var size = ""
    private var lastDropped = 0
    private var dropsPerSecond = 0
    private var channel: Channel? = null
    private var loading: Job? = null
    private var retries = 0
    private var adFilter = TwitchAdFilter()

    /** Why this feed isn't playing, in plain words, for the tile to show. Null while it's fine. */
    var problem by mutableStateOf<String?>(null)
        private set
    /** Waiting on the stream: starting, or stalled until more arrives. */
    var buffering by mutableStateOf(true)
        private set
    /** Twitch is showing an ad on this feed; the picture holds until it's over. */
    var adBreak by mutableStateOf(false)
        private set

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)

    init {
        // Starts once a second of picture is in rather than the default 2.5.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, 1_000, 2_000)
            .build()
        player = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state = when (playbackState) {
                    Player.STATE_BUFFERING -> "Buffering"
                    Player.STATE_READY -> "Playing"
                    Player.STATE_ENDED -> "Ended"
                    else -> "Stopped"
                }
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) retries = 0
                refresh()
            }

            override fun onPlayerError(e: PlaybackException) {
                problem = explain(e)
                retry(e)
                refresh()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) problem = null
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0) size = "${videoSize.width}x${videoSize.height}"
                refresh()
            }
        })
        player.playWhenReady = true
    }

    /** Starts [next] in this player, from a fresh token. A channel of nothing (a tile waiting for its team) stops it. */
    fun load(next: Channel) {
        channel = next
        retries = 0
        problem = null
        loading?.cancel()
        if (next.login == null && next.vodId == null) {
            player.stop()
            player.clearMediaItems()
            adFilter = TwitchAdFilter()
            adBreak = false
            return
        }
        start(next, next.startMs)
    }

    private fun start(target: Channel, positionMs: Long) {
        loading = scope.launch {
            try {
                val url = twitch.playbackUrl(target)
                adFilter = TwitchAdFilter()
                val source = HlsMediaSource.Factory(http)
                    .setPlaylistParserFactory(FilteringParserFactory(adFilter))
                    // A playlist holding still through an ad break isn't a stream that's stuck.
                    .setPlaylistTrackerFactory { dataSources, errors, parsers ->
                        DefaultHlsPlaylistTracker(dataSources, errors, parsers, STUCK_TARGET_DURATIONS)
                    }
                    .createMediaSource(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                if (positionMs > 0) player.setMediaSource(source, positionMs) else player.setMediaSource(source)
                player.prepare()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "Couldn't reach Twitch: ${e.message}"
                retryAfterDelay(target)
            }
        }
    }

    /**
     * Fallen behind the live window: straight back to the edge. Anything else (an expired token's
     * 403, a channel that went off air): a new token, more slowly each time.
     */
    private fun retry(e: PlaybackException) {
        val target = channel ?: return
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        retryAfterDelay(target)
    }

    private fun retryAfterDelay(target: Channel) {
        val wait = RETRY_MS[retries.coerceAtMost(RETRY_MS.lastIndex)]
        retries++
        // A past broadcast picks up where it failed; live goes back to the edge.
        val at = if (target.vodId != null) player.currentPosition else 0L
        loading?.cancel()
        loading = scope.launch {
            delay(wait)
            if (channel === target) start(target, at)
        }
    }

    private var videoOn = true
    private var viewW = 0
    private var viewH = 0
    private var capH = Int.MAX_VALUE
    private var source = false

    /**
     * Whether this feed's picture is wanted, and how much of it. A feed nobody can see keeps only
     * its sound. [source] asks for the best rendition whatever the tile's size (the feed with the
     * audio); otherwise it's the smallest that fills the tile ([width] by [height] px), no taller
     * than [maxHeight] (quiet feeds when there are several).
     */
    fun setVideo(on: Boolean, width: Int, height: Int, source: Boolean = false, maxHeight: Int = Int.MAX_VALUE) {
        // In steps, so a tile being dragged bigger doesn't re-ask for tracks every frame.
        val w = (width.coerceAtLeast(1) + 63) / 64 * 64
        val h = (height.coerceAtLeast(1) + 35) / 36 * 36
        if (on == videoOn && w == viewW && h == viewH && source == this.source && maxHeight == capH) return
        videoOn = on
        viewW = w
        viewH = h
        this.source = source
        capH = maxHeight
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on)
            .apply { if (source) clearViewportSizeConstraints() else setViewportSize(w, h, false) }
            .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
            .setForceHighestSupportedBitrate(source)
            .build()
    }

    /**
     * Keeps the picture going at the size it already has: changing size would pick another
     * rendition and stall the feed, sound included, while it loads.
     */
    fun keepVideo() {
        if (!videoOn) setVideo(true, viewW.takeIf { it > 1 } ?: 1920, viewH.takeIf { it > 1 } ?: 1080, source, capH)
    }

    fun setAudible(audible: Boolean) {
        player.volume = if (audible) 1f else 0f
    }

    /** Rebuilds the diagnostics line and the ad note. Called on player events and once a second by the tile. */
    fun refresh() {
        adBreak = adFilter.inAdBreak
        val dropped = player.videoDecoderCounters?.droppedBufferCount ?: 0
        if (dropped >= lastDropped) dropsPerSecond = dropped - lastDropped
        lastDropped = dropped
        status = listOfNotNull(
            state,
            size.ifEmpty { null },
            when {
                state != "Playing" -> null
                dropsPerSecond == 0 -> "smooth"
                else -> "$dropsPerSecond drops/s"
            },
            if (adBreak) "ad break" else null,
        ).joinToString(" · ")
    }

    fun release() {
        loading?.cancel()
        channel = null
        player.release()
    }

    /** What a playback error means for the viewer. */
    private fun explain(e: PlaybackException): String {
        val code = generateSequence(e as Throwable) { it.cause }
            .firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.responseCode
        return when (code) {
            404 -> "This channel is off air. Trying again."
            401, 403 -> "Twitch refused this stream. Trying again."
            else -> "This feed stopped: ${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}. Trying again."
        }
    }

    private companion object {
        /** Twitch serves its streams to browsers. */
        const val USER_AGENT = "Mozilla/5.0 (Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
        /** Twitch's target is 5 s, so this rides out breaks of several minutes. */
        const val STUCK_TARGET_DURATIONS = 200.0
        val RETRY_MS = longArrayOf(1_000, 3_000, 10_000, 30_000)
    }
}

/** Media3's playlist parser, reading each playlist after [TwitchAdFilter] has been through it. */
@OptIn(UnstableApi::class)
private class FilteringParserFactory(private val filter: TwitchAdFilter) : HlsPlaylistParserFactory {
    private val inner = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser() = wrap(inner.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ) = wrap(inner.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri: Uri, stream: InputStream ->
        val text = stream.readBytes().toString(Charsets.UTF_8)
        parser.parse(uri, ByteArrayInputStream(filter.filter(uri.path.orEmpty(), text).toByteArray()))
    }
}
