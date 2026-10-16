package com.gridcut.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Uri?>(null)
    private var incomingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            incoming = sharedImage(intent)
            incomingLink = sharedLink(intent)
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Color.White,
                    background = Background,
                    surface = Surface,
                    onSurface = Text1,
                    outline = Line,
                ),
            ) {
                App(incoming, incomingLink, onConsumed = { incoming = null; incomingLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedImage(intent)?.let { incoming = it }
        sharedLink(intent)?.let { incomingLink = it }
    }

    private fun sharedLink(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") findPinLink(intent.getStringExtra(Intent.EXTRA_TEXT)) else null

    private fun sharedImage(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else null
}

private enum class Tab { Cut, Library, Settings }

@Composable
private fun App(incoming: Uri?, incomingLink: String?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BatchStore(context) }
    val prefsStore = remember { PrefsStore(context) }
    var prefs by remember { mutableStateOf(prefsStore.load()) }
    var batches by remember { mutableStateOf(store.load()) }
    var tab by rememberSaveable { mutableStateOf(Tab.Cut) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var viewing by rememberSaveable { mutableStateOf<Int?>(null) }
    var shared by remember { mutableStateOf<Uri?>(null) }
    var sharedLink by remember { mutableStateOf<String?>(null) }
    val batch = batches.firstOrNull { it.stamp == open }

    LaunchedEffect(Unit) { batches = withContext(Dispatchers.IO) { store.prune(context.contentResolver) } }
    LaunchedEffect(incoming, incomingLink) {
        if (incoming != null || incomingLink != null) {
            if (incoming != null) shared = incoming
            if (incomingLink != null) sharedLink = incomingLink
            tab = Tab.Cut
            open = null
            viewing = null
            onConsumed()
        }
    }

    val setPrefs = { value: Prefs -> prefs = prefsStore.save(value) }
    val delete = { stamps: Set<String> ->
        scope.launch { batches = withContext(Dispatchers.IO) { store.remove(context.contentResolver, stamps) } }
        Unit
    }

    BackHandler(enabled = viewing != null || open != null || tab != Tab.Cut) {
        when {
            viewing != null -> viewing = null
            open != null -> open = null
            else -> tab = Tab.Cut
        }
    }

    if (batch != null && viewing != null) {
        Viewer(
            batch,
            viewing ?: 0,
            onClose = { viewing = null },
            onTogglePosted = { order ->
                val posted = if (order in batch.posted) batch.posted - order else batch.posted + order
                batches = store.update(batch.copy(posted = posted))
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Background)) {
        Box(Modifier.weight(1f)) {
            when {
                tab == Tab.Cut -> CutScreen(
                    incoming = shared,
                    incomingLink = sharedLink,
                    onLinkConsumed = { sharedLink = null },
                    prefs = prefs,
                    onPrefs = setPrefs,
                    onSaved = {
                        batches = store.add(it)
                        if (prefs.openAfterCut) {
                            open = it.stamp
                            tab = Tab.Library
                        }
                    },
                )
                tab == Tab.Settings -> SettingsScreen(prefs, setPrefs, batches, onDeleteAll = { delete(batches.map { it.stamp }.toSet()) })
                batch != null -> BatchScreen(
                    batch,
                    onBack = { open = null },
                    onDelete = {
                        delete(setOf(batch.stamp))
                        open = null
                    },
                    onView = { viewing = it },
                    onPosted = { batches = store.update(batch.copy(posted = it)) },
                )
                else -> LibraryScreen(
                    batches,
                    onOpen = { open = it.stamp },
                    onDelete = delete,
                    onStart = { tab = Tab.Cut },
                )
            }
        }
        BottomBar(tab, pending = batches.count { !it.done }) { picked ->
            if (picked == tab && picked == Tab.Library) open = null
            tab = picked
        }
    }
}

@Composable
private fun BottomBar(tab: Tab, pending: Int, onChange: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)) {
            Item("Cortar", Icons.Default.Add, tab == Tab.Cut, 0) { onChange(Tab.Cut) }
            Item("Mis cortes", Icons.AutoMirrored.Filled.List, tab == Tab.Library, pending) { onChange(Tab.Library) }
            Item("Ajustes", Icons.Default.Settings, tab == Tab.Settings, 0) { onChange(Tab.Settings) }
        }
    }
}

@Composable
private fun RowScope.Item(label: String, icon: ImageVector, selected: Boolean, count: Int, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxSize().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(22.dp)) {
            Icon(icon, null, tint = if (selected) Accent else Text2, modifier = Modifier.size(22.dp))
            if (count > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-1).dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Pink),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, color = if (selected) Text1 else Text2, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
