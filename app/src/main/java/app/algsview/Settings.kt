package app.algsview

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Choices that live one level down, in Settings, and survive restarts. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("algsview", Context.MODE_PRIVATE)

    var showStats by mutableStateOf(prefs.getBoolean("showStats", false))
        private set
    /** With 3 or 4 feeds, the ones without the audio drop to 480p or 360p. */
    var quietQuality by mutableStateOf(prefs.getBoolean("quietQuality", true))
        private set
    /** The start of the numbered team channels; several can be given, separated by commas. */
    var teamPrefix by mutableStateOf(prefs.getString("teamPrefix", Teams.DEFAULT_PREFIX) ?: Teams.DEFAULT_PREFIX)
        private set
    var teamCount by mutableIntStateOf(prefs.getInt("teamCount", Teams.DEFAULT_COUNT))
        private set
    var battlefyUrl by mutableStateOf(prefs.getString("battlefyUrl", "") ?: "")
        private set

    fun updateShowStats(value: Boolean) { showStats = value; prefs.edit().putBoolean("showStats", value).apply() }
    fun updateQuietQuality(value: Boolean) { quietQuality = value; prefs.edit().putBoolean("quietQuality", value).apply() }
    fun updateTeamPrefix(value: String) { teamPrefix = value; prefs.edit().putString("teamPrefix", value).apply() }
    fun updateTeamCount(value: Int) { teamCount = value.coerceIn(1, 40); prefs.edit().putInt("teamCount", teamCount).apply() }
    fun updateBattlefyUrl(value: String) { battlefyUrl = value; prefs.edit().putString("battlefyUrl", value).apply() }
}
