package themeprobe

import kotlin.math.roundToInt

enum class Theme(val label: String) { LIGHT("light"), DARK("dark") }

enum class Method(val label: String) { CSI_996("csi-996"), OSC_11("osc-11"), COLORFGBG("colorfgbg") }

/** A color with channels normalized to 0..1. */
data class Rgb(val r: Double, val g: Double, val b: Double) {
    val luminance: Double get() = 0.2126 * r + 0.7152 * g + 0.0722 * b

    fun theme(): Theme = if (luminance > 0.5) Theme.LIGHT else Theme.DARK

    fun toHex(): String = "#" + listOf(r, g, b).joinToString("") {
        (it * 255).roundToInt().toString(16).padStart(2, '0')
    }
}

data class Detection(val theme: Theme, val method: Method, val background: Rgb? = null) {
    fun describe(): String = buildString {
        append(theme.label).append(" (via ").append(method.label)
        background?.let { append(", bg=").append(it.toHex()) }
        append(")")
    }
}

/** Preference: CSI 996 answer > OSC 11 luminance > `COLORFGBG` > unknown (null). */
fun detect(replies: TerminalReplies, colorFgBg: String?): Detection? {
    replies.colorScheme?.let { return Detection(it, Method.CSI_996) }
    replies.background?.let { return Detection(it.theme(), Method.OSC_11, it) }
    colorFgBg?.let(::themeFromColorFgBg)?.let { return Detection(it, Method.COLORFGBG) }
    return null
}

/** `COLORFGBG` is `fg;bg` (rxvt may emit `fg;default;bg`); the background is the last field. */
fun themeFromColorFgBg(value: String): Theme? =
    when (value.substringAfterLast(';').toIntOrNull()) {
        in 0..6, 8 -> Theme.DARK
        7, in 9..15 -> Theme.LIGHT
        else -> null
    }

/** Makes control bytes visible: ESC -> `\e`, BEL -> `\a`, others -> `\xNN`. */
fun escapeControl(s: String): String = buildString {
    for (c in s) when {
        c == '\u001b' -> append("\\e")
        c == '\u0007' -> append("\\a")
        c.code < 0x20 || c.code == 0x7f -> append("\\x%02x".format(c.code))
        else -> append(c)
    }
}
