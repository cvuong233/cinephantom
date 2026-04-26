package com.cvuong233.cinephantom.ui.search

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class ShimmerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseRect = RectF()
    private val cornerRadii = floatArrayOf(12f, 12f, 12f, 12f, 12f, 12f, 12f, 12f)

    private val colors = intArrayOf(
        0xFF1E2A47.toInt(), 0xFF334466.toInt(), 0xFF4A5A7A.toInt(),
        0xFF334466.toInt(), 0xFF1E2A47.toInt()
    )
    private val positions = floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f)

    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (animator?.isRunning == true) updateShader(0f)
    }

    private fun updateShader(progress: Float) {
        val w = width.toFloat()
        val shimmerW = w * 2f
        val offset = (-shimmerW / 2) + progress * (w + shimmerW)
        paint.shader = LinearGradient(
            offset, 0f, offset + shimmerW, 0f,
            colors, positions, Shader.TileMode.CLAMP
        )
        invalidate()
    }

    fun startShimmer() {
        stopShimmer()
        visibility = View.VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { updateShader(it.animatedFraction) }
            start()
        }
    }

    fun stopShimmer() { animator?.cancel(); animator = null }
    fun stopAndHide() { stopShimmer(); visibility = View.GONE }

    override fun onDraw(canvas: Canvas) {
        baseRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(baseRect, 12f, 12f, paint)
    }

    override fun onDetachedFromWindow() { stopShimmer(); super.onDetachedFromWindow() }
}
