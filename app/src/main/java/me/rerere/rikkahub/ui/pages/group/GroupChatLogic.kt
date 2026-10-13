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
    private var wave = 1

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
        if (pending.isEmpty() && wave < waves) {
            wave++
            pending.addAll(members)
        }
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

/** Each recipient sees only its own replies as ASSISTANT; every other speaker is labelled context. */
internal fun groupSyncMessages(room: GroupRoom, recipientKey: String): List<UIMessage> = room.lines.map { line ->
    val ownReply = line.memberKey == recipientKey && line.message?.role != MessageRole.USER
    val stableId = Uuid.parse(UUID.nameUUIDFromBytes("group:${room.id}:$recipientKey:${line.id}".toByteArray()).toString())
    (line.message ?: UIMessage.user(line.text)).copy(
        id = stableId,
        role = if (ownReply) MessageRole.ASSISTANT else MessageRole.USER,
        parts = listOf(UIMessagePart.Text("[群聊·${room.title}｜${line.sender}]\n${line.text}")),
        modelId = if (ownReply) line.message?.modelId else null,
    )
}
