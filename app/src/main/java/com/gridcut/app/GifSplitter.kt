package com.gridcut.app

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

const val GIF_MIME = "image/gif"

fun isGif(resolver: ContentResolver, uri: Uri): Boolean = resolver.getType(uri) == GIF_MIME

private object PlainBitmaps : GifDecoder.BitmapProvider {
    override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap = Bitmap.createBitmap(width, height, config)
    override fun release(bitmap: Bitmap) = Unit
    override fun obtainByteArray(size: Int) = ByteArray(size)
    override fun release(bytes: ByteArray) = Unit
    override fun obtainIntArray(size: Int) = IntArray(size)
    override fun release(array: IntArray) = Unit
}

class GifInfo(val width: Int, val height: Int, val frames: Int)

private fun openDecoder(bytes: ByteArray): StandardGifDecoder {
    val decoder = StandardGifDecoder(PlainBitmaps)
    decoder.setDefaultBitmapConfig(Bitmap.Config.ARGB_8888)
    val status = decoder.read(bytes)
    if (status != GifDecoder.STATUS_OK || decoder.frameCount == 0) error("GIF inválido")
    return decoder
}

fun readGifInfo(resolver: ContentResolver, uri: Uri): GifInfo {
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo leer el GIF")
    val decoder = openDecoder(bytes)
    return GifInfo(decoder.width, decoder.height, decoder.frameCount)
}

fun saveGifTiles(resolver: ContentResolver, filesDir: File, uri: Uri, columns: Int, mode: CutMode, stamp: String, asVideo: Boolean): Batch {
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo leer el GIF")
    val decoder = openDecoder(bytes)
    val plan = planCut(decoder.width, decoder.height, columns, mode)
    val tiles = plan.tiles.sortedBy { it.order }
    val writers = tiles.map { GifWriter(ByteArrayOutputStream(), it.width, it.height) }
    repeat(decoder.frameCount) {
        decoder.advance()
        val delay = decoder.nextDelay
        val frame = decoder.nextFrame ?: return@repeat
        tiles.forEachIndexed { i, tile ->
            val pixels = IntArray(tile.width * tile.height)
            frame.getPixels(pixels, 0, tile.width, tile.left, tile.top, tile.width, tile.height)
            writers[i].frame(pixels, delay)
        }
    }
    val base = System.currentTimeMillis()
    if (asVideo) {
        val folder = File(filesDir, "cuts/$stamp").apply { mkdirs() }
        val uris = tiles.mapIndexed { i, tile ->
            val file = File(folder, "${tile.order.toString().padStart(2, '0')}.gif")
            file.writeBytes(writers[i].finish().toByteArray())
            Uri.fromFile(file)
        }
        val spacer = PublishGap()
        val videos = galleryOrder(tiles, plan.cols).associate { tile ->
            spacer.gap()
            val video = encodeTileVideo(resolver, decoder, tile, "gridcut_${stamp}_${tile.order.toString().padStart(2, '0')}.mp4", takenAt(base, tile, plan.cols))
            stampTaken(resolver, video, takenAt(base, tile, plan.cols))
            tile.order to video
        }.toSortedMap().values.toList()
        return Batch.of(stamp, mode, plan, gif = true, uris, videos)
    }
    val index = tiles.withIndex().associate { it.value to it.index }
    val spacer = PublishGap()
    val uris = galleryOrder(tiles, plan.cols).associate { tile ->
        spacer.gap()
        val data = writers[index.getValue(tile)].finish()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "gridcut_${stamp}_${tile.order.toString().padStart(2, '0')}.gif")
            put(MediaStore.Images.Media.MIME_TYPE, GIF_MIME)
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt(base, tile, plan.cols))
        }
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("No se pudo crear el archivo")
        resolver.openOutputStream(target)?.use { it.write(data.toByteArray()) } ?: error("No se pudo escribir el archivo")
        resolver.update(
            target,
            ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
                put(MediaStore.Images.Media.DATE_TAKEN, takenAt(base, tile, plan.cols))
            },
            null,
            null,
        )
        stampTaken(resolver, target, takenAt(base, tile, plan.cols))
        tile.order to target
    }.toSortedMap().values.toList()
    return Batch.of(stamp, mode, plan, gif = true, uris)
}

class GifWriter(private val out: ByteArrayOutputStream, private val width: Int, private val height: Int) {
    init {
        out.write("GIF89a".toByteArray())
        short(width)
        short(height)
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    fun frame(pixels: IntArray, delayMs: Int) {
        val palette = Palette.of(pixels)
        val centis = if (delayMs <= 10) 10 else (delayMs / 10.0).roundToInt()
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4))
        out.write(if (palette.transparent) (2 shl 2) or 1 else 1 shl 2)
        short(centis)
        out.write(0)
        out.write(0)

        out.write(0x2C)
        short(0)
        short(0)
        short(width)
        short(height)
        out.write(0x80 or (palette.bits - 1))
        val table = ByteArray(3 shl palette.bits)
        palette.colors.forEachIndexed { i, rgb ->
            table[i * 3] = (rgb shr 16).toByte()
            table[i * 3 + 1] = (rgb shr 8).toByte()
            table[i * 3 + 2] = rgb.toByte()
        }
        out.write(table)
        val minCode = maxOf(2, palette.bits)
        out.write(minCode)
        lzw(palette.indices, minCode, out)
        out.write(0)
    }

    fun finish(): ByteArrayOutputStream {
        out.write(0x3B)
        return out
    }

    private fun short(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }
}

class Palette(val colors: IntArray, val indices: ByteArray, val transparent: Boolean, val bits: Int) {
    companion object {
        fun of(pixels: IntArray): Palette {
            val transparent = pixels.any { (it ushr 24) < 128 }
            val first = if (transparent) 1 else 0
            val limit = 256 - first
            val map = HashMap<Int, Int>()
            for (p in pixels) {
                if ((p ushr 24) < 128) continue
                val rgb = p and 0xFFFFFF
                if (rgb !in map) {
                    if (map.size == limit) return medianCut(pixels, transparent)
                    map[rgb] = first + map.size
                }
            }
            val colors = IntArray(first + map.size)
            for ((rgb, i) in map) colors[i] = rgb
            val indices = ByteArray(pixels.size) { i ->
                val p = pixels[i]
                if ((p ushr 24) < 128) 0 else map.getValue(p and 0xFFFFFF).toByte()
            }
            return Palette(colors, indices, transparent, bitsFor(colors.size))
        }

        private fun medianCut(pixels: IntArray, transparent: Boolean): Palette {
            val first = if (transparent) 1 else 0
            val counts = HashMap<Int, Int>()
            for (p in pixels) if ((p ushr 24) >= 128) counts.merge(p and 0xFFFFFF, 1, Int::plus)
            val boxes = mutableListOf(counts.keys.toIntArray())
            while (boxes.size < 256 - first) {
                var best = -1
                var bestScore = 0L
                var bestShift = 0
                boxes.forEachIndexed { i, box ->
                    if (box.size < 2) return@forEachIndexed
                    for (shift in intArrayOf(16, 8, 0)) {
                        var lo = 255
                        var hi = 0
                        for (c in box) {
                            val v = (c shr shift) and 0xFF
                            if (v < lo) lo = v
                            if (v > hi) hi = v
                        }
                        val score = (hi - lo).toLong() * box.size
                        if (score > bestScore) {
                            bestScore = score
                            best = i
                            bestShift = shift
                        }
                    }
                }
                if (best < 0) break
                val shift = bestShift
                val sorted = boxes[best].sortedBy { (it shr shift) and 0xFF }
                val total = sorted.sumOf { counts.getValue(it).toLong() }
                var acc = 0L
                var cut = 1
                for (i in sorted.indices) {
                    acc += counts.getValue(sorted[i])
                    if (acc * 2 >= total) {
                        cut = (i + 1).coerceIn(1, sorted.size - 1)
                        break
                    }
                }
                boxes[best] = sorted.subList(0, cut).toIntArray()
                boxes.add(sorted.subList(cut, sorted.size).toIntArray())
            }
            val colors = IntArray(first + boxes.size)
            val lookup = HashMap<Int, Int>(counts.size)
            boxes.forEachIndexed { i, box ->
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0L
                for (c in box) {
                    val w = counts.getValue(c).toLong()
                    r += ((c shr 16) and 0xFF) * w
                    g += ((c shr 8) and 0xFF) * w
                    b += (c and 0xFF) * w
                    n += w
                    lookup[c] = first + i
                }
                colors[first + i] = ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
            val indices = ByteArray(pixels.size) { i ->
                val p = pixels[i]
                if ((p ushr 24) < 128) 0 else lookup.getValue(p and 0xFFFFFF).toByte()
            }
            return Palette(colors, indices, transparent, 8)
        }

        private fun bitsFor(count: Int): Int {
            var bits = 1
            while ((1 shl bits) < count) bits++
            return bits
        }
    }
}

private fun lzw(indices: ByteArray, minCode: Int, out: OutputStream) {
    val clear = 1 shl minCode
    val end = clear + 1
    var next = end + 1
    var size = minCode + 1
    val table = HashMap<Int, Int>()
    val block = ByteArray(255)
    var blockLen = 0
    var acc = 0
    var accBits = 0

    fun emit(code: Int) {
        acc = acc or (code shl accBits)
        accBits += size
        while (accBits >= 8) {
            block[blockLen++] = acc.toByte()
            acc = acc ushr 8
            accBits -= 8
            if (blockLen == 255) {
                out.write(255)
                out.write(block, 0, 255)
                blockLen = 0
            }
        }
    }

    emit(clear)
    var prefix = indices[0].toInt() and 0xFF
    for (i in 1 until indices.size) {
        val k = indices[i].toInt() and 0xFF
        val key = (prefix shl 8) or k
        val code = table[key]
        if (code != null) {
            prefix = code
            continue
        }
        emit(prefix)
        if (next == 4096) {
            emit(clear)
            next = end + 1
            size = minCode + 1
            table.clear()
        } else {
            if (next >= (1 shl size)) size++
            table[key] = next++
        }
        prefix = k
    }
    emit(prefix)
    emit(end)
    if (accBits > 0) {
        block[blockLen++] = acc.toByte()
        if (blockLen == 255) {
            out.write(255)
            out.write(block, 0, 255)
            blockLen = 0
        }
    }
    if (blockLen > 0) {
        out.write(blockLen)
        out.write(block, 0, blockLen)
    }
}
