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
     * Subtle ambient backdrop for both Light and Dark glass modes.
     */
    fun ambientBackdrop(isDark: Boolean): Drawable {
        return object : Drawable() {
            private val basePaint = Paint()
            private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(160f, BlurMaskFilter.Blur.NORMAL)
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat().coerceAtLeast(1f)
                val h = bounds.height().toFloat().coerceAtLeast(1f)

                basePaint.shader = null
                basePaint.color = Color.parseColor(if (isDark) "#0B101B" else "#EEF2FF")
                canvas.drawRect(0f, 0f, w, h, basePaint)

                if (isDark) {
                    // Subtle, low-glare slate/indigo/teal washes
                    orbPaint.color = Color.argb(46, 79, 70, 229)
                    canvas.drawCircle(w * 0.15f, h * 0.18f, minOf(w, h) * 0.36f, orbPaint)

                    orbPaint.color = Color.argb(38, 14, 165, 233)
                    canvas.drawCircle(w * 0.85f, h * 0.50f, minOf(w, h) * 0.34f, orbPaint)

                    orbPaint.color = Color.argb(34, 139, 92, 246)
                    canvas.drawCircle(w * 0.25f, h * 0.85f, minOf(w, h) * 0.32f, orbPaint)
                } else {
                    // Subtle airy sky/lavender/mint washes for Light Glass
                    orbPaint.color = Color.argb(72, 165, 180, 252)
                    canvas.drawCircle(w * 0.15f, h * 0.16f, minOf(w, h) * 0.36f, orbPaint)

                    orbPaint.color = Color.argb(64, 125, 211, 252)
                    canvas.drawCircle(w * 0.88f, h * 0.48f, minOf(w, h) * 0.35f, orbPaint)

                    orbPaint.color = Color.argb(58, 196, 181, 253)
                    canvas.drawCircle(w * 0.22f, h * 0.84f, minOf(w, h) * 0.34f, orbPaint)
                }
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
     * Frosted translucent card adapted separately for Light and Dark readability.
     */
    fun glassCard(isDark: Boolean, radiusPx: Float = 24f, tintHex: String? = null): Drawable {
        return object : Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = when {
                    tintHex != null -> Color.parseColor(tintHex)
                    isDark -> Color.parseColor("#A6131C2E")
                    else -> Color.parseColor("#D9FFFFFF")
                }
            }
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = Color.parseColor(
                    if (isDark) "#2EFFFFFF" else "#B8FFFFFF"
                )
            }

            override fun draw(canvas: Canvas) {
                val inset = 1.5f
                val rect = RectF(
                    bounds.left + inset,
                    bounds.top + inset,
                    bounds.right - inset,
                    bounds.bottom - inset
                )
                canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, borderPaint)
            }

            override fun setAlpha(alpha: Int) {
                fillPaint.alpha = alpha
                borderPaint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                fillPaint.colorFilter = colorFilter
                borderPaint.colorFilter = colorFilter
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
     * Subtle recessed pill/surface for inner strips, inputs, and unselected chips.
     */
    fun glassSubtleSurface(isDark: Boolean, radiusPx: Float = 16f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(
                Color.parseColor(
                    if (isDark) "#800C1220" else "#8CF8FAFC"
                )
            )
            setStroke(
                1,
                Color.parseColor(if (isDark) "#24FFFFFF" else "#80CBD5E1")
            )
        }
    }

    /**
     * Softly toned pill button with subtle border rim.
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
            setStroke(1, Color.parseColor("#38FFFFFF"))
        }
    }

    /**
     * Translucent floating bottom navigation dock for Light and Dark modes.
     */
    fun glassNavDock(isDark: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 26f
            setColor(
                Color.parseColor(
                    if (isDark) "#D9101726" else "#E6FFFFFF"
                )
            )
            setStroke(
                1,
                Color.parseColor(if (isDark) "#2EFFFFFF" else "#B8CBD5E1")
            )
        }
    }

    fun styleGlassSectionHeader(tv: TextView, isDark: Boolean) {
        tv.textSize = 15.5f
        tv.letterSpacing = 0.01f
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        tv.setTextColor(Color.parseColor(if (isDark) "#F1F5F9" else "#0F172A"))
    }
}
