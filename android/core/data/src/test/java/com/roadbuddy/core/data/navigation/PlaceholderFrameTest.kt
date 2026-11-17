package com.roadbuddy.core.data.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class PlaceholderFrameTest {
    @Test
    fun isAJpegAndEachCallIsACopy() {
        val bytes = PlaceholderFrame.jpeg()
        // JPEG start-of-image and end-of-image markers.
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        assertEquals(0xFF, bytes[bytes.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, bytes[bytes.size - 1].toInt() and 0xFF)
        assertNotSame(bytes, PlaceholderFrame.jpeg())
    }
}
