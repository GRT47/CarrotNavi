package com.example.carrotnavi

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

class FakeEqView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF") // 은은한 흰색 투명
        style = Paint.Style.FILL
    }
    
    companion object {
        const val STYLE_BAR = 0
        const val STYLE_WAVE = 1
        const val STYLE_CIRCLE = 2
    }
    
    private var currentStyle = STYLE_BAR
    
    fun setEqStyle(style: Int) {
        currentStyle = style
        invalidate()
    }

    private val barCount = 15
    private val barWidthRatio = 0.6f
    private val heights = FloatArray(barCount)
    private val targetHeights = FloatArray(barCount)
    
    private var isAnimating = false
    private var animator: ValueAnimator? = null
    private var currentFraction = 0f
    
    init {
        for (i in 0 until barCount) {
            heights[i] = 0.1f
            targetHeights[i] = 0.1f
        }
    }

    fun setPlaying(playing: Boolean) {
        if (playing) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    private fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            
            addUpdateListener {
                val fraction = it.animatedFraction
                currentFraction += 0.05f // For wave/circle continuous animations
                for (i in 0 until barCount) {
                    if (fraction < 0.1f) {
                        targetHeights[i] = Random.nextFloat() * 0.8f + 0.1f
                    }
                    val diff = targetHeights[i] - heights[i]
                    heights[i] += diff * 0.2f
                }
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        isAnimating = false
        animator?.cancel()
        animator = null
        
        // 서서히 가라앉히기
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 500
            addUpdateListener {
                for (i in 0 until barCount) {
                    heights[i] = heights[i] * 0.8f
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        val cx = width / 2f
        val cy = height / 2f
        
        when (currentStyle) {
            STYLE_BAR -> drawBarStyle(canvas, width, height)
            STYLE_WAVE -> drawWaveStyle(canvas, width, height)
            STYLE_CIRCLE -> drawCircleStyle(canvas, cx, cy, height)
        }
    }
    
    private fun drawBarStyle(canvas: Canvas, width: Float, height: Float) {
        val totalBarSpace = width / barCount
        val barWidth = totalBarSpace * barWidthRatio
        val spacing = totalBarSpace - barWidth
        
        for (i in 0 until barCount) {
            val left = i * totalBarSpace + spacing / 2
            val right = left + barWidth
            val h = heights[i] * height
            val top = height - h
            
            canvas.drawRoundRect(left, top, right, height, barWidth / 2, barWidth / 2, paint)
        }
    }

    private fun drawWaveStyle(canvas: Canvas, width: Float, height: Float) {
        val totalBarSpace = width / barCount
        val barWidth = totalBarSpace * barWidthRatio
        val spacing = totalBarSpace - barWidth
        val midY = height / 2f

        for (i in 0 until barCount) {
            val left = i * totalBarSpace + spacing / 2
            val right = left + barWidth
            
            // Generate a smooth wave using currentFraction and sine function
            val waveHeight = (Math.sin((i * 0.5f + currentFraction).toDouble()).toFloat() * 0.5f + 0.5f) * height * 0.8f
            // Blend with random heights to make it responsive
            val h = waveHeight * 0.5f + heights[i] * height * 0.5f
            
            val top = midY - h / 2f
            val bottom = midY + h / 2f
            
            canvas.drawRoundRect(left, top, right, bottom, barWidth / 2, barWidth / 2, paint)
        }
    }

    private fun drawCircleStyle(canvas: Canvas, cx: Float, cy: Float, height: Float) {
        val maxRadius = Math.min(cx, cy) * 0.8f
        
        // Base pulsating circle
        val avgHeight = heights.average().toFloat()
        val radius = maxRadius * 0.5f + maxRadius * 0.5f * avgHeight
        canvas.drawCircle(cx, cy, radius, paint)
        
        // Outer rings
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        val outerRadius = radius + (currentFraction % 1f) * maxRadius * 0.5f
        paint.alpha = (255 * (1f - (currentFraction % 1f))).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, outerRadius, paint)
        
        // Restore paint
        paint.style = Paint.Style.FILL
        paint.alpha = 68 // #44 is 68 in decimal
    }
}
