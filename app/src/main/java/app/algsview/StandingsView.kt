package app.algsview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** How often the points are asked for while they're on screen. */
private const val REFRESH_MS = 15_000L
private val NUMBER = 44.dp

/** Keeps the standings current for as long as this is on screen. */
@Composable
fun RefreshStandings(app: AppState) {
    LaunchedEffect(Unit) {
        while (true) {
            app.loadStandings()
            delay(REFRESH_MS)
        }
    }
}

/** The current set's standings over the feeds. They stay visible and playing above it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandingsSheet(app: AppState) {
    RefreshStandings(app)
    // Read out here: inside the sheet's own window the context isn't the activity.
    val activity = LocalContext.current as android.app.Activity
    ModalBottomSheet(
        onDismissRequest = { app.scoresOpen = false },
        sheetState = rememberModalBottomSheetState(),
        containerColor = Ink.surface,
        scrimColor = Color.Transparent,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        KeepSystemBarsHidden(enabled = app.tiles.isNotEmpty() && !app.browsing)
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            StandingsTable(app)
            Text(
                "Open on Battlefy",
                style = Type.footnote,
                modifier = Modifier.pressable { app.scoresOpen = false; app.openScores(activity) }.padding(top = 16.dp, bottom = 4.dp),
            )
        }
    }
}

/** The set's name, where it's got to, and every team by points. */
@Composable
fun StandingsTable(app: AppState) {
    val standings = app.standings
    if (standings == null) {
        Text(app.standingsError ?: "Loading the standings…", style = Type.subhead, modifier = Modifier.padding(vertical = 16.dp))
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(standings.name, style = Type.title2, modifier = Modifier.weight(1f, fill = false))
        if (standings.live) {
            Spacer(Modifier.width(10.dp))
            LiveMark()
        }
    }
    Text(listOfNotNull(standings.match, standings.event).joinToString(" · "), style = Type.footnote, modifier = Modifier.padding(top = 2.dp))
    app.standingsError?.let { Text(it, style = Type.footnote, modifier = Modifier.padding(top = 2.dp)) }

    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) {
        Spacer(Modifier.weight(1f))
        Heading("Kills", NUMBER)
        Heading("Place", NUMBER)
        Heading("Points", NUMBER + 6.dp)
    }
    standings.teams.forEach { TeamRow(app, it) }
}

@Composable
private fun Heading(text: String, width: Dp) {
    Text(text, style = Type.caption, textAlign = TextAlign.End, modifier = Modifier.width(width))
}

@Composable
private fun TeamRow(app: AppState, team: TeamStanding) {
    // A team on screen wears its feed's colour.
    val tile = app.tiles.firstOrNull { it.channel.login?.let { login -> app.channelTeams[login]?.name } == team.name || it.channel.title == team.name }
    Row(
        Modifier.fillMaxWidth().height(36.dp).graphicsLayer { alpha = if (team.eliminated) 0.45f else 1f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${team.position}", style = Type.footnote, textAlign = TextAlign.End, modifier = Modifier.width(22.dp))
        Spacer(Modifier.width(10.dp))
        AsyncImage(model = team.logo, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(team.shortName, style = Type.subhead.copy(color = Ink.primary, fontWeight = FontWeight.SemiBold), modifier = Modifier.width(52.dp), maxLines = 1)
        Text(team.name, style = Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        tile?.let {
            Box(Modifier.padding(horizontal = 6.dp).size(8.dp).clip(CircleShape).background(Accents[it.accent]))
        }
        if (team.wonMatchPoint || team.matchPointEligible) {
            Text(if (team.wonMatchPoint) "Won" else "MP", style = Type.caption.copy(color = Ink.primary), modifier = Modifier.padding(horizontal = 6.dp))
        }
        Text("${team.kills}", style = Type.subhead, textAlign = TextAlign.End, modifier = Modifier.width(NUMBER))
        Text("${team.placementPoints}", style = Type.subhead, textAlign = TextAlign.End, modifier = Modifier.width(NUMBER))
        Text("${team.points}", style = Type.subhead.copy(color = Ink.primary, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.End, modifier = Modifier.width(NUMBER + 6.dp))
    }
}
