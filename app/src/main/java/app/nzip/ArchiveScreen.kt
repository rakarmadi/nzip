package app.nzip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One row inside the archive: a real entry or a folder implied by deeper paths. */
private class Node(val name: String, val path: String, val isDir: Boolean, var size: Long, var count: Int, val modified: Long)

/**
 * Direct children of [prefix] ("" or "a/b/").
 * ponytail: O(entries) scan per folder change; build a tree once if 1M-entry archives feel slow.
 */
private fun children(entries: List<SevenZip.Entry>, prefix: String): List<Node> {
    val map = LinkedHashMap<String, Node>()
    for (e in entries) {
        if (!e.path.startsWith(prefix) || e.path.length <= prefix.length) continue
        val rest = e.path.substring(prefix.length)
        val slash = rest.indexOf('/')
        val name = if (slash < 0) rest else rest.substring(0, slash)
        val nested = slash >= 0
        val node = map.getOrPut(name) { Node(name, prefix + name, nested || e.isDir, 0, 0, e.modified) }
        if (nested) { node.count++; node.size += e.size } else if (!e.isDir) node.size = e.size
    }
    return map.values.sortedWith(compareBy<Node> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

/** "photos.7z" → "photos", "x.tar.gz" → "x", "game.part1.rar" → "game", "a.7z.001" → "a". */
fun archiveBaseName(name: String) =
    name.replace(Regex("""(\.part\d+)?(\.tar)?\.[A-Za-z0-9]+(\.\d{3})?$""", RegexOption.IGNORE_CASE), "").ifEmpty { name }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(file: File, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var password by remember(file) { mutableStateOf<String?>(null) }
    var askPassword by remember(file) { mutableStateOf(false) }
    var listing by remember(file) { mutableStateOf<SevenZip.Listing?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var inner by rememberSaveable(file) { mutableStateOf("") }
    var selected by remember(file) { mutableStateOf(setOf<String>()) }
    var query by remember(file) { mutableStateOf<String?>(null) }
    var showExtract by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    LaunchedEffect(file, password) {
        val (l, res) = withContext(Dispatchers.IO) { SevenZip.list(file.path, password) }
        when {
            l != null -> listing = l
            res.needsPassword -> askPassword = true
            else -> error = res.message
        }
    }

    val nodes = remember(listing, inner) { listing?.let { children(it.entries, inner) } }
    val shown = nodes?.let { all -> query?.takeIf { it.isNotBlank() }?.let { q -> all.filter { q in it.name.lowercase() } } ?: all }

    BackHandler {
        when {
            query != null -> query = null
            inner.isNotEmpty() -> inner = inner.dropLast(1).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
                title = {
                    if (query != null) {
                        TextField(
                            value = query!!, onValueChange = { query = it.lowercase() }, singleLine = true,
                            placeholder = { Text("Cari di folder ini") },
                            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                        )
                    } else Column {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        listing?.let { l ->
                            Text(
                                listOf(l.type, l.method.replace("7zAES", "AES-256"), "${l.entries.count { !it.isDir }} file")
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (query == null) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Search, "Cari") }
                    else IconButton(onClick = { query = null }) { Icon(Icons.Default.Close, "Tutup cari") }
                },
            )
        },
        bottomBar = {
            if (listing != null) BottomAppBar {
                Button(onClick = { showExtract = true }, Modifier.padding(start = 8.dp)) {
                    Text(if (selected.isEmpty()) "Extract semua" else "Extract (${selected.size})")
                }
                TextButton(onClick = {
                    val pw = password
                    ArchiveService.enqueue(ctx, ArchiveService.Job("Test ${file.name}", file.parentFile ?: storageRoot, okText = "Tidak ada error") { run ->
                        SevenZip.test(file.path, pw, run)
                    })
                }) { Text("Test") }
                TextButton(onClick = { showInfo = true }) { Text("Info") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Breadcrumb(
                listOf("/") + inner.split('/').filter { it.isNotEmpty() },
                if (selected.isEmpty()) "" else "${selected.size} dipilih",
            ) { depth -> inner = inner.split('/').filter { it.isNotEmpty() }.take(depth).joinToString("") { "$it/" } }
            when {
                error != null -> Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                shown == null -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.path }) { n ->
                        FileRow(
                            name = n.name,
                            subtitle = if (n.isDir) "Folder · ${n.count} item" else sizeText(n.size),
                            isDir = n.isDir,
                            selected = n.path in selected,
                            showCheck = true,
                            modifier = Modifier.clickable {
                                if (n.isDir && selected.isEmpty()) { inner = n.path + "/"; query = null }
                                else selected = if (n.path in selected) selected - n.path else selected + n.path
                            },
                            onCheck = { selected = if (n.path in selected) selected - n.path else selected + n.path },
                        )
                    }
                }
            }
        }
    }

    if (askPassword) {
        PasswordDialog(
            wrong = password != null,
            onDismiss = { askPassword = false; if (listing == null) onBack() },
            onConfirm = { askPassword = false; password = it },
        )
    }

    val l = listing
    if (showExtract && l != null) {
        val base = archiveBaseName(file.name)
        val parent = file.parentFile?.takeIf { it.path.startsWith(storageRoot.path) }
            ?: File(storageRoot, android.os.Environment.DIRECTORY_DOWNLOADS)
        ExtractDialog(
            title = if (selected.isEmpty()) "Extract semua" else "Extract ${selected.size} item",
            defaultDest = File(parent, base),
            needsPassword = l.encrypted,
            password = password.orEmpty(),
            onDismiss = { showExtract = false },
        ) { dest, pw, overwrite, openAfter ->
            showExtract = false
            val items = selected.takeIf { it.isNotEmpty() }?.toList()
            selected = emptySet()
            val listFile = File(ctx.cacheDir, "extract-${System.nanoTime()}.txt")
            ArchiveService.enqueue(ctx, ArchiveService.Job("Extract ${file.name}", dest, openWhenDone = openAfter) { run ->
                try { SevenZip.extract(file.path, dest, pw.ifEmpty { null }, overwrite, items, listFile, run) }
                finally { listFile.delete() }
            })
        }
    }
    if (showInfo && l != null) InfoDialog(file, l) { showInfo = false }
}
