package com.roadbuddy.core.data.navigation

import com.roadbuddy.core.model.LatLng
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** The GPS facts written into (or read from) a frame's EXIF block. */
data class GpsTag(
    val location: LatLng,
    val capturedAt: Instant,
    /** Direction the camera faced, degrees from true north. */
    val headingDeg: Double? = null,
)

/**
 * Writes and reads standard EXIF GPS tags in a JPEG, the same tags a phone
 * camera writes, so any EXIF reader (Pillow, exiftool, Android's
 * ExifInterface) sees where and when a frame was taken.
 *
 * Plain Kotlin with no Android or library dependencies, so it runs in JVM unit
 * tests. [write] replaces any existing EXIF block and keeps the image data
 * byte-for-byte.
 */
object GpsExif {
    private const val SOI = 0xD8
    private const val SOS = 0xDA
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

    private val EXIF_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(ZoneOffset.UTC)
    private val GPS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd").withZone(ZoneOffset.UTC)

    // TIFF field types.
    private const val BYTE = 1
    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5

    // Tags.
    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_SOFTWARE = 0x0131
    private const val TAG_DATE_TIME = 0x0132
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_GPS_IFD = 0x8825
    private const val TAG_DATE_TIME_ORIGINAL = 0x9003
    private const val TAG_OFFSET_TIME_ORIGINAL = 0x9011
    private const val GPS_VERSION = 0x0000
    private const val GPS_LAT_REF = 0x0001
    private const val GPS_LAT = 0x0002
    private const val GPS_LNG_REF = 0x0003
    private const val GPS_LNG = 0x0004
    private const val GPS_TIME = 0x0007
    private const val GPS_IMG_DIRECTION_REF = 0x0010
    private const val GPS_IMG_DIRECTION = 0x0011
    private const val GPS_DATE_STAMP = 0x001D

    /** Returns a copy of [jpeg] with an EXIF block carrying [tag]. */
    fun write(jpeg: ByteArray, tag: GpsTag, model: String = "RoadBuddy mock dashcam"): ByteArray {
        val segments = splitHeader(jpeg)
        val out = ByteArrayOutputStream(jpeg.size + 512)
        out.write(0xFF)
        out.write(SOI)
        // Keep a JFIF APP0 first, as exiftool does, then our APP1.
        segments.headers.filter { it.marker == APP0 }.forEach { out.write(it.bytes) }
        out.write(app1Segment(buildTiff(tag, model)))
        segments.headers
            .filter { it.marker != APP0 && !(it.marker == APP1 && it.isExif()) }
            .forEach { out.write(it.bytes) }
        out.write(jpeg, segments.restOffset, jpeg.size - segments.restOffset)
        return out.toByteArray()
    }

    /** Reads the GPS tags from [jpeg], or null if it has no EXIF GPS block. */
    fun read(jpeg: ByteArray): GpsTag? {
        val exif = splitHeader(jpeg).headers.firstOrNull { it.marker == APP1 && it.isExif() } ?: return null
        // Segment = FF E1, 2-byte length, "Exif\0\0", then the TIFF block.
        val tiff = ByteBuffer.wrap(exif.bytes, 4 + EXIF_HEADER.size, exif.bytes.size - 4 - EXIF_HEADER.size).slice()
        tiff.order(
            when (String(byteArrayOf(tiff.get(0), tiff.get(1)), Charsets.US_ASCII)) {
                "MM" -> ByteOrder.BIG_ENDIAN
                "II" -> ByteOrder.LITTLE_ENDIAN
                else -> return null
            },
        )
        val ifd0 = readIfd(tiff, tiff.getInt(4))
        val gpsOffset = ifd0[TAG_GPS_IFD]?.let { tiff.getInt(it.valueOffset) } ?: return null
        val gps = readIfd(tiff, gpsOffset)

        fun ascii(tag: Int) = gps[tag]?.let { e ->
            val bytes = ByteArray(e.count) { tiff.get(e.dataOffset(tiff) + it) }
            String(bytes, Charsets.US_ASCII).trimEnd('\u0000')
        }
        fun rationals(tag: Int) = gps[tag]?.let { e ->
            val at = e.dataOffset(tiff)
            DoubleArray(e.count) {
                val num = tiff.getInt(at + it * 8).toLong() and 0xFFFFFFFFL
                val den = tiff.getInt(at + it * 8 + 4).toLong() and 0xFFFFFFFFL
                if (den == 0L) 0.0 else num.toDouble() / den
            }
        }
        fun degrees(dms: DoubleArray) = dms[0] + dms[1] / 60 + dms[2] / 3600

        val lat = rationals(GPS_LAT)?.let(::degrees) ?: return null
        val lng = rationals(GPS_LNG)?.let(::degrees) ?: return null
        val latSign = if (ascii(GPS_LAT_REF) == "S") -1 else 1
        val lngSign = if (ascii(GPS_LNG_REF) == "W") -1 else 1
        val time = rationals(GPS_TIME)
        val date = ascii(GPS_DATE_STAMP)
        val capturedAt = if (time != null && date != null) {
            val nanos = ((time[2] - floor(time[2])) * 1e9).roundToLong()
            LocalDate.parse(date, GPS_DATE)
                .atTime(LocalTime.of(time[0].toInt(), time[1].toInt(), time[2].toInt(), nanos.toInt()))
                .toInstant(ZoneOffset.UTC)
        } else {
            Instant.EPOCH
        }
        return GpsTag(
            location = LatLng(latSign * lat, lngSign * lng),
            capturedAt = capturedAt,
            headingDeg = rationals(GPS_IMG_DIRECTION)?.first(),
        )
    }

    // ---- JPEG segments ----------------------------------------------------------------

    private class Segment(val marker: Int, val bytes: ByteArray) {
        fun isExif() = bytes.size >= 4 + EXIF_HEADER.size &&
            EXIF_HEADER.indices.all { bytes[4 + it] == EXIF_HEADER[it] }
    }

    /** The header segments before the image data, and where the rest (SOS onwards) starts. */
    private class Header(val headers: List<Segment>, val restOffset: Int)

    private fun splitHeader(jpeg: ByteArray): Header {
        require(jpeg.size >= 4 && jpeg.u8(0) == 0xFF && jpeg.u8(1) == SOI) { "Not a JPEG" }
        val headers = mutableListOf<Segment>()
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            require(jpeg.u8(pos) == 0xFF) { "Bad JPEG marker at $pos" }
            val marker = jpeg.u8(pos + 1)
            if (marker == SOS) break
            val length = (jpeg.u8(pos + 2) shl 8) or jpeg.u8(pos + 3)
            require(length >= 2 && pos + 2 + length <= jpeg.size) { "Bad JPEG segment length at $pos" }
            headers += Segment(marker, jpeg.copyOfRange(pos, pos + 2 + length))
            pos += 2 + length
        }
        return Header(headers, pos)
    }

    private fun app1Segment(tiff: ByteArray): ByteArray {
        val length = 2 + EXIF_HEADER.size + tiff.size
        require(length <= 0xFFFF) { "EXIF block too large" }
        return ByteBuffer.allocate(2 + length)
            .put(0xFF.toByte()).put(APP1.toByte())
            .putShort(length.toShort())
            .put(EXIF_HEADER)
            .put(tiff)
            .array()
    }

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF

    // ---- TIFF writing (big-endian) -------------------------------------------------------

    private class Field(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    private fun ascii(tag: Int, text: String) =
        Field(tag, ASCII, text.length + 1, (text + "\u0000").toByteArray(Charsets.US_ASCII))

    private fun rational(tag: Int, vararg values: Pair<Long, Long>): Field {
        val buf = ByteBuffer.allocate(values.size * 8)
        values.forEach { (num, den) -> buf.putInt(num.toInt()).putInt(den.toInt()) }
        return Field(tag, RATIONAL, values.size, buf.array())
    }

    private fun pointer(tag: Int) = Field(tag, LONG, 1, ByteArray(4))

    /** Degrees as EXIF degrees/minutes/seconds, with seconds to 1/10000 (about 3 mm). */
    private fun dms(tag: Int, value: Double): Field {
        var totalTenThousandths = (abs(value) * 3600 * 10_000).roundToLong()
        val degrees = totalTenThousandths / (3600L * 10_000)
        totalTenThousandths -= degrees * 3600 * 10_000
        val minutes = totalTenThousandths / (60L * 10_000)
        val seconds = totalTenThousandths - minutes * 60 * 10_000
        return rational(tag, degrees to 1L, minutes to 1L, seconds to 10_000L)
    }

    private fun buildTiff(tag: GpsTag, model: String): ByteArray {
        val utc = tag.capturedAt.atOffset(ZoneOffset.UTC)
        val dateTime = EXIF_DATE_TIME.format(tag.capturedAt)

        val ifd0 = listOf(
            ascii(TAG_MAKE, "RoadBuddy"),
            ascii(TAG_MODEL, model),
            ascii(TAG_SOFTWARE, "RoadBuddy"),
            ascii(TAG_DATE_TIME, dateTime),
            pointer(TAG_EXIF_IFD),
            pointer(TAG_GPS_IFD),
        )
        val exif = listOf(
            ascii(TAG_DATE_TIME_ORIGINAL, dateTime),
            ascii(TAG_OFFSET_TIME_ORIGINAL, "+00:00"),
        )
        val millisOfSecond = utc.nano / 1_000_000
        val gps = buildList {
            add(Field(GPS_VERSION, BYTE, 4, byteArrayOf(2, 3, 0, 0)))
            add(ascii(GPS_LAT_REF, if (tag.location.lat >= 0) "N" else "S"))
            add(dms(GPS_LAT, tag.location.lat))
            add(ascii(GPS_LNG_REF, if (tag.location.lng >= 0) "E" else "W"))
            add(dms(GPS_LNG, tag.location.lng))
            add(
                rational(
                    GPS_TIME,
                    utc.hour.toLong() to 1L,
                    utc.minute.toLong() to 1L,
                    (utc.second * 1_000L + millisOfSecond) to 1_000L,
                ),
            )
            tag.headingDeg?.let { heading ->
                add(ascii(GPS_IMG_DIRECTION_REF, "T"))
                add(rational(GPS_IMG_DIRECTION, (((heading % 360) + 360) % 360 * 100).roundToLong() % 36_000 to 100L))
            }
            add(ascii(GPS_DATE_STAMP, GPS_DATE.format(tag.capturedAt)))
        }

        val ifds = listOf(ifd0, exif, gps)
        val offsets = IntArray(ifds.size)
        var next = 8 // after the TIFF header
        ifds.forEachIndexed { i, fields ->
            offsets[i] = next
            next += ifdSize(fields)
        }
        val buf = ByteBuffer.allocate(next).order(ByteOrder.BIG_ENDIAN)
        buf.put('M'.code.toByte()).put('M'.code.toByte()).putShort(42).putInt(offsets[0])

        val pointers = mapOf(TAG_EXIF_IFD to offsets[1], TAG_GPS_IFD to offsets[2])
        ifds.forEachIndexed { i, fields -> writeIfd(buf, offsets[i], fields, pointers) }
        return buf.array()
    }

    private fun ifdSize(fields: List<Field>): Int =
        2 + fields.size * 12 + 4 + fields.sumOf { if (it.data.size > 4) it.data.size + it.data.size % 2 else 0 }

    private fun writeIfd(buf: ByteBuffer, at: Int, fields: List<Field>, pointers: Map<Int, Int>) {
        val sorted = fields.sortedBy { it.tag }
        buf.position(at)
        buf.putShort(sorted.size.toShort())
        var dataAt = at + 2 + sorted.size * 12 + 4
        val external = mutableListOf<Pair<Int, ByteArray>>()
        for (field in sorted) {
            buf.putShort(field.tag.toShort()).putShort(field.type.toShort()).putInt(field.count)
            val data = pointers[field.tag]?.let { ByteBuffer.allocate(4).putInt(it).array() } ?: field.data
            if (data.size <= 4) {
                buf.put(data.copyOf(4))
            } else {
                buf.putInt(dataAt)
                external += dataAt to data
                dataAt += data.size + data.size % 2
            }
        }
        buf.putInt(0) // no next IFD
        external.forEach { (offset, data) ->
            buf.position(offset)
            buf.put(data)
        }
    }

    // ---- TIFF reading ------------------------------------------------------------------

    private class Entry(val type: Int, val count: Int, val valueOffset: Int) {
        private val size get() = count * when (type) {
            SHORT -> 2
            LONG -> 4
            RATIONAL -> 8
            else -> 1
        }

        fun dataOffset(tiff: ByteBuffer) = if (size <= 4) valueOffset else tiff.getInt(valueOffset)
    }

    private fun readIfd(tiff: ByteBuffer, at: Int): Map<Int, Entry> {
        val count = tiff.getShort(at).toInt() and 0xFFFF
        return (0 until count).associate { i ->
            val e = at + 2 + i * 12
            (tiff.getShort(e).toInt() and 0xFFFF) to
                Entry(tiff.getShort(e + 2).toInt() and 0xFFFF, tiff.getInt(e + 4), e + 8)
        }
    }
}
