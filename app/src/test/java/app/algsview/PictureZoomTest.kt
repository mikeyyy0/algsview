package app.algsview

import org.junit.Assert.assertEquals
import org.junit.Test

class PictureZoomTest {
    @Test fun zoomsAboutTheCentreWithoutMoving() {
        val z = PictureZoom()
        z.zoomBy(2f, 0f, 0f, 0f, 0f, 1000f, 500f)
        assertEquals(2f, z.zoom, 0.001f)
        assertEquals(0f, z.x, 0.001f)
    }

    @Test fun neverShowsPastThePictureEdge() {
        val z = PictureZoom()
        z.zoomBy(2f, 0f, 0f, 5000f, 0f, 1000f, 500f)
        assertEquals(0.5f, z.x, 0.001f)
    }

    @Test fun cantZoomOutPastTheWholePicture() {
        val z = PictureZoom(2f, 0.5f, 0.5f)
        z.zoomBy(0.1f, 0f, 0f, 0f, 0f, 1000f, 500f)
        assertEquals(1f, z.zoom, 0.001f)
        assertEquals(0f, z.x, 0.001f)
    }

    @Test fun rememberedAsText() {
        val z = PictureZoom.fromText(PictureZoom(3f, 0.25f, -0.5f).toText())
        assertEquals(3f, z.zoom, 0.001f)
        assertEquals(0.25f, z.x, 0.001f)
        assertEquals(-0.5f, z.y, 0.001f)
    }

    @Test fun nothingRememberedIsTheWholePicture() = assertEquals(1f, PictureZoom.fromText(null).zoom, 0.001f)
}
