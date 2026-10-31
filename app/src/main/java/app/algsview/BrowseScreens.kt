package app.algsview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.request.ImageRequest
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clipToBounds
import coil.imageLoader
import kotlinx.coroutines.delay
import kotlin.math.floor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(12.dp)
private val Grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private val ButtonShape = RoundedCornerShape(14.dp)
private val SidePadding = 20.dp

// Pieces.

/** White on black, Apple TV's primary action. */
@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier
            .pressable(enabled = enabled, onClick = onClick)
            .clip(ButtonShape)
            .background(if (enabled) Ink.primary else Ink.surfaceHigh)
            .heightIn(min = 50.dp)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (enabled) Color.Black else Ink.tertiary
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Type.headline.copy(color = color))
    }
}

/** Translucent dark, for actions over artwork. */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .pressable(onClick = onClick)
            .clip(ButtonShape)
            .background(Color.White.copy(alpha = 0.16f))
            .heightIn(min = 50.dp)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.headline)
    }
}

/** A round glyph button that floats over artwork. */
@Composable
fun GlyphButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, backdrop: Float = 1f, onClick: () -> Unit) {
    Box(
        modifier
            .pressable(scaleTo = 0.9f, onClick = onClick)
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f * backdrop)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Ink.primary, modifier = Modifier.size(20.dp))
    }
}

/** Red dot and the word, no badge box. */
@Composable
fun LiveMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.live))
        Spacer(Modifier.width(6.dp))
        Text("LIVE", style = Type.caption.copy(color = Ink.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
    }
}

/** 16:9 artwork with its title and one line under it. */
@Composable
private fun ArtCard(
    image: String?,
    title: String,
    subtitle: String?,
    width: Dp,
    live: Boolean = false,
    dimmed: Boolean = false,
    /** Not started yet: gray artwork, faded, and nothing to tap. */
    upcoming: Boolean = false,
    /** How much has been watched, 0..1, drawn as a bar along the artwork's foot. */
    progress: Float? = null,
    /** Set when tapping the card opens a page whose artwork this image grows into. */
    artKey: String? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(width)
            .pressable(enabled = !upcoming, onClick = onClick)
            .graphicsLayer { alpha = if (dimmed || upcoming) 0.55f else 1f }
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).sharedArt(artKey, cornerRadius = 12).clip(CardShape).background(Ink.surface)) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (upcoming) Grayscale else null,
                modifier = Modifier.fillMaxSize(),
            )
            if (live) LiveMark(Modifier.align(Alignment.TopStart).padding(10.dp))
            progress?.let { done ->
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(Modifier.fillMaxWidth(done.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, style = Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A titled horizontal row. Card width leaves the next card peeking, so the row reads as
 * scrollable. Its scroll position is kept on the app state, so coming back to the page finds it
 * where it was (and a card's artwork can shrink back into it).
 */
@Composable
private fun <T> Shelf(
    app: AppState,
    title: String,
    items: List<T>,
    /** Names the row's kept scroll position; rows on different pages can share a title. */
    key: String = title,
    /** Portrait artwork, for shows and documentaries: more to a row. */
    posters: Boolean = false,
    /** A link by the title, such as View all. */
    action: Pair<String, () -> Unit>? = null,
    card: @Composable (T, Dp) -> Unit,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        val perRow = if (posters) (if (wide) 5.3f else 2.6f) else if (wide) 3.3f else 1.7f
        val cardWidth = ((maxWidth - SidePadding * 2 - 12.dp * perRow.toInt()) / perRow).coerceIn(if (posters) 120.dp else 200.dp, 360.dp)
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = SidePadding, end = SidePadding, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(title, style = Type.title2, modifier = Modifier.weight(1f))
                action?.let { (label, onClick) -> HeaderAction(label, onClick) }
            }
            LazyRow(
                state = app.rowState(key),
                contentPadding = PaddingValues(horizontal = SidePadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items) { card(it, cardWidth) }
            }
        }
    }
}

/**
 * The bar across the top of a page. Clear at rest, so its buttons float over the artwork; it
 * fills in dark and translucent as the page scrolls ([fill], 0..1), and the page [title] fades in
 * once the page's own big title has gone under it ([titleIn]). Content scrolling beneath stays
 * faintly visible through it. Modeled on Apple's TV app.
 */
@Composable
internal fun TopBar(
    title: String,
    fill: () -> Float,
    titleIn: () -> Float,
    leading: (@Composable (backdrop: Float) -> Unit)? = null,
    trailing: (@Composable (backdrop: Float) -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.82f * fill())) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(60.dp)
            .padding(horizontal = 12.dp)
    ) {
        leading?.let { Box(Modifier.align(Alignment.CenterStart)) { it(1f - fill()) } }
        Text(
            title,
            style = Type.headline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 56.dp)
                .graphicsLayer { alpha = titleIn() },
        )
        trailing?.let { Box(Modifier.align(Alignment.CenterEnd)) { it(1f - fill()) } }
    }
}

@Composable
private fun HeaderAction(label: String, onClick: () -> Unit) {
    Row(
        Modifier.pressable(onClick = onClick).padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.headline)
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Ink.primary, modifier = Modifier.size(24.dp))
    }
}

// Home.

private fun duration(seconds: Int): String? {
    if (seconds <= 0) return null
    val h = seconds / 3600
    val m = seconds / 60 % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Live now, Continue watching, and recent broadcasts on the main channel. */
@Composable
fun HomeScreen(app: AppState) {
    LaunchedEffect(Unit) {
        while (true) {
            app.pollStreams()
            delay(30_000)
        }
    }
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val scrolled by remember {
        derivedStateOf { if (list.firstVisibleItemIndex > 0) Float.MAX_VALUE else list.firstVisibleItemScrollOffset.toFloat() }
    }
    val span = with(density) { 50.dp.toPx() }
    Box(Modifier.fillMaxSize().background(Ink.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = SidePadding)) {
                    Spacer(Modifier.statusBarsPadding().height(60.dp))
                    Text("ALGS View", style = Type.largeTitle)
                    PrimaryButton("Pick feeds", modifier = Modifier.padding(top = 16.dp), onClick = app::openPicker)
                    app.homeError?.let { Text(it, style = Type.footnote, modifier = Modifier.padding(top = 12.dp)) }
                }
            }
            item {
                val live = app.liveOthers.filter { it.login == Teams.BROADCAST } + app.liveTeams + app.liveOthers.filter { it.login != Teams.BROADCAST }
                if (live.isEmpty()) {
                    Text("Nothing from the ALGS is live right now.", style = Type.subhead, modifier = Modifier.padding(horizontal = SidePadding))
                }
                Shelf(app, "Live now", live) { stream, width ->
                    val channel = app.channelOf(stream)
                    ArtCard(stream.image, channel.title, if (stream.login == Teams.BROADCAST) stream.title else Teams.channelLabel(stream.login), width, live = true) {
                        app.watch(channel)
                    }
                }
            }
            item {
                Shelf(app, "Continue watching", app.recents.toList()) { saved, width ->
                    ArtCard(saved.image, saved.title, null, width) { app.resumeWatching(saved) }
                }
            }
            item {
                Shelf(app, "Recent broadcasts", app.vods.toList()) { vod, width ->
                    ArtCard(vod.image, vod.title, duration(vod.seconds), width) {
                        app.watch(Channel(vod.title, vodId = vod.id, image = vod.image))
                    }
                }
            }
        }
        TopBar(
            title = "ALGS View",
            fill = { (scrolled / span).coerceIn(0f, 1f) },
            titleIn = { ((scrolled - span * 0.6f) / (span * 0.4f)).coerceIn(0f, 1f) },
            trailing = { backdrop -> GlyphButton(Icons.Rounded.Settings, "Settings", backdrop = backdrop) { app.open(Screen.Settings) } },
        )
    }
}
