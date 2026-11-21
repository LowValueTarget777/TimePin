package org.timepin.app

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import java.time.YearMonth

internal class PageHost(context: Context) : FrameLayout(context) {
    private val easing = DecelerateInterpolator(1.5f)
    private var outgoing: View? = null
    private var lastPage: String? = null
    private var lastMonth: YearMonth? = null
    private var lastGroup: SettingGroup? = null

    fun display(view: View, page: String, month: YearMonth?, group: SettingGroup?) {
        val tabChanged = page != lastPage
        val direction =
            when {
                tabChanged -> 0
                month != null && lastMonth != null -> month.compareTo(lastMonth).coerceIn(-1, 1)
                group != lastGroup -> if (group == null) -1 else 1
                else -> 0
            }
        lastPage = page
        lastMonth = month
        lastGroup = group

        val old = getChildAt(0)
        if (old === view) return
        clearOutgoing()
        old?.animate()?.cancel()
        removeAllViews()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        addView(view)
        if (
            old == null ||
                !isLaidOut ||
                (!tabChanged && direction == 0) ||
                !ValueAnimator.areAnimatorsEnabled()
        )
            return

        val density = resources.displayMetrics.density
        val dx = direction * minOf(width * .18f, 52f * density)
        val dy = if (tabChanged) 12f * density else 0f
        // The outgoing page is visual only. Input goes to the new page immediately.
        overlay.add(old)
        outgoing = old
        old.animate()
            .alpha(0f)
            .translationX(-dx * .45f)
            .translationY(-dy * .4f)
            .setDuration(120)
            .setInterpolator(easing)
            .withEndAction {
                overlay.remove(old)
                if (outgoing === old) outgoing = null
            }
            .start()
        view.alpha = .55f
        view.translationX = dx
        view.translationY = dy
        view
            .animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(190)
            .setInterpolator(easing)
            .start()
    }

    private fun clearOutgoing() {
        outgoing?.let {
            it.animate().cancel()
            overlay.remove(it)
        }
        outgoing = null
    }

    override fun onDetachedFromWindow() {
        clearOutgoing()
        super.onDetachedFromWindow()
    }
}
