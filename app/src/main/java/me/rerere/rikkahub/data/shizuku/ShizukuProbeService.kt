package me.rerere.rikkahub.data.shizuku

import android.os.Process
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Privileged service: fixed probe and explicitly approved shell execution. */
class ShizukuProbeService : IShizukuProbe.Stub() {
    override fun probe(): String = execute("id", 3, 2048)

    override fun execute(command: String, timeoutSeconds: Int, maxOutputChars: Int): String {
        require(command.isNotBlank() && command.length <= 16000 && !command.contains('\u0000')) { "Invalid command" }
        require(timeoutSeconds in 1..60) { "timeout_seconds must be 1..60" }
        require(maxOutputChars in 256..16000) { "max_output_chars must be 256..16000" }
        val result = ShellCommandRunner.run(
            listOf("/system/bin/toybox", "timeout", "-k", "1", timeoutSeconds.toString(), "/system/bin/sh", "-c", command),
            timeoutSeconds, maxOutputChars,
        )
        return buildJsonObject {
            put("uid", Process.myUid())
            put("success", result.exitCode == 0 && !result.timedOut)
            result.exitCode?.let { put("exit_code", it) }
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("stdout_truncated", result.stdoutTruncated)
            put("stderr_truncated", result.stderrTruncated)
            put("timed_out", result.timedOut)
            put("duration_ms", result.durationMs)
        }.toString()
    }

    override fun destroy() { kotlin.system.exitProcess(0) }
}
