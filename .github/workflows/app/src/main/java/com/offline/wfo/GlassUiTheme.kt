package com.offline.wfo

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object GlassUiTheme {

    /**
     * Ambient multi-stop mesh-style gradient behind translucent glass cards.
     */
    fun ambientBackdrop(isDark: Boolean): GradientDrawable {
        val colors = if (isDark) {
            intArrayOf(
                Color.parseColor("#070B19"),
                Color.parseColor("#111B36"),
                Color.parseColor("#17153B"),
                Color.parseColor("#09111E")
            )
        } else {
            intArrayOf(
                Color.parseColor("#E0E7FF"),
                Color.parseColor("#EDE9FE"),
                Color.parseColor("#E0F2FE"),
                Color.parseColor("#F1F5F9")
            )
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
    }

    /**
     * Frosted translucent card with top-highlight specular edge and soft outer border.
     */
    fun glassCard(isDark: Boolean, radiusPx: Float = 26f, tintHex: String? = null): Drawable {
        val fillColor = when {
            tintHex != null -> Color.parseColor(tintHex)
            isDark -> Color.parseColor("#8C17223B")   // ~55% translucent deep slate
            else -> Color.parseColor("#B8FFFFFF")     // ~72% translucent frosted white
        }
        val strokeColor = if (isDark) {
            Color.parseColor("#3894A3B8")             // Luminous frosted rim
        } else {
            Color.parseColor("#CCFFFFFF")             // Crisp white glass edge
        }
        val innerHighlight = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fillColor)
            setStroke(2, strokeColor)
        }
        return innerHighlight
    }

    /**
     * Subtle recessed glass pill for KPI strips, inputs, and status chips.
     */
    fun glassSubtleSurface(isDark: Boolean, radiusPx: Float = 18f): GradientDrawable {
        val fill = if (isDark) Color.parseColor("#660F172A") else Color.parseColor("#80FFFFFF")
        val stroke = if (isDark) Color.parseColor("#2E94A3B8") else Color.parseColor("#99CBD5E1")
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fill)
            setStroke(1, stroke)
        }
    }

    /**
     * Glowing translucent gradient CTA button (replaces flat solid rectangles).
     */
    fun glassAccentButton(
        startHex: String,
        endHex: String,
        radiusPx: Float = 20f
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
        ).apply {
            cornerRadius = radiusPx
            setStroke(2, Color.parseColor("#55FFFFFF"))
        }
    }

    /**
     * Translucent floating bottom navigation dock.
     */
    fun glassNavDock(isDark: Boolean): GradientDrawable {
        val fill = if (isDark) Color.parseColor("#CC111827") else Color.parseColor("#D9FFFFFF")
        val stroke = if (isDark) Color.parseColor("#3394A3B8") else Color.parseColor("#B3CBD5E1")
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            setColor(fill)
            setStroke(2, stroke)
        }
    }

    /**
     * Converts raw status text into cleaner typographic hierarchy (Title + subtle subtitle)
     * without changing underlying strings.
     */
    fun styleGlassSectionHeader(tv: TextView, isDark: Boolean) {
        tv.textSize = 15.5f
        tv.letterSpacing = 0.01f
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        tv.setTextColor(Color.parseColor(if (isDark) "#F8FAFC" else "#0F172A"))
    }
}
