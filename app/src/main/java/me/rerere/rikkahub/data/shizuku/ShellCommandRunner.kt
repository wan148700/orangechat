package me.rerere.rikkahub.data.shizuku

import java.io.InputStream
import java.util.concurrent.TimeUnit

internal data class ShellExecutionResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val timedOut: Boolean,
    val durationMs: Long,
)

/** Drain both streams concurrently even after the retained output limit is reached. */
internal object ShellCommandRunner {
    private class Capture(private val limit: Int) {
        private val text = StringBuilder()
        var truncated = false
            private set
        fun drain(stream: InputStream) {
            try {
                stream.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(this) {
                            val keep = minOf(count, (limit - text.length).coerceAtLeast(0))
                            if (keep > 0) text.append(buffer, 0, keep)
                            if (keep < count) truncated = true
                        }
                    }
                }
            } catch (_: java.io.IOException) { /* Process cleanup closes pipes. */ }
        }
        fun snapshot(): Pair<String, Boolean> = synchronized(this) { text.toString() to truncated }
    }

    fun run(argv: List<String>, timeoutSeconds: Int, maxOutputChars: Int): ShellExecutionResult {
        val start = System.nanoTime()
        val process = ProcessBuilder(argv).directory(java.io.File("/")).start()
        val out = Capture(maxOutputChars)
        val err = Capture(maxOutputChars)
        val outThread = Thread({ out.drain(process.inputStream) }, "Shizuku-stdout").apply { isDaemon = true }
        val errThread = Thread({ err.drain(process.errorStream) }, "Shizuku-stderr").apply { isDaemon = true }
        try {
            process.outputStream.close()
            outThread.start()
            errThread.start()
            val exited = process.waitFor(timeoutSeconds.toLong() + 2, TimeUnit.SECONDS)
            if (!exited) process.destroyForcibly()
            outThread.join(500)
            errThread.join(500)
            val code = if (exited) process.exitValue() else null
            val stdout = out.snapshot()
            val stderr = err.snapshot()
            return ShellExecutionResult(
                code, stdout.first, stderr.first, stdout.second, stderr.second,
                !exited || code == 124 || code == 137,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
            )
        } finally {
            process.destroy()
        }
    }
}
