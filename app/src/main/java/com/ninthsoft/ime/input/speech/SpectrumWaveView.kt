package com.ninthsoft.ime.input.speech

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.withScale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class SpectrumWaveView(context: Context) : View(context), ISpeechView {
    override val view: View get() = this

    private var bgColor = Color.TRANSPARENT
    private var waveformColor = Color.CYAN
    private var targetVolume = 0
    private var smoothVolume = 0f
    private val barCount = 9
    private val contentScale = 0.48f
    private val centerIndex = (barCount - 1) / 2

    // 基础高斯权重
    private val gaussianWeights = FloatArray(barCount) { i ->
        val center = centerIndex.toFloat()
        val sigma = barCount / 4.0f
        val x = (i - center) / sigma
        var weight = exp((-x * x / 2).toDouble()).toFloat()

        // 适当削减两端中高音权重
        val distanceFromCenter = abs(i - center)
        if (distanceFromCenter > 2f) {
            weight *= 0.82f
        }
        weight
    }

    private val currentHeights = FloatArray(barCount) { 0.08f }
    private val targetHeights = FloatArray(barCount)

    private val noisePhase = FloatArray(barCount) {
        Random.nextFloat() * 6.28f
    }

    private val noiseSpeed = FloatArray(barCount) {
        0.015f + Random.nextFloat() * 0.025f
    }

    private val idlePhase = FloatArray(barCount) {
        Random.nextFloat() * 6.28f
    }

    private val idleSpeed = FloatArray(barCount) {
        0.01f + Random.nextFloat() * 0.02f
    }

    private val microPhase = FloatArray(barCount) {
        Random.nextFloat() * 6.28f
    }
    private val microSpeed = FloatArray(barCount) {
        0.03f + Random.nextFloat() * 0.03f
    }

    private val barColors = intArrayOf(
        0xFFFF6B6B.toInt(),
        0xFFFF9F43.toInt(),
        0xFFFFEAA7.toInt(),
        0xFF55EFC4.toInt(),
        0xFF74D7AE.toInt(),
        0xFF54A0FF.toInt(),
        0xFF5F27CD.toInt(),
        0xFF9B59B6.toInt(),
        0xFF3498DB.toInt()
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var globalPhase = 0f
    private var isAnimating = false

    private val frameCallback = object : Runnable {
        override fun run() {
            if (!isAnimating) return
            updateFrame()
            postOnAnimation(this)
        }
    }

    private fun updateFrame() {
        val target = targetVolume.coerceIn(0, 100) / 100f
        smoothVolume += (target - smoothVolume) * 0.16f
        globalPhase += 0.035f

        // 中高音音量映射抑制
        val volume = if (smoothVolume > 0.6f) {
            0.6f + (smoothVolume - 0.6f).pow(1.3f) * 0.6f
        } else {
            smoothVolume.pow(1.2f)
        }

        for (i in 0 until barCount) {
            noisePhase[i] += noiseSpeed[i]
            idlePhase[i] += idleSpeed[i]
            microPhase[i] += microSpeed[i]

            val base = gaussianWeights[i]
            val noise = sin(noisePhase[i].toDouble()).toFloat() * 0.06f
            val idle = sin(idlePhase[i].toDouble()).toFloat() * 0.015f

            // 极小范围的邻域微扰，避免第4、5、6根波动过剧烈
            val microJitter = if (i == centerIndex) {
                0f
            } else {
                sin(microPhase[i].toDouble()).toFloat() * 0.025f * volume
            }

            val breathing = sin(
                (globalPhase + i * 0.25f).toDouble()
            ).toFloat() * 0.02f

            // 基础高度计算
            var height = 0.08f + (base * volume * 0.38f) + microJitter + (noise * volume * 0.025f) + idle + breathing

            // 中高音及过渡区缩减（第4、5、6根周围的过渡更平滑）
            val distanceFromCenter = abs(i - centerIndex)
            if (distanceFromCenter >= 2) {
                val baseFloor = 0.08f + idle + breathing
                val dynamicPart = height - baseFloor
                if (dynamicPart > 0f) {
                    height = baseFloor + dynamicPart * 0.56f
                }
            }

            targetHeights[i] = height
        }

        // 用平滑的滑动平均（邻域柔化）来处理第 4、5、6 根，消除突兀的阶梯感
        // 让第 5 根自然略高于邻居，而不是硬拔高
        for (i in 1 until barCount - 1) {
            // 对中间区域进行轻度的均值融合，使 4、5、6 之间的过渡如丝般顺滑
            if (i in 3..5) {
                targetHeights[i] = (targetHeights[i - 1] * 0.25f) + (targetHeights[i] * 0.5f) + (targetHeights[i + 1] * 0.25f)
            }
        }

        // 确保最中间那根（第5根）依然保持自然最长
        if (targetHeights[centerIndex] < targetHeights[centerIndex - 1]) {
            targetHeights[centerIndex] = targetHeights[centerIndex - 1] * 1.05f
        }
        if (targetHeights[centerIndex] < targetHeights[centerIndex + 1]) {
            targetHeights[centerIndex] = targetHeights[centerIndex + 1] * 1.05f
        }

        for (i in 0 until barCount) {
            targetHeights[i] = targetHeights[i].coerceIn(0.05f, 0.58f)
            updateSmoothHeight(i)
        }

        invalidate()
    }

    private fun updateSmoothHeight(index: Int) {
        val speed = 0.18f + (index % 3) * 0.02f
        currentHeights[index] += (targetHeights[index] - currentHeights[index]) * speed
    }

    override fun updateColors(
        backgroundColor: Int, waveformColor: Int, barColor: Int
    ) {
        bgColor = backgroundColor
        this.waveformColor = waveformColor
        invalidate()
    }

    override fun setWaveformColor(color: Int) {
        waveformColor = color
        invalidate()
    }

    override fun setVolume(volume: Int) {
        targetVolume = volume.coerceIn(0, 100)
    }

    override fun startAnim() {
        if (!isAnimating) {
            isAnimating = true
            postOnAnimation(frameCallback)
        }
    }

    override fun stopAnim() {
        isAnimating = false
        removeCallbacks(frameCallback)
        targetVolume = 0
        smoothVolume = 0f
        currentHeights.fill(0.08f)
        invalidate()
    }

    override fun release() {
        stopAnim()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)

        if (hasWindowFocus) {
            startAnim()
        } else {
            stopAnim()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        canvas.drawColor(bgColor)
        canvas.withScale(
            contentScale, contentScale, width / 2f, height / 2f
        ) {
            val baseDim = min(width, height).toFloat()
            val capsulePad = baseDim * 0.08f
            val capsuleTop = capsulePad * 2.8f
            val capsuleBottom = height - capsulePad * 2.8f
            val capsuleRight = width - capsulePad
            val capsuleRadius = (capsuleBottom - capsuleTop) / 2f

            val capsuleAlpha = 30
            paint.color = Color.argb(
                capsuleAlpha,
                Color.red(waveformColor),
                Color.green(waveformColor),
                Color.blue(waveformColor)
            )
            drawRoundRect(
                capsulePad,
                capsuleTop,
                capsuleRight,
                capsuleBottom,
                capsuleRadius,
                capsuleRadius,
                paint
            )

            val barAreaPad = baseDim * 0.15f
            val barWidth = (width - barAreaPad * 2) / (barCount * 2.2f)
            val spacing = barWidth * 0.6f
            val totalWidth = barCount * barWidth + (barCount - 1) * spacing
            val startX = (width - totalWidth) / 2f
            val alpha = (90 + smoothVolume * 165).toInt().coerceIn(80, 255)
            val barMaxHeight = height * 0.44f

            for (i in 0 until barCount) {
                val barHeight = barMaxHeight * currentHeights[i] / 0.58f
                val x = startX + i * (barWidth + spacing)
                val y = (height - barHeight) / 2f
                val color = barColors[i]
                paint.color = Color.argb(
                    alpha, Color.red(color), Color.green(color), Color.blue(color)
                )
                drawRoundRect(
                    x, y, x + barWidth, y + barHeight, barWidth / 2f, barWidth / 2f, paint
                )
            }
        }
    }
}