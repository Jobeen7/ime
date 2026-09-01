package com.ninthsoft.ime.base.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View

fun View.slideDownExpand(duration: Long = 120) {
    animate().cancel()
    animate().setListener(null)
    pivotY = 0f
    scaleY = 0f
    visibility = View.VISIBLE
    invalidate()
    animate().scaleY(1f).setDuration(duration).start()
}

fun View.slideUpCollapse(
    duration: Long = 120,
    onEnd: (() -> Unit)? = null,
) {
    if (visibility != View.VISIBLE) return
    var cancelled = false
    animate().scaleY(0f).setDuration(duration).setListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            if (cancelled) return
            onEnd?.invoke()
            visibility = View.GONE
            scaleY = 1f
        }

        override fun onAnimationCancel(animation: Animator) {
            cancelled = true
        }
    }).start()
}
