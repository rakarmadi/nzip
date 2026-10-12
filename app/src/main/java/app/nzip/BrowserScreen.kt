package app.nzip

import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private class FileItem(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val children: Int)

private enum class Sort(val label: String) { NAME("Nama"), DATE("Tanggal"), SIZE("Ukuran") }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(dir: File, refreshKey: Int, onDir: (File) -> Unit, onOpenArchive: (File) -> Unit) {
    val ctx = LocalContext.current
    var sort by rememberSaveable { mutableStateOf(Sort.NAME) }
    var query by rememberSaveable(dir) { mutableStateOf<String?>(null) }
    var selected by remember(dir) { mutableStateOf(setOf<File>()) }
    var selecting by remember(dir) { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }

    val items by produceState<List<FileItem>?>(null, dir, refreshKey, sort) {
        value = withContext(Dispatchers.IO) {
            (dir.listFiles() ?: emptyArray()).filter { !it.isHidden }.map {
                val d = it.isDirectory
                FileItem(it, d, if (d) 0 else it.length(), it.lastModified(), if (d) it.list()?.size ?: 0 else 0)
            }.sortedWith(
                compareBy<FileItem> { !it.isDir }.then(
                    when (sort) {
                        Sort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
                        Sort.DATE -> compareByDescending { it.modified }
                        Sort.SIZE -> compareByDescending { it.size }
                    }
                )
            )
        }
    }
    val shown = items?.let { all -> query?.takeIf { it.isNotBlank() }?.let { q -> all.filter { q in it.file.name.lowercase() } } ?: all }
    val inSelection = selecting || selected.isNotEmpty()

    BackHandler(inSelection || query != null || dir != storageRoot) {
        when {
            inSelection -> { selected = emptySet(); selecting = false }
            query != null -> query = null
            else -> dir.parentFile?.let(onDir)
        }
    }

    fun open(item: FileItem) {
        when {
            inSelection -> selected = if (item.file in selected) selected - item.file else selected + item.file
            item.isDir -> onDir(item.file)
            isArchiveName(item.file.name) -> onOpenArchive(item.file)
            else -> runCatching {
                val uri = FileProvider.getUriForFile(ctx, "app.nzip.files", item.file)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.file.extension.lowercase()) ?: "*/*"
                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { Toast.makeText(ctx, "Tidak ada app untuk membuka file ini", Toast.LENGTH_SHORT).show() }
        }
    }

    Scaffold(
        floatingActionButton = {
            if (selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    icon = { Icon(Icons.Default.Archive, null) },
                    text = { Text("Kompres") },
                )
            } else if (!selecting) {
                FloatingActionButton(onClick = { selecting = true }) { Icon(Icons.Default.Add, "Buat archive") }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 88.dp)) {
            // One UI style: large title in the top third, content starts within thumb reach.
            item {
                Column(Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.Bottom) {
                    Text(if (dir == storageRoot) "Internal" else dir.name, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    items?.let { list ->
                        Text(
                            "${list.size} item · ${sizeText(list.sumOf { it.size })}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            stickyHeader {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            when {
                                inSelection -> {
                                    IconButton(onClick = { selected = emptySet(); selecting = false }) { Icon(Icons.Default.Close, "Batal pilih") }
                                    Text(if (selected.isEmpty()) "Pilih file" else "${selected.size} dipilih", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    IconButton(onClick = { selected = shown.orEmpty().map { it.file }.toSet() }) { Icon(Icons.Default.SelectAll, "Pilih semua") }
                                }
                                query != null -> {
                                    IconButton(onClick = { query = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tutup cari") }
                                    TextField(
                                        value = query!!, onValueChange = { query = it.lowercase() },
                                        placeholder = { Text("Cari di folder ini") }, singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                                    )
                                }
                                else -> {
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Search, "Cari") }
                                    var menu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Urutkan") }
                                        DropdownMenu(menu, { menu = false }) {
                                            Sort.entries.forEach { s ->
                                                DropdownMenuItem(
                                                    text = { Text(s.label) }, onClick = { sort = s; menu = false },
                                                    trailingIcon = { if (s == sort) Icon(Icons.Default.Check, null) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Breadcrumb(dir.relativeSegments(), sort.label + " ↓") { depth ->
                            if (dir.path.startsWith(storageRoot.path)) {
                                onDir(File(storageRoot, dir.relativeSegments().drop(1).take(depth).joinToString("/")))
                            }
                        }
                    }
                }
            }
            when {
                shown == null -> item { Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() } }
                shown.isEmpty() -> item {
                    Text(
                        if (query.isNullOrBlank()) "Folder kosong" else "Tidak ditemukan",
                        Modifier.fillMaxWidth().padding(48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> items(shown, key = { it.file.path }) { item ->
                    FileRow(
                        name = item.file.name,
                        subtitle = if (item.isDir) "Folder · ${item.children} item" else "${sizeText(item.size)} · ${dateText(item.modified)}",
                        isDir = item.isDir,
                        selected = item.file in selected,
                        showCheck = inSelection,
                        modifier = Modifier.combinedClickable(
                            onClick = { open(item) },
                            onLongClick = { selected = selected + item.file },
                        ),
                        onCheck = { open(item) },
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateSheet(dir = dir, files = selected.toList(), onDismiss = { showCreate = false }) {
            showCreate = false
            selected = emptySet()
            selecting = false
        }
    }
}
