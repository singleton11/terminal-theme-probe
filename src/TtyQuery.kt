package themeprobe

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val TTY = File("/dev/tty")

/** `CSI ? 996 n` + `OSC 11 ; ? ST` + `CSI c` (DA1 sentinel, always answered, so it goes last). */
private const val QUERY = "\u001b[?996n\u001b]11;?\u001b\\\u001b[c"

data class QueryResult(val replies: TerminalReplies, val elapsedMillis: Long, val timedOut: Boolean)

/**
 * Asks the controlling terminal once over `/dev/tty`. Returns null when there is no usable tty.
 * The tty is switched to raw/no-echo for the duration and always restored.
 */
fun queryTerminal(timeoutMillis: Long = 500): QueryResult? {
    val input: FileInputStream
    val output: FileOutputStream
    try {
        input = FileInputStream(TTY)
        output = FileOutputStream(TTY)
    } catch (_: IOException) {
        return null // ENXIO "Device not configured": no controlling terminal.
    }
    val saved = stty("-g")?.trim() ?: return null

    val restore = thread(start = false) { stty(saved) }
    Runtime.getRuntime().addShutdownHook(restore)
    try {
        stty("raw", "-echo") ?: return null
        return exchange(input, output, timeoutMillis)
    } finally {
        stty(saved)
        runCatching { Runtime.getRuntime().removeShutdownHook(restore) }
    }
}

private fun exchange(input: FileInputStream, output: FileOutputStream, timeoutMillis: Long): QueryResult {
    // FileInputStream.read blocks with no timeout, so a daemon thread reads and hands chunks over a queue;
    // the main thread polls with a deadline. If the terminal never answers, the reader just stays blocked
    // until the JVM exits. An empty chunk signals EOF/error.
    val chunks = LinkedBlockingQueue<ByteArray>()
    thread(isDaemon = true, name = "tty-reader") {
        val buf = ByteArray(256)
        while (true) {
            val n = try { input.read(buf) } catch (_: IOException) { -1 }
            if (n < 0) { chunks.put(ByteArray(0)); break }
            chunks.put(buf.copyOf(n))
        }
    }

    val start = System.nanoTime()
    output.write(QUERY.toByteArray(Charsets.US_ASCII))
    output.flush()

    val scanner = ReplyScanner()
    val deadline = start + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (!scanner.replies.sawDeviceAttributes) {
        val chunk = chunks.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
        if (chunk == null || chunk.isEmpty()) break // Timed out, or the tty went away.
        scanner.feed(chunk)
    }
    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    return QueryResult(scanner.replies, elapsed, timedOut = !scanner.replies.sawDeviceAttributes)
}

/** Runs `stty args...` with /dev/tty as its stdin (stty acts on stdin). Returns stdout, or null on failure. */
private fun stty(vararg args: String): String? = try {
    val process = ProcessBuilder("stty", *args)
        .redirectInput(TTY)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val out = process.inputStream.bufferedReader().readText()
    if (process.waitFor() == 0) out else null
} catch (_: IOException) {
    null
}
