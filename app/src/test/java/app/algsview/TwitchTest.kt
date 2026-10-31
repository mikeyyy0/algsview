package app.algsview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchTest {
    @Test fun login() = assertEquals("live:playapex", parseTwitchTarget("PlayApex")?.key)
    @Test fun channelLink() = assertEquals("algs1_team7", parseTwitchTarget("https://www.twitch.tv/algs1_team7")?.login)
    @Test fun vodLinkWithTime() {
        val c = parseTwitchTarget("twitch.tv/videos/2914042356?t=1h2m3s")!!
        assertEquals("2914042356", c.vodId)
        assertEquals(3_723_000L, c.startMs)
    }
    @Test fun nonsense() = assertEquals(null, parseTwitchTarget("not a channel!"))

    private fun seg(n: Int, title: String = "live") = "#EXT-X-PROGRAM-DATE-TIME:2026-10-31T04:34:%02d.000Z\n#EXTINF:2.000,$title\nhttps://x/$n.ts\n".format(n * 2)
    private fun list(seq: Int, vararg segs: String) = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:$seq\n" + segs.joinToString("")
    private fun seqOf(p: String) = Regex("MEDIA-SEQUENCE:(\\d+)").find(p)!!.groupValues[1].toInt()

    @Test fun passesPlainSegments() {
        val f = TwitchAdFilter()
        val out = f.filter("a", list(10, seg(10), seg(11)))
        assertTrue("https://x/10.ts" in out && "https://x/11.ts" in out)
        assertFalse(f.inAdBreak)
    }

    @Test fun dropsAdsByTitle() {
        val f = TwitchAdFilter()
        val out = f.filter("a", list(0, seg(0), seg(1, "Amazon|123"), seg(2)))
        assertFalse("https://x/1.ts" in out)
        assertTrue("#EXT-X-DISCONTINUITY\n" in out)
    }

    @Test fun dropsAdsByDateRange() {
        val range = "#EXT-X-DATERANGE:ID=\"stitched-ad-1\",CLASS=\"twitch-stitched-ad\",START-DATE=\"2026-10-31T04:34:02.000Z\",DURATION=2.000\n"
        val out = TwitchAdFilter().filter("a", "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n" + range + seg(0) + seg(1) + seg(2))
        assertFalse("https://x/1.ts" in out)
        assertTrue("https://x/2.ts" in out)
    }

    @Test fun prerollIsEmptyThenStartsCleanly() {
        val f = TwitchAdFilter()
        val during = f.filter("a", list(0, seg(0, "Amazon|1"), seg(1, "Amazon|1")))
        assertFalse(".ts" in during)
        assertTrue(f.inAdBreak)
        val after = f.filter("a", list(1, seg(1, "Amazon|1"), seg(2)))
        assertTrue("https://x/2.ts" in after)
        assertEquals(0, seqOf(after))
        assertFalse(f.inAdBreak)
    }

    @Test fun holdsStillThroughABreak() {
        val f = TwitchAdFilter()
        val before = f.filter("a", list(5, seg(5), seg(6)))
        val during = f.filter("a", list(7, seg(7, "Amazon|1"), seg(8, "Amazon|1")))
        assertEquals(before, during)
        assertTrue(f.inAdBreak)
        val after = f.filter("a", list(8, seg(8, "Amazon|1"), seg(9)))
        // Numbers carry on with no gap: 5 and 6 are still 5 and 6, then 9 becomes 7.
        assertEquals(5, seqOf(after))
        assertTrue("https://x/9.ts" in after)
        assertFalse(f.inAdBreak)
    }

    @Test fun leavesPastBroadcastsAlone() {
        val vod = list(0, seg(0, "anything")) + "#EXT-X-ENDLIST\n"
        assertEquals(vod, TwitchAdFilter().filter("a", vod))
    }
}
