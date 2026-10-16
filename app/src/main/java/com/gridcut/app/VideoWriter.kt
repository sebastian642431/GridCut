package com.gridcut.app

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
private const val MIN_SIDE = 720
private const val MAX_SIDE = 1920
private const val MIN_DURATION_MS = 3000
private const val TIMEOUT_US = 10_000L

private fun even(value: Int) = max(2, value - value % 2)

fun videoSize(width: Int, height: Int): Pair<Int, Int> {
    val up = max(1.0, MIN_SIDE.toDouble() / min(width, height))
    val down = min(1.0, MAX_SIDE / (max(width, height) * up))
    val scale = up * down
    var w = even((width * scale).roundToInt())
    var h = even((height * scale).roundToInt())
    val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } }
        ?.getCapabilitiesForType(MIME)?.videoCapabilities
    if (caps != null && !caps.isSizeSupported(w, h)) {
        w = (w + 15) / 16 * 16
        h = (h + 15) / 16 * 16
    }
    return w to h
}

fun encodeTileVideo(
    resolver: ContentResolver,
    decoder: StandardGifDecoder,
    tile: Tile,
    name: String,
    takenAt: Long,
): Uri {
    val (w, h) = videoSize(tile.width, tile.height)
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, ALBUM_PATH)
        put(MediaStore.Video.Media.IS_PENDING, 1)
        put(MediaStore.Video.Media.DATE_TAKEN, takenAt)
    }
    val target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("No se pudo crear el video")
    try {
        resolver.openFileDescriptor(target, "rw")!!.use { fd ->
            val muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, max(4_000_000, w * h * 10))
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var track = -1
            var started = false

            fun drain(endOfStream: Boolean) {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && started) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val src = Rect(tile.left, tile.top, tile.right, tile.bottom)
            val dst = Rect(0, 0, w, h)
            val pixels = IntArray(w * h)
            var ptsUs = 0L
            var loops = 0
            while (loops == 0 || (ptsUs < MIN_DURATION_MS * 1000L && loops < 50)) {
                decoder.resetFrameIndex()
                repeat(decoder.frameCount) {
                    decoder.advance()
                    val delay = decoder.nextDelay.let { if (it <= 10) 100 else it }
                    val source = decoder.nextFrame ?: return@repeat
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(source, src, dst, paint)
                    frame.getPixels(pixels, 0, w, 0, 0, w, h)
                    var input: Int
                    do {
                        input = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (input < 0) drain(false)
                    } while (input < 0)
                    writeYuv(codec.getInputImage(input)!!, pixels, w, h)
                    codec.queueInputBuffer(input, 0, w * h * 3 / 2, ptsUs, 0)
                    ptsUs += delay * 1000L
                    drain(false)
                }
                loops++
            }
            var input: Int
            do {
                input = codec.dequeueInputBuffer(TIMEOUT_US)
                if (input < 0) drain(false)
            } while (input < 0)
            codec.queueInputBuffer(input, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            codec.stop()
            codec.release()
            if (started) muxer.stop()
            muxer.release()
            frame.recycle()
        }
    } catch (e: Throwable) {
        resolver.delete(target, null, null)
        throw e
    }
    resolver.update(
        target,
        ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
            put(MediaStore.Video.Media.DATE_TAKEN, takenAt)
        },
        null,
        null,
    )
    return target
}

private fun writeYuv(image: android.media.Image, argb: IntArray, w: Int, h: Int) {
    val (yPlane, uPlane, vPlane) = image.planes
    val y = yPlane.buffer
    val u = uPlane.buffer
    val v = vPlane.buffer
    val yRow = yPlane.rowStride
    val yPix = yPlane.pixelStride
    val cRow = uPlane.rowStride
    val cPix = uPlane.pixelStride
    for (row in 0 until h) {
        for (col in 0 until w) {
            val p = argb[row * w + col]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            y.put(row * yRow + col * yPix, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
            if (row % 2 == 0 && col % 2 == 0) {
                val at = (row / 2) * cRow + (col / 2) * cPix
                u.put(at, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                v.put(at, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
            }
        }
    }
}
