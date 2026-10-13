package me.rerere.rikkahub.ui.pages.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GroupChatLogicTest {
    private val members = listOf(MentionTarget("a", "Ann"), MentionTarget("b", "Anna"), MentionTarget("c", "小 克"))

    @Test fun mentionsUseCompleteAliasesAndSupportSpaces() {
        assertEquals(listOf("b", "c"), mentionedMemberKeys("@Anna，看看 @小 克 的意见", members))
        assertEquals(emptyList<String>(), mentionedMemberKeys("@Annabelle", members))
        assertEquals(listOf("a", "b", "c"), mentionedMemberKeys("@全体 讨论", members))
    }

    @Test fun originalWindowReceivesCorrectRolesAndStableIds() {
        val room = GroupRoom(title = "任务", lines = listOf(
            GroupLine("我", "开始", message = UIMessage.user("开始")),
            GroupLine("Ann", "建议", memberKey = "a", message = UIMessage.assistant("建议")),
            GroupLine("Anna", "补充", memberKey = "b", message = UIMessage.assistant("补充")),
        ))
        val first = groupSyncMessages(room, "a")
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), first.map { it.role })
        assertEquals(first.map { it.id }, groupSyncMessages(room, "a").map { it.id })
        assertNotEquals(first[0].id, groupSyncMessages(room, "b")[0].id)
    }

    @Test fun mutualMentionsCannotLoopForever() {
        val queue = GroupTurnQueue(listOf("a", "b"), listOf("a"))
        val replies = mutableListOf<String>()
        while (true) {
            val speaker = queue.next() ?: break
            replies += speaker
            queue.followMentions(listOf(if (speaker == "a") "b" else "a"))
            queue.finishTurn()
        }
        assertEquals(listOf("a", "b", "a", "b", "a", "b"), replies)
    }

    @Test fun mentionedMemberSpeaksNextWithoutDuplicateQueueEntries() {
        val queue = GroupTurnQueue(listOf("a", "b", "c"), listOf("a", "b", "c"))
        assertEquals("a", queue.next())
        queue.followMentions(listOf("c", "c"))
        assertEquals("c", queue.next())
        assertEquals("b", queue.next())
        assertEquals(null, queue.next())
    }
}
