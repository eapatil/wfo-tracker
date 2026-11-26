package com.offline.wfo

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

object GlassUiTheme {

    /**
     * Layer 0: Dark obsidian base with soft ambient color pools so glass layers have depth behind them.
     */
    fun ambientBackdrop(isDark: Boolean): Drawable {
        return object : Drawable() {
            private val basePaint = Paint()
            private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(180f, BlurMaskFilter.Blur.NORMAL)
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat().coerceAtLeast(1f)
                val h = bounds.height().toFloat().coerceAtLeast(1f)

                basePaint.color = Color.parseColor(if (isDark) "#060811" else "#0B1020")
                canvas.drawRect(0f, 0f, w, h, basePaint)

                // Indigo / violet glow upper-left
                orbPaint.color = Color.argb(if (isDark) 95 else 110, 99, 102, 241)
                canvas.drawCircle(w * 0.14f, h * 0.15f, minOf(w, h) * 0.38f, orbPaint)

                // Cyan / teal glow mid-right
                orbPaint.color = Color.argb(if (isDark) 80 else 95, 14, 165, 233)
                canvas.drawCircle(w * 0.88f, h * 0.46f, minOf(w, h) * 0.36f, orbPaint)

                // Violet / magenta glow lower-center
                orbPaint.color = Color.argb(if (isDark) 85 else 100, 139, 92, 246)
                canvas.drawCircle(w * 0.35f, h * 0.86f, minOf(w, h) * 0.40f, orbPaint)
            }

            override fun setAlpha(alpha: Int) {
                basePaint.alpha = alpha
                orbPaint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                basePaint.colorFilter = colorFilter
                orbPaint.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    /**
     * Layer 1: Multi-layered frosted glass card with outer rim, translucent fill,
     * and an inner top-edge glass sheen to show thickness.
     */
    fun glassCard(isDark: Boolean, radiusPx: Float = 26f, tintHex: String? = null): Drawable {
        return object : Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = when {
                    tintHex != null -> Color.parseColor(tintHex)
                    isDark -> Color.parseColor("#B312192B")
                    else -> Color.parseColor("#A6182238")
                }
            }
            private val innerSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.parseColor("#24FFFFFF")
            }
            private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.parseColor(if (isDark) "#47FFFFFF" else "#59FFFFFF")
            }

            override fun draw(canvas: Canvas) {
                val rect = RectF(
                    bounds.left + 2f,
                    bounds.top + 2f,
                    bounds.right - 2f,
                    bounds.bottom - 2f
                )
                canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)

                val innerRect = RectF(
                    rect.left + 4f,
                    rect.top + 4f,
                    rect.right - 4f,
                    rect.bottom - 4f
                )
                val innerRadius = (radiusPx - 4f).coerceAtLeast(4f)
                canvas.drawRoundRect(innerRect, innerRadius, innerRadius, innerSheenPaint)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, rimPaint)
            }

            override fun setAlpha(alpha: Int) {
                fillPaint.alpha = alpha
                innerSheenPaint.alpha = alpha
                rimPaint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                fillPaint.colorFilter = colorFilter
                innerSheenPaint.colorFilter = colorFilter
                rimPaint.colorFilter = colorFilter
            }

            override fun onBoundsChange(bounds: Rect) {
                super.onBoundsChange(bounds)
                invalidateSelf()
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    /**
     * Layer 2: Secondary glass sub-panel (KPI row, log summary, unselected chips)
     * with a distinct glass tint so it separates clearly from Layer 1 cards.
     */
    fun glassSubtleSurface(isDark: Boolean, radiusPx: Float = 16f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(
                Color.parseColor(
                    if (isDark) "#7325324D" else "#662A3856"
                )
            )
            setStroke(1, Color.parseColor("#33FFFFFF"))
        }
    }

    /**
     * Layer 3: Active gradient pill for primary buttons, selected tabs, and active filters.
     */
    fun glassAccentButton(
        startHex: String,
        endHex: String,
        radiusPx: Float = 18f
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
        ).apply {
            cornerRadius = radiusPx
            setStroke(1, Color.parseColor("#59FFFFFF"))
        }
    }

    /**
     * Layer 3 Dock: Floating translucent navigation dock at the bottom.
     */
    fun glassNavDock(isDark: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            setColor(Color.parseColor(if (isDark) "#CC101728" else "#BF151E33"))
            setStroke(2, Color.parseColor("#42FFFFFF"))
        }
    }

    fun styleGlassSectionHeader(tv: TextView, isDark: Boolean) {
        tv.textSize = 16f
        tv.letterSpacing = 0.01f
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        tv.setTextColor(Color.parseColor("#F8FAFC"))
    }
}
