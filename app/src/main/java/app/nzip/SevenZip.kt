package app.nzip

import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Thin wrapper around the bundled 7-Zip console binary (lib7zz.so).
 * Every operation is one 7zz process; progress comes from `-bsp1` percent output.
 */
object SevenZip {
    lateinit var exe: String

    data class Entry(val path: String, val isDir: Boolean, val size: Long, val modified: Long, val encrypted: Boolean)

    data class Listing(
        val type: String,
        val method: String,
        val solid: Boolean,
        val physicalSize: Long,
        val entries: List<Entry>,
    ) {
        val encrypted get() = "AES" in method || entries.any { it.encrypted }
    }

    class Result(val code: Int, val output: String, val cancelled: Boolean = false) {
        val ok get() = code <= 1 && !cancelled // 1 = warnings only, output is still valid
        val needsPassword get() = "Wrong password" in output || "Enter password" in output
        val message: String
            get() = when {
                cancelled -> "Dibatalkan"
                ok -> "Selesai"
                needsPassword -> "Password salah atau dibutuhkan"
                else -> output.lines().map { it.trim() }
                    .filter { it.startsWith("ERROR") || it.startsWith("Error") || it.startsWith("Can not") || it.startsWith("Cannot") }
                    .distinct().take(3).joinToString("\n")
                    .ifBlank { "Gagal (kode $code)" }
            }
    }

    /** Handle for one running operation; [cancel] kills the 7zz process(es). */
    class Run(val onProgress: (percent: Int, file: String) -> Unit = { _, _ -> }) {
        @Volatile var cancelled = false; private set
        private val procs = mutableListOf<Process>()

        @Synchronized fun cancel() { cancelled = true; procs.forEach { it.destroy() } }

        @Synchronized internal fun start(pb: ProcessBuilder): Process {
            val p = pb.start()
            procs += p
            if (cancelled) p.destroy()
            return p
        }
    }

    private val progressRe = Regex("""^\s*(\d{1,3})%(?:\s+\d+)?(?:\s+[+\-=UR.])?\s*(.*)$""")

    /** Reads 7zz console output: progress segments go to [run], everything else is kept for error messages. */
    internal fun drain(input: InputStream, run: Run): String {
        val out = StringBuilder()
        val seg = StringBuilder()
        fun flush() {
            val m = progressRe.matchEntire(seg)
            if (m != null) run.onProgress(m.groupValues[1].toInt(), m.groupValues[2].trim())
            else out.append(seg).append('\n')
            seg.clear()
        }
        input.bufferedReader().use { r ->
            while (true) {
                val c = r.read()
                if (c < 0) break
                when (c.toChar()) {
                    '\n' -> if (seg.isEmpty()) out.append('\n') else flush() // blank lines separate list entries
                    '\b', '\r' -> if (seg.isNotEmpty()) flush()
                    else -> seg.append(c.toChar())
                }
            }
        }
        if (seg.isNotEmpty()) flush()
        return out.toString()
    }

    private fun process(args: List<String>, dir: File?) =
        ProcessBuilder(listOf(exe) + args).directory(dir).redirectErrorStream(true)

    /** Runs 7zz with [args]; stdin is closed so a missing password fails instead of blocking. */
    fun run(args: List<String>, run: Run = Run(), dir: File? = null): Result {
        val p = run.start(process(args, dir))
        p.outputStream.close()
        val out = drain(p.inputStream, run)
        return Result(p.waitFor(), out, run.cancelled)
    }

    private fun pw(password: String?) = if (password.isNullOrEmpty()) emptyList() else listOf("-p$password")

    fun list(archive: String, password: String?): Pair<Listing?, Result> {
        val res = run(listOf("l", "-slt", "-sccUTF-8") + pw(password) + archive)
        if (!res.ok) return null to res
        return parseListing(res.output) to res
    }

    fun parseListing(text: String): Listing {
        val header = HashMap<String, String>()
        val entries = ArrayList<Entry>()
        var cur = HashMap<String, String>()
        val methods = LinkedHashSet<String>() // zip/tar have no archive-level Method
        var section = 0 // 0 = preamble, 1 = archive header, 2 = entries
        fun push() {
            val path = cur["Path"]
            if (path != null) entries += Entry(
                path = path,
                isDir = cur["Folder"] == "+" || cur["Attributes"]?.startsWith("D") == true,
                size = cur["Size"]?.toLongOrNull() ?: 0,
                modified = parseTime(cur["Modified"]),
                encrypted = cur["Encrypted"] == "+",
            )
            cur["Method"]?.takeIf { it.isNotBlank() && methods.size < 4 }?.let { methods += it }
            cur = HashMap()
        }
        for (line in text.lineSequence()) {
            when {
                section == 0 && line == "--" -> section = 1
                section == 1 && line == "----------" -> section = 2
                section == 1 -> line.split(" = ", limit = 2).takeIf { it.size == 2 }?.let { header[it[0]] = it[1] }
                section == 2 && line.isBlank() -> push()
                section == 2 -> {
                    val i = line.indexOf(" = ")
                    if (i > 0) cur[line.substring(0, i)] = line.substring(i + 3)
                }
            }
        }
        push()
        return Listing(
            type = header["Type"] ?: "?",
            method = header["Method"] ?: methods.joinToString(" "),
            solid = header["Solid"] == "+",
            physicalSize = header["Physical Size"]?.toLongOrNull() ?: 0,
            entries = entries,
        )
    }

    private val timeFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    private fun parseTime(s: String?): Long =
        if (s.isNullOrBlank() || s.length < 19) 0 else runCatching { timeFmt.parse(s.substring(0, 19))!!.time }.getOrDefault(0)

    enum class Overwrite(val flag: String) { OVERWRITE("-aoa"), SKIP("-aos"), RENAME("-aou") }

    /** [items] = archive paths to extract (folders include their contents); null = everything. */
    fun extract(archive: String, outDir: File, password: String?, overwrite: Overwrite, items: List<String>?, listFile: File, run: Run): Result {
        val args = mutableListOf("x", "-y", "-bso0", "-bsp1", "-scsUTF-8", overwrite.flag, "-o${outDir.path}")
        args += pw(password)
        args += archive
        if (items != null) {
            listFile.writeText(items.joinToString("\n"))
            args += "@${listFile.path}"
        }
        return run(args, run)
    }

    fun test(archive: String, password: String?, run: Run): Result =
        run(listOf("t", "-bso0", "-bsp1") + pw(password) + archive, run)

    enum class Format(val ext: String, val label: String, val method: String) {
        SEVEN_Z("7z", "7z", "LZMA2"),
        ZIP("zip", "zip", "Deflate"),
        TAR_GZ("tar.gz", "tar.gz", "gzip"),
        TAR_XZ("tar.xz", "tar.xz", "xz");

        val isTar get() = ext.startsWith("tar")
    }

    /**
     * Archives [names] (relative to [dir]) into [out]. tar.* runs two 7zz processes:
     * tar to stdout piped into the compressor.
     */
    fun create(
        format: Format, level: Int, password: String?, encryptNames: Boolean, volumeMb: Int,
        dir: File, names: List<String>, out: File, listFile: File, run: Run,
    ): Result {
        listFile.writeText(names.joinToString("\n"))
        val common = listOf("-bso0", "-scsUTF-8") + (if (volumeMb > 0) listOf("-v${volumeMb}m") else emptyList())
        val res = if (!format.isTar) {
            val args = mutableListOf("a", "-t${format.ext}", "-mx=$level", "-bsp1") + common
            val extra = when {
                password.isNullOrEmpty() -> emptyList()
                format == Format.SEVEN_Z -> pw(password) + (if (encryptNames) listOf("-mhe=on") else emptyList())
                else -> pw(password) + "-mem=AES256"
            }
            run(args + extra + out.path + "@${listFile.path}", run, dir)
        } else {
            val tar = run.start(process(listOf("a", "-ttar", "-so", "-an", "-bsp2", "-bso0", "-scsUTF-8", "@${listFile.path}"), dir).redirectErrorStream(false))
            val comp = run.start(process(listOf("a", if (format == Format.TAR_GZ) "-tgzip" else "-txz", "-mx=$level", "-si${out.name.removeSuffix(".gz").removeSuffix(".xz")}", "-bsp0") + common + out.path, null))
            tar.outputStream.close()
            val pump = thread { tar.inputStream.use { i -> comp.outputStream.use { o -> runCatching { i.copyTo(o, 1 shl 16) } } } }
            var compOut = ""
            val reader = thread { compOut = drain(comp.inputStream, Run()) }
            val tarOut = drain(tar.errorStream, run)
            val tarCode = tar.waitFor()
            pump.join()
            val compCode = comp.waitFor()
            reader.join()
            Result(maxOf(tarCode, compCode), tarOut + compOut, run.cancelled)
        }
        if (!res.ok) {
            // Only remove what this run produced: out itself or its volumes (out.001, out.002, ...).
            val vol = Regex(Regex.escape(out.name) + """\.\d{3}""")
            out.parentFile?.listFiles { f -> f.name == out.name || vol.matches(f.name) }?.forEach { it.delete() }
        }
        return res
    }
}
