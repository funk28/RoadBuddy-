package com.roadbuddy.core.data.navigation

import com.roadbuddy.core.model.LatLng
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsExifTest {
    private val tag = GpsTag(
        location = LatLng(40.4237, -86.9212),
        capturedAt = Instant.parse("2026-11-13T14:48:02.734Z"),
        headingDeg = 92.5,
    )

    @Test
    fun writtenTagsReadBack() {
        val tagged = GpsExif.write(MockPotholeFrames.rawImage(0), tag)

        val read = GpsExif.read(tagged)!!

        // 1/10000 arc-second is about 3 mm.
        assertEquals(40.4237, read.location.lat, 1e-7)
        assertEquals(-86.9212, read.location.lng, 1e-7)
        assertEquals(tag.capturedAt, read.capturedAt)
        assertEquals(92.5, read.headingDeg!!, 1e-9)
    }

    @Test
    fun handlesSouthernAndEasternHemispheresAndNoHeading() {
        val sydney = GpsTag(LatLng(-33.8568, 151.2153), Instant.parse("2026-01-02T03:04:05Z"))

        val read = GpsExif.read(GpsExif.write(MockPotholeFrames.rawImage(0), sydney))!!

        assertEquals(-33.8568, read.location.lat, 1e-7)
        assertEquals(151.2153, read.location.lng, 1e-7)
        assertEquals(sydney.capturedAt, read.capturedAt)
        assertNull(read.headingDeg)
    }

    @Test
    fun keepsImageDataAndReplacesOldExif() {
        val raw = MockPotholeFrames.rawImage(0)
        val once = GpsExif.write(raw, tag)
        val moved = tag.copy(location = LatLng(40.4280, -86.9110))

        val twice = GpsExif.write(once, moved)

        // Same size as tagging once: the old block was replaced, not stacked.
        assertEquals(once.size, twice.size)
        assertEquals(40.4280, GpsExif.read(twice)!!.location.lat, 1e-7)
        // Everything from the start-of-scan marker on is the untouched image data.
        assertArrayEquals(raw.sliceFromScan(), twice.sliceFromScan())
        // Still a JPEG with the EXIF block straight after JFIF.
        assertEquals(0xD8, twice[1].toInt() and 0xFF)
        assertTrue(String(twice, 0, 64, Charsets.ISO_8859_1).contains("Exif\u0000\u0000MM"))
    }

    @Test
    fun untaggedImageHasNoGps() {
        assertNull(GpsExif.read(MockPotholeFrames.rawImage(0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonJpeg() {
        GpsExif.write("not a jpeg".toByteArray(), tag)
    }

    @Test
    fun bundledFramesRotateAndCarryThePosition() {
        val route = MockRoutes.CAMPUS_LOOP
        val position = route.positionAt(500.0)
        val first = MockPotholeFrames.frameAt(position, tag.capturedAt)
        val second = MockPotholeFrames.frameAt(position, tag.capturedAt)

        assertEquals(2, MockPotholeFrames.count)
        assertTrue("frames should differ", !first.contentEquals(second))
        for (frame in listOf(first, second)) {
            val read = assertNotNullAndGet(GpsExif.read(frame))
            assertEquals(position.location.lat, read.location.lat, 1e-7)
            assertEquals(position.location.lng, read.location.lng, 1e-7)
            assertEquals(position.headingDeg, read.headingDeg!!, 0.01)
            assertTrue("frame is a real image, not a placeholder", frame.size > 20_000)
        }
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun ByteArray.sliceFromScan(): ByteArray {
        var pos = 2
        while ((this[pos + 1].toInt() and 0xFF) != 0xDA) {
            pos += 2 + (((this[pos + 2].toInt() and 0xFF) shl 8) or (this[pos + 3].toInt() and 0xFF))
        }
        return copyOfRange(pos, size)
    }
}
