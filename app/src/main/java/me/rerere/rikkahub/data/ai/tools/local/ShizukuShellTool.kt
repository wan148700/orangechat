package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.shizuku.ShizukuDiagnostics

fun shizukuShellTool(context: Context) = Tool(
    name = "shizuku_shell",
    needsApproval = true,
    description = "Execute an Android shell command through THIS app's authorized Shizuku UserService. Returns stdout, stderr, exit_code, timed_out and truncation flags. Commands can inspect shared-storage files, installed apps and system state or perform user-requested changes. Runs as ADB shell (uid 2000), or root only if Shizuku actually runs as root; ADB shell cannot read other apps' private data, including Termux home. Working directory is /. This is a one-shot command, not a persistent terminal or a root bypass. Use shizuku_status to diagnose connection/permission failures. Do not repeat a state-changing command blindly after a connection error: it may already have executed. Shell quoting applies; never print credentials unnecessarily. Default timeout 15 seconds, max 60; default retained output 8000 characters per stream, max 16000. Application tool confirmation applies before execution.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            putJsonObject("command") { put("type", "string"); put("description", "Android sh command. Up to 16000 characters.") }
            putJsonObject("timeout_seconds") { put("type", "integer"); put("minimum", 1); put("maximum", 60); put("description", "Execution timeout; default 15.") }
            putJsonObject("max_output_chars") { put("type", "integer"); put("minimum", 256); put("maximum", 16000); put("description", "Retained characters per stream; default 8000. Excess output is drained and flagged.") }
        }, required = listOf("command"))
    },
    execute = { input ->
        val args = input.jsonObject
        val command = args["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val timeout = args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 15
        val outputLimit = args["max_output_chars"]?.jsonPrimitive?.intOrNull ?: 8000
        val result = if (command.isBlank() || command.length > 16000 || command.contains('\u0000') || timeout !in 1..60 || outputLimit !in 256..16000) {
            buildJsonObject { put("status", "invalid_arguments"); put("error", "Provide a non-empty command, timeout_seconds 1..60 and max_output_chars 256..16000.") }
        } else ShizukuDiagnostics.execute(context, command, timeout, outputLimit)
        listOf(UIMessagePart.Text(result.toString()))
    }
)
