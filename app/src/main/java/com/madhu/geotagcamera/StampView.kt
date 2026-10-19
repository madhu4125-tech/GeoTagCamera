package com.madhu.geotagcamera

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/** Transparent overlay on top of the camera preview that shows the live stamp. */
class StampView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var info: StampInfo = StampInfo(null, null, "Finding place…", "", null)
        set(value) {
            field = value
            invalidate()
        }

    var showDateTime: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        StampRenderer.draw(canvas, width.toFloat(), height.toFloat(), info, showDateTime)
    }
}
