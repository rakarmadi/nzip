package app.nzip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Runs archive jobs one at a time as a foreground service.
 * Progress and cancel live in the notification, so the app can be closed meanwhile.
 */
class ArchiveService : Service() {

    class Job(
        val title: String,
        /** Folder to show for "Buka folder" and to refresh in the UI. */
        val outDir: File,
        /** Created archive, offered for sharing. */
        val outFile: File? = null,
        val openWhenDone: Boolean = false,
        /** Success text; defaults to the output path. */
        val okText: String? = null,
        val work: (SevenZip.Run) -> SevenZip.Result,
    )

    class Done(val job: Job, val result: SevenZip.Result)

    companion object {
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val ID_PROGRESS = 1
        private const val ACTION_CANCEL = "app.nzip.CANCEL"

        private val queue = ConcurrentLinkedQueue<Job>()
        private val _done = MutableSharedFlow<Done>(extraBufferCapacity = 16)
        val done = _done.asSharedFlow()

        fun enqueue(context: Context, job: Job) {
            queue += job
            context.startForegroundService(Intent(context, ArchiveService::class.java))
        }
    }

    @Volatile private var current: SevenZip.Run? = null
    private var worker: Thread? = null
    private var nextDoneId = 100

    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Selesai", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            queue.clear()
            current?.cancel()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, ID_PROGRESS, progress(queue.peek()?.title ?: "nzip", 0, ""),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (worker?.isAlive != true) worker = thread(name = "nzip-jobs") { drainQueue() }
        return START_NOT_STICKY
    }

    private fun drainQueue() {
        while (true) {
            val job = queue.poll() ?: break
            var last = -1
            val run = SevenZip.Run { pct, file ->
                if (pct != last) {
                    last = pct
                    nm.notify(ID_PROGRESS, progress(job.title, pct, file))
                }
            }
            current = run
            nm.notify(ID_PROGRESS, progress(job.title, 0, ""))
            val result = runCatching { job.work(run) }
                .getOrElse { SevenZip.Result(-1, it.message ?: it.toString()) }
            current = null
            if (!result.cancelled) nm.notify(nextDoneId++, finished(job, result))
            _done.tryEmit(Done(job, result))
        }
        // Decide on the main thread, which also runs onStartCommand, so a job enqueued
        // while this thread was finishing is never dropped.
        android.os.Handler(mainLooper).post {
            if (queue.isNotEmpty()) worker = thread(name = "nzip-jobs") { drainQueue() }
            else {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun openApp(dir: File) = PendingIntent.getActivity(
        this, dir.path.hashCode(),
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_DIR, dir.path),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progress(title: String, pct: Int, file: String): Notification {
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, ArchiveService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (file.isEmpty()) "$pct%" else "$pct% · $file")
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Batal", cancel)
            .build()
    }

    private fun finished(job: Job, result: SevenZip.Result): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(if (result.ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (result.ok) "${job.title} selesai" else "${job.title} gagal")
            .setContentText(if (result.ok) job.okText ?: (job.outFile ?: job.outDir).path else result.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (result.ok) job.okText ?: (job.outFile ?: job.outDir).path else result.message))
            .setAutoCancel(true)
            .setContentIntent(openApp(job.outDir))
        if (result.ok) {
            b.addAction(0, "Buka folder", openApp(job.outDir))
            val f = job.outFile
            if (f != null && f.exists()) {
                val uri = FileProvider.getUriForFile(this, "app.nzip.files", f)
                val share = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                b.addAction(0, "Bagikan", PendingIntent.getActivity(this, f.path.hashCode(), Intent.createChooser(share, null), PendingIntent.FLAG_IMMUTABLE))
            }
        }
        return b.build()
    }
}
