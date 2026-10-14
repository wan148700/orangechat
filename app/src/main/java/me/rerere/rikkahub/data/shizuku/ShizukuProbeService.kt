package me.rerere.rikkahub.data.shizuku

import android.os.Process
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A fixed, read-only test. No arbitrary command or user input is accepted. */
class ShizukuProbeService : IShizukuProbe.Stub() {
    override fun probe(): String {
        val process = ProcessBuilder("/system/bin/id").redirectErrorStream(true).start()
        return try {
            val completed = process.waitFor(3, TimeUnit.SECONDS)
            buildJsonObject {
                put("uid", Process.myUid())
                put("command", "id")
                put("success", completed && process.exitValue() == 0 && Process.myUid() in setOf(0, 2000))
                if (completed) {
                    put("exit_code", process.exitValue())
                    put("output", process.inputStream.bufferedReader().use { it.readText() }.take(2048))
                } else put("error", "command_timeout")
            }.toString()
        } finally {
            process.destroy()
        }
    }

    override fun destroy() { kotlin.system.exitProcess(0) }
}
