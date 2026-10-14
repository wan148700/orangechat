package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.shizuku.ShizukuDiagnostics

fun shizukuStatusTool(context: Context) = Tool(
    name = "shizuku_status",
    description = "Read-only Shizuku connection diagnostic for THIS app. Checks live Binder, app authorization, and runs a fixed id test in a Shizuku UserService. Cannot change settings, request permission, run arbitrary commands, or control apps. A connected Binder alone does not mean the test passed. If disconnected, ask the user to start Shizuku; if permission_required, use Settings > System tools > Shizuku to authorize this app.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { listOf(UIMessagePart.Text(ShizukuDiagnostics.test(context).toString())) }
)
