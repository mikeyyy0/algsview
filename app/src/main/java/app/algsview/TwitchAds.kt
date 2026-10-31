package app.algsview

import java.util.TreeSet

/**
 * Takes stitched-in ads out of a Twitch media playlist before Media3 reads it, the way Streamlink's
 * --twitch-disable-ads does: nothing marked as an ad is ever fetched, so the player runs out of
 * picture and holds its last frame until real segments come back.
 *
 * Media3 numbers segments by their place in the playlist, so taking some out means renumbering:
 * every real segment moves down by the number of ads before it, and a discontinuity marks each
 * join (the picture's timestamps jump across a break). One filter serves all of a feed's
 * renditions, which share Twitch's numbering, so they stay lined up with each other.
 */
class TwitchAdFilter {
    private class Segment(val seq: Long, val date: String?, val extinf: String, val uri: String, val map: String?)

    private val ads = TreeSet<Long>()
    private val breaks = TreeSet<Long>()
    private val windows = HashMap<String, ArrayList<Segment>>()

    /** The newest thing Twitch has for this feed is an ad. */
    @Volatile var inAdBreak = false
        private set

    /** [rendition] names the playlist, as each rendition remembers its own recent segments. */
    @Synchronized
    fun filter(rendition: String, playlist: String): String {
        // Multivariant playlists and whole past broadcasts go through untouched.
        if ("#EXT-X-STREAM-INF" in playlist || "#EXT-X-ENDLIST" in playlist || "#EXT-X-PLAYLIST-TYPE:VOD" in playlist) return playlist

        var target = "#EXT-X-TARGETDURATION:6"
        var discontinuity = false
        var date: String? = null
        var extinf: String? = null
        var map: String? = null
        val lines = playlist.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var next = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }?.substringAfter(':')?.toLongOrNull() ?: 0L
        // Date ranges can sit anywhere, ahead of the segments they cover or behind them.
        val adRanges = lines.filter { it.startsWith("#EXT-X-DATERANGE:") }.mapNotNull(::adRange)

        val window = windows.getOrPut(rendition) { ArrayList() }
        var lastWasAd = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = line
                line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> Unit
                line.startsWith("#EXT-X-DISCONTINUITY") -> discontinuity = true
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> date = line
                line.startsWith("#EXT-X-MAP:") -> map = line
                line.startsWith("#EXTINF:") -> extinf = line
                line.startsWith("#") -> Unit
                extinf != null -> {
                    val title = extinf.substringAfter(',', "")
                    val at = date?.let { parseDate(it.substringAfter(':')) }
                    val ad = (title.isNotEmpty() && title != "live") || (at != null && adRanges.any { at >= it.first && at < it.second })
                    lastWasAd = ad
                    if (ad) {
                        ads.add(next)
                    } else {
                        if (discontinuity || (next - 1) in ads) breaks.add(next)
                        if (window.none { it.seq == next }) {
                            window.add(Segment(next, date, extinf, line, map))
                            if (window.size > WINDOW) window.removeAt(0)
                        }
                    }
                    next++
                    extinf = null
                    date = null
                    discontinuity = false
                }
            }
        }
        inAdBreak = lastWasAd
        // The window outlives a break, so the last real segments stand in and the playlist simply doesn't move.
        return render(target, window, next)
    }

    private fun virtual(seq: Long) = seq - ads.headSet(seq).size

    private fun render(target: String, window: List<Segment>, next: Long): String {
        val out = StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n").append(target).append('\n')
        val first = window.firstOrNull()?.seq ?: next
        out.append("#EXT-X-MEDIA-SEQUENCE:").append(virtual(first)).append('\n')
        out.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(breaks.headSet(first, true).size).append('\n')
        var lastMap: String? = null
        window.forEachIndexed { i, s ->
            if (i > 0 && s.seq in breaks) out.append("#EXT-X-DISCONTINUITY\n")
            if (s.map != null && s.map != lastMap) out.append(s.map).append('\n')
            lastMap = s.map
            s.date?.let { out.append(it).append('\n') }
            out.append(s.extinf).append('\n').append(s.uri).append('\n')
        }
        return out.toString()
    }

    private fun adRange(line: String): Pair<Long, Long>? {
        if ("CLASS=\"twitch-stitched-ad\"" !in line && "ID=\"stitched-ad-" !in line) return null
        val start = Regex("START-DATE=\"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let(::parseDate) ?: return null
        val seconds = Regex("[,:]DURATION=([0-9.]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return start to start + (seconds * 1000).toLong()
    }

    private fun parseDate(text: String): Long? = runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()

    private companion object {
        const val WINDOW = 6
    }
}
