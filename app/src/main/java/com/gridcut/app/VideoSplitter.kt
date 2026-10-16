package com.gridcut.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

fun isVideo(resolver: ContentResolver, uri: Uri): Boolean = resolver.getType(uri)?.startsWith("video/") == true

fun loadVideo(context: Context, uri: Uri, previewSide: Int): Pair<Loaded, VideoInfo> {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
        val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
        val (w, h) = if (rotation % 180 == 0) rawW to rawH else rawH to rawW
        if (w <= 0 || h <= 0) error("Video inválido")
        val scale = minOf(1.0, previewSide.toDouble() / max(w, h))
        val frame: Bitmap = retriever.getScaledFrameAtTime(
            0,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            max(1, (w * scale).roundToInt()),
            max(1, (h * scale).roundToInt()),
        ) ?: error("No se pudo leer el video")
        return Loaded(frame, w, h) to VideoInfo(w, h, duration)
    } finally {
        retriever.release()
    }
}

private fun ndc(value: Int, size: Int) = -1f + 2f * value / size

private suspend fun exportTile(context: Context, uri: Uri, info: VideoInfo, tile: Tile, output: File, onProgress: (Float) -> Unit) =
    withContext(Dispatchers.Main) {
        val crop = Crop(
            ndc(tile.left, info.width),
            ndc(tile.right, info.width),
            -ndc(tile.bottom, info.height),
            -ndc(tile.top, info.height),
        )
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), listOf(crop)))
            .build()
        val bitrate = max(10_000_000, tile.width * tile.height * 30 / 2)
        suspendCancellableCoroutine { cont ->
            var poll: Job? = null
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        poll?.cancel()
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        poll?.cancel()
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(item, output.absolutePath)
            val holder = ProgressHolder()
            poll = launch {
                while (true) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    delay(250)
                }
            }
            cont.invokeOnCancellation {
                poll?.cancel()
                transformer.cancel()
            }
        }
    }

fun patchMp4Created(file: File, takenAt: Long) {
    val seconds = takenAt / 1000 + 2082844800L
    RandomAccessFile(file, "rw").use { raf ->
        fun find(start: Long, end: Long, type: String): Long {
            var at = start
            while (at + 8 <= end) {
                raf.seek(at)
                var size = raf.readInt().toLong() and 0xFFFFFFFFL
                val name = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
                var header = 8L
                if (size == 1L) {
                    size = raf.readLong()
                    header = 16L
                } else if (size == 0L) {
                    size = end - at
                }
                if (name == type) return at + header
                if (size < header) return -1
                at += size
            }
            return -1
        }
        val moov = find(0, raf.length(), "moov")
        if (moov < 0) return
        raf.seek(moov - 8)
        val moovSize = raf.readInt().toLong() and 0xFFFFFFFFL
        val mvhd = find(moov, moov - 8 + moovSize, "mvhd")
        if (mvhd < 0) return
        raf.seek(mvhd)
        val version = raf.readUnsignedByte()
        raf.seek(mvhd + 4)
        if (version == 1) {
            raf.writeLong(seconds)
            raf.writeLong(seconds)
        } else {
            raf.writeInt(seconds.toInt())
            raf.writeInt(seconds.toInt())
        }
    }
}

suspend fun saveVideoTiles(
    context: Context,
    uri: Uri,
    info: VideoInfo,
    columns: Int,
    mode: CutMode,
    stamp: String,
    onProgress: (done: Int, total: Int, fraction: Float) -> Unit,
): Batch {
    val resolver = context.contentResolver
    val plan = planCut(info.width, info.height, columns, mode)
    val base = System.currentTimeMillis()
    val ordered = galleryOrder(plan.tiles, plan.cols)
    val written = sortedMapOf<Int, Uri>()
    ordered.forEachIndexed { i, tile ->
        val temp = File(context.cacheDir, "tile_${tile.order}.mp4")
        temp.delete()
        exportTile(context, uri, info, tile, temp) { onProgress(i, ordered.size, it) }
        withContext(Dispatchers.IO) {
            val taken = takenAt(base, tile, plan.cols)
            runCatching { patchMp4Created(temp, taken) }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "gridcut_${stamp}_${tile.order.toString().padStart(2, '0')}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, ALBUM_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
                put(MediaStore.Video.Media.DATE_TAKEN, taken)
            }
            val target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("No se pudo crear el video")
            resolver.openOutputStream(target)?.use { out -> temp.inputStream().use { it.copyTo(out) } } ?: error("No se pudo escribir el video")
            resolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            stampTaken(resolver, target, taken)
            temp.delete()
            written[tile.order] = target
        }
        onProgress(i + 1, ordered.size, 0f)
    }
    val uris = written.values.toList()
    return Batch.of(stamp, mode, plan, gif = false, uris, uris).copy(video = true)
}
