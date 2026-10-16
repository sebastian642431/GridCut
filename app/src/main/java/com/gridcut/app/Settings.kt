package com.gridcut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(prefs: Prefs, onPrefs: (Prefs) -> Unit, batches: List<Batch>, onDeleteAll: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val pieces = batches.sumOf { it.count }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ajustes", color = Text1, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Group("Calidad de las fotos") {
            Segmented(
                prefs.lossless,
                listOf(true to "PNG sin pérdida", false to "JPEG liviano"),
                onChange = { onPrefs(prefs.copy(lossless = it)) },
            )
            Text(
                if (prefs.lossless) "Cada pieza queda idéntica al original. Pesa más."
                else "Pesa mucho menos, con una pérdida mínima de calidad.",
                color = Text2,
                fontSize = 12.sp,
            )
        }

        Group("GIF animados") {
            Segmented(
                prefs.gifAsVideo,
                listOf(true to "Video MP4", false to "GIF"),
                onChange = { onPrefs(prefs.copy(gifAsVideo = it)) },
            )
            Text(
                if (prefs.gifAsVideo) "Cada pieza se guarda como video. Es lo que aceptan TikTok e Instagram."
                else "Cada pieza queda como GIF animado. TikTok e Instagram no los toman.",
                color = Text2,
                fontSize = 12.sp,
            )
        }

        Group("Vista previa") {
            Toggle("Números de orden sobre la imagen", prefs.numbers) { onPrefs(prefs.copy(numbers = it)) }
            Toggle("Abrir el corte apenas termina", prefs.openAfterCut) { onPrefs(prefs.copy(openAfterCut = it)) }
        }

        Group("Cómo subirlas") {
            Tip("Grilla: subí primero la 1 y seguí en orden. Queda armada en el perfil porque lo último que subís va arriba a la izquierda.")
            Tip("Carrusel: elegí todas juntas en un solo post, de la 1 a la última.")
            Tip("En Mis cortes marcá cada pieza con “Ya la subí” para no perder la cuenta.")
        }

        Group("Almacenamiento") {
            Text(
                if (batches.isEmpty()) "No hay cortes guardados." else "${batches.size} cortes · $pieces piezas en DCIM/$ALBUM",
                color = Text1,
                fontSize = 13.sp,
            )
            if (batches.isNotEmpty()) {
                Text(
                    "Borrar todos los cortes",
                    color = Pink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { confirm = true }.padding(vertical = 6.dp),
                )
            }
        }

        Text("GridCut 1.2.0", color = Text2, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
    }

    if (confirm) {
        ConfirmDelete(
            text = "Se borran ${batches.size} cortes ($pieces piezas) de la galería. No se puede deshacer.",
            onConfirm = { confirm = false; onDeleteAll() },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Text2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Text1, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Background,
                uncheckedThumbColor = Text2,
                uncheckedBorderColor = Line,
            ),
        )
    }
}

@Composable
private fun Tip(text: String) {
    Row {
        Text("•", color = Accent, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Text1, fontSize = 13.sp)
    }
}
