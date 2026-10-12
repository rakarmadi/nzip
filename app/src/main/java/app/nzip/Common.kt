package app.nzip

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val archiveExts = setOf(
    "7z", "zip", "rar", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz", "zst", "tzst", "lz", "lzma",
    "z", "iso", "cab", "wim", "arj", "lzh", "lha", "cpio", "rpm", "deb", "jar", "apk", "xpi", "dmg", "vhd", "001",
)

fun isArchiveName(name: String) = name.substringAfterLast('.', "").lowercase() in archiveExts

@Composable
fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

fun dateText(millis: Long): String =
    if (millis <= 0) "" else DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()

/** Colored type badge from the mockup: folder, archive by format, media, other. */
@Composable
fun FileBadge(name: String, isDir: Boolean) {
    val ext = name.substringAfterLast('.', "").lowercase()
    val (label, color) = when {
        isDir -> "DIR" to Color(0xFFE8A33D)
        ext == "7z" -> "7Z" to Color(0xFF3B5BDB)
        ext in setOf("zip", "jar", "apk", "xpi") -> ext.uppercase() to Color(0xFF2F9E44)
        ext == "rar" -> "RAR" to Color(0xFFC2255C)
        isArchiveName(name) -> ext.take(3).uppercase() to Color(0xFF7048E8)
        ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp") -> ext.take(3).uppercase() to Color(0xFF1098AD)
        ext in setOf("mp4", "mkv", "webm", "avi", "mov", "mp3", "flac", "ogg", "m4a", "wav") -> ext.take(3).uppercase() to Color(0xFFE8590C)
        else -> (ext.take(3).uppercase().ifEmpty { "?" }) to Color(0xFF868E96)
    }
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun FileRow(
    name: String,
    subtitle: String,
    isDir: Boolean,
    selected: Boolean,
    showCheck: Boolean,
    modifier: Modifier = Modifier,
    onCheck: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheck) {
            Checkbox(checked = selected, onCheckedChange = onCheck?.let { f -> { _ -> f() } })
            Spacer(Modifier.width(12.dp))
        }
        FileBadge(name, isDir)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Internal › Download › foo" with tappable segments; [onPick] gets the depth (0 = root). */
@Composable
fun Breadcrumb(segments: List<String>, trailing: String, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState(Int.MAX_VALUE))) {
            segments.forEachIndexed { i, s ->
                if (i > 0) Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(
                    s, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { onPick(i) }.padding(vertical = 8.dp),
                )
            }
        }
        if (trailing.isNotEmpty()) Text(trailing, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
    HorizontalDivider()
}

fun File.relativeSegments(): List<String> =
    if (!path.startsWith(storageRoot.path)) listOf(path)
    else listOf("Internal") + path.removePrefix(storageRoot.path).split('/').filter { it.isNotEmpty() }
