package com.gridcut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Background = Color(0xFF0E0F13)
val Surface = Color(0xFF16181F)
val Raised = Color(0xFF1E2029)
val Line = Color(0xFF2A2D38)
val Text1 = Color(0xFFECEDF2)
val Text2 = Color(0xFF8B8FA0)
val Accent = Color(0xFF7C5CFF)
val Pink = Color(0xFFFF7AC6)
val Ok = Color(0xFF3DDC97)

@Composable
fun SmallButton(label: String, onClick: () -> Unit, filled: Boolean = false, modifier: Modifier = Modifier) {
    val padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    if (filled) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            contentPadding = padding,
            modifier = modifier.height(34.dp),
        ) {
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(10.dp), contentPadding = padding, modifier = modifier.height(34.dp)) {
            Text(label, color = Text1, fontSize = 12.sp)
        }
    }
}

@Composable
fun <T> Segmented(value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, modifier: Modifier = Modifier, fill: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.clip(shape).background(Surface).border(1.dp, Line, shape).padding(3.dp)) {
        for ((option, label) in options) {
            val selected = option == value
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Accent else Color.Transparent)
                    .clickable { onChange(option) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (selected) Color.White else Text2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun Stepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundButton("−", enabled = value > min) { onChange(value - 1) }
        Text(
            value.toString(),
            color = Text1,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.Center,
        )
        RoundButton("+", enabled = value < max) { onChange(value + 1) }
    }
}

@Composable
private fun RoundButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (enabled) Surface else Background)
            .border(1.dp, Line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Text1 else Line, fontSize = 17.sp)
    }
}

@Composable
fun Badge(number: Int, modifier: Modifier = Modifier, color: Color = Accent) {
    Box(modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(number.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
