package com.offline.wfo

import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.cos
import kotlin.math.sin

object GlassUiTheme {

    // ==================== CENTRALIZED TEXT & BORDER TOKENS ====================

    fun textPrimaryColor(isDark: Boolean): Int {
        return Color.parseColor(if (isDark) "#FFFFFF" else "#F8FAFC")
    }

    fun textSecondaryColor(isDark: Boolean): Int {
        return Color.parseColor(if (isDark) "#E2E8F0" else "#CBD5E1")
    }

    fun borderHex(isDark: Boolean): String {
        return if (isDark) "#59FFFFFF" else "#4DFFFFFF"
    }

    // ==================== REAL BLUR HELPERS (ANDROID 12+) ====================

    fun applyGlassBlur(view: View, radiusPx: Float = 18f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                view.setRenderEffect(
                    RenderEffect.createBlurEffect(
                        radiusPx,
                        radiusPx,
                        Shader.TileMode.CLAMP
                    )
                )
            }
        }
    }

    fun clearGlassBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { view.setRenderEffect(null) }
        }
    }

    // ==================== LAYER 0: MOVING AMBIENT GLASS BACKDROP ====================

    /**
     * Multi-layer dark canvas with gently drifting Indigo, Cyan, and Violet light pools
     * so translucent glass cards show live depth and separation behind them.
     */
    fun ambientBackdrop(isDark: Boolean): Drawable {
        return MovingAuraBackdropDrawable(isDark)
    }

    private class MovingAuraBackdropDrawable(private val isDark: Boolean) : Drawable() {
        private val basePaint = Paint()
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(195f, BlurMaskFilter.Blur.NORMAL)
        }

        private var phase: Float = 0f
        private var animator: ValueAnimator? = null

        init {
            startDrift()
        }

        private fun startDrift() {
            if (animator != null) return
            val selfRef = WeakReference(this)
            animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
                duration = 14000L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener { va ->
                    val target = selfRef.get()
                    if (target == null) {
                        va.cancel()
                        return@addUpdateListener
                    }
                    target.phase = va.animatedValue as Float
                    target.invalidateSelf()
                }
                start()
            }
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat().coerceAtLeast(1f)
            val h = bounds.height().toFloat().coerceAtLeast(1f)
            val minDim = minOf(w, h)

            basePaint.color = Color.parseColor(if (isDark) "#060914" else "#0A1022")
            canvas.drawRect(0f, 0f, w, h, basePaint)

            // 1. Electric Indigo orb (top-left drift)
            val cx1 = w * (0.18f + 0.10f * sin(phase.toDouble()).toFloat())
            val cy1 = h * (0.16f + 0.06f * cos(phase.toDouble()).toFloat())
            orbPaint.color = Color.argb(if (isDark) 128 else 140, 99, 102, 241)
            canvas.drawCircle(cx1, cy1, minDim * 0.42f, orbPaint)

            // 2. Luminous Cyan/Aqua orb (mid-right drift)
            val cx2 = w * (0.85f + 0.09f * cos((phase * 0.85f).toDouble()).toFloat())
            val cy2 = h * (0.46f + 0.08f * sin((phase * 0.85f).toDouble()).toFloat())
            orbPaint.color = Color.argb(if (isDark) 112 else 124, 6, 182, 212)
            canvas.drawCircle(cx2, cy2, minDim * 0.38f, orbPaint)

            // 3. Soft Violet/Magenta orb (lower-center drift)
            val cx3 = w * (0.34f + 0.12f * sin((phase * 0.65f + 1.4f).toDouble()).toFloat())
            val cy3 = h * (0.84f + 0.06f * cos((phase * 0.65f + 1.4f).toDouble()).toFloat())
            orbPaint.color = Color.argb(if (isDark) 118 else 130, 168, 85, 247)
            canvas.drawCircle(cx3, cy3, minDim * 0.44f, orbPaint)
        }

        override fun setAlpha(alpha: Int) {
            basePaint.alpha = alpha
            orbPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            basePaint.colorFilter = colorFilter
            orbPaint.colorFilter = colorFilter
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ==================== LAYER 1: PRIMARY FROSTED GLASS CARD ====================

    /**
     * Multi-layered glass card with translucent fill, subtle inner glass rim,
     * and crisp specular outer stroke so card boundaries stand out clearly.
     */
    fun glassCard(isDark: Boolean, radiusPx: Float = 26f, tintHex: String? = null): Drawable {
        return object : Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = when {
                    tintHex != null -> Color.parseColor(tintHex)
                    isDark -> Color.parseColor("#A811182C")
                    else -> Color.parseColor("#9E151F38")
                }
            }
            private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = Color.parseColor("#29FFFFFF")
            }
            private val outerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.parseColor(borderHex(isDark))
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
                canvas.drawRoundRect(innerRect, innerRadius, innerRadius, innerRimPaint)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, outerRimPaint)
            }

            override fun setAlpha(alpha: Int) {
                fillPaint.alpha = alpha
                innerRimPaint.alpha = alpha
                outerRimPaint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                fillPaint.colorFilter = colorFilter
                innerRimPaint.colorFilter = colorFilter
                outerRimPaint.colorFilter = colorFilter
            }

            override fun onBoundsChange(bounds: Rect) {
                super.onBoundsChange(bounds)
                invalidateSelf()
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    // ==================== LAYER 2: LIFTED / RECESSED GLASS SUB-SURFACE ====================

    /**
     * Distinct secondary glass surface for KPI rows, calendar cells, log bars, and chips.
     */
    fun glassSubtleSurface(isDark: Boolean, radiusPx: Float = 16f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(
                Color.parseColor(
                    if (isDark) "#7828385C" else "#6E2E4068"
                )
            )
            setStroke(1, Color.parseColor("#42FFFFFF"))
        }
    }

    // ==================== LAYER 3: BRIGHT GRADIENT PILLS & DOCK ====================

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
            setStroke(1, Color.parseColor("#66FFFFFF"))
        }
    }

    fun glassNavDock(isDark: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            setColor(Color.parseColor(if (isDark) "#CC10172A" else "#C4151F36"))
            setStroke(2, Color.parseColor("#4DFFFFFF"))
        }
    }

    fun styleGlassSectionHeader(tv: TextView, isDark: Boolean) {
        tv.textSize = 16f
        tv.letterSpacing = 0.01f
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        tv.setTextColor(textPrimaryColor(isDark))
    }
}
