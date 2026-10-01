package themeprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ESC = "\u001b"
private const val BEL = "\u0007"
private const val ST = "$ESC\\"

private fun scan(vararg chunks: String): TerminalReplies {
    val scanner = ReplyScanner()
    chunks.forEach { scanner.feed(it.toByteArray(Charsets.ISO_8859_1)) }
    return scanner.replies
}

class ReplyScannerTest {
    @Test
    fun `csi 996 reply 1 means dark`() {
        assertEquals(Theme.DARK, scan("$ESC[?997;1n").colorScheme)
    }

    @Test
    fun `csi 996 reply 2 means light`() {
        assertEquals(Theme.LIGHT, scan("$ESC[?997;2n").colorScheme)
    }

    @Test
    fun `other device status reports are not a color scheme`() {
        val replies = scan("$ESC[0n$ESC[?997;3n")
        assertNull(replies.colorScheme)
    }

    @Test
    fun `osc 11 with 4-digit channels terminated by ST`() {
        val bg = scan("$ESC]11;rgb:fafa/fafa/fafa$ST").background
        assertEquals(Rgb(0xfafa / 65535.0, 0xfafa / 65535.0, 0xfafa / 65535.0), bg)
        assertEquals("#fafafa", bg?.toHex())
    }

    @Test
    fun `osc 11 with 2-digit channels terminated by BEL`() {
        val bg = scan("$ESC]11;rgb:1e/1f/29$BEL").background
        assertEquals("#1e1f29", bg?.toHex())
    }

    @Test
    fun `osc 11 channels of mixed digit counts are each normalized by their own width`() {
        // f, ff, fff all mean "full"; 0 means "none".
        assertEquals(Rgb(1.0, 1.0, 0.0), scan("$ESC]11;rgb:f/ffff/0$ST").background)
    }

    @Test
    fun `osc for a different color slot is ignored`() {
        assertNull(scan("$ESC]10;rgb:ffff/ffff/ffff$ST").background)
    }

    @Test
    fun `malformed osc 11 color is ignored`() {
        assertNull(scan("$ESC]11;rgb:zz/00/00$BEL").background)
        assertNull(scan("$ESC]11;rgb:00/00$BEL").background)
        assertNull(scan("$ESC]11;rgb:00000/00/00$BEL").background)
    }

    @Test
    fun `da1 reply is detected as the sentinel`() {
        val replies = scan("$ESC[?62;22c")
        assertTrue(replies.sawDeviceAttributes)
    }

    @Test
    fun `sentinel is not seen before the da1 reply is complete`() {
        val scanner = ReplyScanner()
        scanner.feed("$ESC[?997;1n$ESC[?62;".toByteArray(Charsets.ISO_8859_1))
        assertFalse(scanner.replies.sawDeviceAttributes)
        scanner.feed("22c".toByteArray(Charsets.ISO_8859_1))
        assertTrue(scanner.replies.sawDeviceAttributes)
    }

    @Test
    fun `replies fragmented at every byte are reassembled`() {
        val stream = "$ESC[?997;2n$ESC]11;rgb:ffff/ffff/ffff$ST$ESC[?1;2c"
        val replies = scan(*stream.map { it.toString() }.toTypedArray())
        assertEquals(Theme.LIGHT, replies.colorScheme)
        assertEquals("#ffffff", replies.background?.toHex())
        assertTrue(replies.sawDeviceAttributes)
        assertEquals(3, replies.raw.size)
    }

    @Test
    fun `ST split between chunks still terminates the osc`() {
        val replies = scan("$ESC]11;rgb:00/00/00$ESC", "\\$ESC[?62c")
        assertEquals("#000000", replies.background?.toHex())
        assertTrue(replies.sawDeviceAttributes)
    }

    @Test
    fun `stray bytes such as typed keys around replies are skipped`() {
        val replies = scan("abc$ESC[?997;1nxyz$ESC[?62cq")
        assertEquals(Theme.DARK, replies.colorScheme)
        assertTrue(replies.sawDeviceAttributes)
    }
}
