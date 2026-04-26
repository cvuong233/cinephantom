package com.cvuong233.cinephantom.ui.detail

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Simple flow/wrap layout: children are laid out left-to-right and
 * wrap to the next line when they exceed the container width.
 */
class FlowLayout(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)

        var totalHeight = paddingTop + paddingBottom
        var lineWidth = paddingLeft.toFloat()
        var lineHeight = 0
        var maxLineWidth = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, totalHeight)
            val lp = child.layoutParams as? MarginLayoutParams ?: continue

            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (lineWidth + childWidth > width - paddingRight) {
                // Wrap to next line
                totalHeight += lineHeight
                maxLineWidth = maxOf(maxLineWidth, lineWidth.toInt())
                lineWidth = paddingLeft.toFloat()
                lineHeight = 0
            }

            lineWidth += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }

        totalHeight += lineHeight
        maxLineWidth = maxOf(maxLineWidth, lineWidth.toInt())

        val resolvedWidth = if (widthMode == MeasureSpec.AT_MOST) {
            minOf(maxLineWidth + paddingRight, width)
        } else {
            width
        }
        setMeasuredDimension(
            resolveSize(resolvedWidth, widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var lineX = paddingLeft
        var lineY = paddingTop
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue

            val lp = child.layoutParams as? MarginLayoutParams ?: continue
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (lineX + childWidth > width - paddingRight) {
                // Wrap to next line
                lineY += lineHeight
                lineX = paddingLeft
                lineHeight = 0
            }

            val cx = lineX + lp.leftMargin
            val cy = lineY + lp.topMargin
            child.layout(cx, cy, cx + child.measuredWidth, cy + child.measuredHeight)

            lineX += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateLayoutParams(p: LayoutParams): LayoutParams {
        return MarginLayoutParams(p)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    override fun checkLayoutParams(p: LayoutParams): Boolean = p is MarginLayoutParams
}
