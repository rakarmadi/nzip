package app.nzip

import org.junit.Assert.assertEquals
import org.junit.Test

class SevenZipTest {
    // Trimmed real `7zz l -slt` output; entries are separated only by blank lines.
    private val listing = """

7-Zip (z) 26.04 (x64) : Copyright (c) 1999-2026 Igor Pavlov : 2026-10-05

Listing archive: plain.zip

--
Path = plain.zip
Type = zip
Physical Size = 398

----------
Path = a.txt
Folder = -
Size = 6
Modified = 2026-10-12 11:30:55.3006435
Attributes =  -rw-r--r--
Encrypted = -
Method = Store

Path = d
Folder = +
Size = 0
Attributes = D drwxr-xr-x
Encrypted = -
Method = Store

Path = d/a = b.txt
Folder = -
Size = 6
Encrypted = +
Method = Deflate

"""

    @Test fun parsesEntriesFromConsoleStream() {
        // Progress noise interleaved with backspaces must not swallow blank separator lines.
        val raw = "  0%\b\b\b\b    \b\b\b\b" + listing
        val progress = mutableListOf<Int>()
        val text = SevenZip.drain(raw.byteInputStream(), SevenZip.Run { p, _ -> progress += p })
        val l = SevenZip.parseListing(text)

        assertEquals(listOf(0), progress)
        assertEquals("zip", l.type)
        assertEquals(398L, l.physicalSize)
        assertEquals(listOf("a.txt", "d", "d/a = b.txt"), l.entries.map { it.path })
        assertEquals(listOf(false, true, false), l.entries.map { it.isDir })
        assertEquals("Store Deflate", l.method)
        assertEquals(true, l.encrypted)
    }
}
