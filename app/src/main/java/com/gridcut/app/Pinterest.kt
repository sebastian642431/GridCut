package com.gridcut.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
private val LINK = Regex("""https?://(?:[a-z]{2,3}\.)?(?:pin\.it|(?:[a-z]{2,3}\.)?pinterest\.[a-z.]+)/\S+""", RegexOption.IGNORE_CASE)
private val VIDEO = Regex("""https://v\d*\.pinimg\.com/videos/[^"'\s\\]+?\.(?:mp4|m3u8)""")
private val IMAGE = Regex("""https://i\.pinimg\.com/originals/[^"'\s\\]+?\.(?:jpg|jpeg|png|gif|webp)""")
private val WIDTH = Regex("""_(\d+)w(?:_t\d)?\.mp4$""")

fun findPinLink(text: String?): String? = text?.let { LINK.find(it)?.value?.trimEnd('.', ',', ')') }

private fun open(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", AGENT)
        setRequestProperty("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
    }

private fun reachable(url: String): Boolean = runCatching {
    val connection = open(url)
    connection.requestMethod = "HEAD"
    val ok = connection.responseCode in 200..299
    connection.disconnect()
    ok
}.getOrDefault(false)

private fun rank(url: String): Int {
    val family = when {
        "/expMp4/" in url -> 3
        "hevc" in url.lowercase() || "h265" in url -> 2
        else -> 1
    }
    val width = WIDTH.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return family * 10_000 + width
}

private fun pickVideo(html: String): String? {
    val found = VIDEO.findAll(html).map { it.value }.toSet()
    found.filter { it.endsWith(".mp4") }.sortedByDescending(::rank).firstOrNull { reachable(it) }?.let { return it }
    return found.filter { it.endsWith(".m3u8") }.firstNotNullOfOrNull { hls ->
        val derived = hls.replace(Regex("""/videos/iht/[^/]+/"""), "/videos/iht/expMp4/").replace(".m3u8", "_720w.mp4")
        derived.takeIf { reachable(it) }
    }
}

class PinMedia(val uri: Uri, val video: Boolean)

suspend fun downloadPin(context: Context, link: String, onProgress: (Float) -> Unit): PinMedia = withContext(Dispatchers.IO) {
    val page = open(link)
    val html = try {
        if (page.responseCode !in 200..299) error("Pinterest respondió ${page.responseCode}")
        page.inputStream.bufferedReader().readText()
    } finally {
        page.disconnect()
    }.replace("\\/", "/").replace("\\u002F", "/")

    val video = pickVideo(html)
    val media = video ?: IMAGE.findAll(html).map { it.value }.firstOrNull() ?: error("No encontré video ni imagen en ese pin")
    val isVideo = video != null
    val extension = media.substringAfterLast('.').lowercase()
    val mime = when {
        isVideo -> "video/mp4"
        extension == "png" -> "image/png"
        extension == "gif" -> "image/gif"
        extension == "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    val name = "pinterest_${System.currentTimeMillis()}.${if (isVideo) "mp4" else extension}"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            "${if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/Pinterest",
        )
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val resolver = context.contentResolver
    val target = resolver.insert(collection, values) ?: error("No se pudo crear el archivo")
    try {
        val connection = open(media)
        try {
            if (connection.responseCode !in 200..299) error("No se pudo bajar (${connection.responseCode})")
            val total = connection.contentLengthLong
            resolver.openOutputStream(target)!!.use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Throwable) {
        resolver.delete(target, null, null)
        throw e
    }
    resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    PinMedia(target, isVideo)
}
