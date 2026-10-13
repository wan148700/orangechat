/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 柔和光斑 (浅色版)
 *
 * - 一团大半径的淡色光斑: 用多段径向渐变 stop 逼近高斯衰减, 最外圈 alpha 已经是 0,
 *   所以看不出"圆"的边界, 而且不依赖 Modifier.blur (低版本 Android 也有同样的柔和感)
 * - 颜色恒定: 状态区分只靠动效, 不换颜色
 * - 聆听 = 慢呼吸 (一个来回 3.6s, 缩放幅度 ±5%)
 * - 思考 = 稍快呼吸 (一个来回 2.4s)
 * - 说话 = 呼吸 + 跟随音量轻微跳动
 * - Idle / Error = 最慢呼吸
 */
@Composable
fun VoiceOrb(
    modifier: Modifier = Modifier,
    amplitudes: List<Float> = emptyList(),
    status: VoiceCallStatus = VoiceCallStatus.Idle,
    baseColor: Color = Color(0xFFF2A77E), // 浅橙光斑
    accentColor: Color = Color.White,
    size: Dp = 300.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice_orb")

    // 呼吸节奏: 只有速度在变, 颜色不变
    // (repeatMode = Reverse, 所以"一个来回" = 单程时长 x2)
    val breatheDurationMs = when (status) {
        VoiceCallStatus.Listening -> 1800 // 一个来回 3.6s - 慢
        VoiceCallStatus.Processing -> 1200 // 一个来回 2.4s - 稍快
        VoiceCallStatus.Speaking -> 1500
        else -> 2600
    }
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f, // 缩放幅度 ±5%
        animationSpec = infiniteRepeatable(
            animation = tween(breatheDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // 振幅 -> 说话时的轻微跳动 (聆听/思考不跟音量, 只做呼吸)
    val currentAmplitude = if (amplitudes.isNotEmpty()) {
        amplitudes.takeLast(4).average().toFloat()
    } else {
        0f
    }
    val amplitudeBoost = if (status == VoiceCallStatus.Speaking) {
        currentAmplitude.coerceIn(0f, 1f) * 0.06f
    } else {
        0f
    }
    val scale = breathe + amplitudeBoost

    Canvas(
        modifier = modifier.size(size)
    ) {
        val canvasSize = this.size.minDimension
        val center = Offset(canvasSize / 2f, canvasSize / 2f)
        val radius = canvasSize / 2f * scale

        // 主光斑: 中心透明度 0.55, 向外一路衰减到完全透明
        // 多段 stop 让衰减接近高斯曲线, 边缘完全看不出圆的边界
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to baseColor.copy(alpha = 0.55f),
                0.20f to baseColor.copy(alpha = 0.46f),
                0.40f to baseColor.copy(alpha = 0.32f),
                0.60f to baseColor.copy(alpha = 0.18f),
                0.78f to baseColor.copy(alpha = 0.07f),
                0.90f to baseColor.copy(alpha = 0.02f),
                1.00f to baseColor.copy(alpha = 0f),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )

        // 极淡的内核提亮 (只加一点点, 避免中心看起来太平)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.10f),
                    accentColor.copy(alpha = 0f)
                ),
                center = center,
                radius = radius * 0.45f
            ),
            radius = radius * 0.45f,
            center = center
        )
    }
}
