/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.voice

/**
 * 语音通话状态机
 *
 * 状态流转:
 * Idle -> Listening -> Processing -> Speaking -> Listening -> ...
 *                                    |-> Error -> Idle
 */
enum class VoiceCallStatus {
    Idle,
    Listening,
    Processing,
    Speaking,
    Error
}

/**
 * 语音通话 UI 状态
 */
data class VoiceCallUiState(
    val status: VoiceCallStatus = VoiceCallStatus.Idle,
    val userTranscript: String = "",
    /**
     * 字幕专用: 用户"还没发出去"的那部分文字。
     *
     * 增量型 ASR (Volcengine) 的 userTranscript 是整场通话累积的, 直接拿去当字幕会越堆越多,
     * 所以 Service 会把"已经作为消息发送"的前缀去掉后写进这个字段;
     * 其它 ASR (SiliconFlow) 的转写本身就是"这一句", 等于 userTranscript。
     */
    val userPendingTranscript: String = "",
    val assistantText: String = "",
    val errorMessage: String? = null,
    val amplitudes: List<Float> = emptyList(),
    val isMuted: Boolean = false,
    val autoSendEnabled: Boolean = true,
) {
    val isActive: Boolean
        get() = status != VoiceCallStatus.Idle
}