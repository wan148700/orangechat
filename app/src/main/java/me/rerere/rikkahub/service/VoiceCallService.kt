/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.VOICE_CALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.hooks.createCustomAsrState
import me.rerere.rikkahub.ui.hooks.createCustomTtsState
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallService"

// 少于这个长度的转写不发送: 过滤空白 / 单字噪音, 避免发出空消息
private const val MIN_SEND_TRANSCRIPT_LENGTH = 2

// 打断 AI 说话所需的新增"有效文字"个数 (字母/数字/汉字, 空格和标点不算)
private const val INTERRUPT_MIN_NEW_CHARS = 2

/**
 * 语音通话后台服务
 *
 * 把原来 VoiceCallVM 里的业务逻辑迁移成"独立运行、跟随 Service 生命周期"的形式.
 * 用户在 VoiceCallPage 手动开始通话后, 切到后台/退出页面, 通话依然继续跑,
 * 有持续通知栏, 点通知能回到通话页面. 只有用户主动点"挂断"才真正结束.
 *
 * 同一时刻只允许存在一路通话 (由 _activeConversationId 这个 companion object 级别的
 * StateFlow 做单例保护).
 */
class VoiceCallService : Service(), KoinComponent {
    private val chatService: ChatService by inject()
    private val httpClient: OkHttpClient by inject()
    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "VoiceCallService coroutine exception", e)
        }
    )

    private lateinit var conversationId: Uuid
    private lateinit var asr: CustomAsrState
    private lateinit var tts: CustomTtsState

    private val _uiState = MutableStateFlow(VoiceCallUiState())
    val uiState: StateFlow<VoiceCallUiState> = _uiState.asStateFlow()

    val conversation: StateFlow<Conversation>
        get() = chatService.getConversationFlow(conversationId)

    // 任务协程
    private var vadJob: Job? = null
    private var speakingMonitorJob: Job? = null
    private var conversationMonitorJob: Job? = null
    private var asrMonitorJob: Job? = null
    private var interruptDetectJob: Job? = null
    private var lastSpokenText: String = ""

    // 增量发送 (Volcengine 这类会给出"已确定文本"的流式 ASR):
    // 已经作为用户消息发出去的"已确定文本"前缀. 下一次只发它后面新增的部分,
    // 所以不会再出现"上一句被重复发一遍"的问题.
    // 只在 startCall() 里清零, 跨"轮"不清零 (服务端的文本是整场累积的).
    private var sentFinalizedPrefix: String = ""

    // 跟踪 AI 消息的增量, 用于流式 TTS
    private var lastAssistantText: String = ""
    private var hasSentCurrentMessage = false

    // 流式 TTS: 记录已发送给 TTS 的文本长度
    private var ttsSentLength: Int = 0

    // 静音状态 (独立于 _uiState.isMuted, 检测循环里直接读这个字段更快)
    private var isMuted: Boolean = false

    companion object {
        private val _activeConversationId = MutableStateFlow<String?>(null)
        val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

        fun isRunning(): Boolean = _activeConversationId.value != null

        /**
         * 启动服务: 调用方 (VoiceCallPage) 负责在自己判断"没有冲突"之后才调这个方法.
         */
        fun start(context: Context, conversationId: String) {
            val intent = Intent(context, VoiceCallService::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "启动 VoiceCallService 失败, conversationId=$conversationId", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, VoiceCallService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "停止 VoiceCallService 失败", e)
            }
        }

        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val ACTION_HANG_UP = "me.rerere.rikkahub.VOICE_CALL_HANG_UP"
        const val NOTIFICATION_ID = 40001
    }

    // Binder, 供 VoiceCallPage bindService 用
    inner class LocalBinder : Binder() {
        fun getService(): VoiceCallService = this@VoiceCallService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户点了通知栏上的"挂断"按钮
        if (intent?.action == ACTION_HANG_UP) {
            endCall()
            stopSelf()
            return START_NOT_STICKY
        }

        val convIdStr = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        if (convIdStr == null) {
            Log.e(TAG, "onStartCommand 缺少 conversationId 参数, 无法启动通话")
            stopSelf()
            return START_NOT_STICKY
        }

        // 已经在跑同一个对话的通话: 不要重复 startCall, 只刷新前台通知
        if (_activeConversationId.value == convIdStr) {
            return START_NOT_STICKY
        }

        // 兜底: 已经在跑别的对话的通话, 防御性丢弃
        if (_activeConversationId.value != null && _activeConversationId.value != convIdStr) {
            Log.w(
                TAG,
                "已有通话 ${_activeConversationId.value} 在进行, 忽略新的 start 请求 $convIdStr"
            )
            return START_NOT_STICKY
        }

        try {
            conversationId = Uuid.parse(convIdStr)
        } catch (e: Exception) {
            Log.e(TAG, "conversationId 解析失败: $convIdStr", e)
            stopSelf()
            return START_NOT_STICKY
        }

        _activeConversationId.value = convIdStr

        // 关键修复: 必须先同步调用 startForeground, 用一个初始状态的通知占位.
        // Android 要求 startForegroundService() 调用后 5 秒内必须调用 startForeground(),
        // 否则触发 ForegroundServiceDidNotStartInTimeException 崩溃.
        // 不能等 ASR/TTS 异步初始化完成后才调用, 真正的初始化放到下面的协程里做.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(_uiState.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败, conversationId=$conversationId", e)
            _activeConversationId.value = null
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                // 关键修复: 两个工厂函数现在是 suspend 函数, 会真正挂起等待
                // provider 设置完成后才返回实例, 消除了之前 controller 为 null 的竞态.
                asr = createCustomAsrState(applicationContext, httpClient, settingsStore)
                tts = createCustomTtsState(applicationContext, settingsStore)

                startCall()

                // 订阅 uiState 变化, 实时刷新通知内容
                launch {
                    uiState.collect { state ->
                        try {
                            val manager =
                                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            manager.notify(NOTIFICATION_ID, buildNotification(state))
                        } catch (e: Exception) {
                            Log.e(TAG, "刷新通话通知失败", e)
                        }
                    }
                }

                // Service 自己订阅 asr.state, 同步振幅数据 + 捕获底层 ASR 错误
                launch {
                    asr.state.collect { asrState ->
                        updateAmplitudes(asrState.amplitudes)
                        // 字幕用的"用户还没发出去的文字"要跟着 ASR 状态实时刷新
                        refreshPendingUserTranscript()
                        if (asrState.status == me.rerere.asr.ASRStatus.Error) {
                            val msg = asrState.errorMessage ?: "语音识别发生未知错误"
                            Log.e(TAG, "ASR 底层报错, conversationId=$conversationId, msg=$msg")
                            _uiState.update {
                                it.copy(
                                    status = VoiceCallStatus.Error,
                                    errorMessage = "语音识别错误: $msg"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "初始化语音通话失败, conversationId=$conversationId", e)
                _uiState.update {
                    it.copy(
                        status = VoiceCallStatus.Error,
                        errorMessage = "初始化失败: ${e.message}"
                    )
                }
            }
        }

        // 不用 START_STICKY: 通话被系统杀死不应该自动重启接着录音
        return START_NOT_STICKY
    }

    /**
     * 开始语音通话
     *
     * ASR 在整个通话期间持续录音 (不再像原来那样只在 Listening 状态开启).
     * 这里只调用一次 asr.start(), 作为整场通话唯一的录音启动点
     * (除非用户中途静音又取消).
     */
    fun startCall() {
        if (_uiState.value.status != VoiceCallStatus.Idle) return
        lastAssistantText = ""
        lastSpokenText = ""
        hasSentCurrentMessage = false
        ttsSentLength = 0
        isMuted = false
        // 新通话 = 从头开始: 增量发送指针清零 (挂断重打一次电话, 识别结果从零开始)
        sentFinalizedPrefix = ""

        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Listening,
                userTranscript = "",
                userPendingTranscript = "",
                // 字幕起点: 本次通话开始之后产生的消息才进字幕
                callStartedAt = System.currentTimeMillis(),
                errorMessage = null,
                isMuted = false
            )
        }

        try {
            asr.start { transcript ->
                _uiState.update { it.copy(userTranscript = transcript) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动 ASR 失败, conversationId=$conversationId", e)
            _uiState.update {
                it.copy(
                    status = VoiceCallStatus.Error,
                    errorMessage = "麦克风启动失败: ${e.message}"
                )
            }
            return
        }

        startVadDetection()
        startAsrMonitor()
        startConversationMonitor()
    }

    /**
     * 从别的状态切回 Listening 时复位状态 + VAD 计时器.
     * 不再调用 asr.stop()/asr.start() (ASR 现在贯穿全程).
     */
    private fun startListening() {
        tts.stop()
        ttsSentLength = 0
        lastAssistantText = ""
        hasSentCurrentMessage = false

        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Listening,
                userTranscript = "",
                userPendingTranscript = "",
                errorMessage = null
            )
        }

        // 停止"打断检测"协程 (Speaking 状态才需要它)
        interruptDetectJob?.cancel()

        // 重启 ASR: 非流式 ASR (SiliconFlow) 是“录一段→停”的一次性模式,
        // AI 说完话回到 Listening 时它已停, 不重启则音波球不动、说话发不出去.
        // 流式 ASR 的 start() 有 isRecording 守卫, 重复调用无副作用.
        if (!isMuted) {
            runCatching {
                asr.start { transcript ->
                    _uiState.update { it.copy(userTranscript = transcript) }
                }
            }.onFailure { Log.e(TAG, it.toString(), it) }
        }

        startVadDetection()
    }

    /**
     * VAD: 检测用户停顿后自动发送 (仅 Listening 状态生效).
     *
     * 两条路径:
     * - A 路径 (会给出"已确定文本"的流式 ASR, 例如 Volcengine):
     *   服务端在 utterances 里用 definite 标记某句已判停确定, 这类文本只增不改 (append-only)。
     *   只有当"出现了新的已确定文本"且"用户已经不再说话 (pendingText 为空)"时才发送,
     *   而且只发新增的那部分 —— 所以不会再重复发上一句, 也不会在句子中间被切断。
     * - B 路径 (兜底, 没有"已确定文本"的 ASR, 例如 SiliconFlow):
     *   原逻辑保持不变: 转写稳定 800ms 或音量静默 2 秒就发送整段转写。
     *
     * 阈值参数 (800ms / 2 字符 / 2 秒音量超时) 保持不变, 不要动.
     */
    private fun startVadDetection() {
        vadJob?.cancel()
        vadJob = serviceScope.launch {
            var lastTranscript = ""
            var silenceStartTime: Long = 0L
            var lastAmplitudeTime: Long = System.currentTimeMillis()
            val silenceThresholdMs = 800L
            val minTranscriptLength = 2
            val amplitudeTimeoutMs = 2000L
            // A 路径: 已经有"已确定文本"但用户还在连续说话时, 最多憋这么久就先发出去,
            // 否则超长连续说话可能一直等不到判停, 消息永远发不出去
            val finalizedHoldMaxMs = 6000L
            var pendingFinalizedSince = 0L
            // 见过"已确定文本/未确定尾巴"就说明这个 ASR 支持 A 路径, 这一轮之后都走 A
            var structuredAsr = false

            while (true) {
                delay(100)
                if (_uiState.value.status != VoiceCallStatus.Listening) break
                if (isMuted) continue // 静音期间不检测, 也不发送
                if (!_uiState.value.autoSendEnabled) continue

                val currentTranscript = _uiState.value.userTranscript
                val amplitudes = _uiState.value.amplitudes
                val recentAmplitude = amplitudes.takeLast(3).average().toFloat()

                // 检测音量活动 - 如果有声音就重置计时
                if (recentAmplitude > 0.05f) {
                    lastAmplitudeTime = System.currentTimeMillis()
                }

                // ---------- A 路径: 只发"新增的已确定文本" ----------
                val asrState = asr.state.value
                val finalizedText = asrState.finalizedText
                if (finalizedText.isNotEmpty() || asrState.pendingText.isNotEmpty()) {
                    structuredAsr = true
                }
                if (structuredAsr) {
                    if (finalizedText.isNotEmpty()) {
                        // 服务端重连 / 改写了已确定文本时, 已发送前缀会失配, 必须归零重算
                        if (!finalizedText.startsWith(sentFinalizedPrefix)) {
                            Log.w(
                                TAG,
                                "已确定文本不再是已发送前缀 (sent=${sentFinalizedPrefix.length}, " +
                                    "now=${finalizedText.length}), 重置增量发送指针"
                            )
                            sentFinalizedPrefix = ""
                        }
                        val newText = finalizedText.substring(sentFinalizedPrefix.length).trim()
                        if (newText.length >= minTranscriptLength) {
                            if (asrState.pendingText.isBlank()) {
                                // 服务端已判停 + 没有未确定尾巴 = 用户说完了, 立刻发新增部分
                                sendTranscript(newText, reason = "ASR 判停, 发送新增的已确定文本")
                                markFinalizedSent(finalizedText)
                                break
                            }
                            // 用户还在连续说话: 先不发, 但憋太久就把已确定的部分先发出去
                            if (pendingFinalizedSince == 0L) {
                                pendingFinalizedSince = System.currentTimeMillis()
                            } else if (System.currentTimeMillis() - pendingFinalizedSince >= finalizedHoldMaxMs) {
                                sendTranscript(newText, reason = "已确定文本积压 ${finalizedHoldMaxMs}ms")
                                markFinalizedSent(finalizedText)
                                pendingFinalizedSince = 0L
                                break
                            }
                        } else {
                            pendingFinalizedSince = 0L
                        }
                    }
                    continue // 走 A 路径时不再执行下面的兜底逻辑, 避免重复发送
                }

                // ---------- B 路径 (兜底): 原逻辑保持不变 ----------
                if (currentTranscript != lastTranscript) {
                    // 转写还在变化, 重置静音计时
                    lastTranscript = currentTranscript
                    silenceStartTime = 0L
                } else if (currentTranscript.length >= minTranscriptLength) {
                    // 转写稳定且有内容, 开始/继续计时
                    if (silenceStartTime == 0L) {
                        silenceStartTime = System.currentTimeMillis()
                    }
                    val silentFor = System.currentTimeMillis() - silenceStartTime
                    val amplitudeSilentFor = System.currentTimeMillis() - lastAmplitudeTime

                    // 触发条件: 转写稳定且静音足够, 或音量持续低迷
                    if (silentFor >= silenceThresholdMs || amplitudeSilentFor >= amplitudeTimeoutMs) {
                        Log.d(
                            TAG,
                            "VAD triggered auto-send: $currentTranscript (silentFor=$silentFor, ampSilent=$amplitudeSilentFor)"
                        )
                        sendCurrentMessage()
                        break
                    }
                }
            }
        }
    }

    /**
     * 发送当前整段转写 (兜底路径用: SiliconFlow 这类"录一段 → 识别一段"的 ASR).
     * 不再调用 asr.stop() (ASR 要持续跑到整场通话结束).
     */
    private fun sendCurrentMessage() {
        sendTranscript(_uiState.value.userTranscript.trim(), reason = "转写稳定 / 音量静默")
    }

    /**
     * 记下"这段已确定文本已经作为消息发出去了", 并刷新字幕。
     *
     * 只做记录 + 字幕刷新, 不参与任何发送判断。
     */
    private fun markFinalizedSent(finalizedText: String) {
        sentFinalizedPrefix = finalizedText
        refreshPendingUserTranscript()
    }

    /**
     * 刷新"用户还没发出去的文字" (字幕专用)。
     *
     * 增量型 ASR (Volcengine) 的 userTranscript 是整场通话累积的, 直接当字幕会越堆越多,
     * 所以这里去掉"已经作为消息发送"的前缀, 只留下还没发送的部分。
     * 其它 ASR (SiliconFlow) 的转写本身就是"这一句", 直接等于 userTranscript。
     */
    private fun refreshPendingUserTranscript() {
        val asrState = asr.state.value
        val hasIncrementalText =
            asrState.finalizedText.isNotEmpty() || asrState.pendingText.isNotEmpty()
        val displayText = if (hasIncrementalText) {
            val finalized = asrState.finalizedText
            val unsentFinalized = if (finalized.startsWith(sentFinalizedPrefix)) {
                finalized.substring(sentFinalizedPrefix.length)
            } else {
                finalized
            }
            unsentFinalized + asrState.pendingText
        } else {
            _uiState.value.userTranscript
        }
        _uiState.update { it.copy(userPendingTranscript = displayText) }
    }

    /**
     * 把一段文本当成用户消息发给 AI.
     *
     * @param transcript 这次真正要发送的文本. 可能是整段转写, 也可能只是整场转写里
     *                   "新增的已确定部分" (Volcengine 走增量发送).
     * @param reason 触发原因, 只用于日志排查.
     */
    private fun sendTranscript(transcript: String, reason: String) {
        vadJob?.cancel()

        // 空白 / 过短一律不发, 避免产生空消息或噪音消息
        if (transcript.isBlank() || transcript.length < MIN_SEND_TRANSCRIPT_LENGTH) {
            Log.d(TAG, "跳过发送: 文本为空或过短 (len=${transcript.length}, reason=$reason)")
            // 没有有效内容, 回到监听
            startListening()
            return
        }

        Log.d(TAG, "发送用户消息 (reason=$reason): $transcript")
        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Processing,
                assistantText = ""
            )
        }
        ttsSentLength = 0
        lastAssistantText = ""

        try {
            chatService.sendMessage(
                conversationId,
                listOf(UIMessagePart.Text(transcript))
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "发送消息失败, conversationId=$conversationId, transcript=$transcript",
                e
            )
            _uiState.update {
                it.copy(
                    status = VoiceCallStatus.Error,
                    errorMessage = "发送失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 监听对话流变化, 实现:
     * 1. 流式 TTS (检测到新句子即朗读)
     * 2. AI 开始输出时立即进入 Speaking 状态, 让用户可以打断
     * 3. AI 回复完成后回到 Listening
     */
    private fun startConversationMonitor() {
        conversationMonitorJob?.cancel()
        conversationMonitorJob = serviceScope.launch {
            conversation.collect { conv ->
                if (_uiState.value.status != VoiceCallStatus.Processing &&
                    _uiState.value.status != VoiceCallStatus.Speaking
                ) return@collect

                val lastMessage = conv.currentMessages.lastOrNull()
                if (lastMessage?.role != MessageRole.ASSISTANT) return@collect

                val currentText = lastMessage.toText()

                // 更新 UI 显示的 AI 回复
                _uiState.update { it.copy(assistantText = currentText) }

                // 流式 TTS: 只朗读新增的部分
                if (currentText.length > ttsSentLength) {
                    val newText = currentText.substring(ttsSentLength)
                    // 按句子分割, 朗读完整句子
                    val sentences = extractCompleteSentences(newText)
                    for (sentence in sentences) {
                        if (sentence.isNotBlank()) {
                            tts.enqueueText(sentence)
                            Log.d(TAG, "Streaming TTS: $sentence")
                        }
                    }
                    ttsSentLength = currentText.length - getPendingRemainder(newText).length
                }

                // 一旦 AI 有内容输出, 立即切换到 Speaking 状态
                // 这样用户随时可以打断, UI 反馈更即时
                if (_uiState.value.status == VoiceCallStatus.Processing && currentText.isNotBlank()) {
                    _uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
                    startInterruptDetection()
                }

                lastAssistantText = currentText
            }
        }

        // 监听生成完成 -> 等待 TTS 播放完成 -> 回到 Listening
        speakingMonitorJob?.cancel()
        speakingMonitorJob = serviceScope.launch {
            chatService.generationDoneFlow.collect { convId ->
                if (convId != conversationId) return@collect
                onGenerationDone()
            }
        }
    }

    private suspend fun onGenerationDone() {
        // 朗读最后剩余的文本
        val finalText = _uiState.value.assistantText
        if (finalText.length > ttsSentLength) {
            val remaining = finalText.substring(ttsSentLength)
            if (remaining.isNotBlank()) {
                tts.enqueueText(remaining)
                ttsSentLength = finalText.length
            }
        }

        _uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
        startInterruptDetection()
        waitForTtsToFinish()

        // 回到监听
        if (_uiState.value.status == VoiceCallStatus.Speaking) {
            startListening()
        }
    }

    private suspend fun waitForTtsToFinish() {
        // 等待 TTS 开始播放
        var waitStart = System.currentTimeMillis()
        while (!tts.isSpeaking.value && System.currentTimeMillis() - waitStart < 5000) {
            delay(100)
        }
        // 等待 TTS 播放完成.
        // 不能只靠 isSpeaking: 它在 worker 的 finally 里才会变 false,
        // 一旦 worker 挂在网络请用/音频播放上 (isSpeaking 永远 true),
        // 这里就死循环, 通话永远卡在 "正在传达".
        // 改用 "活动超时": 跟踪 TTS 最后一次处于活动状态的时间,
        // 连续 5 秒没有新的播放活动(不是 Playing/Buffering 且 isSpeaking 为 false)
        // 就认为说完了. 另勠 5 分钟硬截止兜底.
        val idleTimeoutMs = 5_000L
        val hardDeadlineMs = 300_000L
        val startTime = System.currentTimeMillis()
        var lastActiveTime = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val status = tts.playbackState.value.status
            val active = tts.isSpeaking.value ||
                status == me.rerere.tts.model.PlaybackStatus.Playing ||
                status == me.rerere.tts.model.PlaybackStatus.Buffering
            if (active) {
                lastActiveTime = now
            }
            // 连续 idleTimeoutMs 没活动 → 说完了
            if (!active && now - lastActiveTime >= idleTimeoutMs) {
                break
            }
            // 硬截止兜底 (TTS 真卡死)
            if (now - startTime > hardDeadlineMs) {
                Log.w(TAG, "TTS 播放超过 5 分钟未结束, 强制停止以防卡死")
                tts.stop()
                break
            }
            delay(300)
        }
        // 额外等待状态更新
        delay(300)
    }

    /**
     * 从增量文本中提取完整的句子 (以句号/问号/感叹号/换行结尾)
     */
    private fun extractCompleteSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (char in text) {
            current.append(char)
            if (char == '。' || char == '？' || char == '！' || char == '.' ||
                char == '?' || char == '!' || char == '\n'
            ) {
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) {
                    result.add(sentence)
                }
                current.clear()
            }
        }
        // 保存未完成的部分 (不朗读, 等下次)
        return result
    }

    /**
     * 获取增量文本中未形成完整句子的剩余部分
     */
    private fun getPendingRemainder(text: String): String {
        val lastSentenceEnd =
            text.lastIndexOfAny(charArrayOf('。', '？', '！', '.', '?', '!', '\n'))
        return if (lastSentenceEnd >= 0 && lastSentenceEnd < text.length - 1) {
            text.substring(lastSentenceEnd + 1)
        } else if (lastSentenceEnd < 0) {
            text
        } else {
            ""
        }
    }

    /**
     * Speaking 状态下的打断检测.
     *
     * 与 startVadDetection (判断"该发送了") 职责不同:
     * 这里只关心"用户是否真的开始说话了".
     *
     * 判定只看识别结果: 相对进入 Speaking 时, 必须识别出至少 [INTERRUPT_MIN_NEW_CHARS] 个
     * "有效文字" (字母/数字/汉字, 空格和标点都不算).
     * 不再用音量阈值 —— 戴耳机打字、手指碰到手机、环境杂音的音量都可能很大,
     * 但只要没识别出文字就绝不打断 AI 说话.
     */
    private fun startInterruptDetection() {
        interruptDetectJob?.cancel()
        interruptDetectJob = serviceScope.launch {
            val baselineTranscript = _uiState.value.userTranscript
            while (true) {
                delay(150)
                if (_uiState.value.status != VoiceCallStatus.Speaking) break
                if (isMuted) continue // 静音期间不判断打断

                val currentTranscript = _uiState.value.userTranscript
                // 只统计"进入 Speaking 之后新增的文字".
                // 正常情况 baseline 就是 current 的前缀; 万一转写被服务端改写导致前缀失配,
                // 退回按最长公共前缀算, 避免把旧文字当成新文字误判成打断.
                val addedText = if (currentTranscript.startsWith(baselineTranscript)) {
                    currentTranscript.substring(baselineTranscript.length)
                } else {
                    currentTranscript.substring(
                        longestCommonPrefixLength(baselineTranscript, currentTranscript)
                    )
                }
                val newCharCount = addedText.count { it.isLetterOrDigit() }

                if (newCharCount >= INTERRUPT_MIN_NEW_CHARS) {
                    Log.d(
                        TAG,
                        "检测到用户打断: 新增有效文字 ${newCharCount} 个 (transcript=$currentTranscript)"
                    )
                    interruptSpeaking()
                    break
                }
            }
        }
    }

    /**
     * 两个字符串的最长公共前缀长度 (用于转写被改写时兜底计算新增部分)
     */
    private fun longestCommonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var index = 0
        while (index < max && a[index] == b[index]) index++
        return index
    }

    /**
     * 用户打断 AI 说话 (Barge-in).
     * 不再调用 asr.start() (ASR 一直是开着的), 只做状态切换 + cancel 协程.
     */
    fun interruptSpeaking() {
        if (_uiState.value.status != VoiceCallStatus.Speaking) return
        speakingMonitorJob?.cancel()
        interruptDetectJob?.cancel()
        startListening()
    }

    /**
     * 监听 ASR 状态 (用于非流式 ASR 如 SiliconFlow).
     * 当 ASR 从 Recording -> Idle 且转写不为空时, 立即发送.
     *
     * 加了 !isMuted 判断, 避免静音操作本身触发的 Recording→非Recording 跳变被误判成"该发送了".
     *
     * 【重要】对"会给出已确定文本"的增量型 ASR (Volcengine), 这里坚决不发:
     * 它的 transcript 是"整场会话累积"的, 从这里发就会把上一句重复发一遍
     * (最典型的触发场景是 ASR 报错: status 变 Error 也是 Recording→非Recording,
     *  而那时 _uiState.status 可能还没来得及变成 Error, 仍然等于 Listening).
     * 这类 ASR 的发送完全由 VAD 的 A 路径按增量负责, 不会漏 —— A 路径在
     * Listening 期间每 100ms 轮询一次, "已确定但还没发"的文本会被它补发出去.
     */
    private fun startAsrMonitor() {
        asrMonitorJob?.cancel()
        asrMonitorJob = serviceScope.launch {
            var wasRecording = false
            asr.state.collect { asrState ->
                val isRecording = asrState.isRecording

                // 检测到从 Recording 变为非 Recording
                if (wasRecording && !isRecording && !isMuted && _uiState.value.status == VoiceCallStatus.Listening) {
                    val isIncrementalAsr =
                        asrState.finalizedText.isNotEmpty() || asrState.pendingText.isNotEmpty()
                    val transcript = asrState.transcript.trim()
                    if (isIncrementalAsr) {
                        Log.d(
                            TAG,
                            "ASR monitor: 增量型 ASR 跳过整段发送, 交给 VAD 增量路径 (transcript=${transcript.length} 字)"
                        )
                    } else if (transcript.isNotEmpty() && _uiState.value.autoSendEnabled) {
                        Log.d(TAG, "ASR monitor: Auto-send after ASR completed: $transcript")
                        sendCurrentMessage()
                    } else {
                        // 转写为空(没说话/未识别到): 非流式 ASR (SiliconFlow)
                        // 此时已停在 Idle, 不重启的话音波球不动、下一句说话发不出去.
                        // 流式 ASR 的 start() 有 isRecording 守卫, 重复调用无副作用.
                        if (!isMuted && _uiState.value.status == VoiceCallStatus.Listening) {
                            runCatching {
                                asr.start { t -> _uiState.update { it.copy(userTranscript = t) } }
                            }.onFailure { Log.e(TAG, it.toString(), it) }
                        }
                    }
                }

                wasRecording = isRecording
            }
        }
    }

    /**
     * 切换静音. 在任何状态下都要能生效/取消, 不再判断 status.
     * 静音 = 模型听不到; 取消静音 = 不管 Listening 还是 Speaking 都重新开始监听.
     */
    fun toggleMute() {
        isMuted = !isMuted
        _uiState.update { it.copy(isMuted = isMuted) }

        try {
            if (isMuted) {
                asr.stop()
            } else {
                // 不管当前是 Listening 还是 Speaking, 取消静音都要重新开始监听
                asr.start { transcript ->
                    _uiState.update { it.copy(userTranscript = transcript) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "切换静音状态失败, isMuted=$isMuted", e)
            _uiState.update { it.copy(errorMessage = "麦克风切换失败: ${e.message}") }
        }
    }

    /**
     * 切换自动发送模式. UI 上不再挂载按钮, 但保留方法 (autoSendEnabled 字段仍在用).
     */
    fun toggleAutoSend() {
        _uiState.update { it.copy(autoSendEnabled = !it.autoSendEnabled) }
    }

    /**
     * 挂断 / 结束通话.
     * 额外复位 _activeConversationId 和移除前台通知.
     */
    fun endCall() {
        vadJob?.cancel()
        speakingMonitorJob?.cancel()
        conversationMonitorJob?.cancel()
        asrMonitorJob?.cancel()
        interruptDetectJob?.cancel()
        asr.stop()
        tts.stop()
        _uiState.update {
            it.copy(status = VoiceCallStatus.Idle)
        }
        _activeConversationId.value = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "stopForeground 失败", e)
        }
    }

    /**
     * 更新振幅数据 (供 UI 动画使用)
     */
    fun updateAmplitudes(amplitudes: List<Float>) {
        _uiState.update { it.copy(amplitudes = amplitudes) }
    }

    /**
     * 构建通话通知. 通话中这种更醒目、带操作按钮的通知.
     */
    private fun buildNotification(state: VoiceCallUiState): android.app.Notification {
        val contentText = when (state.status) {
            VoiceCallStatus.Listening -> "正在聆听..."
            VoiceCallStatus.Processing -> "正在思考..."
            VoiceCallStatus.Speaking -> "正在说话..."
            VoiceCallStatus.Error -> state.errorMessage ?: "通话出错"
            VoiceCallStatus.Idle -> "通话中"
        }

        // 点击通知本体: 回到 RouteActivity 并导航到 VoiceCallPage
        val contentIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            Intent(this, RouteActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("openVoiceCallConversationId", conversationId.toString())
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 通知上的"挂断"按钮: 直接发一个带 ACTION_HANG_UP 的 Intent 给自己这个 Service
        val hangUpIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, VoiceCallService::class.java).apply { action = ACTION_HANG_UP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, VOICE_CALL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("语音通话")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "挂断", hangUpIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // 兜底, 防止外部通过 stopService 直接杀掉时状态没清理干净
            endCall()
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy 清理失败", e)
        }
        serviceScope.cancel()
    }
}
