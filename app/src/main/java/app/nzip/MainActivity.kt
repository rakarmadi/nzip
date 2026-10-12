package app.nzip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_DIR = "dir"
    }

    /** Latest intent; screens react to it (open-with archive or "Buka folder" from a notification). */
    private var launch by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SevenZip.exe = applicationInfo.nativeLibraryDir + "/lib7zz.so"
        enableEdgeToEdge()
        if (savedInstanceState == null) launch = intent
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { NzipTheme { App(launch) { launch = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launch = intent
    }
}

@Composable
fun NzipTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

val storageRoot: File = Environment.getExternalStorageDirectory()

private fun hasStorageAccess(ctx: android.content.Context) =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

@Composable
fun App(launch: Intent?, consumeLaunch: () -> Unit) {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(hasStorageAccess(ctx)) }
    var dir by rememberSaveable { mutableStateOf(storageRoot.path) }
    var archive by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        access = hasStorageAccess(ctx)
        refresh++ // files may have changed while we were away
        onPauseOrDispose {}
    }

    LaunchedEffect(Unit) {
        ArchiveService.done.collect { done ->
            refresh++
            if (done.result.ok && done.job.openWhenDone) {
                archive = null
                dir = done.job.outDir.path
            }
        }
    }
    LaunchedEffect(launch) {
        val i = launch ?: return@LaunchedEffect
        i.getStringExtra(MainActivity.EXTRA_DIR)?.let { archive = null; dir = it }
        if (i.action == Intent.ACTION_VIEW) i.data?.let { uri ->
            archive = withContext(Dispatchers.IO) { resolveArchive(ctx, uri) }?.path
        }
        consumeLaunch()
    }

    if (!access) {
        AccessScreen(onGrant = {
            if (Build.VERSION.SDK_INT >= 30) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            } else {
                (ctx as ComponentActivity).requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        })
        return
    }

    val open = archive
    if (open != null) {
        ArchiveScreen(File(open), onBack = { archive = null })
    } else {
        BrowserScreen(
            dir = File(dir),
            refreshKey = refresh,
            onDir = { dir = it.path },
            onOpenArchive = { archive = it.path },
        )
    }
}

@Composable
private fun AccessScreen(onGrant: () -> Unit) {
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Akses file dibutuhkan", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "nzip perlu izin \"Akses semua file\" untuk membaca dan membuat archive di penyimpanan.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onGrant) { Text("Beri izin") }
        }
    }
}

/**
 * Maps an "Open with" URI to a file 7zz can read. Real path when the provider exposes one;
 * otherwise copies to cache.
 * ponytail: the cache copy doubles disk use and breaks multi-volume sets; stream via fd if that matters.
 */
private fun resolveArchive(ctx: android.content.Context, uri: Uri): File? {
    if (uri.scheme == "file") return uri.path?.let(::File)
    runCatching {
        if (DocumentsContract.isDocumentUri(ctx, uri) && uri.authority == "com.android.externalstorage.documents") {
            val (vol, rel) = DocumentsContract.getDocumentId(uri).split(":", limit = 2)
            if (vol == "primary") File(storageRoot, rel).takeIf { it.canRead() }?.let { return it }
        }
    }
    runCatching {
        ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let(::File)?.takeIf { it.canRead() }?.let { return it }
        }
    }
    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "archive"
    val dir = File(ctx.cacheDir, "open").apply { deleteRecursively(); mkdirs() }
    val out = File(dir, name.replace('/', '_'))
    return runCatching {
        ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        out
    }.getOrNull()
}
