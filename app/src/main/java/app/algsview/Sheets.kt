package app.algsview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val Rounded = RoundedCornerShape(14.dp)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink.primary,
    unfocusedTextColor = Ink.primary,
    cursorColor = Ink.primary,
    focusedBorderColor = Ink.secondary,
    unfocusedBorderColor = Ink.surfaceHigh,
)

/**
 * Picks what to watch: follow a team by name, then the live team channels with the team each one
 * has now, then the broadcast, then any Twitch channel or link. Rows picked show a check; feeds
 * already on screen say so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedPickerSheet(app: AppState, picker: FeedPicker) {
    ModalBottomSheet(
        onDismissRequest = app::closePicker,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        // Over the viewer (adding a feed), the status bar stays hidden; while browsing it shows.
        KeepSystemBarsHidden(enabled = app.tiles.isNotEmpty() && !app.browsing)
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            ) {
                Text("Add feeds", style = Type.title2)
                Text(
                    picker.note ?: if (app.tiles.isEmpty()) "Pick up to 4. You can switch any of them while watching."
                    else "Feeds on screen are already picked. Unpick one to make room for another.",
                    style = Type.footnote.copy(color = if (picker.note != null) Ink.primary else Ink.secondary),
                    modifier = Modifier.padding(top = 4.dp),
                )

                OutlinedTextField(
                    value = picker.search,
                    onValueChange = { picker.search = it },
                    placeholder = { Text("Search for a team to follow", style = Type.body.copy(color = Ink.secondary)) },
                    singleLine = true,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                val teams = app.liveTeams
                val query = picker.search.trim()
                if (query.isNotEmpty()) {
                    val names = teams.map { Teams.teamName(it.title) }.filter { it.contains(query, ignoreCase = true) }.distinct()
                    names.forEach { FollowRow(app, picker, it) }
                    // A team that isn't on air yet can be followed all the same.
                    if (names.none { it.equals(query, ignoreCase = true) }) FollowRow(app, picker, query)
                }

                if (teams.isNotEmpty()) {
                    Text("Team channels", style = Type.headline, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
                    teams.filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) }.forEach { stream ->
                        PickRow(app, picker, app.channelOf(stream), Teams.channelLabel(stream.login))
                    }
                } else {
                    Text("No team channels are live right now.", style = Type.subhead, modifier = Modifier.padding(top = 24.dp))
                }

                val others = app.liveOthers
                if (others.isNotEmpty()) {
                    Text("Broadcast", style = Type.headline, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
                    others.forEach { stream -> PickRow(app, picker, app.channelOf(stream), stream.title.takeIf { stream.login == Teams.BROADCAST }) }
                }

                Text("Add channel or link", style = Type.headline, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                val target = parseTwitchTarget(picker.link)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = picker.link,
                        onValueChange = { picker.link = it },
                        placeholder = { Text("Twitch channel, or twitch.tv/videos/…", style = Type.body.copy(color = Ink.secondary)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        colors = fieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    if (target != null) {
                        Spacer(Modifier.size(10.dp))
                        SecondaryButton("Add") {
                            app.togglePick(target)
                            picker.link = ""
                        }
                    }
                }
                picker.selected.filter { it.login !in app.streams && it.follow == null }.forEach { PickRow(app, picker, it, null) }
                Spacer(Modifier.height(16.dp))
            }

            val adds = picker.selected.size
            val drops = picker.removing.size
            PrimaryButton(
                when {
                    adds == 0 && drops == 0 -> "Pick a feed"
                    app.tiles.isEmpty() -> if (adds == 1) "Watch 1 feed" else "Watch $adds feeds"
                    drops == 0 -> if (adds == 1) "Add 1 feed" else "Add $adds feeds"
                    adds == 0 -> if (drops == 1) "Remove 1 feed" else "Remove $drops feeds"
                    else -> "Update feeds"
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                enabled = adds > 0 || drops > 0,
                onClick = app::watchPicked,
            )
        }
    }
}

@Composable
private fun FollowRow(app: AppState, picker: FeedPicker, name: String) {
    val channel = app.followChannel(name)
    val picked = picker.selected.any { it.key == channel.key } || app.tiles.any { it.channel.key == channel.key }
    Row(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.99f) { app.togglePick(channel) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Follow $name", style = Type.body, modifier = Modifier.weight(1f))
        if (picked) Icon(Icons.Rounded.Check, null, tint = Ink.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PickRow(app: AppState, picker: FeedPicker, channel: Channel, detail: String?) {
    val playing = app.isPlaying(channel)
    val dropping = picker.removing.any { it.login == channel.login && it.vodId == channel.vodId }
    val picked = picker.selected.any { it.key == channel.key }
    Row(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.99f) { app.togglePick(channel) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(channel.title, style = Type.body.copy(color = if (dropping) Ink.secondary else Ink.primary), maxLines = 1)
            detail?.let { Text(it, style = Type.footnote, maxLines = 1) }
        }
        when {
            playing && !dropping -> Text("On", style = Type.footnote.copy(color = Ink.primary))
            dropping -> Text("Off", style = Type.footnote)
            picked -> Icon(Icons.Rounded.Check, null, tint = Ink.primary, modifier = Modifier.size(20.dp))
        }
    }
}

// Settings.

@Composable
private fun Group(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Text(title, style = Type.subhead, modifier = Modifier.padding(start = 36.dp, end = 20.dp, top = 28.dp, bottom = 8.dp))
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(Rounded).background(Ink.surface).padding(vertical = 6.dp)
    ) { content() }
    footer?.let { Text(it, style = Type.footnote, modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 8.dp)) }
}

@Composable
private fun SettingRow(label: String, value: String? = null, valueColor: Color = Ink.secondary, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(scaleTo = 0.99f, onClick = onClick) else Modifier)
            .heightIn(min = 50.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.body, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = Type.body.copy(color = valueColor)) }
        trailing?.invoke()
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(label, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF30D158),
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Ink.surfaceHigh,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** A saved line of text, in a settings group. */
@Composable
private fun SettingField(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = Type.footnote) },
        placeholder = { Text(hint, style = Type.body.copy(color = Ink.secondary)) },
        singleLine = true,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun SettingsScreen(app: AppState) {
    val s = app.settings
    val list = rememberLazyListState()
    val density = LocalDensity.current
    // The large title is about 50dp tall: the bar fills over its first half and takes the title over.
    val scrolled by remember {
        derivedStateOf { if (list.firstVisibleItemIndex > 0) Float.MAX_VALUE else list.firstVisibleItemScrollOffset.toFloat() }
    }
    val span = with(density) { 50.dp.toPx() }
    Box(Modifier.fillMaxSize().background(Ink.background)) {
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        item {
            Spacer(Modifier.statusBarsPadding().height(60.dp))
            Text("Settings", style = Type.largeTitle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        item {
            Group("Playback", footer = "With 3 or 4 feeds, the ones you aren't listening to drop to 480p or 360p. The one with the audio always asks for the best.") {
                SwitchRow("Lower quality on quiet feeds", s.quietQuality, s::updateQuietQuality)
                SwitchRow("Stream stats on tiles", s.showStats, s::updateShowStats)
            }
        }
        item {
            Group("Team channels", footer = "Numbered channels are the prefix plus 1 up to the count. For more than one set, separate prefixes with commas.") {
                SettingField("Channel prefix", s.teamPrefix, Teams.DEFAULT_PREFIX, s::updateTeamPrefix)
                SettingRow("How many") {
                    Text("−", style = Type.title2, modifier = Modifier.pressable { s.updateTeamCount(s.teamCount - 1) }.padding(horizontal = 14.dp, vertical = 6.dp))
                    Text("${s.teamCount}", style = Type.body)
                    Text("+", style = Type.title2, modifier = Modifier.pressable { s.updateTeamCount(s.teamCount + 1) }.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
        }
        item {
            Group("Scores", footer = "The Scores button opens this link beside the feeds.") {
                SettingField("Battlefy tournament link", s.battlefyUrl, "battlefy.com/…", s::updateBattlefyUrl)
            }
        }
        item {
            Group("Updates", footer = "ALGS View checks for a new version each time it opens.") {
                SettingRow("Check for updates", onClick = app.updater::checkNow)
            }
        }
        item {
            val context = LocalContext.current
            Group(
                "Help",
                footer = "Opens a GitHub issue with your app version and phone model filled in. Nothing is sent until you submit it.",
            ) {
                SettingRow("Report a problem", onClick = { CrashReports.reportProblem(context) })
            }
        }
        item {
            Text(
                "ALGS View ${BuildConfig.VERSION_NAME} · built ${BuildConfig.BUILT_AT}\nUnofficial. Not made or endorsed by EA, Respawn, ALGS or Twitch.",
                style = Type.caption,
                modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 28.dp, bottom = 40.dp),
            )
        }
    }
    TopBar(
        title = "Settings",
        fill = { (scrolled / span).coerceIn(0f, 1f) },
        titleIn = { ((scrolled - span * 0.6f) / (span * 0.4f)).coerceIn(0f, 1f) },
        leading = { backdrop -> GlyphButton(Icons.Rounded.ArrowBackIosNew, "Back", backdrop = backdrop) { app.back() } },
    )
    }
}
