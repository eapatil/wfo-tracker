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
     * Dark glassmorphism canvas with soft glowing ambient color orbs behind frosted panels.
     */
    fun ambientBackdrop(isDark: Boolean): Drawable {
        return object : Drawable() {
            private val basePaint = Paint()
            private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(140f, BlurMaskFilter.Blur.NORMAL)
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat().coerceAtLeast(1f)
                val h = bounds.height().toFloat().coerceAtLeast(1f)

                basePaint.shader = null
                basePaint.color = if (isDark) {
                    Color.parseColor("#06080F")
                } else {
                    Color.parseColor("#0D1220")
                }
                canvas.drawRect(0f, 0f, w, h, basePaint)

                // Top-left warm coral/crimson glow
                orbPaint.color = Color.argb(if (isDark) 115 else 135, 239, 68, 68)
                canvas.drawCircle(w * 0.12f, h * 0.16f, minOf(w, h) * 0.34f, orbPaint)

                // Mid-right electric violet/indigo glow
                orbPaint.color = Color.argb(if (isDark) 120 else 140, 124, 58, 237)
                canvas.drawCircle(w * 0.88f, h * 0.44f, minOf(w, h) * 0.38f, orbPaint)

                // Lower-left cyan/blue glow
                orbPaint.color = Color.argb(if (isDark) 105 else 125, 14, 165, 233)
                canvas.drawCircle(w * 0.22f, h * 0.82f, minOf(w, h) * 0.36f, orbPaint)
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
     * Translucent glass card with subtle top/outer specular border and optional tint.
     */
    fun glassCard(isDark: Boolean, radiusPx: Float = 28f, tintHex: String? = null): Drawable {
        return object : Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = when {
                    tintHex != null -> Color.parseColor(tintHex)
                    isDark -> Color.parseColor("#CC141926")
                    else -> Color.parseColor("#B8192236")
                }
            }
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                color = Color.parseColor(if (isDark) "#40FFFFFF" else "#52FFFFFF")
            }
            private val topHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.parseColor("#33FFFFFF")
            }

            override fun draw(canvas: Canvas) {
                val inset = 2f
                val rect = RectF(
                    bounds.left + inset,
                    bounds.top + inset,
                    bounds.right - inset,
                    bounds.bottom - inset
                )
                canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, borderPaint)

                val highlightRect = RectF(
                    rect.left + 12f,
                    rect.top + 2f,
                    rect.right - 12f,
                    rect.bottom - 2f
                )
                canvas.drawLine(
                    highlightRect.left,
                    highlightRect.top,
                    highlightRect.right,
                    highlightRect.top,
                    topHighlightPaint
                )
            }

            override fun setAlpha(alpha: Int) {
                fillPaint.alpha = alpha
                borderPaint.alpha = alpha
                topHighlightPaint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                fillPaint.colorFilter = colorFilter
                borderPaint.colorFilter = colorFilter
                topHighlightPaint.colorFilter = colorFilter
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
     * Recessed dark glass surface for inner boxes, KPI rows, and unselected chips.
     */
    fun glassSubtleSurface(isDark: Boolean, radiusPx: Float = 18f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(
                Color.parseColor(
                    if (isDark) "#99090D16" else "#8C0F172A"
                )
            )
            setStroke(2, Color.parseColor("#26FFFFFF"))
        }
    }

    /**
     * Vibrant pill CTA button matching modern glass UI highlights.
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
            setStroke(2, Color.parseColor("#4DFFFFFF"))
        }
    }

    /**
     * Floating dark frosted bottom navigation dock.
     */
    fun glassNavDock(isDark: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            setColor(Color.parseColor(if (isDark) "#D9111622" else "#CC141B2D"))
            setStroke(2, Color.parseColor("#38FFFFFF"))
        }
    }

    fun styleGlassSectionHeader(tv: TextView, isDark: Boolean) {
        tv.textSize = 16f
        tv.letterSpacing = 0.01f
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        tv.setTextColor(Color.parseColor("#F8FAFC"))
    }
}
