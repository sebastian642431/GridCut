package com.gridcut.app

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private fun decodeDrawable(context: Context, uri: Uri, maxSide: Int): Drawable? = runCatching {
    if (uri.toString().contains("/video/")) {
        val thumb = context.contentResolver.loadThumbnail(uri, android.util.Size(maxSide, maxSide), null)
        return@runCatching android.graphics.drawable.BitmapDrawable(context.resources, thumb)
    }
    ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val side = max(info.size.width, info.size.height)
        if (side > maxSide) {
            val scale = maxSide.toDouble() / side
            decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
        }
    }
}.getOrNull()

@Composable
fun MediaImage(uri: Uri, maxSide: Int, modifier: Modifier = Modifier, fit: Boolean = false) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, uri, maxSide) {
        value = withContext(Dispatchers.IO) { decodeDrawable(context, uri, maxSide) }
    }
    DisposableEffect(drawable) {
        val animated = drawable as? AnimatedImageDrawable
        animated?.start()
        onDispose { animated?.stop() }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = if (fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP } },
        update = { it.setImageDrawable(drawable) },
        modifier = modifier.background(if (fit) Color.Transparent else Surface),
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = androidx.compose.runtime.remember(uri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = {
            androidx.media3.ui.PlayerView(it).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        },
        modifier = modifier,
    )
}

@Composable
fun Mosaic(batch: Batch, gap: Dp, maxSide: Int, modifier: Modifier = Modifier, marks: Boolean = true, onTile: ((Int) -> Unit)? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        for (r in 0 until batch.rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (c in 0 until batch.cols) {
                    val order = batch.orderAt(r, c)
                    val uri = batch.uriAt(r, c)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(batch.ratio)
                            .background(Surface)
                            .then(if (onTile != null) Modifier.clickable { onTile(order - 1) } else Modifier),
                    ) {
                        val posted = marks && order in batch.posted
                        if (uri != null) MediaImage(uri, maxSide, Modifier.fillMaxSize().alpha(if (posted) 0.35f else 1f))
                        if (posted) PostedMark(Modifier.align(Alignment.Center), small = maxSide < 400)
                    }
                }
            }
        }
    }
}

@Composable
private fun PostedMark(modifier: Modifier, small: Boolean = false) {
    val size = if (small) 14.dp else 28.dp
    Icon(
        Icons.Default.Check,
        "Subida",
        tint = Color.White,
        modifier = modifier.size(size).clip(CircleShape).background(Ok).padding(if (small) 2.dp else 4.dp),
    )
}

private enum class Filter { All, Pending, Done }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(batches: List<Batch>, onOpen: (Batch) -> Unit, onDelete: (Set<String>) -> Unit, onStart: () -> Unit) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    val shown = when (filter) {
        Filter.All -> batches
        Filter.Pending -> batches.filter { !it.done }
        Filter.Done -> batches.filter { it.done }
    }

    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = if (selecting) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Cancelar", tint = Text1) }
                Text("${selected.size} seleccionados", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (selected.size == shown.size) emptySet() else shown.map { it.stamp }.toSet() }) {
                    Text(if (selected.size == shown.size) "Ninguno" else "Todos", color = Accent, fontSize = 13.sp)
                }
                IconButton(onClick = { share(context, batches.filter { it.stamp in selected }.flatMap { it.shareUris }) }) {
                    Icon(Icons.Default.Share, "Compartir", tint = Text1)
                }
                IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Borrar", tint = Pink) }
            } else {
                Text("Mis cortes", color = Text1, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        }

        if (batches.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Todavía no cortaste nada", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("Lo que cortes aparece acá para verlo y subirlo en orden.", color = Text2, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                SmallButton("Cortar una imagen", onClick = onStart, filled = true)
            }
            return
        }

        Segmented(
            filter,
            listOf(Filter.All to "Todos", Filter.Pending to "Por subir", Filter.Done to "Subidos"),
            onChange = { filter = it; selected = emptySet() },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (filter == Filter.Done) "Ninguno subido del todo todavía" else "No queda nada por subir", color = Text2, fontSize = 13.sp)
            }
            return
        }

        if (!selecting) {
            Text("Mantené apretado para seleccionar varios", color = Text2, fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { it.stamp }) { batch ->
                val isSelected = batch.stamp in selected
                val shape = RoundedCornerShape(12.dp)
                Column(
                    Modifier
                        .clip(shape)
                        .background(Surface)
                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) Accent else Line, shape)
                        .combinedClickable(
                            onClick = {
                                if (selecting) selected = if (isSelected) selected - batch.stamp else selected + batch.stamp
                                else onOpen(batch)
                            },
                            onLongClick = { selected = selected + batch.stamp },
                        ),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp), contentAlignment = Alignment.Center) {
                        val wide = batch.cols * batch.ratio >= batch.rows
                        Mosaic(
                            batch,
                            gap = 1.dp,
                            maxSide = 240,
                            modifier = if (wide) Modifier.fillMaxWidth() else Modifier.fillMaxHeight().aspectRatio(batch.cols * batch.ratio / batch.rows),
                        )
                        if (selecting) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Accent else Color.Black.copy(alpha = 0.5f))
                                    .border(1.5.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(batch.shape(), color = Text1, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (batch.done) "Subido · ${batch.label()}" else "${batch.posted.size}/${batch.count} subidas · ${batch.label()}",
                            color = if (batch.done) Ok else Text2,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    if (confirm) {
        val pieces = batches.filter { it.stamp in selected }.sumOf { it.count }
        ConfirmDelete(
            text = "Se borran ${selected.size} cortes ($pieces piezas) de la galería.",
            onConfirm = { onDelete(selected); selected = emptySet(); confirm = false },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
fun BatchScreen(batch: Batch, onBack: () -> Unit, onDelete: () -> Unit, onView: (Int) -> Unit, onPosted: (Set<Int>) -> Unit) {
    val context = LocalContext.current
    var ordered by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val next = (1..batch.count).firstOrNull { it !in batch.posted }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Text1) }
            Column(Modifier.weight(1f)) {
                Text(batch.shape(), color = Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(batch.label(), color = Text2, fontSize = 12.sp)
            }
            IconButton(onClick = { share(context, batch.shareUris) }) { Icon(Icons.Default.Share, "Compartir todo", tint = Text1) }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Borrar", tint = Text2) }
        }

        Progress(batch, next, onNext = { next?.let { onView(it - 1) } }, onPosted = onPosted)

        Segmented(
            ordered,
            listOf(false to "Perfil", true to "Por orden"),
            onChange = { ordered = it },
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
        )

        if (ordered) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(batch.uris) { index, uri ->
                    val posted = (index + 1) in batch.posted
                    Box(
                        Modifier
                            .aspectRatio(batch.ratio.coerceIn(0.5f, 1.5f))
                            .clickable { onView(index) },
                    ) {
                        MediaImage(uri, 400, Modifier.fillMaxSize().alpha(if (posted) 0.35f else 1f))
                        Badge(index + 1, Modifier.align(Alignment.TopStart).padding(6.dp), color = if (posted) Ok else Accent)
                        if (posted) PostedMark(Modifier.align(Alignment.Center))
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Mosaic(batch, gap = 2.dp, maxSide = 720, modifier = Modifier.fillMaxWidth(), onTile = onView)
                Text(if (batch.posted.isEmpty()) "Así se ve en el perfil cuando subís todas. Tocá una para verla sola." else "Las apagadas con tilde ya están subidas. Tocá una para verla sola.", color = Text2, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            }
        }
    }

    if (confirm) {
        ConfirmDelete(
            text = "Se borran las ${batch.count} piezas de la galería.",
            onConfirm = { confirm = false; onDelete() },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun Progress(batch: Batch, next: Int?, onNext: () -> Unit, onPosted: (Set<Int>) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Surface)
            .border(1.dp, Line, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (next == null) "Todas subidas" else "Subidas ${batch.posted.size} de ${batch.count}",
                color = if (next == null) Ok else Text1,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Line)) {
                Box(
                    Modifier
                        .fillMaxWidth(batch.posted.size.toFloat() / batch.count)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (next == null) Ok else Accent),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (next != null) Action("Marcar todas", Ok) { onPosted((1..batch.count).toSet()) }
                if (batch.posted.isNotEmpty()) Action("Desmarcar todas", Text2) { onPosted(emptySet()) }
            }
        }
        if (next != null) {
            Spacer(Modifier.width(12.dp))
            SmallButton(if (batch.posted.isEmpty()) "Empezar a subir" else "Subir la $next", onClick = onNext, filled = true)
        }
    }
}

@Composable
private fun Action(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

@Composable
fun Viewer(batch: Batch, start: Int, onClose: () -> Unit, onTogglePosted: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, batch.count - 1)) { batch.count }
    val order = pager.currentPage + 1
    val posted = order in batch.posted
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
            val done = (page + 1) in batch.posted
            Box(Modifier.fillMaxSize()) {
                if (batch.video) {
                    VideoPlayer(batch.uris[page], Modifier.fillMaxSize().alpha(if (done) 0.45f else 1f))
                } else {
                    MediaImage(batch.uris[page], 2048, Modifier.fillMaxSize().alpha(if (done) 0.45f else 1f), fit = true)
                }
                if (done) {
                    Row(
                        Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(50))
                            .background(Ok)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ya subida", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cerrar", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Text("$order / ${batch.count}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { share(context, listOf(batch.shareUris[pager.currentPage])) }) {
                Icon(Icons.Default.Share, "Compartir", tint = Color.White)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (batch.mode.grid) "Pieza $order · subila en ese orden" else "Slide $order",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    onTogglePosted(order)
                    if (!posted && pager.currentPage < batch.count - 1) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (posted) Ok else Accent),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                if (posted) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (posted) "Subida · tocá para desmarcar" else "Ya la subí", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ConfirmDelete(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Borrar?") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Borrar", color = Pink) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Text1) } },
        containerColor = Surface,
        titleContentColor = Text1,
        textContentColor = Text2,
    )
}

fun share(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    val videos = uris.count { it.toString().contains("/video/") }
    send.type = when (videos) {
        0 -> "image/*"
        uris.size -> "video/*"
        else -> "*/*"
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Compartir"))
}
