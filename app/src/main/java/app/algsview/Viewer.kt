package app.algsview

import android.app.Activity
import android.os.Build
import android.view.LayoutInflater
import android.view.RoundedCorner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val CHROME_Z = 2e9f
private const val CHROME_HIDE_MS = 4_000L
/** How often channel titles and live status are asked for. */
private const val POLL_MS = 30_000L
/** Below this width Chrome's sheet comes up from the bottom; at or above, in from the side. */
private val SIDE_SHEET = 840.dp
/** How far the feeds move down (by shrinking) while the controls are up: the top bar's height. */
private val CONTROLS_BAND = 56.dp
/** A camera within this fraction of the screen's width from a side counts as on that side. */
private const val SIDE_CUTOUT = 0.3f
/** The first time a stream plays, the explanation overlay waits this long for things to settle. */
private const val HELP_FIRST_DELAY_MS = 2_500L
/** On a live stream, this close to the newest picture still shows as Live. */
private const val LIVE_EDGE_MS = 30_000L


/** The camera holes and notches, in screen pixels. Read again after a fold or unfold. */
@Composable
private fun rememberCutouts(): List<android.graphics.Rect> {
    val view = LocalView.current
    val config = LocalConfiguration.current
    val screen = Triple(config.screenWidthDp, config.screenHeightDp, config.orientation)
    var rects by remember { mutableStateOf(emptyList<android.graphics.Rect>()) }
    LaunchedEffect(view, screen) {
        // Insets aren't attached on the first frame.
        repeat(10) {
            view.rootWindowInsets?.let { insets ->
                rects = insets.displayCutout?.boundingRects.orEmpty().filter { !it.isEmpty }
                return@LaunchedEffect
            }
            delay(100)
        }
    }
    return rects
}

/** Room a row of controls between [top] and [bottom] leaves at its start and end for the camera. */
private class Clearance(val start: Float, val end: Float)

private fun clearOf(cutouts: List<android.graphics.Rect>, width: Float, top: Float, bottom: Float): Clearance {
    var start = 0f
    var end = 0f
    cutouts.filter { it.bottom > top && it.top < bottom }.forEach { r ->
        // Only a camera near a side pushes the buttons in. One near the middle (the cover
        // screen's) sits over the title; treating it as a right-side camera padded half the bar
        // away and pushed the map button off the screen.
        when {
            r.exactCenterX() < width * SIDE_CUTOUT -> start = maxOf(start, r.right.toFloat())
            r.exactCenterX() > width * (1 - SIDE_CUTOUT) -> end = maxOf(end, width - r.left)
        }
    }
    return Clearance(start, end)
}

@Composable
fun ViewerScreen(app: AppState) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val bars = WindowCompat.getInsetsController(window, view)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // The controls step back a few seconds after the last touch, unless paused.
    // helpOpen is a key so opening the overlay cancels a hide already counting down; it used
    // to fire anyway and take the top bar away from under its labels.
    LaunchedEffect(app.chromeTouch, app.chromeVisible, app.playing, app.helpOpen) {
        // Stays up while arranging, so the lock is there to tap when done.
        if (app.chromeVisible && app.playing && !app.arranging && !app.helpOpen) {
            delay(CHROME_HIDE_MS)
            app.chromeVisible = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            app.tick()
            delay(500)
        }
    }
    // Who's on which channel, for labels and for tiles following a team.
    LaunchedEffect(Unit) {
        while (true) {
            app.pollStreams()
            delay(POLL_MS)
        }
    }

    val cutouts = rememberCutouts()
    // The controls and the feeds move together: one curve, one duration. While arranging the
    // feeds stay full size, so what you place is where it ends up.
    val motion = tween<Float>(Motion.SCREEN_MS, easing = EaseOut)
    // Laptop mode keeps the stream still: the controls lie over its top edge instead.
    val lift by animateFloatAsState(if (app.chromeVisible && !app.arranging && !app.laptopActive) 1f else 0f, motion, label = "lift")

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Ink.background)
            .pointerInput(Unit) { detectTapGestures(onTap = { app.toggleChrome() }) }
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val band = with(density) { CONTROLS_BAND.toPx() }
        val narrow = with(density) { width.toDp() } < SIDE_SHEET
        // The scores sheet rises over the bottom of a narrow screen: the feeds glide up to the top
        // edge, clear of it, and back when it closes.
        val sheetShift by animateFloatAsState(
            if (app.scoresSheetOpen && narrow) app.tiles.minOfOrNull { it.y } ?: 0f else 0f,
            motion,
            label = "scoresSheet",
        )
        // On a wide one, the feeds shrink into the space left of it.
        val sheetSide by animateFloatAsState(if (app.scoresSheetOpen && !narrow) 1f else 0f, motion, label = "scoresSide")
        // The pop-out's tiny window says nothing about the layout; it picks up again on return.
        LaunchedEffect(app.layoutRequest, app.inPip) { if (!app.inPip) app.layoutIfRequested(width, height) }
        LaunchedEffect(width, height, app.inPip) { if (!app.inPip) app.onCanvasResized(width, height) }

        // Feeds and map, shrinking toward the bottom while the controls are up so nothing sits
        // under the top bar. Touches inside are in the unshrunk canvas's pixels.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val k = 1f - sheetSide * AppState.SCORES_SHEET_WIDTH
                    scaleX = k
                    scaleY = k
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .graphicsLayer {
                    val s = if (app.inPip) 1f else 1f - lift * band / height
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    translationY = -sheetShift
                }
        ) {
            // The pop-out is this same screen with one feed filling it: the feed keeps its video
            // surface, so its decoder carries on rather than restarting from the next keyframe.
            val popped = if (app.inPip) app.pipTile() else null
            app.tiles.forEach { tile ->
                if (popped == null || tile === popped) key(tile.id) { TileView(app, tile) }
            }
            if (app.inPip) return@Box

            // Laptop mode: the half below the hinge is the feed list and the map. It rises in as the
            // feeds glide into place and sinks away when the phone opens flat.
            val laptop = app.laptopActive && app.hinge != null
            // The last hinge seen, so the panel still knows its place while it leaves.
            var lastHinge by remember { mutableStateOf(app.hinge) }
            app.hinge?.let { lastHinge = it }
            val swap = tween<Float>(Motion.SCREEN_MS, easing = EaseOut)
            val slide = tween<IntOffset>(Motion.SCREEN_MS, easing = EaseOut)
            AnimatedVisibility(
                laptop,
                Modifier.zIndex(1e9f),
                enter = fadeIn(swap) + slideInVertically(slide) { it / 2 },
                exit = fadeOut(swap) + slideOutVertically(slide) { it / 2 },
            ) {
                val top = lastHinge?.second ?: height
                Box(Modifier.fillMaxSize()) {
                    LaptopPanel(
                        app,
                        Modifier
                            .offset { IntOffset(0, top.roundToInt()) }
                            .requiredSize(with(density) { width.toDp() }, with(density) { (height - top).coerceAtLeast(0f).toDp() }),
                    )
                }
            }
            // Above every tile (their z grows by one per touch), below the headers and controls.
            app.currentMap?.let { map ->
                Box(Modifier.fillMaxSize().zIndex(1.2e9f)) { MapPanelView(app, map) }
            }
            // The feeds' headers, above everything else on the canvas.
            Box(Modifier.fillMaxSize().zIndex(1.5e9f)) {
                app.tiles.forEach { tile -> key(tile.id) { TileHeader(app, tile) } }
            }
        }

        if (app.inPip) return@BoxWithConstraints

        val fade = motion
        val topBand = with(density) { 64.dp.toPx() }
        val bottomBand = with(density) { 110.dp.toPx() }
        AnimatedVisibility(
            app.chromeVisible,
            Modifier.zIndex(CHROME_Z).align(Alignment.TopCenter),
            enter = fadeIn(fade), exit = fadeOut(fade),
        ) { TopChrome(app, clearOf(cutouts, width, 0f, topBand)) }
        // The playback controls step aside while arranging, so the bottom feeds can be seen.
        AnimatedVisibility(
            app.chromeVisible && !app.arranging,
            Modifier.zIndex(CHROME_Z).align(Alignment.BottomCenter),
            enter = fadeIn(fade), exit = fadeOut(fade),
        ) { BottomChrome(app, clearOf(cutouts, width, height - bottomBand, height)) }

        Messages(app, Modifier.zIndex(CHROME_Z + 1).align(Alignment.Center))

        // The explanation overlay, above everything. Shown once by itself, the first time.
        Box(Modifier.fillMaxSize().zIndex(CHROME_Z + 2)) {
            HelpOverlay(app, width, height, feedScale = 1f - lift * band / height, compact = with(density) { minOf(width, height).toDp() } < 600.dp)
        }
        LaunchedEffect(Unit) {
            if (!app.helpSeen) {
                delay(HELP_FIRST_DELAY_MS)
                if (app.tiles.isNotEmpty()) app.showHelp()
            }
        }
    }

    app.tileMenu?.let { TileMenuSheet(app, it) }
    if (app.layoutSheetOpen) LayoutSheet(app)
    if (app.mapSheetOpen) MapSheet(app)
    LaptopPrompt(app)
}


@Composable
private fun Glyph(icon: ImageVector, description: String, size: Int = 24, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.pressable(scaleTo = 0.88f, onClick = onClick).size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Ink.primary, modifier = Modifier.size(size.dp))
    }
}

/** Padding that keeps a row's buttons out from under the camera, at least [base]. */
@Composable
private fun Modifier.clear(of: Clearance, base: androidx.compose.ui.unit.Dp): Modifier {
    val density = LocalDensity.current
    val gap = 6.dp
    return padding(
        start = maxOf(base, with(density) { of.start.toDp() } + if (of.start > 0f) gap else 0.dp),
        end = maxOf(base, with(density) { of.end.toDp() } + if (of.end > 0f) gap else 0.dp),
    )
}

@Composable
private fun TopChrome(app: AppState, cutout: Clearance) {
    Row(
        Modifier
            .fillMaxWidth()
            // Taller and darker than the bar itself, so the buttons read over any picture; the
            // feeds have already moved down out of the way.
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.92f),
                    0.55f to Color.Black.copy(alpha = 0.6f),
                    1f to Color.Transparent,
                )
            )
            .clear(cutout, 8.dp)
            .padding(top = 6.dp, bottom = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Icons.Rounded.Close, "Stop watching", modifier = Modifier.helpAnchor(app, "close"), onClick = app::stopAll)
        Text(
            app.playingTitle,
            style = Type.headline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Glyph(if (app.arranging) Icons.Rounded.LockOpen else Icons.Rounded.Lock, if (app.arranging) "Lock layout" else "Unlock to move and resize", modifier = Modifier.helpAnchor(app, "lock"), onClick = app::toggleArranging)
        Glyph(Icons.Rounded.Add, "Add a feed", modifier = Modifier.helpAnchor(app, "add")) { app.openPicker() }
        Glyph(Icons.Rounded.GridView, "Layout: ${app.layoutMode.label}", modifier = Modifier.helpAnchor(app, "layout")) { app.layoutSheetOpen = true; app.showChrome() }
        val activity = LocalContext.current as Activity
        Glyph(Icons.Rounded.Map, "Map", modifier = Modifier.helpAnchor(app, "map")) { app.mapSheetOpen = true; app.showChrome() }
        Glyph(Icons.Rounded.Leaderboard, "Scores", modifier = Modifier.helpAnchor(app, "scores")) { app.openScores(activity) }
        Glyph(Icons.Rounded.HelpOutline, "What everything does", 22, onClick = app::showHelp)
    }
}

@Composable
private fun BottomChrome(app: AppState, cutout: Clearance) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val duration = app.durationMs
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))
            .clear(cutout, 20.dp)
            .padding(top = 24.dp, bottom = 8.dp)
    ) {
        // Twitch keeps only a few seconds behind live, so only past broadcasts scrub.
        if (duration > 0 && !app.isLive) {
            Box(Modifier.helpAnchor(app, "scrubber"), contentAlignment = Alignment.CenterStart) {
                AppleSlider(
                    value = scrubbing ?: (app.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { scrubbing = it; app.showChrome() },
                    onValueChangeFinished = {
                        scrubbing?.let { app.seekTo((it * duration).toLong()) }
                        scrubbing = null
                    },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shown = scrubbing?.let { (it * duration).toLong() } ?: app.positionMs
            if (app.isLive) LiveMark() else Text(clock(shown), style = Type.footnote.copy(color = Ink.primary))
            Spacer(Modifier.weight(1f))
            if (!app.isLive) {
                Glyph(Icons.Rounded.Replay10, "Back 10 seconds", 28) { app.skip(-10_000) }
                Spacer(Modifier.width(12.dp))
            }
            Glyph(if (app.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (app.playing) "Pause" else "Play", 36, onClick = app::togglePlay)
            if (!app.isLive) {
                Spacer(Modifier.width(12.dp))
                Glyph(Icons.Rounded.Forward10, "Forward 10 seconds", 28) { app.skip(10_000) }
            }
            Spacer(Modifier.weight(1f))
            if (app.isLive) {
                Text("Go live", style = Type.footnote.copy(color = Ink.primary), modifier = Modifier.pressable(onClick = app::goLive).padding(8.dp))
            } else {
                Text("−" + clock((duration - shown).coerceAtLeast(0)), style = Type.footnote.copy(color = Ink.primary))
            }
            Spacer(Modifier.width(8.dp))
            Glyph(if (app.mutedAll) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (app.mutedAll) "Unmute" else "Mute all", modifier = Modifier.helpAnchor(app, "mute"), onClick = app::toggleMuteAll)
        }
    }
}


/** One status line in the middle, for things in progress or gone wrong. */
@Composable
private fun Messages(app: AppState, modifier: Modifier) {
    val text = app.message ?: return
    LaunchedEffect(text) {
        if (!text.endsWith("...")) {
            delay(6_000)
            app.dismissMessage()
        }
    }
    Text(
        text.removeSuffix("...").let { if (text.endsWith("...")) "$it…" else it },
        style = Type.subhead.copy(color = Ink.primary),
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .pressable(onClick = app::dismissMessage)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private val overlayShadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)

/**
 * A feed. Tap brings the audio here (or, if it's here, shows or hides the controls), double-tap
 * brings it into the big slot, long-press opens its menu. While
 * arranging, one finger drags and two resize. With the controls up, the header shows the feed's
 * name (tap for its menu), a speaker that moves the audio here, and on the small feeds a close.
 */
@Composable
private fun TileView(app: AppState, tile: Tile) {
    val density = LocalDensity.current
    LaunchedEffect(tile.id) {
        while (true) {
            tile.feed.refresh()
            delay(1000)
        }
    }

    Box(
        Modifier
            .then(
                if (app.inPip) Modifier.fillMaxSize()
                else Modifier
                    .offset { IntOffset(tile.x.roundToInt(), tile.y.roundToInt()) }
                    .requiredSize(with(density) { tile.w.toDp() }, with(density) { tile.h.toDp() })
            )
            .zIndex(tile.z)
            .helpAnchor(app, "tile:${tile.id}")
            .clipToBounds()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { context ->
                LayoutInflater.from(context).inflate(R.layout.player_texture, null) as PlayerView
            },
            update = {
                it.player = tile.feed.player
                // The last frame stays up while a followed team moves channel.
                it.setKeepContentOnPlayerReset(true)
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = tile.zoom
                scaleY = tile.zoom
                translationX = tile.zoomX
                translationY = tile.zoomY
            },
        )
        if (app.inPip) return@Box

        val zoomable = app.canZoom(tile) && !app.arranging
        // Moved out of the main spot, the whole picture comes back.
        LaunchedEffect(zoomable) { if (!zoomable) tile.resetZoom() }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(tile.id) {
                    detectTapGestures(
                        onPress = { app.raise(tile) },
                        onTap = { app.tapFeed(tile) },
                        onDoubleTap = { app.doubleTap(tile) },
                        onLongPress = { app.openTileMenu(tile); app.showChrome() },
                    )
                }
                .then(if (app.arranging) Modifier.arrangeGestures(app, tile) else Modifier)
                .then(if (zoomable) Modifier.zoomGestures(tile) else Modifier)
        )


        val fade = tween<Float>(Motion.SCREEN_MS, easing = EaseOut)
        if (tile.coolingOff) {
            Text(
                "Video paused to let the phone cool down",
                style = Type.subhead.copy(color = Ink.primary, shadow = overlayShadow, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp).widthIn(max = 420.dp),
            )
        }

        // A tile following a team that isn't on air right now.
        if (tile.channel.login == null && tile.channel.vodId == null) {
            Text(
                tile.channel.title,
                style = Type.subhead.copy(color = Ink.primary, shadow = overlayShadow, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            )
        } else if (tile.feed.problem == null && !tile.coolingOff && !tile.feed.adBreak) {
            BufferingRing(tile.feed, Modifier.align(Alignment.Center))
        }

        // The picture holds on its last frame; the ad itself is never fetched.
        AnimatedVisibility(tile.feed.adBreak, Modifier.align(Alignment.BottomStart).padding(10.dp), enter = fadeIn(fade), exit = fadeOut(fade)) {
            Text(
                "Ad break, back soon",
                style = Type.footnote.copy(color = Ink.primary),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        tile.feed.problem?.let { problem ->
            Text(
                problem,
                style = Type.subhead.copy(color = Ink.primary, shadow = overlayShadow, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp).widthIn(max = 420.dp),
            )
        }

        if (app.settings.showStats) {
            Text(
                tile.feed.status,
                style = Type.caption.copy(color = Ink.primary, shadow = overlayShadow),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 48.dp, bottom = 8.dp),
            )
        }

        AnimatedVisibility(app.chromeVisible && app.arranging, Modifier.align(Alignment.BottomEnd), enter = fadeIn(fade), exit = fadeOut(fade)) {
            Box(
                Modifier
                    .size(44.dp)
                    .pointerInput(tile.id) {
                        detectDragGestures(
                            onDragStart = { app.beginArrange(tile) },
                            onDragEnd = app::endArrange,
                            onDragCancel = app::endArrange,
                        ) { change, drag ->
                            change.consume()
                            // A diagonal drag counts both ways; the tile keeps 16:9.
                            app.arrangeBy(tile, 0f, 0f, (drag.x + drag.y * 16f / 9f) / 2f)
                        }
                    }
            ) {
                Canvas(Modifier.align(Alignment.Center).size(14.dp)) {
                    val stroke = 2.dp.toPx()
                    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), stroke)
                    drawLine(Color.White, Offset(size.width, size.height / 2), Offset(size.width / 2, size.height), stroke)
                }
            }
        }
    }
}

/**
 * The map cam in the main spot: two fingers zoom, and once zoomed one finger moves around. A single
 * tap or double-tap still gets through (a double-tap goes back to the whole picture).
 */
private fun Modifier.zoomGestures(tile: Tile) = pointerInput(tile.id) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var travel = Offset.Zero
        var moving = false
        do {
            val event = awaitPointerEvent()
            val pan = event.calculatePan()
            val pinch = event.changes.count { it.pressed } >= 2
            if (!moving) {
                travel += pan
                moving = pinch || (tile.zoom > 1f && travel.getDistance() > viewConfiguration.touchSlop)
            }
            if (moving) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                tile.zoomBy(event.calculateZoom(), event.calculateCentroid() - centre, pan)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * While arranging: one finger moves the tile, two resize it. Starts past the touch slop so taps
 * and double-taps still work, and stops if the map claimed the touch first.
 */
private fun Modifier.arrangeGestures(app: AppState, tile: Tile) = pointerInput(tile.id) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var moving = false
        var travel = Offset.Zero
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            if (!moving) {
                travel += pan
                if (travel.getDistance() > viewConfiguration.touchSlop || zoom != 1f) {
                    moving = true
                    app.beginArrange(tile)
                }
            }
            if (moving) {
                app.arrangeBy(tile, pan.x, pan.y, if (zoom != 1f) tile.w * (zoom - 1f) else 0f)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (moving) app.endArrange()
    }
}


/**
 * A feed's header (its accent, its name for the menu, the speaker, and on small feeds a close),
 * drawn from a layer above the map.
 */
@Composable
private fun TileHeader(app: AppState, tile: Tile) {
    val density = LocalDensity.current
    val fade = tween<Float>(Motion.SCREEN_MS, easing = EaseOut)
    Box(
        Modifier
            .offset { IntOffset(tile.x.roundToInt(), tile.y.roundToInt()) }
            .width(with(density) { tile.w.toDp() })
            .zIndex(tile.z)
    ) {
        AnimatedVisibility(app.chromeVisible, Modifier.fillMaxWidth(), enter = fadeIn(fade), exit = fadeOut(fade)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent))
                    .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Takes the width left over, so what follows sits at the feed's right edge.
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.pressable { app.openTileMenu(tile); app.showChrome() }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Accents[tile.accent]))
                        Spacer(Modifier.width(6.dp))
                        Text(tile.channel.title, Modifier.weight(1f, fill = false), style = Type.footnote.copy(color = Ink.primary, shadow = overlayShadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Ink.primary, modifier = Modifier.size(18.dp))
                        if (tile.pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = Ink.primary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                    }
                }
                // With one feed there's no audio to move; Mute all is in the controls.
                if (app.tiles.size > 1 && !tile.channel.isMapCam) {
                    val hearing = tile.audible && !app.mutedAll
                    Glyph(if (hearing) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, if (hearing) "Playing audio" else "Play audio from this feed", 20) {
                        app.setAudio(tile, byUser = true)
                        app.showChrome()
                    }
                }
                // The small feeds close from here; the rest close up to fill the space.
                if (app.tiles.size > 1 && !app.isMainTile(tile)) {
                    Glyph(Icons.Rounded.Close, "Close ${tile.channel.title}", 18) { app.remove(tile) }
                }
            }
        }
    }
}

/** One stream to switch to, by name; "Now" on the one playing. */
@Composable
private fun ChannelRow(app: AppState, tile: Tile, channel: Channel, detail: String? = null) {
    val current = channel.login != null && channel.login == tile.channel.login
    Row(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.99f) { app.switchFeed(tile, channel) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            channel.title,
            style = Type.body.copy(color = if (current) Ink.secondary else Ink.primary),
            modifier = Modifier.weight(1f),
        )
        if (current) Text("Now", style = Type.footnote) else detail?.let { Text(it, style = Type.footnote) }
    }
}

/** A tile's actions and the other streams it could show. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileMenuSheet(app: AppState, tile: Tile) {
    ModalBottomSheet(
        onDismissRequest = app::closeTileMenu,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        KeepSystemBarsHidden()
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(tile.channel.title, style = Type.title2)
            tile.channel.login?.let { Text(Teams.channelLabel(it), style = Type.footnote, modifier = Modifier.padding(top = 2.dp)) }
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!tile.audible && !tile.channel.isMapCam) SecondaryButton("Play its audio") { app.setAudio(tile, byUser = true); app.closeTileMenu() }
                if (tile.channel.follow != null) SecondaryButton("Stop following") { app.toggleFollow(tile) }
                else if (tile.channel.login?.let { Teams.isTeamChannel(it, app.settings.teamPrefix) } == true) SecondaryButton("Follow this team") { app.toggleFollow(tile) }
                SecondaryButton(if (tile.pinned) "Unpin" else "Pin") { app.togglePin(tile); app.closeTileMenu() }
                SecondaryButton("Remove") { app.remove(tile) }
            }
            Text("Switch to", style = Type.headline, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            app.liveOthers.forEach { ChannelRow(app, tile, app.channelOf(it)) }
            app.liveTeams.forEach { ChannelRow(app, tile, app.channelOf(it), Teams.channelLabel(it.login)) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * A bottom sheet is its own window, which brings the status bar back over the viewer. Inside a
 * sheet opened over the viewer, this hides it again, the way the viewer itself does.
 */
@Composable
internal fun KeepSystemBarsHidden(enabled: Boolean = true) {
    val view = LocalView.current
    LaunchedEffect(view, enabled) {
        if (!enabled) return@LaunchedEffect
        var parent = view.parent
        while (parent != null && parent !is androidx.compose.ui.window.DialogWindowProvider) parent = parent.parent
        val window = (parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@LaunchedEffect
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** A row of choices, one picked, in the style of iOS's segmented control. */
@Composable
private fun <T> Segments(options: List<T>, chosen: T, label: (T) -> String, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(Ink.background).padding(3.dp)) {
        options.forEach { option ->
            val on = option == chosen
            Box(
                Modifier
                    .pressable { onPick(option) }
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Ink.surfaceHigh else Color.Transparent)
                    .padding(horizontal = 22.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = Type.subhead.copy(color = if (on) Ink.primary else Ink.secondary))
            }
        }
    }
}

/** Built-in layouts, the saved one, saving the current arrangement, and Snap or Free arranging. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutSheet(app: AppState) {
    ModalBottomSheet(
        onDismissRequest = { app.layoutSheetOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        KeepSystemBarsHidden()
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Layout", style = Type.title2)
            val n = app.tiles.size
            val hasSaved = app.savedLayout(n) != null
            val modes = LayoutMode.entries.filter { it != LayoutMode.CUSTOM || hasSaved }
            Segments(modes, app.layoutMode, { it.label }, Modifier.padding(top = 20.dp)) { app.chooseLayout(it) }
            Text(
                if (hasSaved) "My layout is the one you saved for $n feed${if (n == 1) "" else "s"} with the phone ${app.wayUp}."
                else "Arrange the feeds the way you like, then save it. Upright and sideways each keep their own.",
                style = Type.footnote,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 16.dp)) {
                SecondaryButton("Save this layout") { app.saveLayoutAsDefault(); app.layoutSheetOpen = false }
            }
            Text(
                "Every session with $n feed${if (n == 1) "" else "s"} will start like this. Main + strip and Grid stay as they are.",
                style = Type.footnote,
                modifier = Modifier.padding(top = 8.dp),
            )

            Column(Modifier.padding(top = 20.dp).clip(RoundedCornerShape(12.dp)).background(Ink.background).padding(vertical = 4.dp)) {
                SwitchRow("Laptop mode when half-folded", app.laptopEnabled, app::chooseLaptop)
            }
            Text(
                "Open the phone partway, like a laptop, and one feed takes the top half while your other feeds and the map fill the bottom.",
                style = Type.footnote,
                modifier = Modifier.padding(top = 8.dp),
            )

            Text("Arranging", style = Type.headline, modifier = Modifier.padding(top = 24.dp))
            Segments(listOf(true, false), app.snapMode, { if (it) "Snap" else "Free" }, Modifier.padding(top = 10.dp)) { app.chooseSnap(it) }
            Text(
                if (app.snapMode) "Edges pull together and feeds don't overlap; push hard to go over one. Growing a feed pushes the others into open space."
                else "Feeds go exactly where you put them, over each other if you like.",
                style = Type.footnote,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}


/** How long a feed waits on the stream before the ring shows: the quick skips that line feeds up stay unmarked. */
private const val BUFFERING_SHOW_MS = 600L

/** A thin spinning ring while [feed] waits on the stream for longer than a moment. */
@Composable
private fun BufferingRing(feed: FeedPlayer, modifier: Modifier) {
    var shown by remember(feed) { mutableStateOf(false) }
    LaunchedEffect(feed, feed.buffering) {
        if (feed.buffering) {
            delay(BUFFERING_SHOW_MS)
            shown = true
        } else {
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(Motion.SCREEN_MS, easing = EaseOut), label = "buffering")
    if (alpha > 0f) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = modifier.size(36.dp).graphicsLayer { this.alpha = alpha },
            color = Ink.primary,
            // A faint dark track, so the ring still reads over a bright sky.
            trackColor = Color.Black.copy(alpha = 0.3f),
            strokeWidth = 2.5.dp,
        )
    }
}

