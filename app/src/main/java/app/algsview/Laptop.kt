package app.algsview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * The half of the screen below the hinge in laptop mode: the feeds to choose from, with Scores, on
 * the left. On the right, the map in its own window if one is set, else the standings.
 */
@Composable
fun LaptopPanel(app: AppState, modifier: Modifier) {
    Row(modifier.background(Ink.background)) {
        Column(Modifier.weight(AppState.LAPTOP_LIST_SHARE).fillMaxHeight().padding(start = 20.dp, top = 12.dp)) {
            app.tiles.forEach { tile ->
                Row(
                    Modifier.fillMaxWidth().pressable(scaleTo = 0.99f) { app.putOnTop(tile) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Accents[tile.accent]))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tile.channel.title,
                        style = Type.headline.copy(color = if (tile.id == app.bigTileIdForUi) Ink.primary else Ink.secondary),
                        maxLines = 1,
                    )
                }
            }
            Text(
                "Scores",
                style = Type.headline,
                modifier = Modifier.pressable { app.scoresOpen = true }.padding(vertical = 14.dp),
            )
        }
        // The map's window lies over this side when there is one; otherwise the standings fill it.
        Box(Modifier.weight(1f - AppState.LAPTOP_LIST_SHARE).fillMaxHeight()) {
            if (app.currentMap == null) {
                RefreshStandings(app)
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) { StandingsTable(app) }
            }
        }
    }
}

/** The one-time question when the phone is first held half-open like a laptop. */
@Composable
fun LaptopPrompt(app: AppState) {
    if (!app.laptopPromptOpen) return
    Dialog(onDismissRequest = { app.answerLaptopPrompt(false) }) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ink.surface)
                .padding(24.dp)
        ) {
            Text("Laptop mode?", style = Type.title2)
            Text(
                "With the phone half-open like this, one feed can sit on the top half, with your other feeds and the map on the bottom half. You can turn this on or off any time in Layout.",
                style = Type.subhead,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                SecondaryButton("No thanks") { app.answerLaptopPrompt(false) }
                PrimaryButton("Use laptop mode") { app.answerLaptopPrompt(true) }
            }
        }
    }
}
