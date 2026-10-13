package me.rerere.rikkahub.ui.pages.group

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject
import java.util.UUID

/** 独立任务群：只读取本群发言，不读取普通聊天和其他群的上下文。 */
@Serializable
private data class GroupLine(val sender: String, val text: String)

@Serializable
private data class GroupRoom(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val modelIds: List<String>,
    val lines: List<GroupLine> = emptyList(),
)

private val roomJson = Json { ignoreUnknownKeys = true }

@Composable
fun GroupRoomPage(
    settingsStore: SettingsStore = koinInject(),
    providerManager: ProviderManager = koinInject(),
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("task_group_rooms", Context.MODE_PRIVATE) }
    val rooms = remember {
        mutableStateListOf<GroupRoom>().apply {
            addAll(runCatching {
                roomJson.decodeFromString<List<GroupRoom>>(prefs.getString("rooms", "[]") ?: "[]")
            }.getOrDefault(emptyList()))
        }
    }
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val models = remember(settings.providers) {
        settings.providers.flatMap { it.models }.filter { it.type == ModelType.CHAT }.distinctBy { it.id }
    }
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var showRoomEditor by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(setOf<String>()) }
    var input by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(room: GroupRoom) {
        val index = rooms.indexOfFirst { it.id == room.id }
        if (index < 0) rooms.add(room) else rooms[index] = room
        prefs.edit().putString("rooms", roomJson.encodeToString(rooms.toList())).apply()
    }

    fun runRound(id: String, userText: String?) {
        if (job?.isActive == true) return
        val initial = rooms.firstOrNull { it.id == id } ?: return
        if (initial.modelIds.isEmpty()) return
        val first = if (userText.isNullOrBlank()) initial else initial.copy(
            lines = initial.lines + GroupLine("我", userText.trim())
        ).also(::save)
        input = ""
        error = null
        job = scope.launch {
            var current = first
            try {
            for (modelId in first.modelIds) {
                val model = models.firstOrNull { it.id.toString() == modelId } ?: continue
                val provider = model.findProvider(settings.providers) ?: continue
                try {
                    // 逐个传递本群最近的发言；每位模型收到同一群的完整发言顺序。
                    val transcript = current.lines.takeLast(24).joinToString("\n") { "${it.sender}：${it.text}" }
                    val reply = providerManager.getProviderByType(provider).generateText(
                        providerSetting = provider,
                        messages = listOf(
                            UIMessage.system("你正在任务讨论群“${current.title}”中。请回应群内任务与其他成员的意见。只输出自己的回复，不代替其他成员发言。"),
                            UIMessage.user("群聊记录：\n$transcript\n\n现在轮到你发言。")
                        ),
                        params = TextGenerationParams(model = model, reasoningLevel = ReasoningLevel.OFF),
                    ).choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
                    if (reply.isNotBlank()) {
                        current = current.copy(lines = current.lines + GroupLine(model.displayName.ifBlank { model.modelId }, reply))
                        save(current)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    error = "${model.displayName.ifBlank { model.modelId }}：${e.message ?: "请求失败"}"
                    break
                }
            }
            } finally {
                job = null
            }
        }
    }

    val selected = rooms.firstOrNull { it.id == selectedId }
    Scaffold(topBar = { TopAppBar(title = { Text(selected?.title ?: "任务群聊") }, navigationIcon = {
        if (selected == null) BackButton() else TextButton(onClick = { job?.cancel(); selectedId = null }) { Text("群列表") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (selected == null) {
                Button(onClick = { title = ""; checked = emptySet(); editingId = null; showRoomEditor = true }) { Text("新建群聊") }
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rooms, key = { it.id }) { room ->
                        Card(onClick = { selectedId = room.id }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(room.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${room.modelIds.size} 位模型 · ${room.lines.size} 条消息")
                                }
                                TextButton(onClick = {
                                    editingId = room.id; title = room.title; checked = room.modelIds.toSet(); showRoomEditor = true
                                }) { Text("成员") }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(selected.lines) { line ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(line.sender, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                Text(line.text)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (job?.isActive == true) {
                    Row { CircularProgressIndicator(); TextButton(onClick = { job?.cancel() }) { Text("停止") } }
                } else if (selected.lines.isNotEmpty()) {
                    TextButton(onClick = { runRound(selected.id, null) }) { Text("让成员再讨论一轮") }
                }
                OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(), label = { Text("输入任务或消息") })
                Button(onClick = { runRound(selected.id, input) }, enabled = input.isNotBlank() && job?.isActive != true) { Text("发送") }
            }
        }
    }

    if (showRoomEditor) {
        AlertDialog(onDismissRequest = { showRoomEditor = false }, title = { Text(if (editingId == null) "新建群聊" else "编辑群聊") },
            text = { Column {
                OutlinedTextField(title, { title = it }, label = { Text("群名称") })
                Text("选择参与模型（至少一个）")
                LazyColumn(Modifier.height(300.dp)) {
                    items(models, key = { it.id.toString() }) { model ->
                        val id = model.id.toString()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = id in checked, onCheckedChange = { enabled ->
                                checked = if (enabled) checked + id else checked - id
                            })
                            Text(model.displayName.ifBlank { model.modelId })
                        }
                    }
                }
            } },
            confirmButton = { TextButton(enabled = title.isNotBlank() && checked.isNotEmpty(), onClick = {
                val existing = rooms.firstOrNull { it.id == editingId }
                save((existing ?: GroupRoom(title = title.trim(), modelIds = emptyList())).copy(title = title.trim(), modelIds = checked.toList()))
                showRoomEditor = false
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showRoomEditor = false }) { Text("取消") } }
        )
    }
}
