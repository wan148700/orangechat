package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.shizuku.ShizukuDiagnostics
import me.rerere.rikkahub.ui.components.ui.CardGroup
import rikka.shizuku.Shizuku

@Composable
fun ShizukuConnectionCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(ShizukuDiagnostics.snapshot(context)) }
    var testResult by remember { mutableStateOf("尚未测试") }
    var busy by remember { mutableStateOf(false) }
    fun refresh() {
        val next = ShizukuDiagnostics.snapshot(context)
        if (next["connected"] != status["connected"] || next["authorized"] != status["authorized"]) {
            testResult = "连接或授权已变化，请重新测试"
        }
        status = next
    }
    DisposableEffect(context) {
        val received = Shizuku.OnBinderReceivedListener { scope.launch { refresh() } }
        val dead = Shizuku.OnBinderDeadListener { scope.launch { refresh() } }
        val permission = Shizuku.OnRequestPermissionResultListener { code, _ ->
            if (code == ShizukuDiagnostics.REQUEST_CODE) scope.launch { refresh() }
        }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        onDispose {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
            Shizuku.removeRequestPermissionResultListener(permission)
        }
    }
    LaunchedEffect(context) { while (true) { refresh(); delay(3000) } }
    val connected = status["connected"].toString() == "true"
    val authorized = status["authorized"].toString() == "true"
    CardGroup(title = { Text("Shizuku 连接") }, modifier = Modifier.padding(horizontal = 8.dp)) {
        item(
            headlineContent = { Text(if (connected) "服务已连接" else "服务未连接") },
            supportingContent = { Text("当前应用：${context.packageName}\n" +
                if (!connected) "请先启动 Shizuku，回到此页会自动刷新。" else if (!authorized) "尚未授权当前应用" else "当前应用已获授权；是否可用请看实际测试结果。") }
        )
        item(
            headlineContent = { Text("授权当前应用") },
            supportingContent = { Text("橘瓣和星语分别授权，与 Termux 的授权无关。") },
            trailingContent = {
                FilledTonalButton(enabled = connected && !authorized && !busy, onClick = {
                    runCatching { Shizuku.requestPermission(ShizukuDiagnostics.REQUEST_CODE) }
                        .onFailure { testResult = "授权请求失败：${it.message}" }
                }) { Text(if (authorized) "已授权" else "授权") }
            }
        )
        item(
            headlineContent = { Text("实际连接测试") },
            supportingContent = { Text(testResult) },
            trailingContent = {
                FilledTonalButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val result = ShizukuDiagnostics.test(context)
                            refresh()
                            testResult = when (result["status"]?.jsonPrimitive?.contentOrNull) {
                                "test_passed" -> {
                                    val uid = result["test"]?.jsonObject?.get("uid").toString()
                                    val mode = if (uid == "0") "Root 模式" else "ADB 模式"
                                    val time = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                                    "$time 测试通过，可以执行命令（$mode）"
                                }
                                "permission_required" -> "未授权，请先点授权。"
                                "disconnected" -> "连接已断开，请启动 Shizuku。"
                                else -> "测试失败：" + (result["error"]?.jsonPrimitive?.contentOrNull ?: result.toString())
                            }
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "测试中…" else "只读测试") }
            }
        )
        item(headlineContent = { Text("AI 命令执行") }, supportingContent = { Text("AI 可调用 shizuku_shell 执行你要求的命令，并收到输出和退出码。") })
        item(headlineContent = { Text("AI 连接自检") }, supportingContent = { Text("AI 可调用 shizuku_status 自检。检测仅读取身份信息；命令执行遵循现有工具确认设置。") })
    }
}
