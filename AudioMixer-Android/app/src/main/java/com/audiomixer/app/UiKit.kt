package com.audiomixer.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

object Palette {
    const val BG = 0xFF0E1016.toInt()
    const val CARD = 0xFF161A24.toInt()
    const val CARD2 = 0xFF1E2431.toInt()
    const val LINE = 0xFF2A3140.toInt()
    const val ACCENT = 0xFF00D3A7.toInt()
    const val GOLD = 0xFFFFC94A.toInt()
    const val RED = 0xFFFF6B6B.toInt()
    const val BLUE = 0xFF5B93FF.toInt()
    const val TEXT = 0xFFECEEF5.toInt()
    const val MUTED = 0xFF8A90A6.toInt()
    const val ON_ACCENT = 0xFF04201A.toInt()
}

object UiKit {

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun bg(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f, ctx: Context): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        d.setColor(color)
        if (strokeColor != 0 && strokeDp > 0f) d.setStroke(dp(ctx, strokeDp), strokeColor)
        return d
    }

    fun text(ctx: Context, s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(size)
        t.setTextColor(color)
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD)
        t.includeFontPadding = false
        return t
    }

    fun card(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        l.background = bg(Palette.CARD, 18f, Palette.LINE, 1f, ctx)
        val p = dp(ctx, 14f)
        l.setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(ctx, 12f)
        l.layoutParams = lp
        return l
    }

    fun sectionTitle(ctx: Context, n: String, title: String, spoken: String): TextView {
        val t = text(ctx, "$n  $title", 13f, Palette.ACCENT, true)
        t.letterSpacing = 0.06f
        t.contentDescription = spoken
        t.isAccessibilityHeading = true
        t.layoutParams = lp(ctx, 0f, 8f)
        return t
    }

    fun lp(ctx: Context, topDp: Float = 0f, bottomDp: Float = 0f): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(ctx, topDp)
        p.bottomMargin = dp(ctx, bottomDp)
        return p
    }

    fun hint(ctx: Context, s: String): TextView = text(ctx, s, 12.5f, Palette.MUTED)

    fun button(ctx: Context, label: String, primary: Boolean): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(if (primary) 16f else 14.5f)
        b.setTextColor(if (primary) Palette.ON_ACCENT else Palette.TEXT)
        b.setTypeface(Typeface.DEFAULT_BOLD)
        b.gravity = Gravity.CENTER
        b.background = if (primary) bg(Palette.ACCENT, 14f, ctx = ctx) else bg(Palette.CARD2, 14f, Palette.LINE, 1f, ctx)
        b.minHeight = dp(ctx, 56f)
        b.minimumHeight = dp(ctx, 56f)
        b.stateListAnimator = null
        b.setPadding(dp(ctx, 10f), dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f))
        b.isEnabled = true
        return b
    }

    fun slider(ctx: Context): SeekBar {
        val s = SeekBar(ctx)
        s.max = 100
        s.progressTintList = ColorStateList.valueOf(Palette.ACCENT)
        s.thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
        s.progressBackgroundTintList = ColorStateList.valueOf(Palette.CARD2)
        s.splitTrack = false
        s.minimumHeight = dp(ctx, 48f)
        return s
    }

    fun check(ctx: Context, label: String): CheckBox {
        val c = CheckBox(ctx)
        c.text = label
        c.setTextSize(15.5f)
        c.setTextColor(Palette.TEXT)
        c.buttonTintList = ColorStateList.valueOf(Palette.ACCENT)
        c.minHeight = dp(ctx, 52f)
        c.minimumHeight = dp(ctx, 52f)
        c.setPadding(dp(ctx, 8f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f))
        return c
    }

    fun spinner(ctx: Context, description: String): Spinner {
        val s = Spinner(ctx)
        s.background = bg(Palette.CARD2, 12f, Palette.LINE, 1f, ctx)
        s.setPopupBackgroundDrawable(bg(Palette.CARD2, 12f, Palette.ACCENT, 1f, ctx))
        s.minimumHeight = dp(ctx, 56f)
        s.contentDescription = description
        return s
    }

    fun subHeading(ctx: Context, s: String): TextView {
        val t = text(ctx, s, 15.5f, Palette.TEXT, true)
        t.isAccessibilityHeading = true
        t.layoutParams = lp(ctx, 14f, 4f)
        return t
    }

    fun divider(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(Palette.LINE)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1f)).also {
            it.topMargin = dp(ctx, 10f)
            it.bottomMargin = dp(ctx, 10f)
        }
        return v
    }

    fun row(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun colorAlpha(c: Int, a: Int): Int = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}


/** Dark, large, high-contrast items for combo boxes (drop-down lists). */
class DarkAdapter(private val ctx: Context, items: List<String>) :
    ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, items) {

    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    private fun style(t: TextView, dropdown: Boolean) {
        t.setTextColor(Palette.TEXT)
        t.setTextSize(16f)
        t.minHeight = UiKit.dp(ctx, 56f)
        t.gravity = Gravity.CENTER_VERTICAL
        t.isSingleLine = false
        t.maxLines = 4
        t.setPadding(UiKit.dp(ctx, 14f), UiKit.dp(ctx, 8f), UiKit.dp(ctx, 14f), UiKit.dp(ctx, 8f))
        if (!dropdown) {
            t.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0)
            t.compoundDrawableTintList = ColorStateList.valueOf(Palette.ACCENT)
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val t = super.getView(position, convertView, parent) as TextView
        style(t, false)
        return t
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val t = super.getDropDownView(position, convertView, parent) as TextView
        style(t, true)
        t.setBackgroundColor(Palette.CARD2)
        return t
    }
}
