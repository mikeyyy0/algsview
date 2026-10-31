package app.algsview

import org.junit.Assert.assertEquals
import org.junit.Test

class RefMapTest {
    private val map = RefMap("a", "Storm Point", "/x.png", 1000, 1000)

    @Test fun tapDropsAPin() = assertEquals(listOf(Pin(0.5f, 0.5f, 2)), map.tapped(0.5f, 0.5f, 0.02f, 2).pins)

    @Test fun tapOnAPinRemovesIt() {
        val pinned = map.tapped(0.5f, 0.5f, 0.02f, 2)
        assertEquals(emptyList<Pin>(), pinned.tapped(0.51f, 0.5f, 0.02f, 0).pins)
    }

    @Test fun tapElsewhereAddsAnother() {
        val pinned = map.tapped(0.5f, 0.5f, 0.02f, 2)
        assertEquals(2, pinned.tapped(0.2f, 0.2f, 0.02f, 0).pins.size)
    }

    @Test fun pinsSurviveStorage() {
        val pinned = map.tapped(0.25f, 0.75f, 0.02f, 3)
        assertEquals(listOf(pinned), RefMap.listFromJson(RefMap.listToJson(listOf(pinned))))
    }
}
