package app.algsview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** A dropped pin, in fractions of the image, coloured by one of the feeds' [Accents]. */
data class Pin(val x: Float, val y: Float, val color: Int)

/** A map image the person imported. The image lives in the app's storage; no game art ships with the app. */
data class RefMap(val id: String, val name: String, val file: String, val width: Int, val height: Int, val pins: List<Pin> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("file", file).put("width", width).put("height", height)
        .put("pins", JSONArray(pins.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble(), it.color)) }))

    /** The pin within [reach] (fractions of the image's width) of a tap, if any, for removing it. */
    fun pinAt(x: Float, y: Float, reach: Float): Pin? {
        val aspect = height.toFloat() / width.coerceAtLeast(1)
        return pins.filter { hypot(it.x - x, (it.y - y) * aspect) <= reach }.minByOrNull { hypot(it.x - x, (it.y - y) * aspect) }
    }

    /** A tap on the map: takes away the pin it landed on, else drops one. */
    fun tapped(x: Float, y: Float, reach: Float, color: Int): RefMap {
        val hit = pinAt(x, y, reach)
        return copy(pins = if (hit != null) pins - hit else pins + Pin(x, y, color))
    }

    companion object {
        fun listFromJson(text: String?): List<RefMap> = runCatching {
            val list = JSONArray(text ?: return emptyList())
            (0 until list.length()).map { i ->
                val j = list.getJSONObject(i)
                val pins = j.getJSONArray("pins")
                RefMap(
                    j.getString("id"), j.getString("name"), j.getString("file"), j.getInt("width"), j.getInt("height"),
                    (0 until pins.length()).map { k ->
                        val p = pins.getJSONArray(k)
                        Pin(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getInt(2))
                    },
                )
            }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<RefMap>): String = JSONArray(list.map { it.toJson() }).toString()
    }
}

/**
 * The map in its own window, over the feeds. Tap to drop a pin, tap a pin to remove it. While
 * arranging and unlocked, a drag moves it, a pinch resizes and turns it and the corner handle
 * resizes it. Locked, arranging touches go through to the feeds underneath.
 */
@Composable
fun MapPanelView(app: AppState, map: RefMap) {
    val panel = app.activeMapPanel
    val density = LocalDensity.current
    val moving = app.arranging && !app.mapLocked
    // The image fitted inside the window.
    val k = min(panel.w / map.width.coerceAtLeast(1), panel.h / map.height.coerceAtLeast(1))
    val imageW = map.width * k
    val imageH = map.height * k
    val reach = with(density) { PIN_REACH.toPx() } / imageW.coerceAtLeast(1f)
    Box(
        Modifier
            .offset { IntOffset(panel.x.roundToInt(), panel.y.roundToInt()) }
            // requiredSize, not size: size() is capped at the screen, and a bigger window would lose its edge.
            .requiredSize(with(density) { panel.w.toDp() }, with(density) { panel.h.toDp() })
            .then(
                if (moving) Modifier.pointerInput(Unit) {
                    detectTransformGestures(onGesture = { _, pan, zoom, turn -> app.arrangeMap(pan, zoom, turn) })
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .requiredSize(with(density) { imageW.toDp() }, with(density) { imageH.toDp() })
                .graphicsLayer {
                    rotationZ = panel.rotation
                    alpha = app.mapOpacity
                }
                .then(
                    if (!app.arranging) Modifier.pointerInput(map.id, imageW, imageH, reach) {
                        detectTapGestures { at -> app.tapMap(at.x / size.width, at.y / size.height, reach) }
                    } else Modifier
                )
        ) {
            AsyncImage(model = File(map.file), contentDescription = map.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val r = 7.dp.toPx()
                map.pins.forEach { pin ->
                    val at = Offset(pin.x * size.width, pin.y * size.height)
                    drawCircle(Color.Black, r + 2.dp.toPx(), at)
                    drawCircle(Accents[pin.color.coerceIn(Accents.indices)], r, at)
                }
            }
        }
        if (moving) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(44.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragEnd = app::storeMapPanel, onDragCancel = app::storeMapPanel) { change, drag ->
                            change.consume()
                            val span = (panel.w + panel.h).coerceAtLeast(1f)
                            app.arrangeMap(Offset.Zero, 1f + (drag.x + drag.y) / span, 0f)
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

private val PIN_REACH = 22.dp

/** Which map, importing one, pins, and how the overlay looks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSheet(app: AppState) {
    ModalBottomSheet(
        onDismissRequest = { app.mapSheetOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        KeepSystemBarsHidden()
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Map", style = Type.title2)
            Text("A screenshot of the in-game map, over the feeds. There's no live position data for Apex, so the pins are yours.", style = Type.footnote, modifier = Modifier.padding(top = 4.dp))

            app.maps.forEach { map ->
                Row(
                    Modifier.fillMaxWidth().pressable(scaleTo = 0.99f) { app.chooseMap(map) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(map.name, style = Type.body, modifier = Modifier.weight(1f))
                    if (map.id == app.currentMap?.id) Icon(Icons.Rounded.Check, null, tint = Ink.primary, modifier = Modifier.size(20.dp))
                }
            }

            val picked = app.mapToName
            if (picked != null) {
                var name by remember(picked) { mutableStateOf("") }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Name this map", style = Type.body.copy(color = Ink.secondary)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Save map", enabled = name.isNotBlank()) { app.importMap(name.trim()) }
                    SecondaryButton("Cancel") { app.cancelMapImport() }
                }
            } else {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Import a map") { app.pickMapImage() }
                    if (app.currentMap != null) SecondaryButton("Hide map") { app.chooseMap(null) }
                }
            }

            app.currentMap?.let { map ->
                Text("Pins", style = Type.headline, modifier = Modifier.padding(top = 24.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Accents.forEachIndexed { i, color ->
                        Box(
                            Modifier.size(44.dp).pressable { app.pinColor = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(if (app.pinColor == i) 30.dp else 20.dp).clip(CircleShape).background(color))
                        }
                    }
                    Text("Clear pins", style = Type.headline, modifier = Modifier.pressable(onClick = app::clearPins).padding(horizontal = 14.dp, vertical = 10.dp))
                }
                Text("Tap the map to drop a pin in this colour; tap a pin to remove it. The colours match the dots on your feeds.", style = Type.footnote, modifier = Modifier.padding(top = 6.dp))

                Text("Opacity", style = Type.headline, modifier = Modifier.padding(top = 24.dp))
                AppleSlider(
                    value = app.mapOpacity,
                    onValueChange = app::updateMapOpacity,
                    valueRange = 0.2f..1f,
                    onValueChangeFinished = app::storeMapPanel,
                    trackColor = Ink.surfaceHigh,
                )
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Turn", style = Type.headline)
                        Text("%.0f°".format(app.activeMapPanel.rotation), style = Type.footnote)
                    }
                    listOf("↺" to -15f, "↻" to 15f).forEach { (label, step) ->
                        Text(label, style = Type.title2, modifier = Modifier.pressable { app.rotateMap(step) }.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                    Text("Reset", style = Type.headline, modifier = Modifier.pressable(onClick = app::resetMapRotation).padding(horizontal = 14.dp, vertical = 10.dp))
                }
                Column(Modifier.padding(top = 16.dp).clip(RoundedCornerShape(12.dp)).background(Ink.background).padding(vertical = 4.dp)) {
                    SwitchRow("Lock the map in place", app.mapLocked) { app.toggleMapLock() }
                }
                Text("Unlock the layout from the top bar to drag, pinch or twist the map. Locked, it stays put while you arrange the feeds under it.", style = Type.footnote, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Remove this map",
                    style = Type.headline.copy(color = Ink.secondary),
                    modifier = Modifier.pressable { app.removeMap(map) }.padding(top = 20.dp, bottom = 4.dp),
                )
            }
        }
    }
}
