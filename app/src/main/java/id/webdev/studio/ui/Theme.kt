package id.webdev.studio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.webdev.studio.data.ServerStatus

object Dev {
    val Bg = Color(0xFF0D1117)
    val Surface = Color(0xFF161B22)
    val Surface2 = Color(0xFF1F262E)
    val Border = Color(0xFF30363D)
    val Text = Color(0xFFE6EDF3)
    val Muted = Color(0xFF8B949E)
    val Blue = Color(0xFF58A6FF)
    val Green = Color(0xFF3FB950)
    val Red = Color(0xFFF85149)
    val Amber = Color(0xFFD29922)
    val Mono = FontFamily.Monospace
}

private val DarkScheme = darkColorScheme(
    primary = Dev.Blue, onPrimary = Color(0xFF04111F),
    secondary = Dev.Green, onSecondary = Color.Black,
    background = Dev.Bg, onBackground = Dev.Text,
    surface = Dev.Surface, onSurface = Dev.Text,
    surfaceVariant = Dev.Surface2, onSurfaceVariant = Dev.Muted,
    outline = Dev.Border, error = Dev.Red,
    surfaceContainer = Dev.Surface, surfaceContainerHigh = Dev.Surface2,
    surfaceContainerHighest = Dev.Surface2, surfaceContainerLow = Dev.Surface,
    secondaryContainer = Color(0xFF1B3A5C), onSecondaryContainer = Dev.Text
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0969DA), secondary = Color(0xFF1A7F37),
    background = Color(0xFFF6F8FA), surface = Color.White,
    surfaceVariant = Color(0xFFEAEEF2), outline = Color(0xFFD0D7DE), error = Color(0xFFCF222E)
)

@Composable
fun WebDevTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}

@Composable
fun Chip(text: String, color: Color = Dev.Blue, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) { Text(text, color = color, fontSize = 11.sp, fontFamily = Dev.Mono, fontWeight = FontWeight.Medium) }
}

fun statusColor(s: ServerStatus?): Color = when (s) {
    ServerStatus.RUNNING -> Dev.Green
    ServerStatus.STARTING, ServerStatus.INSTALLING -> Dev.Amber
    ServerStatus.FAILED -> Dev.Red
    ServerStatus.NEEDS_DEPS -> Dev.Amber
    null -> Dev.Muted
}

fun statusText(s: ServerStatus?): String = when (s) {
    ServerStatus.RUNNING -> "RUNNING"
    ServerStatus.STARTING -> "STARTING"
    ServerStatus.INSTALLING -> "INSTALLING"
    ServerStatus.FAILED -> "FAILED"
    ServerStatus.NEEDS_DEPS -> "NEEDS DEPS"
    null -> "STOPPED"
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.padding(top = 18.dp, bottom = 8.dp),
        color = Dev.Muted, fontSize = 12.sp, fontFamily = Dev.Mono, fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
    )
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = Dev.Muted, fontSize = 12.sp, fontFamily = Dev.Mono)
        Text(v, color = Dev.Text, fontSize = 12.sp, fontFamily = Dev.Mono, modifier = Modifier.padding(start = 12.dp))
    }
}

fun humanSize(b: Long): String = when {
    b < 0 -> "?"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
