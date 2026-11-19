package com.roadbuddy.core.data.navigation

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stand-in for a CameraX frame when the fake "pothole detected" trigger fires.
 *
 * It takes one of the bundled dashcam-style pothole images (drawn for the
 * mockup; drop real photos into `src/main/resources/.../navigation/` to use
 * those instead) and writes EXIF GPS tags for the car's position, heading and
 * capture time, so the uploaded JPEG carries the same location as the `meta`
 * JSON.
 */
object MockPotholeFrames {
    private val names = listOf("mock_pothole_1.jpg", "mock_pothole_2.jpg")
    private val next = AtomicInteger(0)

    private val images: List<ByteArray> by lazy {
        names.map { name ->
            MockPotholeFrames::class.java.getResourceAsStream(name)?.use { it.readBytes() }
                ?: error("Missing mock frame resource $name")
        }
    }

    val count: Int get() = names.size

    /** The bundled image [index] (wrapping), with no GPS tags. */
    fun rawImage(index: Int): ByteArray = images[Math.floorMod(index, images.size)].copyOf()

    /** The next bundled image (they rotate), tagged with [position] and [capturedAt]. */
    fun frameAt(position: RoutePosition, capturedAt: Instant): ByteArray =
        GpsExif.write(
            rawImage(next.getAndIncrement()),
            GpsTag(position.location, capturedAt, position.headingDeg),
        )
}
