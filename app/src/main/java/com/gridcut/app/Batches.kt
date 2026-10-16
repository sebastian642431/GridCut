package com.gridcut.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

data class Batch(
    val stamp: String,
    val mode: CutMode,
    val cols: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val gif: Boolean,
    val uris: List<Uri>,
    val posted: Set<Int> = emptySet(),
    val videos: List<Uri> = emptyList(),
    val video: Boolean = false,
) {
    val shareUris get() = videos.ifEmpty { uris }
    val done get() = posted.size >= uris.size
    val count get() = uris.size
    val ratio get() = tileWidth.toFloat() / tileHeight

    fun orderAt(row: Int, col: Int): Int =
        if (mode.grid) rows * cols - (row * cols + col) else col + 1

    fun uriAt(row: Int, col: Int): Uri? = uris.getOrNull(orderAt(row, col) - 1)

    fun label(): String {
        val date = runCatching {
            val parsed = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(stamp)!!
            SimpleDateFormat("dd/MM · HH:mm", Locale.US).format(parsed)
        }.getOrDefault(stamp)
        return date
    }

    fun shape(): String {
        val kind = when (mode) {
            CutMode.Grid -> "Grilla $cols × $rows"
            CutMode.Fill -> "Grilla completa $cols × $rows"
            CutMode.Carousel -> "Carrusel de $cols"
        }
        return when {
            video -> "$kind · Video"
            gif -> "$kind · GIF"
            else -> kind
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("stamp", stamp)
        .put("mode", mode.name)
        .put("cols", cols)
        .put("rows", rows)
        .put("tileWidth", tileWidth)
        .put("tileHeight", tileHeight)
        .put("gif", gif)
        .put("uris", JSONArray(uris.map { it.toString() }))
        .put("posted", JSONArray(posted.sorted()))
        .put("videos", JSONArray(videos.map { it.toString() }))
        .put("video", video)

    companion object {
        fun of(stamp: String, mode: CutMode, plan: CutPlan, gif: Boolean, uris: List<Uri>, videos: List<Uri> = emptyList()): Batch {
            val tile = plan.tiles.first()
            return Batch(stamp, mode, plan.cols, plan.rows, tile.width, tile.height, gif, uris, videos = videos)
        }

        fun fromJson(json: JSONObject): Batch {
            val list = json.getJSONArray("uris")
            return Batch(
                stamp = json.getString("stamp"),
                mode = CutMode.valueOf(json.getString("mode")),
                cols = json.getInt("cols"),
                rows = json.getInt("rows"),
                tileWidth = json.getInt("tileWidth"),
                tileHeight = json.getInt("tileHeight"),
                gif = json.getBoolean("gif"),
                uris = List(list.length()) { Uri.parse(list.getString(it)) },
                video = json.optBoolean("video", false),
                videos = json.optJSONArray("videos")?.let { v -> List(v.length()) { Uri.parse(v.getString(it)) } } ?: emptyList(),
                posted = json.optJSONArray("posted")?.let { p -> List(p.length()) { p.getInt(it) }.toSet() } ?: emptySet(),
            )
        }
    }
}

class BatchStore(context: Context) {
    private val file = File(context.filesDir, "batches.json")

    fun load(): List<Batch> = runCatching {
        val array = JSONArray(file.readText())
        List(array.length()) { Batch.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun add(batch: Batch): List<Batch> = save(listOf(batch) + load().filter { it.stamp != batch.stamp })

    fun update(batch: Batch): List<Batch> = save(load().map { if (it.stamp == batch.stamp) batch else it })

    fun remove(resolver: ContentResolver, stamps: Set<String>): List<Batch> {
        val all = load()
        all.filter { it.stamp in stamps }.forEach { b ->
            (b.uris + b.videos).distinct().forEach { uri ->
                runCatching { if (uri.scheme == "file") File(uri.path!!).delete() else resolver.delete(uri, null, null) }
            }
            b.uris.firstOrNull { it.scheme == "file" }?.path?.let { File(it).parentFile?.delete() }
        }
        return save(all.filter { it.stamp !in stamps })
    }

    fun prune(resolver: ContentResolver): List<Batch> {
        val all = load()
        val alive = all.filter { batch -> batch.shareUris.any { exists(resolver, it) } }
        return if (alive.size == all.size) all else save(alive)
    }

    private fun exists(resolver: ContentResolver, uri: Uri): Boolean =
        if (uri.scheme == "file") File(uri.path!!).exists() else runCatching { resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false }
            .getOrDefault(false)

    private fun save(batches: List<Batch>): List<Batch> {
        file.writeText(JSONArray(batches.map { it.toJson() }).toString())
        return batches
    }
}
