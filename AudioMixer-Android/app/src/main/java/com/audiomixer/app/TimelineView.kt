package com.audiomixer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Small timeline drawing: one horizontal lane per track, so overlaps, gaps and locks are
 * visible at a glance, plus the live playhead while the final mix is running.
 */
class TimelineView(ctx: Context) : View(ctx) {

    class Lane(val start: Float, val len: Float, val color: Int, val locked: Boolean, val label: String)

    private var lanes: List<Lane> = emptyList()
    private var head = 0f
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0B1210.toInt()
        textSize = UiKit.dp(ctx, 11f).toFloat()
        typeface = Typeface.DEFAULT_BOLD
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.GOLD }
    private val rect = RectF()
    private val rowH: Float = UiKit.dp(ctx, 24f).toFloat()

    init {
        setBackgroundColor(Palette.CARD2)
        setPadding(0, UiKit.dp(ctx, 8f), 0, UiKit.dp(ctx, 8f))
    }

    fun setLanes(l: List<Lane>) {
        lanes = l
        requestLayout()
        invalidate()
    }

    fun setHead(f: Float) {
        head = if (f < 0f) -1f else f.coerceAtMost(1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val wanted = paddingTop + paddingBottom + (lanes.size.coerceAtLeast(1) * rowH).toInt() + UiKit.dp(context, 4f)
        setMeasuredDimension(w, wanted.coerceAtLeast(UiKit.dp(context, 34f)))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat() - paddingLeft - paddingRight
        val left = paddingLeft.toFloat()
        if (lanes.isEmpty()) return
        val radius = rowH / 3f
        for ((i, lane) in lanes.withIndex()) {
            val top = paddingTop + i * rowH + rowH * 0.14f
            val bottom = top + rowH * 0.72f
            val x0 = left + lane.start * w
            val x1 = left + (lane.start + lane.len) * w
            rect.set(x0, top, x1.coerceAtLeast(x0 + rowH * 0.5f), bottom)
            bar.color = lane.color
            bar.alpha = if (lane.locked) 235 else 205
            canvas.drawRoundRect(rect, radius, radius, bar)
            if (lane.locked) {
                bar.style = Paint.Style.STROKE
                bar.color = Palette.GOLD
                bar.strokeWidth = UiKit.dp(context, 1.6f).toFloat()
                canvas.drawRoundRect(rect, radius, radius, bar)
                bar.style = Paint.Style.FILL
            }
            if (rect.width() > UiKit.dp(context, 26f)) {
                canvas.drawText(lane.label, rect.left + UiKit.dp(context, 5f), rect.centerY() + txt.textSize * 0.36f, txt)
            }
        }
        if (head < 0f) return
        val hx = left + head * w
        line.strokeWidth = UiKit.dp(context, 2f).toFloat()
        canvas.drawLine(hx, paddingTop.toFloat(), hx, height - paddingBottom.toFloat(), line)
    }
}
