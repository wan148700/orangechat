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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CallEnd01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MicOff01
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallPage"

// 浅色版色板 (暖白底 + 浅橙光斑 + 细线图标)
private val ColorBgWarm = Color(0xFFF6F3EF)      // 暖奶白纯色背景
private val ColorBlob = Color(0xFFF2A77E)        // 光斑浅橙
private val ColorStatusText = Color(0xFFA8A09A)  // 状态文字浅灰
private val ColorSubtitle = Color(0xFF4A423C)    // 字幕文字 (浅底上必须够深才看得清)
private val ColorIcon = Color(0xFF4A423C)        // 麦克风细线图标
private val ColorHangUp = Color(0xFFD9794F)      // 挂断图标 (偏深的浅橙)

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBgWarm)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 中部: 柔和光斑 + 下方状态文字 (状态文字从顶部移到这里, 弱化成浅灰小字)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
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

            // 字幕区: 按状态切换显示谁的字幕
            // - 聆听/思考: 显示用户刚说的话 (思考时保留, 让用户确认 AI 听到了什么)
            // - 传达/就绪: 显示 AI 的话 (传达时逐句增长, 说完后仍保留在屏上,
            //   直到下一轮用户开始说话、Service 清掉 assistantText 才换掉)
            val subtitleText = when (uiState.status) {
                VoiceCallStatus.Listening,
                VoiceCallStatus.Processing -> uiState.userTranscript
                VoiceCallStatus.Speaking,
                VoiceCallStatus.Idle -> uiState.assistantText
                VoiceCallStatus.Error -> ""
            }
            if (subtitleText.isNotBlank()) {
                StreamingSubtitle(
                    text = subtitleText
                )
            }

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
 * 多行流式字幕
 *
 * - 自动换行, 超出容器高度向上滚动, 始终显示最新文字
 * - Listening/Processing 状态使用; Speaking/Idle 由上层隐藏
 */
@Composable
private fun StreamingSubtitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    // 文本增长时滚到最底部, 让最新内容始终可见
    LaunchedEffect(text) {
        if (text.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier
            .padding(horizontal = 36.dp, vertical = 16.dp)
            .heightIn(max = 140.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text.ifBlank { " " },
            color = ColorSubtitle.copy(alpha = 0.9f),
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
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
