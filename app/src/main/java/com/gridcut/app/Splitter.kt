package com.gridcut.app

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class CutMode {
    Grid,
    Fill,
    Carousel;

    val grid get() = this != Carousel
}

data class Tile(val row: Int, val col: Int, val left: Int, val top: Int, val right: Int, val bottom: Int, val order: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

data class CutPlan(
    val rows: Int,
    val cols: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val tiles: List<Tile>,
)

const val MAX_COLUMNS = 10
private const val PREVIEW_SIDE = 2048
const val ALBUM = "GridCut"
val ALBUM_PATH = "${Environment.DIRECTORY_DCIM}/$ALBUM"

fun planCut(width: Int, height: Int, columns: Int, mode: CutMode): CutPlan {
    val cols = columns.coerceIn(1, MAX_COLUMNS)
    val cellW: Double
    val cellH: Double
    val rows: Int
    when (mode) {
        CutMode.Grid -> {
            cellW = min(width, height).toDouble() / cols
            cellH = cellW
            rows = cols
        }
        CutMode.Fill -> {
            cellW = width.toDouble() / cols
            cellH = height.toDouble() / cols
            rows = cols
        }
        CutMode.Carousel -> {
            cellW = width.toDouble() / cols
            cellH = height.toDouble()
            rows = 1
        }
    }
    val offsetX = (width - cellW * cols) / 2
    val offsetY = (height - cellH * rows) / 2
    val total = rows * cols
    val tiles = buildList {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val index = r * cols + c
                add(
                    Tile(
                        row = r,
                        col = c,
                        left = (offsetX + c * cellW).roundToInt(),
                        top = (offsetY + r * cellH).roundToInt(),
                        right = (offsetX + (c + 1) * cellW).roundToInt().coerceAtMost(width),
                        bottom = (offsetY + (r + 1) * cellH).roundToInt().coerceAtMost(height),
                        order = if (mode.grid) total - index else index + 1,
                    ),
                )
            }
        }
    }
    return CutPlan(
        rows = rows,
        cols = cols,
        left = offsetX.roundToInt(),
        top = offsetY.roundToInt(),
        right = (offsetX + cellW * cols).roundToInt(),
        bottom = (offsetY + cellH * rows).roundToInt(),
        tiles = tiles,
    )
}

fun takenAt(base: Long, tile: Tile, cols: Int): Long = base - (tile.row * cols + tile.col) * 1000L

fun galleryOrder(tiles: List<Tile>, cols: Int): List<Tile> = tiles.sortedByDescending { it.row * cols + it.col }

class PublishGap {
    private var last = 0L

    fun gap() {
        val wait = last + 1100 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
    }
}

fun writeExifDate(file: File, takenAt: Long) {
    val date = Date(takenAt)
    val exif = ExifInterface(file)
    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date))
    exif.setAttribute(ExifInterface.TAG_DATETIME, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date))
    exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, SimpleDateFormat("XXX", Locale.US).format(date))
    exif.saveAttributes()
}

fun stampTaken(resolver: ContentResolver, uri: Uri, takenAt: Long) {
    runCatching {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, takenAt) }, null, null)
    }
}

class Loaded(val preview: Bitmap, val width: Int, val height: Int)

fun loadPreview(resolver: ContentResolver, uri: Uri): Loaded {
    var width = 0
    var height = 0
    val preview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        width = info.size.width
        height = info.size.height
        val side = max(width, height)
        if (side > PREVIEW_SIDE) {
            val scale = PREVIEW_SIDE.toDouble() / side
            decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
        }
    }
    return Loaded(preview, width, height)
}

private fun decodeFull(resolver: ContentResolver, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

fun saveTiles(resolver: ContentResolver, cacheDir: File, uri: Uri, columns: Int, mode: CutMode, stamp: String, lossless: Boolean): Batch {
    val ext = if (lossless) "png" else "jpg"
    val image = try {
        decodeFull(resolver, uri)
    } catch (_: OutOfMemoryError) {
        error("La imagen es demasiado grande para la memoria del celular")
    }
    val plan = planCut(image.width, image.height, columns, mode)
    val base = System.currentTimeMillis()
    val written = galleryOrder(plan.tiles, plan.cols).associate { tile ->
        tile.order to run {
        val piece = Bitmap.createBitmap(image, tile.left, tile.top, tile.width, tile.height)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "gridcut_${stamp}_${tile.order.toString().padStart(2, '0')}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, if (lossless) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt(base, tile, plan.cols))
        }
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("No se pudo crear el archivo")
        val temp = File(cacheDir, "piece.$ext")
        temp.outputStream().use { piece.compress(if (lossless) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (lossless) 100 else 92, it) }
        if (piece !== image) piece.recycle()
        runCatching { writeExifDate(temp, takenAt(base, tile, plan.cols)) }
        resolver.openOutputStream(target)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
            ?: error("No se pudo escribir el archivo")
        temp.delete()
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
        target
        }
    }
    val uris = written.toSortedMap().values.toList()
    image.recycle()
    return Batch.of(stamp, mode, plan, gif = false, uris)
}
