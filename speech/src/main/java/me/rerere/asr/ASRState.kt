/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.asr

enum class ASRStatus {
    Idle,
    Connecting,
    Listening,
    Stopping,
    Error
}

data class ASRState(
    val status: ASRStatus = ASRStatus.Idle,
    val isAvailable: Boolean = false,
    val transcript: String = "",
    val errorMessage: String? = null,
    val amplitudes: List<Float> = emptyList(),
    val audioFilePath: String? = null,
    val durationMs: Long = 0L,
    /**
     * 已经"判停确定、不会再被改写"的文本前缀 (只增不改 / append-only)。
     *
     * 目前只有 Volcengine 会填: 服务端在 utterances 里用 definite 标记某句已判停确定。
     * 上层 (VoiceCallService) 依赖这个"只增不改"的性质做增量发送 —— 只发新增部分,
     * 所以不会再把上一句重复发一遍。其它 provider 保持空字符串。
     */
    val finalizedText: String = "",
    /**
     * 当前还没确定的尾巴 (服务端还在改的那半句)。目前只有 Volcengine 会填。
     * 非空表示"用户还在说话", 上层据此避免在句子中间就把消息发出去。
     */
    val pendingText: String = "",
) {
    val isRecording: Boolean
        get() = status == ASRStatus.Connecting || status == ASRStatus.Listening || status == ASRStatus.Stopping
}
