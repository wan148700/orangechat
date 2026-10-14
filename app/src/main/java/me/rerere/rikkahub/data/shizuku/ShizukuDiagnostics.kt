package me.rerere.rikkahub.data.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

object ShizukuDiagnostics {
    const val REQUEST_CODE = 10002
    private val testMutex = Mutex()

    fun snapshot(context: Context): JsonObject = buildJsonObject {
        put("package_name", context.packageName)
        put("checked_at_ms", System.currentTimeMillis())
        try {
            val connected = Shizuku.pingBinder()
            put("connected", connected)
            val permitted = connected && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            put("authorized", permitted)
            if (permitted) put("server_uid", Shizuku.getUid())
            put("status", if (!connected) "disconnected" else if (!permitted) "permission_required" else "ready_for_test")
        } catch (e: Exception) {
            put("connected", false)
            put("authorized", false)
            put("status", "connection_error")
            put("error", e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun test(context: Context): JsonObject = invoke(context, true) { it.probe() }

    suspend fun execute(context: Context, command: String, timeoutSeconds: Int, maxOutputChars: Int): JsonObject =
        invoke(context, false) { it.execute(command, timeoutSeconds, maxOutputChars) }

    private suspend fun invoke(context: Context, probe: Boolean, operation: (IShizukuProbe) -> String): JsonObject = testMutex.withLock {
        withContext(Dispatchers.IO) {
            val before = snapshot(context)
            if (before["status"].toString() != "\"ready_for_test\"") return@withContext before
            val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuProbeService::class.java))
                .daemon(false).processNameSuffix("shizuku_probe").version(2)
            var connection: ServiceConnection? = null
            var invocationStarted = false
            try {
                val service = withTimeoutOrNull(5000) {
                    suspendCancellableCoroutine<IShizukuProbe?> { continuation ->
                        val callback = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                                if (continuation.isActive) continuation.resume(IShizukuProbe.Stub.asInterface(binder))
                            }
                            override fun onServiceDisconnected(name: ComponentName) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                        connection = callback
                        Shizuku.bindUserService(args, callback)
                    }
                }
                if (service == null) buildJsonObject {
                    before.forEach { (key, value) -> put(key, value) }
                    put("status", "test_failed")
                    put("error", "service_connection_timeout_or_disconnected")
                } else buildJsonObject {
                    snapshot(context).forEach { (key, value) -> put(key, value) }
                    invocationStarted = true
                    val result = Json.parseToJsonElement(operation(service)) as JsonObject
                    put(if (probe) "test" else "execution", result)
                    put("status", if (result["success"].toString() == "true") { if (probe) "test_passed" else "command_completed" } else { if (probe) "test_failed" else "command_failed" })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                buildJsonObject {
                    snapshot(context).forEach { (key, value) -> put(key, value) }
                    put("status", if (probe) "test_failed" else if (invocationStarted) "command_result_unknown" else "connection_failed")
                    put("may_have_executed", !probe && invocationStarted)
                    put("error", e.message ?: e.javaClass.simpleName)
                }
            } finally {
                connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
            }
        }
    }
}
