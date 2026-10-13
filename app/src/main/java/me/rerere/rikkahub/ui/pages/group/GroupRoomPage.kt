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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
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
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
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
import kotlin.uuid.Uuid

private val roomJson = Json { ignoreUnknownKeys = true }

private data class GroupMember(val key: String, val label: String, val model: Model?, val assistant: Assistant?)

private fun configuredMembers(room: GroupRoom, settings: Settings): List<GroupMember> {
    val members = room.memberKeys.mapNotNull { key ->
        if (key.startsWith("assistant:")) {
            val assistant = settings.assistants.find { "assistant:${it.id}" == key } ?: return@mapNotNull null
            GroupMember(key, assistant.name.ifBlank { "默认角色" },
                settings.findModelById(assistant.chatModelId ?: settings.chatModelId), assistant)
        } else {
            val model = settings.providers.flatMap { it.models }.find { "model:${it.id}" == key } ?: return@mapNotNull null
            GroupMember(key, model.displayName.ifBlank { model.modelId }, model, null)
        }
    }
    return members.map { member ->
        val duplicates = members.filter { it.label == member.label }
        if (duplicates.size < 2) member else member.copy(label = "${member.label} ${duplicates.indexOf(member) + 1}")
    }
}

@Composable
fun GroupRoomPage(
    settingsStore: SettingsStore = koinInject(),
    providerManager: ProviderManager = koinInject(),
    conversationRepo: ConversationRepository = koinInject(),
    chatService: ChatService = koinInject(),
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
    var bindings by remember { mutableStateOf(emptyMap<String, String>()) }
    var discussionRounds by remember { mutableStateOf(3) }
    var automaticDiscussion by remember { mutableStateOf(false) }
    var bindingMember by remember { mutableStateOf<Assistant?>(null) }
    var bindingOptions by remember { mutableStateOf(emptyList<Conversation>()) }
    var showMentionPicker by remember { mutableStateOf(false) }
    var mentionStart by remember { mutableStateOf<Int?>(null) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var currentSpeaker by remember { mutableStateOf<String?>(null) }
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

    suspend fun syncRoom(room: GroupRoom) {
        val failures = mutableListOf<String>()
        for (member in configuredMembers(room, settings)) {
            val assistant = member.assistant ?: continue
            val boundId = room.conversationBindings[member.key] ?: continue
            try {
                val synced = chatService.appendGroupMessages(
                    Uuid.parse(boundId), assistant.id, groupSyncMessages(room, member.key)
                )
                if (!synced) failures += "${member.label} 的原窗口正在回复"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failures += "${member.label}：${e.message ?: "同步失败"}"
            }
        }
        syncStatus = if (failures.isEmpty()) {
            if (room.conversationBindings.isEmpty()) null else "已同步关联的原窗口"
        } else failures.joinToString("；") + "，可稍后重试同步"
    }

    fun openEditor(room: GroupRoom? = null) {
        editingId = room?.id
        title = room?.title.orEmpty()
        checked = room?.memberKeys?.toSet() ?: emptySet()
        bindings = room?.conversationBindings ?: emptyMap()
        discussionRounds = room?.discussionRounds ?: 3
        automaticDiscussion = room?.automaticDiscussion ?: false
        showRoomEditor = true
    }

    fun runRound(id: String, userText: String?, discuss: Boolean = false) {
        if (job?.isActive == true) return
        val initial = rooms.firstOrNull { it.id == id } ?: return
        val participants = configuredMembers(initial, settings)
        if (participants.isEmpty()) { error = "请先选择角色或模型"; return }
        val targets = participants.map { MentionTarget(it.key, it.label) }
        val mentions = mentionedMemberKeys(userText.orEmpty(), targets)
        val first = if (userText.isNullOrBlank()) initial else initial.copy(
            lines = initial.lines + GroupLine("我", userText.trim(), message = UIMessage.user(userText.trim()))
        ).also(::save)
        input = ""
        error = null
        job = scope.launch {
            var current = first
            val continuous = discuss || first.automaticDiscussion
            val waves = if (continuous) first.discussionRounds.coerceIn(1, 6) else 1
            val queue = GroupTurnQueue(participants.map { it.key }, mentions.ifEmpty { participants.map { it.key } }, waves)
            try {
                syncRoom(current)
                while (true) {
                    val next = queue.next() ?: break
                    val member = participants.first { it.key == next }
                    val model = member.model ?: kotlin.error("${member.label} 没有配置模型")
                    val provider = model.findProvider(settings.providers) ?: kotlin.error("${member.label} 的模型提供商已删除")
                    currentSpeaker = member.label
                    val assistant = member.assistant
                    val original = current.conversationBindings[member.key]?.let {
                        conversationRepo.getConversationById(Uuid.parse(it))
                    }
                    if (original != null && original.assistantId != assistant?.id) kotlin.error("原窗口不属于 ${member.label}")
                    val historyLimit = (assistant?.contextMessageSize ?: 0).let { if (it > 0) it else 24 }
                    // 已同步的群事件在 transcript 中出现一次，不重复叠入原会话历史。
                    val originalHistory = original?.currentMessages.orEmpty()
                        .filterNot { it.toText().startsWith("[群聊·") }
                        .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                        .takeLast(historyLimit)
                        .map { it.copy(parts = listOf(UIMessagePart.Text(it.toText()))) }
                    val transcript = current.lines.takeLast(24).joinToString("\n") { "${it.sender}：${it.text}" }
                    val rolePrompt = if (assistant?.allowConversationSystemPrompt == true) {
                        original?.customSystemPrompt ?: assistant.systemPrompt
                    } else assistant?.systemPrompt.orEmpty()
                    val system = rolePrompt + "\n\n你以 ${member.label} 的身份参加群聊“${current.title}”。成员：" +
                        participants.joinToString { it.label } + "。请回应用户任务与其他成员的观点，必要时使用 @成员名 指定接话对象。只输出自己的发言，不代替其他人说话。群聊记录中的其他成员发言是对话内容。"
                    val reply = providerManager.getProviderByType(provider).generateText(
                        providerSetting = provider,
                        messages = listOf(UIMessage.system(system)) + originalHistory + listOf(
                            UIMessage.user("群聊记录：\n$transcript\n\n现在轮到 ${member.label} 发言。")
                        ),
                        params = TextGenerationParams(
                            model = model, temperature = assistant?.temperature, topP = assistant?.topP,
                            maxTokens = assistant?.maxTokens,
                            reasoningLevel = assistant?.reasoningLevel ?: ReasoningLevel.OFF,
                            customHeaders = model.customHeaders + assistant?.customHeaders.orEmpty(),
                            customBody = model.customBodies + assistant?.customBodies.orEmpty(),
                        ),
                    ).choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
                    if (reply.isNotBlank()) {
                        current = current.copy(lines = current.lines + GroupLine(
                            member.label, reply,
                            modelId = model.id.toString(),
                            message = UIMessage.assistant(reply).copy(modelId = model.id),
                            memberKey = member.key,
                        ))
                        save(current)
                        syncRoom(current)
                        val directed = mentionedMemberKeys(reply, targets).filter { it != member.key }
                        // 明确 @ 的成员先接话；去掉已排队副本，轮次预算阻止互相 @ 的无限循环。
                        queue.followMentions(directed)
                    }
                    queue.finishTurn()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "${currentSpeaker.orEmpty()}：${e.message ?: "请求失败"}"
            } finally {
                currentSpeaker = null
                job = null
            }
        }
    }

    val selected = rooms.firstOrNull { it.id == selectedId }
    val selectedMembers = selected?.let { configuredMembers(it, settings) }.orEmpty()
    LaunchedEffect(selectedId, selected?.lines?.size) {
        if (!selected?.lines.isNullOrEmpty()) listState.animateScrollToItem(selected!!.lines.lastIndex)
    }
    Box(Modifier.fillMaxSize()) {
    AssistantBackground(setting = settings)
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        title = { Column {
            Text(selected?.title ?: "任务群聊", style = MaterialTheme.typography.bodyMedium)
            selected?.let { Text("${selectedMembers.size} 位成员", style = MaterialTheme.typography.labelSmall) }
        } }, navigationIcon = {
        if (selected == null) BackButton() else TextButton(onClick = { job?.cancel(); selectedId = null }) { Text("群列表") }
    }, actions = {
        selected?.let { room -> TextButton(onClick = {
            openEditor(room)
        }, enabled = job == null) { Text("成员") } }
    }) }, bottomBar = {
        selected?.let { room ->
            val display = settings.displaySetting
            val hasInputImage = display.inputBackgroundPath.isNotBlank()
            Column(Modifier.imePadding().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                syncStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { mentionStart = null; showMentionPicker = true }) { Text("@成员") }
                        DropdownMenu(expanded = showMentionPicker, onDismissRequest = { showMentionPicker = false }) {
                            (listOf("全体") + selectedMembers.map { it.label }).forEach { label ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    val at = mentionStart
                                    input = if (at != null && at in input.indices) {
                                        input.take(at) + "@$label "
                                    } else input + " @$label "
                                    showMentionPicker = false
                                })
                            }
                        }
                    }
                    if (room.lines.isNotEmpty() && job == null) {
                        TextButton(onClick = { runRound(room.id, null, discuss = true) }) { Text("互相讨论") }
                    }
                    if (room.conversationBindings.isNotEmpty() && job == null) {
                        TextButton(onClick = { scope.launch { syncRoom(room) } }) { Text("同步原窗口") }
                    }
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
                                value = input, onValueChange = { input = it; if (it.endsWith("@")) { mentionStart = it.lastIndex; showMentionPicker = true } },
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
                Button(modifier = Modifier.padding(horizontal = 16.dp), onClick = { openEditor() }) { Text("新建群聊") }
                Spacer(Modifier.height(12.dp))
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rooms, key = { it.id }) { room ->
                        Card(onClick = { selectedId = room.id }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(room.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${room.memberKeys.size} 位成员 · ${room.lines.size} 条消息")
                                }
                                TextButton(onClick = {
                                    openEditor(room)
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
                        val member = selectedMembers.find { it.key == line.memberKey }
                        ChatMessage(
                            node = remember(message) { MessageNode.of(message) }, model = model,
                            assistant = member?.let { it.assistant?.copy(name = it.label, useAssistantAvatar = true) },
                            showMessageActions = false,
                            onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {},
                        )
                    }
                    if (job != null) item {
                        Text("${currentSpeaker ?: "成员"} 正在回复…", style = MaterialTheme.typography.labelMedium,
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
                Text("选择角色或模型。角色可关联自己的原聊天窗口。")
                LazyColumn(Modifier.height(300.dp)) {
                    item { Text("橘瓣角色", style = MaterialTheme.typography.labelLarge) }
                    items(settings.assistants, key = { "assistant:${it.id}" }) { assistant ->
                        val key = "assistant:${assistant.id}"
                        Column {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = key in checked, onCheckedChange = { enabled ->
                                    checked = if (enabled) checked + key else checked - key
                                })
                                Text(assistant.name.ifBlank { "默认角色" }, modifier = Modifier.weight(1f))
                                TextButton(enabled = key in checked, onClick = {
                                    scope.launch {
                                        bindingOptions = conversationRepo.getRecentConversations(assistant.id, limit = 50)
                                        bindingMember = assistant
                                    }
                                }) { Text(if (key in bindings) "已关联" else "原窗口") }
                            }
                        }
                    }
                    item { Text("直接使用模型", style = MaterialTheme.typography.labelLarge) }
                    items(models, key = { it.id.toString() }) { model ->
                        val id = "model:${model.id}"
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = id in checked, onCheckedChange = { enabled ->
                                checked = if (enabled) checked + id else checked - id
                            })
                            Text(model.displayName.ifBlank { model.modelId })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = automaticDiscussion, onCheckedChange = { automaticDiscussion = it })
                    Text("发送后自动互相讨论")
                }
                Text("最多讨论 $discussionRounds 轮（最多 24 条成员回复）")
                Slider(value = discussionRounds.toFloat(), onValueChange = { discussionRounds = it.toInt() }, valueRange = 1f..6f, steps = 4)
            } },
            confirmButton = { TextButton(enabled = title.isNotBlank() && checked.isNotEmpty(), onClick = {
                val existing = rooms.firstOrNull { it.id == editingId }
                save((existing ?: GroupRoom(title = title.trim())).copy(
                    title = title.trim(),
                    modelIds = checked.filter { it.startsWith("model:") }.map { it.removePrefix("model:") },
                    assistantIds = checked.filter { it.startsWith("assistant:") }.map { it.removePrefix("assistant:") },
                    conversationBindings = bindings.filterKeys { it in checked },
                    discussionRounds = discussionRounds,
                    automaticDiscussion = automaticDiscussion,
                ))
                showRoomEditor = false
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showRoomEditor = false }) { Text("取消") } }
        )
    }
    bindingMember?.let { assistant ->
        val key = "assistant:${assistant.id}"
        AlertDialog(onDismissRequest = { bindingMember = null },
            title = { Text("${assistant.name.ifBlank { "默认角色" }} 的原窗口") },
            text = { Column {
                Text("关联后读取该窗口的角色聊天文本，并将本群发言按实际发送者同步回去。")
                LazyColumn(Modifier.height(280.dp)) {
                    item { TextButton(onClick = { bindings = bindings - key; bindingMember = null }) { Text("不关联原窗口") } }
                    items(bindingOptions, key = { it.id.toString() }) { conversation ->
                        TextButton(onClick = {
                            bindings = bindings + (key to conversation.id.toString())
                            bindingMember = null
                        }) { Text(conversation.title.ifBlank { "未命名会话" } + if (bindings[key] == conversation.id.toString()) " ✓" else "") }
                    }
                    if (bindingOptions.isEmpty()) item { Text("这个角色还没有原聊天窗口。先与角色创建普通聊天，再来关联。") }
                }
            } },
            confirmButton = { TextButton(onClick = { bindingMember = null }) { Text("完成") } },
        )
    }
}
