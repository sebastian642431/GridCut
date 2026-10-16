package com.gridcut.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

class GifWriterTest {
    private fun roundTrip(width: Int, height: Int, frames: List<IntArray>): List<IntArray> {
        val writer = GifWriter(ByteArrayOutputStream(), width, height)
        frames.forEach { writer.frame(it, 80) }
        val bytes = writer.finish().toByteArray()
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        return List(reader.getNumImages(true)) { i ->
            val image = reader.read(i)
            image.getRGB(0, 0, width, height, null, 0, width)
        }
    }

    @Test
    fun keepsExactColorsAcrossFrames() {
        val random = Random(7)
        val colors = IntArray(200) { 0xFF000000.toInt() or random.nextInt(0xFFFFFF) }
        val frames = List(3) { IntArray(97 * 61) { colors[random.nextInt(colors.size)] } }
        val decoded = roundTrip(97, 61, frames)
        assertEquals(3, decoded.size)
        frames.indices.forEach { f -> frames[f].indices.forEach { assertEquals(frames[f][it], decoded[f][it]) } }
    }

    @Test
    fun longRunsSurviveTableResets() {
        val random = Random(3)
        val frame = IntArray(400 * 400) { 0xFF000000.toInt() or (random.nextInt(256) * 0x010101) }
        val decoded = roundTrip(400, 400, listOf(frame))
        frame.indices.forEach { assertEquals(frame[it], decoded[0][it]) }
    }

    @Test
    fun singleColorFrame() {
        val frame = IntArray(10 * 10) { 0xFF336699.toInt() }
        val decoded = roundTrip(10, 10, listOf(frame))
        frame.indices.forEach { assertEquals(frame[it], decoded[0][it]) }
    }

    @Test
    fun transparentPixelsStayTransparent() {
        val frame = IntArray(8 * 8) { if (it % 3 == 0) 0 else 0xFFFF0000.toInt() }
        val decoded = roundTrip(8, 8, listOf(frame))
        frame.indices.forEach { assertEquals(frame[it] ushr 24 == 0, decoded[0][it] ushr 24 == 0) }
    }

    @Test
    fun tooManyColorsStaysClose() {
        val frame = IntArray(128 * 128) { 0xFF000000.toInt() or ((it % 128) * 2 shl 16) or ((it / 128) * 2 shl 8) or 0x40 }
        val decoded = roundTrip(128, 128, listOf(frame))
        val worst = frame.indices.maxOf { i ->
            val a = frame[i]
            val b = decoded[0][i]
            maxOf(kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)), kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)))
        }
        assert(worst <= 12) { "worst channel error $worst" }
    }
}
