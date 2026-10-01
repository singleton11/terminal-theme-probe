package themeprobe

private const val ESC = '\u001b'
private const val BEL = '\u0007'

/** What the terminal told us so far. Pure data, no IO. */
data class TerminalReplies(
    /** Answer to `CSI ? 996 n`, if any. */
    val colorScheme: Theme? = null,
    /** Answer to `OSC 11 ; ?`, if any. */
    val background: Rgb? = null,
    /** True once the DA1 reply (`ESC [ ? ... c`) arrived: no further replies will come. */
    val sawDeviceAttributes: Boolean = false,
    /** Every complete escape sequence received, verbatim, in arrival order. */
    val raw: List<String> = emptyList(),
)

/**
 * Incremental tokenizer for terminal replies. Bytes may arrive in arbitrary fragments; an incomplete
 * trailing sequence is kept until more bytes arrive. Bytes outside escape sequences (e.g. keys the
 * user typed) are dropped.
 */
class ReplyScanner {
    // ISO-8859-1 maps every byte to exactly one char, so offsets stay byte-exact.
    private val pending = StringBuilder()

    var replies = TerminalReplies()
        private set

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        pending.append(String(bytes, 0, length, Charsets.ISO_8859_1))
        drain()
    }

    private fun drain() {
        var pos = 0 // Everything before pos has been consumed.
        while (pos < pending.length) {
            val start = pending.indexOf(ESC, pos)
            if (start < 0) {
                pos = pending.length // Only stray bytes left.
                break
            }
            val end = sequenceEnd(start)
            if (end == null) {
                pos = start // Keep the incomplete sequence for the next feed.
                break
            }
            accept(pending.substring(start, end))
            pos = end
        }
        pending.delete(0, pos)
    }

    /** Exclusive end index of the sequence starting at [start], or null if it is still incomplete. */
    private fun sequenceEnd(start: Int): Int? {
        if (start + 1 >= pending.length) return null
        return when (pending[start + 1]) {
            '[' -> csiEnd(start + 2)
            ']' -> oscEnd(start + 2)
            else -> start + 2 // Some other two-byte escape; not ours, skip it.
        }
    }

    private fun csiEnd(from: Int): Int? {
        for (i in from until pending.length) {
            if (pending[i].code in 0x40..0x7e) return i + 1 // Final byte.
        }
        return null
    }

    private fun oscEnd(from: Int): Int? {
        for (i in from until pending.length) {
            when (pending[i]) {
                BEL -> return i + 1
                ESC -> return if (i + 1 < pending.length) i + 2 else null // ESC \ (ST)
            }
        }
        return null
    }

    private fun accept(seq: String) {
        var r = replies.copy(raw = replies.raw + seq)
        when {
            seq.startsWith("$ESC[") -> {
                val body = seq.substring(2)
                when {
                    body == "?997;1n" -> r = r.copy(colorScheme = Theme.DARK)
                    body == "?997;2n" -> r = r.copy(colorScheme = Theme.LIGHT)
                    body.startsWith("?") && body.endsWith("c") -> r = r.copy(sawDeviceAttributes = true)
                }
            }
            seq.startsWith("$ESC]11;") -> {
                val payload = seq.removePrefix("$ESC]11;").removeSuffix("$ESC\\").removeSuffix("$BEL")
                parseXColor(payload)?.let { r = r.copy(background = it) }
            }
        }
        replies = r
    }
}

/** Parses an XParseColor `rgb:R/G/B` spec (1-4 hex digits per channel) into 0..1 channels. */
internal fun parseXColor(spec: String): Rgb? {
    val channels = spec.removePrefix("rgb:").takeIf { it != spec }?.split('/') ?: return null
    if (channels.size != 3) return null
    val values = channels.map { hex ->
        if (hex.length !in 1..4) return null
        val v = hex.toIntOrNull(16) ?: return null
        val max = (1 shl (4 * hex.length)) - 1
        v.toDouble() / max
    }
    return Rgb(values[0], values[1], values[2])
}
