package com.cvuong233.cinephantom.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.Animatable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

object FuturisticAnim {

    /** Staggered entrance: cards fly in from bottom with overshoot, each delayed by staggerMs */
    fun staggeredEntrance(views: List<View>, startDelay: Long = 0, staggerMs: Long = 60) {
        views.forEachIndexed { i, view ->
            view.translationY = 80f
            view.alpha = 0f
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(400)
                .setStartDelay(startDelay + i * staggerMs)
                .setInterpolator(OvershootInterpolator(0.8f))
                .start()
        }
    }

    /** Glow pulse: subtle scale pulse on a view */
    fun glowPulse(view: View, scaleMin: Float = 0.96f, scaleMax: Float = 1.04f) {
        val pulse = ValueAnimator.ofFloat(scaleMin, scaleMax).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { view.scaleX = it.animatedValue as Float; view.scaleY = it.animatedValue as Float }
        }
        pulse.start()
    }

    /** Slide up + fade in — for section headers */
    fun slideUpFade(view: View, delay: Long = 0) {
        view.translationY = 30f
        view.alpha = 0f
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(delay)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Gentle floating animation — for background elements */
    fun floatAnimation(view: View, amplitude: Float = 8f, duration: Long = 3000) {
        val float = ValueAnimator.ofFloat(0f, amplitude).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { view.translationY = it.animatedValue as Float }
        }
        float.start()
    }
}
