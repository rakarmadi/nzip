package app.nzip

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "Sembunyikan" else "Lihat")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun PasswordDialog(wrong: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Archive terenkripsi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wrong) Text("Password salah.", color = MaterialTheme.colorScheme.error)
                PasswordField(pw, { pw = it }, "Password", isError = wrong)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pw) }, enabled = pw.isNotEmpty()) { Text("Buka") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

/** Maps a SAF tree pick ("primary:Download/x") back to a filesystem path; we hold all-files access. */
private fun treeToFile(uri: Uri): File? = runCatching {
    val (vol, rel) = DocumentsContract.getTreeDocumentId(uri).split(":", limit = 2)
    if (vol == "primary") File(storageRoot, rel) else File("/storage/$vol", rel)
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractDialog(
    title: String,
    defaultDest: File,
    needsPassword: Boolean,
    password: String,
    onDismiss: () -> Unit,
    onConfirm: (dest: File, password: String, overwrite: SevenZip.Overwrite, openAfter: Boolean) -> Unit,
) {
    var dest by remember { mutableStateOf(defaultDest.path) }
    var pw by remember { mutableStateOf(password) }
    var overwrite by remember { mutableStateOf(SevenZip.Overwrite.SKIP) }
    var openAfter by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::treeToFile)?.let { dest = File(it, defaultDest.name).path }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = dest, onValueChange = { dest = it }, label = { Text("Tujuan") },
                    trailingIcon = { TextButton(onClick = { pick.launch(null) }) { Text("Ubah") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (needsPassword) PasswordField(pw, { pw = it }, "Password")
                Text("Jika file sudah ada", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val opts = listOf(SevenZip.Overwrite.OVERWRITE to "Timpa", SevenZip.Overwrite.SKIP to "Lewati", SevenZip.Overwrite.RENAME to "Rename")
                    opts.forEachIndexed { i, (o, label) ->
                        SegmentedButton(overwrite == o, { overwrite = o }, SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(label) }
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { openAfter = !openAfter }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(openAfter, { openAfter = it })
                    Text("Buka folder setelah selesai")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(File(dest.trim()), pw, overwrite, openAfter) }, enabled = dest.isNotBlank() && (!needsPassword || pw.isNotEmpty())) {
                Text("Extract")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

private val levels = listOf("Simpan" to 0, "Cepat" to 1, "Normal" to 5, "Maksimum" to 7, "Ultra" to 9)
private val volumes = listOf("Tidak" to 0, "100 MB" to 100, "700 MB" to 700, "4 GB (FAT32)" to 4095)

/** First free "name.ext" in [dir], also checking split-volume names ("name.ext.001"). */
private fun uniqueOut(dir: File, name: String, ext: String): File {
    var n = 1
    while (true) {
        val f = File(dir, (if (n == 1) name else "$name ($n)") + ".$ext")
        if (!f.exists() && !File(f.path + ".001").exists()) return f
        n++
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateSheet(dir: File, files: List<File>, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(if (files.size == 1) files[0].let { if (it.isDirectory) it.name else it.nameWithoutExtension } else dir.name) }
    var format by remember { mutableStateOf(SevenZip.Format.SEVEN_Z) }
    var level by remember { mutableFloatStateOf(2f) }
    var pw by remember { mutableStateOf("") }
    var encryptNames by remember { mutableStateOf(true) }
    var volume by remember { mutableStateOf(volumes[0]) }
    val total by produceState(-1L, files) {
        value = withContext(Dispatchers.IO) { files.sumOf { f -> f.walk().filter { it.isFile }.sumOf { it.length() } } }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Buat archive", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    "${files.size} item" + if (total >= 0) " · ${sizeText(total)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it.replace('/', '_') }, singleLine = true,
                suffix = { Text(".${format.ext}") }, modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SevenZip.Format.entries.forEach { f ->
                    FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                }
            }
            Row {
                Text("Level: ", style = MaterialTheme.typography.bodyMedium)
                Text(levels[level.toInt()].first, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(format.method, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(value = level, onValueChange = { level = it }, valueRange = 0f..4f, steps = 3)
            if (!format.isTar) PasswordField(pw, { pw = it }, "Password (opsional)")
            if (format == SevenZip.Format.SEVEN_Z && pw.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enkripsi nama file", Modifier.weight(1f))
                    Switch(encryptNames, { encryptNames = it })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Split volume", Modifier.weight(1f))
                var open by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { open = true }) { Text(volume.first + " ▾") }
                    DropdownMenu(open, { open = false }) {
                        volumes.forEach { v -> DropdownMenuItem(text = { Text(v.first) }, onClick = { volume = v; open = false }) }
                    }
                }
            }
            Button(
                onClick = {
                    val out = uniqueOut(dir, name.trim(), format.ext)
                    // gzip/xz have no "store" level.
                    val mx = levels[level.toInt()].second.let { if (format.isTar) maxOf(it, 1) else it }
                    val password = pw.takeIf { !format.isTar && it.isNotEmpty() }
                    val names = files.map { it.name }
                    val enc = encryptNames
                    val vol = volume.second
                    val listFile = File(ctx.cacheDir, "create-${System.nanoTime()}.txt")
                    ArchiveService.enqueue(ctx, ArchiveService.Job("Buat ${out.name}", dir, outFile = out) { run ->
                        try { SevenZip.create(format, mx, password, enc, vol, dir, names, out, listFile, run) }
                        finally { listFile.delete() }
                    })
                    onStarted()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Buat") }
        }
    }
}

@Composable
fun InfoDialog(file: File, l: SevenZip.Listing, onDismiss: () -> Unit) {
    val unpacked = l.entries.sumOf { it.size }
    val packed = if (l.physicalSize > 0) l.physicalSize else file.length()
    val rows = listOf(
        "Format" to l.type,
        "Metode" to l.method.replace("7zAES", "AES-256").ifBlank { "-" },
        "Solid" to if (l.solid) "Ya" else "Tidak",
        "Terenkripsi" to if (l.encrypted) "Ya" else "Tidak",
        "File" to l.entries.count { !it.isDir }.toString(),
        "Folder" to l.entries.count { it.isDir }.toString(),
        "Ukuran asli" to sizeText(unpacked),
        "Ukuran archive" to sizeText(packed),
        "Rasio" to if (unpacked > 0) "${packed * 100 / unpacked}%" else "-",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (k, v) ->
                    Row {
                        Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}
