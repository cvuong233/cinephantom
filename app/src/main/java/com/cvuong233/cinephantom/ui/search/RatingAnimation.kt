package com.cvuong233.cinephantom.ui.search

import android.animation.ValueAnimator
import android.widget.TextView

/**
 * Animates a numeric rating value with a rolling digit effect.
 * Starts with a continuous roll (shuffling through random digits for visual effect),
 * then finalizes to the target rating.
 */
object RatingAnimation {

    private val continuousAnimators = mutableMapOf<TextView, ValueAnimator>()

    /**
     * Start continuous rolling digits on the TextView (lottery rolling effect).
     * Cycles through random values until stopped.
     */
    fun startContinuousRoll(view: TextView) {
        // Cancel any existing animation on this view
        stop(view)

        val animator = ValueAnimator.ofFloat(0f, 100f).apply {
            duration = 100000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                // Cycle through 0.0 → 9.9 range for rolling effect
                val cycle = (value % 10f)
                val whole = cycle.toInt().coerceIn(0, 9)
                val decimal = ((cycle - whole) * 10).toInt().coerceIn(0, 9)
                view.text = "IMDb $whole.$decimal"
            }
        }
        animator.start()
        continuousAnimators[view] = animator
        view.tag = "rolling"
    }

    /** Stop animation on a view. Returns false if nothing was running. */
    fun stop(view: TextView): Boolean {
        val existing = continuousAnimators.remove(view)
        if (existing != null) {
            existing.cancel()
            return true
        }
        return false
    }

    /** Start the rolling animation on a TextView from 0 to target rating.
     * Stops any running continuous roll first, then animates to the final value.
     * Uses floor-based math to avoid rounding up (e.g. 8.8 → "8.8", not "9.0"). */
    fun animateRolling(view: TextView, targetRating: Float) {
        stop(view)

        val animator = ValueAnimator.ofFloat(0f, targetRating).apply {
            duration = 500
            addUpdateListener { animation ->
                val currentValue = animation.animatedValue as Float
                val whole = currentValue.toInt()
                val decimal = ((currentValue - whole) * 10).toInt().coerceIn(0, 9)
                view.text = "IMDb $whole.$decimal"
            }
        }
        animator.start()
    }
}
