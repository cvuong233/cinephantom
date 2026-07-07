package com.cvuong233.cinephantom.ui.search

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * A RecyclerView that wraps its content like normal, but never measures taller than
 * [maxHeightPx]. Used for the search suggestions dropdown so a long list scrolls
 * internally instead of pushing the search bar off screen.
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Wrap content as usual, but never request more than maxHeightPx — the parent
        // (wrap_content, no weight) would otherwise size us to fit all rows and push
        // whatever comes after us (the search bar) off screen.
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}
