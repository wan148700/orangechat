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

    @Test fun agreementMentionsDoNotRequestAnotherReply() {
        assertEquals(emptyList<String>(), requestedMemberKeys("@Ann +1，你说得对。", members))
        assertEquals(emptyList<String>(), requestedMemberKeys("@Anna 随时喊我，我在。", members))
        assertEquals(listOf("b"), requestedMemberKeys("@Anna，你怎么看这个方案？", members))
    }

    @Test fun discussionDoesNotRestartRollCallWhenQueueIsEmpty() {
        val queue = GroupTurnQueue(listOf("a", "b"), listOf("a", "b"), waves = 6)
        assertEquals("a", queue.next())
        queue.finishTurn()
        assertEquals("b", queue.next())
        queue.finishTurn()
        assertEquals(null, queue.next())
    }

    @Test fun acknowledgementTasksAndRoomBackgroundSurviveSerialization() {
        assertEquals(true, isAcknowledgementTask("@全体 测试消息，收到请回答"))
        assertEquals(false, isAcknowledgementTask("讨论一下如何修复同步"))
        val room = GroupRoom(title = "测试", backgroundPath = "/private/group.jpg")
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString<GroupRoom>(json.encodeToString(GroupRoom.serializer(), room))
        assertEquals(room.backgroundPath, restored.backgroundPath)
    }

    @Test fun originalContextKeepsDialogueAndExcludesSyncedGroupEvents() {
        val history = groupOriginalHistory(listOf(
            UIMessage.user("我喜欢蓝色"), UIMessage.assistant("记住了"),
            UIMessage.user("[群聊·任务｜我]\n开始"), UIMessage.system("不要作为聊天历史")
        ), 24)
        assertEquals(listOf("我喜欢蓝色", "记住了"), history.map { it.toText() })
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), history.map { it.role })
    }

    @Test fun completeRoundGoesOnlyToItsActualParticipantsIncludingLateJoiners() {
        val room = GroupRoom(title = "测试", lines = listOf(
            GroupLine("我", "两位聊聊", message = UIMessage.user("两位聊聊"), roundId = "r1"),
            GroupLine("Ann", "你好", memberKey = "a", message = UIMessage.assistant("你好"), roundId = "r1"),
            GroupLine("Anna", "你好呀", memberKey = "b", message = UIMessage.assistant("你好呀"), roundId = "r1"),
            GroupLine("小 克", "加入", memberKey = "c", message = UIMessage.assistant("加入"), roundId = "r1"),
            GroupLine("我", "只问Ann", message = UIMessage.user("只问Ann"), roundId = "r2"),
            GroupLine("Ann", "收到", memberKey = "a", message = UIMessage.assistant("收到"), roundId = "r2"),
        ))
        assertEquals(6, groupSyncMessages(room, "a").size)
        assertEquals(4, groupSyncMessages(room, "b").size)
        assertEquals(4, groupSyncMessages(room, "c").size)
        assertEquals(0, groupSyncMessages(room, "d").size)
        assertEquals(listOf(MessageRole.USER, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), groupSyncMessages(room, "b").map { it.role })
        assertEquals(true, groupSyncMessages(room, "b")[1].toText().contains("不是用户发言"))
        assertEquals(groupSyncMessages(room, "b").map { it.id }, groupSyncMessages(room, "b").map { it.id })
    }

    @Test fun twoPersonRoundIsNotWrittenToOtherWindowsAndLegacyRoundsRemainSeparate() {
        val room = GroupRoom(title = "测试", lines = listOf(
            GroupLine("我", "两位聊聊", message = UIMessage.user("两位聊聊")),
            GroupLine("Ann", "你好", memberKey = "a", message = UIMessage.assistant("你好")),
            GroupLine("Anna", "你好呀", memberKey = "b", message = UIMessage.assistant("你好呀")),
            GroupLine("我", "只问小克", message = UIMessage.user("只问小克")),
            GroupLine("小 克", "收到", memberKey = "c", message = UIMessage.assistant("收到")),
        ))
        assertEquals(3, groupSyncMessages(room, "a").size)
        assertEquals(3, groupSyncMessages(room, "b").size)
        assertEquals(2, groupSyncMessages(room, "c").size)
        assertEquals(3, groupObsoleteSyncIds(room, "c").size)
    }

    @Test fun memberConversationUsesBoundedAlternatingSpeakers() {
        assertEquals(true, isMemberConversationRequest("你们互相聊两句"))
        assertEquals(listOf("a", "b", "a", "b"), memberConversationPlan(listOf("a", "b"), 2))
        assertEquals(24, memberConversationPlan(listOf("a", "b", "c", "d", "e"), 6).size)
        assertEquals(emptyList<String>(), memberConversationPlan(listOf("a"), 3))
    }

    @Test fun namedPairDoesNotInviteTheEntireGroup() {
        val targets = listOf(MentionTarget("d", "DeepSeek"), MentionTarget("c", "Claude"), MentionTarget("k", "Kimi"))
        assertEquals(true, isMemberConversationRequest("DeepSeek和claude互聊"))
        assertEquals(listOf("d", "c"), memberConversationTargets("DeepSeek和claude互聊", targets))
        assertEquals(listOf("d", "c", "k"), memberConversationTargets("你们互相聊两句", targets))
    }
}

