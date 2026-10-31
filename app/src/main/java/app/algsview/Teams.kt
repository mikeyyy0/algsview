package app.algsview

/**
 * Who is on which channel. At a LAN every team's POV has a numbered channel whose stream title
 * names the team, as "ZETA DIVISION (Group A) | ALGS Year 6 Group Stage | …", and the team on a
 * channel can change between matches.
 */
object Teams {
    private fun prefixes(text: String) = text.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** Every channel to ask about: the numbered ones for each prefix, their map cams, and the broadcast. */
    fun logins(prefixes: String, count: Int): List<String> =
        prefixes(prefixes).flatMap { p ->
            (1..count).map { "$p$it" } + if (p.endsWith("team")) listOf(p.removeSuffix("team") + "map") else emptyList()
        } + BROADCAST

    fun isTeamChannel(login: String, prefixes: String) =
        prefixes(prefixes).any { p -> login.startsWith(p) && login.removePrefix(p).toIntOrNull() != null }

    /** The team's part of a title: what comes before " (Group" or the first " | ". */
    fun teamName(title: String): String =
        title.substringBefore(" (Group").substringBefore(" | ").trim()

    /** "Team 7" for algs1_team7. */
    fun channelLabel(login: String): String =
        Regex("(\\d+)$").find(login)?.let { "Team ${it.groupValues[1]}" } ?: login

    /** How a tile labels a stream: the team from the title on team channels. */
    fun label(stream: StreamInfo, prefixes: String): String = when {
        stream.login == BROADCAST -> "Broadcast"
        isTeamChannel(stream.login, prefixes) -> teamName(stream.title).ifEmpty { channelLabel(stream.login) }
        stream.login.endsWith("_map") -> "Map cam"
        else -> stream.displayName.ifEmpty { stream.login }
    }

    private fun wholeWord(name: String, text: String) =
        Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(name) + "($|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /**
     * The live channel carrying [name], case-insensitive: a whole-word match first, then a
     * substring. Only the team's part of each title counts, so "Global" or "Group" further along
     * never match. [preferred] wins a tie, so a tile isn't moved between equal matches.
     */
    fun find(name: String, streams: List<StreamInfo>, preferred: String? = null): StreamInfo? {
        val live = streams.filter { it.live && it.title.isNotBlank() }.sortedByDescending { it.login == preferred }
        return live.firstOrNull { wholeWord(name, teamName(it.title)) }
            ?: live.firstOrNull { teamName(it.title).contains(name, ignoreCase = true) }
    }

    /** What a following tile should do after a poll. */
    sealed interface Decision {
        data object Stay : Decision
        data class Move(val to: StreamInfo) : Decision
        data object Wait : Decision
    }

    /** [current] is the login the tile plays now, null while it's waiting. */
    fun decide(name: String, current: String?, streams: List<StreamInfo>): Decision {
        val match = find(name, streams, current)
        return when {
            match == null -> if (current == null) Decision.Stay else Decision.Wait
            match.login == current -> Decision.Stay
            else -> Decision.Move(match)
        }
    }

    const val BROADCAST = "playapex"
    const val DEFAULT_PREFIX = "algs1_team"
    const val DEFAULT_COUNT = 20
}
