/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.voice

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CallEnd01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MicOff01
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallPage"

// 浅色版色板 (暖白底 + 浅橙光斑 + 细线图标)
private val ColorBgWarm = Color(0xFFF6F3EF)      // 暖奶白纯色背景
private val ColorBlob = Color(0xFFF2A77E)        // 光斑浅橙
private val ColorStatusText = Color(0xFFA8A09A)  // 状态文字浅灰
private val ColorSubtitleUser = Color(0xFFB8643E) // 用户说的话 (深橙, 暖白底上够清晰)
private val ColorSubtitleAi = Color(0xFF4A423C)   // AI 回复 (深棕灰)
private val ColorIcon = Color(0xFF4A423C)        // 麦克风细线图标
private val ColorHangUp = Color(0xFFD9794F)      // 挂断图标 (偏深的浅橙)

// 字幕行为参数 (想调层次 / 间距 / 动效都在这里改)
private const val SubtitleFadeMs = 300             // 层次过渡 / 新句入场时长
private const val SubtitleMaxBlocks = 5            // 最多显示几句 (当前句 + 往上 4 句)
private const val SubtitleLiveUserId = "subtitle_live_user" // 用户"正在说"那句的稳定 key
private const val SubtitleCurrentFontSp = 20f      // 当前句字号
private const val SubtitleHistoryFontSp = 17f      // 历史句字号
private const val SubtitleCurrentWeight = 500      // 当前句字重 (Medium)
private const val SubtitleHistoryWeight = 400      // 历史句字重 (Normal)
private const val SubtitleNameFontSp = 12f         // 说话人名行字号
private const val SubtitleResumeAutoScrollMs = 4000L // 用户松手后多久恢复自动滚动
// 透明度: 当前句 -> 往上第 4 句
private val SubtitleAlphas = listOf(1f, 0.6f, 0.45f, 0.32f, 0.22f)
private val SubtitleBlockSpacing = 16.dp           // 块与块的垂直间距
private val SubtitleHorizontalPadding = 32.dp      // 字幕左右内边距
private val SubtitleEnterOffset = 8.dp             // 新句子入场时从下方上移的距离
private val SubtitleTopFadeHeight = 56.dp          // 字幕顶部渐隐高度

/**
 * 语音通话页面 (ChatGPT 独立语音模式风格)
 *
 * - 暖白纯色背景 (#F6F3EF) + 一团柔和浅橙光斑 (状态区分只靠呼吸动效, 颜色不变)
 * - 光斑下方是一行浅灰小字状态提示
 * - 多行流式字幕 (聆听/思考显示, 传达/就绪隐藏)
 * - 底部只有两个无底色的细线图标按钮: 麦克风 / 挂断
 * - 返回键 = 切后台继续通话 (不挂断)
 * - 业务逻辑全部跑在 VoiceCallService 里, 页面只负责 bind + 显示 uiState
 */
@Composable
fun VoiceCallPage(
    conversationId: Uuid,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var boundService by remember { mutableStateOf<VoiceCallService?>(null) }

    // 录音权限
    val asrPermission = rememberPermissionState(PermissionRecordAudio)

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as? VoiceCallService.LocalBinder)?.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
    }

    // bind/unbind Service. 关键: onDispose 只解绑, 绝不调用 endCall/stopService
    DisposableEffect(conversationId) {
        // 如果 Service 还没在跑这个对话的通话, 先 start 再 bind
        // 如果已经在跑 (用户是从通知点回来的), 只 bind, 不重复 start
        if (VoiceCallService.activeConversationId.value != conversationId.toString()) {
            // 权限检查: 没权限先请求, 拿到权限后再 start (见下方 LaunchedEffect)
            if (asrPermission.allRequiredPermissionsGranted) {
                VoiceCallService.start(context, conversationId.toString())
            }
        }
        val intent = Intent(context, VoiceCallService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        onDispose {
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                Log.e(TAG, "unbindService 失败", e)
            }
        }
    }

    // 权限授予后启动 Service (如果还没启动)
    LaunchedEffect(asrPermission.allRequiredPermissionsGranted) {
        if (asrPermission.allRequiredPermissionsGranted &&
            VoiceCallService.activeConversationId.value == null
        ) {
            VoiceCallService.start(context, conversationId.toString())
        }
    }

    // 进入页面时, 如果还没权限, 请求权限
    LaunchedEffect(Unit) {
        if (!asrPermission.allRequiredPermissionsGranted) {
            asrPermission.requestPermissions()
        }
    }

    // boundService 为 null (绑定还没完成) 时, 显示默认空状态
    val uiState by (boundService?.uiState
        ?: MutableStateFlow(VoiceCallUiState()).asStateFlow())
        .collectAsStateWithLifecycle(initialValue = VoiceCallUiState())

    // 返回键 = 切后台继续通话, 不挂断. 这是这次改动最核心的行为变化.
    BackHandler {
        onBack()
    }

    // 背景变浅了, 状态栏/导航栏图标必须改成深色, 否则在奶白底上看不见
    val view = LocalView.current
    val systemBarController = remember(view) {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
    }
    if (!view.isInEditMode && systemBarController != null) {
        // Theme.kt 也会写这两个值, 这里持续重申, 保证通话页始终使用深色图标
        SideEffect {
            systemBarController.isAppearanceLightStatusBars = true
            systemBarController.isAppearanceLightNavigationBars = true
        }
        val previousStatusBarIcons = remember { systemBarController.isAppearanceLightStatusBars }
        val previousNavBarIcons = remember { systemBarController.isAppearanceLightNavigationBars }
        DisposableEffect(view) {
            onDispose {
                systemBarController.isAppearanceLightStatusBars = previousStatusBarIcons
                systemBarController.isAppearanceLightNavigationBars = previousNavBarIcons
            }
        }
    }

    // ---------- 字幕数据 ----------
    // 历史句直接取当前对话里的消息 (不另建历史存储), 并且只保留"本次通话开始之后"的用户 / AI 消息
    val settings = LocalSettings.current
    // 注意: boundService 有可能在 conversationId 还没初始化时就被拿到 (onBind 早于 onStartCommand),
    // 所以这里等 activeConversationId 就绪后再取 conversation, 避免 lateinit 未初始化崩溃
    val activeCallId by VoiceCallService.activeConversationId.collectAsStateWithLifecycle()
    val conversationFlow: Flow<Conversation?> = remember(boundService, activeCallId) {
        val service = boundService
        if (service == null || activeCallId == null) {
            MutableStateFlow<Conversation?>(null)
        } else {
            runCatching { service.conversation }.getOrNull() ?: MutableStateFlow<Conversation?>(null)
        }
    }
    val conversation by conversationFlow.collectAsStateWithLifecycle(initialValue = null)

    val zone = remember { TimeZone.currentSystemDefault() }
    val callStartedAt = uiState.callStartedAt
    val historyLines = remember(conversation, callStartedAt) {
        if (callStartedAt <= 0L) {
            emptyList()
        } else {
            conversation?.currentMessages.orEmpty()
                .filter { it.createdAt.toInstant(zone).toEpochMilliseconds() >= callStartedAt }
                .mapNotNull { it.toSubtitleLine() }
        }
    }

    // AI 流式生成时, 对话里的文本可能比 uiState.assistantText 更新得慢一点;
    // 只要后者是前者的"更长版本", 就用它替换最后一条, 字幕跟得更顺
    val streamingAssistantText = uiState.assistantText
    val linesWithStreaming = remember(historyLines, streamingAssistantText) {
        val last = historyLines.lastOrNull()
        if (last != null &&
            last.speaker == SubtitleSpeaker.Assistant &&
            streamingAssistantText.length > last.text.length &&
            streamingAssistantText.startsWith(last.text)
        ) {
            historyLines.dropLast(1) + last.copy(text = streamingAssistantText)
        } else {
            historyLines
        }
    }

    // 用户正在说、还没发出去的那句就是最新一句 (优先级最高)
    val liveUserText = uiState.userPendingTranscript
    val allLines = remember(linesWithStreaming, liveUserText) {
        if (liveUserText.isBlank()) {
            linesWithStreaming
        } else {
            linesWithStreaming + SubtitleLine(
                id = SubtitleLiveUserId,
                speaker = SubtitleSpeaker.User,
                text = liveUserText
            )
        }
    }

    // 只保留最近几句, 同时算好层次 (0 = 当前句) 和"这一句要不要显示说话人名"
    val userName = settings.displaySetting.userNickname.trim()
    val assistantName = (
        settings.getAssistantById(conversation?.assistantId ?: settings.assistantId)?.name
            ?: settings.getCurrentAssistant().name
        ).trim()
    val subtitleItems = remember(allLines, userName, assistantName) {
        val visible = allLines.takeLast(SubtitleMaxBlocks)
        val firstVisibleIndex = allLines.size - visible.size
        visible
            .mapIndexed { index, line ->
                val previousSpeaker = allLines.getOrNull(firstVisibleIndex + index - 1)?.speaker
                SubtitleBlockItem(
                    id = line.id,
                    speaker = line.speaker,
                    // 只在说话人切换的那一句上方显示名字; 名字取不到就整行不显示
                    speakerName = if (previousSpeaker != line.speaker) {
                        when (line.speaker) {
                            SubtitleSpeaker.User -> userName.ifBlank { null }
                            SubtitleSpeaker.Assistant -> assistantName.ifBlank { null }
                        }
                    } else {
                        null
                    },
                    text = line.text,
                    level = visible.lastIndex - index
                )
            }
            // LazyColumn 用了 reverseLayout, 所以这里传"最新 -> 最旧"
            .reversed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBgWarm)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部: 柔和光斑 + 下方状态文字 (固定不参与权重, 把剩余空间全让给字幕)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 64.dp)
            ) {
                VoiceOrb(
                    amplitudes = uiState.amplitudes,
                    status = uiState.status,
                    baseColor = ColorBlob,
                    size = 300.dp
                )

                Spacer(modifier = Modifier.size(12.dp))

                Text(
                    text = statusText(uiState.status),
                    color = ColorStatusText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal
                )

                // 绑定还没完成时, 显示一个小的加载指示器
                if (boundService == null) {
                    Spacer(modifier = Modifier.size(16.dp))
                    CircularProgressIndicator(
                        color = ColorStatusText,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 字幕区: 占满"状态文字下方 → 按钮上方"的全部空间
            // 用户和 AI 的话按时间顺序排成一列 (最新的在最下面), 像歌词一样越旧越浅
            SubtitleArea(
                items = subtitleItems,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // 错误信息 (保留, 方便调试)
            uiState.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }

            // 底部: 只有两个按钮 (ChatGPT 风格)
            // 左: 静音, 右: 挂断.
            Row(
                horizontalArrangement = Arrangement.spacedBy(56.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 64.dp)
            ) {
                // 静音按钮 (细线条图标, 无底色)
                val canControl = boundService != null
                ControlButton(
                    icon = if (uiState.isMuted) HugeIcons.MicOff01 else HugeIcons.Mic01,
                    contentDescription = "静音",
                    onClick = {
                        boundService?.toggleMute()
                    },
                    iconTint = ColorIcon,
                    enabled = canControl
                )

                // 挂断按钮 (听筒朝下的图标, 偏深的浅橙)
                ControlButton(
                    icon = HugeIcons.CallEnd01,
                    contentDescription = "挂断",
                    onClick = {
                        VoiceCallService.stop(context)
                        onBack()
                    },
                    iconTint = ColorHangUp,
                    enabled = true // 挂断始终可点, 即使 service 还没绑定
                )
            }
        }
    }
}

/**
 * 字幕区: 像歌词一样的对话流
 *
 * - 用户和 AI 的话按时间顺序排成一列, 最新的在最下面 (reverseLayout 天然贴底)
 * - 每条消息一个稳定的 key (消息 id), 所以 AI 流式变长时不会重播入场动画
 * - 层次 (距离最新一句有几句) 决定字号 / 透明度 / 字重, 变化用 300ms 过渡, 不跳变
 * - 新句子: 淡入 + 从下方上移 8dp; 已经在屏幕上的句子只做整体上移
 * - 用户手动滑动时暂停自动滚动, 松手几秒后恢复
 */
@Composable
private fun SubtitleArea(
    items: List<SubtitleBlockItem>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    var userTouching by remember { mutableStateOf(false) }

    // 用户按住字幕区就暂停自动滚动, 松手几秒后恢复
    LaunchedEffect(userTouching) {
        if (userTouching) {
            autoScroll = false
        } else {
            delay(SubtitleResumeAutoScrollMs)
            autoScroll = true
        }
    }

    // 有新句子进来时滚到最新 (reverseLayout: 最新的在 index 0)
    val newestId = items.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (autoScroll && items.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter
    ) {
        // 顶部渐隐最多只占字幕区的 1/3, 避免屏幕矮的时候遮罩把字幕整个吃掉
        val topFadeHeight = minOf(SubtitleTopFadeHeight, maxHeight / 3)

        LazyColumn(
            state = listState,
            // 反转布局: index 0 贴在最下面, 所以传进来的顺序是"最新 -> 最旧"
            reverseLayout = true,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SubtitleBlockSpacing, Alignment.Bottom),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SubtitleHorizontalPadding)
                .pointerInput(Unit) {
                    // 只观察手指按下 / 抬起, 不消费事件, 所以不影响正常滚动
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        userTouching = true
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                        }
                        userTouching = false
                    }
                }
        ) {
            items(items = items, key = { it.id }) { item ->
                SubtitleBlock(
                    item = item,
                    modifier = Modifier.animateItem()
                )
            }
        }

        // 顶部渐隐: 用背景色把最上面一段"吃掉", 文字往上滚时慢慢消失
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(topFadeHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(ColorBgWarm, ColorBgWarm.copy(alpha = 0f))
                    )
                )
        )
    }
}

/**
 * 一条字幕块: [说话人名 (可选行)] + [正文]
 */
@Composable
private fun SubtitleBlock(
    item: SubtitleBlockItem,
    modifier: Modifier = Modifier,
) {
    // 层次动画: 从"当前句"变成历史句时, 字号 / 透明度 / 字重一起平滑过渡
    val animatedLevel by animateFloatAsState(
        targetValue = item.level.toFloat(),
        animationSpec = tween(SubtitleFadeMs),
        label = "subtitleLevel"
    )
    // 入场动画: 淡入 + 从下方上移; 每个块只在自己第一次出现时播一次
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, animationSpec = tween(SubtitleFadeMs))
    }

    val fontSp = subtitleFontSpFor(animatedLevel)
    val blockAlpha = subtitleAlphaFor(animatedLevel) * enter.value
    val textColor = when (item.speaker) {
        SubtitleSpeaker.User -> ColorSubtitleUser
        SubtitleSpeaker.Assistant -> ColorSubtitleAi
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = blockAlpha
                // enter: 0 -> 1 时从下方 8dp 滑到最终位置
                translationY = (1f - enter.value) * SubtitleEnterOffset.toPx()
            }
    ) {
        item.speakerName?.let { name ->
            Text(
                text = name,
                color = ColorStatusText,
                fontSize = SubtitleNameFontSp.sp,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = item.text,
            color = textColor,
            fontSize = fontSp.sp,
            lineHeight = (fontSp * 1.4f).sp,
            fontWeight = FontWeight(subtitleWeightFor(animatedLevel)),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 说话人 */
private enum class SubtitleSpeaker { User, Assistant }

/** 一句字幕: 一条用户消息 / 一条 AI 回复 / 用户"正在说"的那句话 */
private data class SubtitleLine(
    val id: String,
    val speaker: SubtitleSpeaker,
    val text: String,
)

/** 真正拿去渲染的字幕块 (层次和"要不要显示说话人名"都已经算好) */
private data class SubtitleBlockItem(
    val id: String,
    val speaker: SubtitleSpeaker,
    val speakerName: String?,
    val text: String,
    val level: Int, // 0 = 当前句 (最下面那句), 越大越旧
)

/** 一条消息 -> 一句字幕; 只取用户 / AI 的文本消息, 其它角色和空文本都跳过 */
private fun UIMessage.toSubtitleLine(): SubtitleLine? {
    val speaker = when (role) {
        MessageRole.USER -> SubtitleSpeaker.User
        MessageRole.ASSISTANT -> SubtitleSpeaker.Assistant
        else -> return null
    }
    val text = toText()
    if (text.isBlank()) return null
    return SubtitleLine(id = id.toString(), speaker = speaker, text = text)
}

/** 层次 -> 透明度 (在 [SubtitleAlphas] 之间线性插值, 所以过渡是平滑的) */
private fun subtitleAlphaFor(level: Float): Float {
    val clamped = level.coerceIn(0f, SubtitleAlphas.lastIndex.toFloat())
    val lower = floor(clamped).toInt().coerceIn(0, SubtitleAlphas.lastIndex)
    val upper = (lower + 1).coerceAtMost(SubtitleAlphas.lastIndex)
    val fraction = clamped - lower
    return SubtitleAlphas[lower] + (SubtitleAlphas[upper] - SubtitleAlphas[lower]) * fraction
}

/** 层次 -> 字号 (当前句 20sp, 历史句 17sp, 中间插值) */
private fun subtitleFontSpFor(level: Float): Float {
    val fraction = level.coerceIn(0f, 1f)
    return SubtitleCurrentFontSp + (SubtitleHistoryFontSp - SubtitleCurrentFontSp) * fraction
}

/** 层次 -> 字重 (当前句 Medium, 历史句 Normal, 中间插值) */
private fun subtitleWeightFor(level: Float): Int {
    val fraction = level.coerceIn(0f, 1f)
    return (SubtitleCurrentWeight + (SubtitleHistoryWeight - SubtitleCurrentWeight) * fraction).roundToInt()
}

/**
 * 控制按钮 (细线条图标, 无底色)
 *
 * 触摸区域仍然是 [size] 的圆形范围 (和改造前的实心圆按钮一样大), 只是不再画底色;
 * contentDescription 保持不变, 无障碍描述不受影响.
 */
@Composable
private fun ControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconTint: Color,
    size: Dp = 64.dp,
    enabled: Boolean = true,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) iconTint else iconTint.copy(alpha = 0.35f),
            modifier = Modifier.size(size * 0.45f)
        )
    }
}

private fun statusText(status: VoiceCallStatus): String = when (status) {
    VoiceCallStatus.Idle -> "准备就绪"
    VoiceCallStatus.Listening -> "正在聆听"
    VoiceCallStatus.Processing -> "正在思考"
    VoiceCallStatus.Speaking -> "正在传达"
    VoiceCallStatus.Error -> "出错了"
}
