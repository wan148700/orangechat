package me.rerere.rikkahub.ui.pages.group

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject
import java.util.UUID

/** 独立任务群：只读取本群发言，不读取普通聊天和其他群的上下文。 */
@Serializable
private data class GroupLine(
    val sender: String,
    val text: String,
    val id: String = UUID.randomUUID().toString(),
    val modelId: String? = null,
    val message: UIMessage? = null,
)

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
    val listState = rememberLazyListState()
    val hazeState = rememberHazeState()

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
            lines = initial.lines + GroupLine("我", userText.trim(), message = UIMessage.user(userText.trim()))
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
                        current = current.copy(lines = current.lines + GroupLine(
                            model.displayName.ifBlank { model.modelId }, reply,
                            modelId = model.id.toString(),
                            message = UIMessage.assistant(reply).copy(modelId = model.id),
                        ))
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
    LaunchedEffect(selectedId, selected?.lines?.size) {
        if (!selected?.lines.isNullOrEmpty()) listState.animateScrollToItem(selected!!.lines.lastIndex)
    }
    Box(Modifier.fillMaxSize()) {
    AssistantBackground(setting = settings)
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        title = { Column {
            Text(selected?.title ?: "任务群聊", style = MaterialTheme.typography.bodyMedium)
            selected?.let { Text("${it.modelIds.size} 位模型", style = MaterialTheme.typography.labelSmall) }
        } }, navigationIcon = {
        if (selected == null) BackButton() else TextButton(onClick = { job?.cancel(); selectedId = null }) { Text("群列表") }
    }, actions = {
        selected?.let { room -> TextButton(onClick = {
            editingId = room.id; title = room.title; checked = room.modelIds.toSet(); showRoomEditor = true
        }, enabled = job == null) { Text("成员") } }
    }) }, bottomBar = {
        selected?.let { room ->
            val display = settings.displaySetting
            val hasInputImage = display.inputBackgroundPath.isNotBlank()
            Column(Modifier.imePadding().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (room.lines.isNotEmpty() && job == null) {
                    TextButton(onClick = { runRound(room.id, null) }) { Text("继续讨论一轮") }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).then(
                        if (display.enableBlurEffect) Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.ultraThin(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) else Modifier
                    ),
                    shape = MaterialTheme.shapes.large,
                    color = if (hasInputImage || display.enableBlurEffect) Color.Transparent
                        else display.inputFieldColor?.toComposeColor() ?: MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Box {
                        if (hasInputImage) AsyncImage(
                            model = display.inputBackgroundPath, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()
                        )
                        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom) {
                            TextField(
                                value = input, onValueChange = { input = it },
                                modifier = Modifier.weight(1f), maxLines = 6,
                                placeholder = { Text("输入消息与成员讨论") },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                            )
                            FilledIconButton(
                                onClick = { if (job != null) job?.cancel() else runRound(room.id, input) },
                                enabled = job != null || input.isNotBlank(),
                            ) { Icon(if (job != null) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
                                contentDescription = if (job != null) "停止" else "发送") }
                        }
                    }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (selected == null) {
                Button(modifier = Modifier.padding(horizontal = 16.dp), onClick = { title = ""; checked = emptySet(); editingId = null; showRoomEditor = true }) { Text("新建群聊") }
                Spacer(Modifier.height(12.dp))
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                LazyColumn(
                    Modifier.weight(1f).hazeSource(state = hazeState), state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(selected.lines, key = { it.id }) { line ->
                        val model = models.firstOrNull { it.id.toString() == line.modelId }
                            ?: models.firstOrNull { it.displayName == line.sender || it.modelId == line.sender }
                        val message = remember(line) {
                            line.message ?: if (line.sender == "我") UIMessage.user(line.text) else UIMessage.assistant(line.text)
                        }
                        ChatMessage(
                            node = MessageNode.of(message), model = model,
                            showMessageActions = false,
                            onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {},
                        )
                    }
                    if (job != null) item {
                        Text("成员正在回复…", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
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
