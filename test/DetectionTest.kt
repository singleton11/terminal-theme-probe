package themeprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DetectionTest {
    private val darkBg = Rgb(0.1, 0.1, 0.12)
    private val lightBg = Rgb(0.98, 0.98, 0.98)

    @Test
    fun `luminance threshold classifies backgrounds`() {
        assertEquals(Theme.DARK, darkBg.theme())
        assertEquals(Theme.LIGHT, lightBg.theme())
        // Pure blue is perceptually dark even though one channel is maxed.
        assertEquals(Theme.DARK, Rgb(0.0, 0.0, 1.0).theme())
        // Pure green is perceptually light.
        assertEquals(Theme.LIGHT, Rgb(0.0, 1.0, 0.0).theme())
    }

    @Test
    fun `csi 996 answer wins over osc 11 and COLORFGBG`() {
        val replies = TerminalReplies(colorScheme = Theme.DARK, background = lightBg)
        assertEquals(Detection(Theme.DARK, Method.CSI_996), detect(replies, colorFgBg = "0;15"))
    }

    @Test
    fun `osc 11 is used when 996 is unanswered`() {
        val replies = TerminalReplies(background = lightBg)
        assertEquals(Detection(Theme.LIGHT, Method.OSC_11, lightBg), detect(replies, colorFgBg = "15;0"))
    }

    @Test
    fun `COLORFGBG is the fallback when the terminal answered nothing`() {
        assertEquals(Detection(Theme.DARK, Method.COLORFGBG), detect(TerminalReplies(), "15;0"))
        assertEquals(Detection(Theme.LIGHT, Method.COLORFGBG), detect(TerminalReplies(), "0;15"))
        // rxvt style "fg;default;bg": the background is the last field.
        assertEquals(Detection(Theme.LIGHT, Method.COLORFGBG), detect(TerminalReplies(), "0;default;7"))
    }

    @Test
    fun `COLORFGBG background classes`() {
        (0..6).plus(8).forEach { assertEquals(Theme.DARK, themeFromColorFgBg("15;$it"), "bg=$it") }
        listOf(7).plus(9..15).forEach { assertEquals(Theme.LIGHT, themeFromColorFgBg("0;$it"), "bg=$it") }
        assertNull(themeFromColorFgBg("0;16"))
        assertNull(themeFromColorFgBg("garbage"))
        assertNull(themeFromColorFgBg(""))
    }

    @Test
    fun `nothing known yields null`() {
        assertNull(detect(TerminalReplies(), colorFgBg = null))
    }

    @Test
    fun `output lines`() {
        assertEquals("dark (via csi-996)", Detection(Theme.DARK, Method.CSI_996).describe())
        assertEquals(
            "light (via osc-11, bg=#fafafa)",
            Detection(Theme.LIGHT, Method.OSC_11, Rgb(0xfa / 255.0, 0xfa / 255.0, 0xfa / 255.0)).describe(),
        )
        assertEquals("dark (via colorfgbg)", Detection(Theme.DARK, Method.COLORFGBG).describe())
    }

    @Test
    fun `control bytes are escaped for verbose output`() {
        assertEquals("\\e]11;rgb:0/0/0\\a", escapeControl("\u001b]11;rgb:0/0/0\u0007"))
        assertEquals("\\x01\\e[?62c", escapeControl("\u0001\u001b[?62c"))
    }
}
