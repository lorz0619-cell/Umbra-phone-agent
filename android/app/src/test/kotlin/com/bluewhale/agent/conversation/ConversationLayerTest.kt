package com.bluewhale.agent.conversation

import com.bluewhale.agent.model.VlmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLayerTest {
    private val router =
        OpenAiConversationRouter(
            VlmConfig(apiKey = "test", baseUrl = "https://example.test", model = "test"),
        )

    @Test
    fun parsesChatWithoutStartingTask() {
        val result =
            router.parse(
                """{"route":"chat","reply":"你好，有什么想聊的？"}""",
                "hi",
            )

        assertEquals(
            ConversationRoute.Chat("你好，有什么想聊的？"),
            result,
        )
    }

    @Test
    fun parsesContextCompletedDeviceTask() {
        val result =
            router.parse(
                """{"route":"task","task":"打开微信并搜索天气"}""",
                "搜索天气",
            )

        assertEquals(
            ConversationRoute.Task("打开微信并搜索天气"),
            result,
        )
    }

    @Test
    fun emptyResponseFallsBackWithoutJsonException() {
        assertEquals(
            ConversationRoute.Chat("模型暂时没有返回有效内容，请再说一次。"),
            router.parse("", "你好"),
        )
        assertEquals(
            ConversationRoute.Task("打开QQ应用"),
            router.parse("   ", "打开QQ应用"),
        )
    }

    @Test
    fun plainTextResponseRemainsNormalChat() {
        assertEquals(
            ConversationRoute.Chat("你好，我在。"),
            router.parse("你好，我在。", "hi"),
        )
    }

    @Test
    fun fallbackRecognizesDirectSystemCapabilityRequests() {
        assertEquals(
            ConversationRoute.Task("帮我导航到无锡一中"),
            router.parse("", "帮我导航到无锡一中"),
        )
        assertEquals(
            ConversationRoute.Task("设置一个十分钟倒计时"),
            router.parse("", "设置一个十分钟倒计时"),
        )
    }

    @Test
    fun conversationCodecPreservesAppendOnlyTimeline() {
        val original =
            listOf(
                ConversationMessage(
                    id = "u1",
                    role = ConversationRole.USER,
                    text = "你好",
                    timestampMs = 1,
                ),
                ConversationMessage(
                    id = "a1",
                    role = ConversationRole.ASSISTANT,
                    text = "你好",
                    kind = ConversationKind.CHAT,
                    timestampMs = 2,
                ),
            )

        val restored = ConversationStore.decode(ConversationStore.encode(original))

        assertEquals(listOf("u1", "a1"), restored.map { it.id })
        assertEquals(listOf("你好", "你好"), restored.map { it.text })
        assertTrue(restored.zipWithNext().all { (first, second) ->
            first.timestampMs <= second.timestampMs
        })
    }
}
