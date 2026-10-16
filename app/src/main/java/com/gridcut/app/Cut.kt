package com.gridcut.app

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CutScreen(
    incoming: Uri?,
    incomingLink: String?,
    onLinkConsumed: () -> Unit,
    prefs: Prefs,
    onPrefs: (Prefs) -> Unit,
    onSaved: (Batch) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf<Uri?>(null) }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var gif by remember { mutableStateOf<GifInfo?>(null) }
    var video by remember { mutableStateOf<VideoInfo?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val columns = prefs.columns
    val mode = prefs.mode

    LaunchedEffect(incoming) { if (incoming != null) source = incoming }
    var pin by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(incomingLink) {
        if (incomingLink != null) {
            pin = incomingLink
            onLinkConsumed()
        }
    }
    val openPin = {
        val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        pin = findPinLink(clip) ?: ""
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) source = uri
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    LaunchedEffect(source) {
        val uri = source ?: return@LaunchedEffect
        error = null
        gif = null
        video = null
        loaded = null
        loaded = runCatching {
            withContext(Dispatchers.IO) {
                when {
                    isVideo(context.contentResolver, uri) -> loadVideo(context, uri, 2048).let { (preview, info) ->
                        video = info
                        preview
                    }
                    isGif(context.contentResolver, uri) -> {
                        gif = readGifInfo(context.contentResolver, uri)
                        loadPreview(context.contentResolver, uri)
                    }
                    else -> loadPreview(context.contentResolver, uri)
                }
            }
        }.onFailure { error = "No se pudo abrir ese archivo" }.getOrNull()
    }

    val image = loaded
    val plan = remember(image, columns, mode) { image?.let { planCut(it.width, it.height, columns, mode) } }

    pin?.let { initial ->
        PinDialog(initial, onDone = { source = it; pin = null }, onDismiss = { pin = null })
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("GridCut", color = Text1, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SmallButton("Pinterest", onClick = openPin)
            if (image != null) {
                Spacer(Modifier.size(8.dp))
                SmallButton("Cambiar", onClick = pick, filled = true)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (image == null || plan == null) {
                EmptyPick(loading = source != null && error == null, onPick = pick, onPinterest = openPin)
            } else {
                Preview(image, plan, numbers = prefs.numbers)
                Facts(plan, gif, video)
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Columnas", color = Text1, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Stepper(columns, 1, MAX_COLUMNS) { onPrefs(prefs.copy(columns = it)) }
                }
                Segmented(
                    mode,
                    listOf(CutMode.Grid to "Cuadrada", CutMode.Fill to "Completa", CutMode.Carousel to "Carrusel"),
                    onChange = { onPrefs(prefs.copy(mode = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    fill = true,
                )
                Text(
                    when (mode) {
                        CutMode.Grid -> "Piezas cuadradas. Lo que sobra se recorta."
                        CutMode.Fill -> "Usa toda la imagen sin recortar. Las piezas pueden no ser cuadradas."
                        CutMode.Carousel -> "Franjas de alto completo para un carrusel."
                    },
                    color = Text2,
                    fontSize = 12.sp,
                )
            }

            error?.let { Text(it, color = Pink, fontSize = 13.sp) }
            Spacer(Modifier.height(4.dp))
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Button(
                onClick = {
                    val uri = source ?: return@Button
                    val animated = gif != null
                    val clip = video
                    busy = true
                    progress = null
                    error = null
                    scope.launch {
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        runCatching {
                            if (clip != null) {
                                saveVideoTiles(context, uri, clip, columns, mode, stamp) { done, total, fraction ->
                                    progress = "Pieza ${minOf(done + 1, total)} de $total · ${(fraction * 100).toInt()}%"
                                }
                            } else withContext(Dispatchers.IO) {
                                if (animated) saveGifTiles(context.contentResolver, context.filesDir, uri, columns, mode, stamp, prefs.gifAsVideo)
                                else saveTiles(context.contentResolver, context.cacheDir, uri, columns, mode, stamp, prefs.lossless)
                            }
                        }.onSuccess {
                            Toast.makeText(context, "${it.count} piezas guardadas en la galería", Toast.LENGTH_SHORT).show()
                            onSaved(it)
                        }.onFailure { error = it.message ?: "No se pudo guardar" }
                        busy = false
                        progress = null
                    }
                },
                enabled = plan != null && !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Surface, disabledContentColor = Text2),
            ) {
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        progress?.let {
                            Spacer(Modifier.size(10.dp))
                            Text(it, color = Color.White, fontSize = 13.sp)
                        }
                    }
                    plan == null -> Text("Elegí una imagen para empezar")
                    else -> Text("Cortar en ${plan.tiles.size} piezas", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PinDialog(initial: String, onDone: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(initial) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val start = {
        val url = findPinLink(link)
        if (url == null) {
            status = "Pegá un link de Pinterest (pin.it o pinterest.com/pin)"
            failed = true
        } else {
            working = true
            failed = false
            status = "Buscando el video…"
            scope.launch {
                runCatching {
                    downloadPin(context, url) { status = "Bajando… ${(it * 100).toInt()}%" }
                }.onSuccess {
                    Toast.makeText(context, if (it.video) "Video guardado en Movies/Pinterest" else "Imagen guardada en Pictures/Pinterest", Toast.LENGTH_SHORT).show()
                    onDone(it.uri)
                }.onFailure {
                    status = it.message ?: "No se pudo bajar"
                    failed = true
                }
                working = false
            }
        }
    }
    LaunchedEffect(Unit) { if (findPinLink(initial) != null) start() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Bajar de Pinterest") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    singleLine = true,
                    enabled = !working,
                    placeholder = { Text("https://pin.it/…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                status?.let { Text(it, color = if (failed) Pink else Text2, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { start() }, enabled = !working) { Text("Bajar", color = Accent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !working) { Text("Cancelar", color = Text1) }
        },
        containerColor = Surface,
        titleContentColor = Text1,
        textContentColor = Text2,
    )
}

@Composable
private fun EmptyPick(loading: Boolean, onPick: () -> Unit, onPinterest: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(Surface)
            .border(1.dp, Line, shape)
            .clickable(enabled = !loading, onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Accent, strokeWidth = 2.dp)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Raised), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, null, tint = Accent, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("Elegir imagen, GIF o video", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("También podés compartirla a GridCut desde la galería", color = Text2, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                SmallButton("Bajar video de Pinterest", onClick = onPinterest)
            }
        }
    }
}

@Composable
private fun Facts(plan: CutPlan, gif: GifInfo?, video: VideoInfo?) {
    val tile = plan.tiles.first()
    val facts = buildList {
        add(if (plan.rows > 1) "${plan.cols} × ${plan.rows}" else "${plan.cols} slides")
        add("${tile.width} × ${tile.height} px")
        if (gif != null) add("GIF · ${gif.frames} cuadros")
        if (video != null) add("Video · ${video.durationMs / 60000}:${((video.durationMs / 1000) % 60).toString().padStart(2, '0')}")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (fact in facts) {
            Text(
                fact,
                color = Text2,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Surface)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Preview(loaded: Loaded, plan: CutPlan, numbers: Boolean) {
    val measurer = rememberTextMeasurer()
    val ratio = loaded.width.toFloat() / loaded.height
    val imageBitmap = remember(loaded) { loaded.preview.asImageBitmap() }
    Box(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(ratio)) {
            Image(imageBitmap, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / loaded.width
                val sy = size.height / loaded.height
                val shade = Color.Black.copy(alpha = 0.65f)
                drawRect(shade, Offset.Zero, Size(size.width, plan.top * sy))
                drawRect(shade, Offset(0f, plan.bottom * sy), Size(size.width, size.height - plan.bottom * sy))
                drawRect(shade, Offset(0f, plan.top * sy), Size(plan.left * sx, (plan.bottom - plan.top) * sy))
                drawRect(shade, Offset(plan.right * sx, plan.top * sy), Size(size.width - plan.right * sx, (plan.bottom - plan.top) * sy))
                val stroke = 2.dp.toPx()
                for (tile in plan.tiles) {
                    if (tile.col > 0) drawLine(Color.White, Offset(tile.left * sx, tile.top * sy), Offset(tile.left * sx, tile.bottom * sy), stroke)
                    if (tile.row > 0) drawLine(Color.White, Offset(tile.left * sx, tile.top * sy), Offset(tile.right * sx, tile.top * sy), stroke)
                }
                val cellPx = minOf(plan.tiles.first().width * sx, plan.tiles.first().height * sy)
                if (numbers && cellPx >= 28.dp.toPx()) {
                    val style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    val pad = 4.dp.toPx()
                    for (tile in plan.tiles) {
                        val layout = measurer.measure(tile.order.toString(), style)
                        val w = layout.size.width + pad * 2
                        val h = layout.size.height.toFloat()
                        val at = Offset(tile.left * sx + pad, tile.top * sy + pad)
                        drawRoundRect(Accent, at, Size(w, h), CornerRadius(h / 2))
                        drawText(layout, topLeft = Offset(at.x + pad, at.y))
                    }
                }
            }
        }
    }
}
