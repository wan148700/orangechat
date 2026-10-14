package me.rerere.rikkahub.ui.pages.group

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.UUID
import kotlin.uuid.Uuid

@Serializable
internal data class GroupLine(
    val sender: String,
    val text: String,
    val id: String = UUID.randomUUID().toString(),
    val modelId: String? = null,
    val message: UIMessage? = null,
    val memberKey: String? = null,
    val addressedMemberKeys: List<String> = emptyList(),
    val roundId: String? = null,
)

@Serializable
internal data class GroupRoom(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val modelIds: List<String> = emptyList(),
    val lines: List<GroupLine> = emptyList(),
    val assistantIds: List<String> = emptyList(),
    val conversationBindings: Map<String, String> = emptyMap(),
    val discussionRounds: Int = 3,
    val automaticDiscussion: Boolean = false,
    val backgroundPath: String = "",
    val backgroundOpacity: Float = 1f,
) {
    val memberKeys get() = assistantIds.map { "assistant:$it" } + modelIds.map { "model:$it" }
}

internal data class MentionTarget(val key: String, val label: String)

internal class GroupTurnQueue(
    private val members: List<String>,
    initialTargets: List<String>,
    private val waves: Int = 1,
) {
    private val pending = initialTargets.distinct().filter { it in members }.toMutableList()
    private val limit = (if (waves > 1) members.size * waves else maxOf(members.size, 6)).coerceAtMost(24)
    private var turns = 0


    fun next(): String? {
        if (turns >= limit || pending.isEmpty()) return null
        turns++
        return pending.removeAt(0)
    }

    fun followMentions(keys: List<String>) {
        keys.distinct().filter { it in members }.asReversed().forEach { key ->
            pending.remove(key)
            pending.add(0, key)
        }
    }

    fun finishTurn() {
        // No automatic roll-call: only a new, directed request may add a turn.
    }
}

/** Exact visible aliases, including aliases with spaces; @Ann must not match @Anna. */
internal fun mentionedMemberKeys(text: String, targets: List<MentionTarget>): List<String> {
    if (Regex("@(全体|所有人|all)(?=$|[\\s，,。.!！？?：:;；])", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
        return targets.map { it.key }
    }
    return targets.filter { target ->
        Regex("@${Regex.escape(target.label)}(?=$|[\\s，,。.!！？?：:;；])", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }.map { it.key }
}

/** Only relevant user messages and the recipient's own replies enter its private window. */
internal fun groupSyncMessageId(room: GroupRoom, recipientKey: String, line: GroupLine): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes("group:${room.id}:$recipientKey:${line.id}".toByteArray()).toString())

/** Legacy records infer a round at each user message; new records store an explicit round ID. */
internal fun groupRounds(room: GroupRoom): List<List<GroupLine>> {
    var legacyRound = room.lines.firstOrNull()?.id.orEmpty()
    return room.lines.groupBy { line ->
        if (line.memberKey == null && (line.sender == "我" || line.message?.role == MessageRole.USER)) legacyRound = line.id
        line.roundId ?: "legacy:$legacyRound"
    }.values.toList()
}

internal fun groupLineBelongsToRecipient(room: GroupRoom, line: GroupLine, recipientKey: String): Boolean =
    groupRounds(room).any { round ->
        round.any { it.id == line.id } && round.any { it.memberKey == recipientKey }
    }

internal fun groupSyncLines(room: GroupRoom, recipientKey: String): List<GroupLine> {
    val visibleIds = groupRounds(room).filter { round -> round.any { it.memberKey == recipientKey } }
        .flatten().map { it.id }.toSet()
    return room.lines.filter { it.id in visibleIds }
}

internal fun groupObsoleteSyncIds(room: GroupRoom, recipientKey: String): Set<Uuid> {
    val visibleIds = groupSyncLines(room, recipientKey).map { it.id }.toSet()
    return room.lines.filterNot { it.id in visibleIds }.map { groupSyncMessageId(room, recipientKey, it) }.toSet()
}

internal fun groupSyncMessages(room: GroupRoom, recipientKey: String): List<UIMessage> = groupSyncLines(room, recipientKey).map { line ->
        val ownReply = line.memberKey == recipientKey
        (line.message ?: UIMessage.user(line.text)).copy(
            id = groupSyncMessageId(room, recipientKey, line),
            role = if (ownReply) MessageRole.ASSISTANT else MessageRole.USER,
            parts = listOf(UIMessagePart.Text("[群聊·${room.title}｜${line.sender}]\n" +
                (if (line.memberKey != null && !ownReply) "[其他群成员发言，不是用户发言]\n" else "") + line.text)),
            modelId = if (ownReply) line.message?.modelId else null,
        )
    }

internal fun isMemberConversationRequest(text: String): Boolean =
    Regex("互聊|互相.*(聊|讨论|说)|你们.*(聊|讨论)|彼此.*(聊|讨论)").containsMatchIn(text)

internal fun memberConversationPlan(keys: List<String>, rounds: Int): List<String> =
    if (keys.size < 2) emptyList() else List(rounds.coerceIn(1, 6)) { keys }.flatten().take(24)

/** A mention in agreement is not an invitation to generate another message. */
internal fun requestedMemberKeys(text: String, targets: List<MentionTarget>): List<String> {
    val request = Regex("[?？]|请|能否|怎么看|你觉得|你认为|说说|回答|分析|解释|补充一下|检查一下|评估|讨论一下|\\b(can you|could you|please|what do you think)\\b", RegexOption.IGNORE_CASE)
    return if (request.containsMatchIn(text)) mentionedMemberKeys(text, targets) else emptyList()
}

internal fun isAcknowledgementTask(text: String): Boolean =
    Regex("收到.*(回复|回答)|回复.*收到|测试消息|打个招呼|确认.*(在线|收到)").containsMatchIn(text)

internal fun groupOriginalHistory(messages: List<UIMessage>, limit: Int): List<UIMessage> = messages
    .filterNot { it.toText().startsWith("[群聊·") }
    .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
    .takeLast(limit.coerceAtLeast(1))
    .map { it.copy(parts = listOf(UIMessagePart.Text(it.toText()))) }

internal fun memberConversationTargets(text: String, targets: List<MentionTarget>): List<String> =
    mentionedMemberKeys(text, targets).ifEmpty {
        val named = targets.filter { text.contains(it.label, ignoreCase = true) }.map { it.key }
        named.takeIf { it.size >= 2 } ?: targets.map { it.key }
    }
