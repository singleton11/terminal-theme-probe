import themeprobe.detect
import themeprobe.escapeControl
import themeprobe.queryTerminal
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val verbose = "--verbose" in args || "-v" in args

    val result = queryTerminal()
    if (result == null) {
        println("unknown (no tty)")
        exitProcess(2)
    }

    if (verbose) {
        // stderr, so stdout stays a single parseable line.
        result.replies.raw.forEach { System.err.println("reply: ${escapeControl(it)}") }
        val end = if (result.timedOut) "timed out, no DA1 reply" else "DA1 sentinel received"
        System.err.println("after ${result.elapsedMillis} ms: $end")
        System.err.println("COLORFGBG=${System.getenv("COLORFGBG") ?: "(unset)"}")
    }

    val detection = detect(result.replies, System.getenv("COLORFGBG"))
    println(detection?.describe() ?: "unknown")
    exitProcess(if (detection != null) 0 else 1)
}
