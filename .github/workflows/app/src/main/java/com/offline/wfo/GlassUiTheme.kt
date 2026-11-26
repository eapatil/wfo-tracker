package com.offline.wfo

import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
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

    // ==================== LIGHT & DARK GLASS TOKENS ====================

    // Light Theme: Frosted Soft White Panel
    val lightCanvasStart = Color.parseColor("#F1F5F9")
    val lightCanvasEnd = Color.parseColor("#E2E8F0")
    val lightGlassSurface = Color.parseColor("#66FFFFFF") // ~40% frosted white (0.40)
    val lightGlassBorder = Color.parseColor("#73FFFFFF")  // ~45% border (0.45)
    val lightGlassHighlight = Color.parseColor("#BFFFFFFF") // ~75% top/left highlight (0.75)
    val lightTextPrimary = Color.parseColor("#0F172A")
    val lightTextSecondary = Color.parseColor("#475569")

    // Dark Theme: Smoked Deep Velvet Glass
    val darkCanvasStart = Color.parseColor("#090D16")
    val darkCanvasEnd = Color.parseColor("#05070F")
    val darkGlassSurface = Color.parseColor("#590F172A") // ~35% deep slate micro-opacity (0.35)
    val darkGlassBorder = Color.parseColor("#0FFFFFFF")  // ~6% subtle border (0.06)
    val darkGlassHighlight = Color.parseColor("#29FFFFFF") // ~16% top/left highlight (0.16)
    val darkTextPrimary = Color.parseColor("#FFFFFF")
    val darkTextSecondary = Color.parseColor("#94A3B8")

    // Shared Accents & Sub-Surfaces
    val orbIndigo = Color.parseColor("#6366F1")
    val orbCyan = Color.parseColor("#06B6D4")
    val orbViolet = Color.parseColor("#8B5CF6")

    val textOnAccent = Color.parseColor("#FFFFFF")

    val accentIndigo = Color.parseColor("#4F46E5")
    val accentIndigoEnd = Color.parseColor("#6366F1")
    val accentBlue = Color.parseColor("#0284C7")
    val accentBlueEnd = Color.parseColor("#0EA5E9")
    val accentEmerald = Color.parseColor("#059669")
    val accentEmeraldEnd = Color.parseColor("#10B981")
    val accentPurple = Color.parseColor("#7C3AED")
    val accentPurpleEnd = Color.parseColor("#8B5CF6")
    val accentAmber = Color.parseColor("#D97706")
    val accentAmberEnd = Color.parseColor("#F59E0B")
    val accentSlate = Color.parseColor("#334155")
    val accentSlateEnd = Color.parseColor("#475569")

    // High-contrast status colors tuned for both frosted light and smoked dark glass
    fun statusSuccessText(isDark: Boolean): Int =
        Color.parseColor(if (isDark) "#34D399" else "#047857")

    fun statusWarningText(isDark: Boolean): Int =
        Color.parseColor(if (isDark) "#FBBF24" else "#B45309")

    fun statusDangerText(isDark: Boolean): Int =
        Color.parseColor(if (isDark) "#F87171" else "#B91C1C")

    fun statusInfoText(isDark: Boolean): Int =
        Color.parseColor(if (isDark) "#A5B4FC" else "#3730A3")

    fun statusHolidayText(isDark: Boolean): Int =
        Color.parseColor(if (isDark) "#C4B5FD" else "#5B21B6")

    fun statusTint(isDark: Boolean, kind: String): Int {
        return when (kind) {
            "success" -> Color.parseColor(if (isDark) "#59064E3B" else "#80D1FAE5")
            "warning" -> Color.parseColor(if (isDark) "#59451A03" else "#80FEF3C7")
            "danger" -> Color.parseColor(if (isDark) "#59450A0A" else "#80FEE2E2")
            "holiday" -> Color.parseColor(if (isDark) "#592E1065" else "#80EDE9FE")
            else -> Color.parseColor(if (isDark) "#591E1B4B" else "#80E0E7FF")
        }
    }

    // ==================== CENTRALIZED ACCESSORS ====================

    fun textPrimaryColor(isDark: Boolean): Int =
        if (isDark) darkTextPrimary else lightTextPrimary

    fun textSecondaryColor(isDark: Boolean): Int =
        if (isDark) darkTextSecondary else lightTextSecondary

    fun borderColor(isDark: Boolean): Int =
        if (isDark) darkGlassHighlight else lightGlassHighlight

    fun borderHex(isDark: Boolean): String =
        String.format("#%08X", borderColor(isDark))

    fun accentPairForHex(hex: String): Pair<Int, Int> {
        return when (hex.trim().uppercase(Locale.US)) {
            "#4F46E5", "#6366F1" -> accentIndigo to accentIndigoEnd
            "#0284C7", "#0EA5E9" -> accentBlue to accentBlueEnd
            "#10B981", "#059669" -> accentEmerald to accentEmeraldEnd
            "#8B5CF6", "#7C3AED" -> accentPurple to accentPurpleEnd
            "#F59E0B", "#D97706" -> accentAmber to accentAmberEnd
            "#334155", "#475569" -> accentSlate to accentSlateEnd
            else -> {
                val parsed = runCatching { Color.parseColor(hex) }.getOrDefault(accentIndigo)
                parsed to parsed
            }
        }
    }

    // ==================== BLUR HELPERS (ANDROID 12+) ====================

    fun applyGlassBlur(view: View, radiusPx: Float = 20f) {
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

    // ==================== LAYER 0: CANVAS + MOVING AMBIENT GLOWS ====================

    fun ambientBackdrop(isDark: Boolean): Drawable = CanvasBackdropDrawable(isDark)

    private class CanvasBackdropDrawable(private val isDark: Boolean) : Drawable() {
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(210f, BlurMaskFilter.Blur.NORMAL)
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
                duration = 15000L
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

        private fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat().coerceAtLeast(1f)
            val h = bounds.height().toFloat().coerceAtLeast(1f)
            val minDim = minOf(w, h)

            val startCol = if (isDark) darkCanvasStart else lightCanvasStart
            val endCol = if (isDark) darkCanvasEnd else lightCanvasEnd
            basePaint.shader = LinearGradient(0f, 0f, w, h, startCol, endCol, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, basePaint)

            val orbAlphaBase = if (isDark) 82 else 68
            val cx1 = w * (0.18f + 0.10f * sin(phase.toDouble()).toFloat())
            val cy1 = h * (0.16f + 0.06f * cos(phase.toDouble()).toFloat())
            orbPaint.color = withAlpha(orbIndigo, orbAlphaBase)
            canvas.drawCircle(cx1, cy1, minDim * 0.42f, orbPaint)

            val cx2 = w * (0.85f + 0.09f * cos((phase * 0.85f).toDouble()).toFloat())
            val cy2 = h * (0.48f + 0.08f * sin((phase * 0.85f).toDouble()).toFloat())
            orbPaint.color = withAlpha(orbCyan, (orbAlphaBase * 0.85f).roundToInt())
            canvas.drawCircle(cx2, cy2, minDim * 0.38f, orbPaint)

            val cx3 = w * (0.34f + 0.12f * sin((phase * 0.65f + 1.4f).toDouble()).toFloat())
            val cy3 = h * (0.85f + 0.06f * cos((phase * 0.65f + 1.4f).toDouble()).toFloat())
            orbPaint.color = withAlpha(orbViolet, (orbAlphaBase * 0.90f).roundToInt())
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

    // ==================== LAYER 1: FROSTED GLASS CARD + SPECULAR RIM ====================

    fun glassCard(
        isDark: Boolean,
        radiusPx: Float = 24f,
        tintColor: Int? = null
    ): Drawable {
        return object : Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = tintColor ?: if (isDark) darkGlassSurface else lightGlassSurface
            }
            private val baseBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = if (isDark) darkGlassBorder else lightGlassBorder
            }
            private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = if (isDark) darkGlassHighlight else lightGlassHighlight
            }

            override fun draw(canvas: Canvas) {
                val rect = RectF(
                    bounds.left + 1.5f,
                    bounds.top + 1.5f,
                    bounds.right - 1.5f,
                    bounds.bottom - 1.5f
                )
                canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, baseBorderPaint)

                // Directional top & left specular highlight edge
                val topHighlight = RectF(
                    rect.left + 1f,
                    rect.top + 1f,
                    rect.right - 1f,
                    rect.top + (rect.height() * 0.35f).coerceAtLeast(12f)
                )
                canvas.drawArc(topHighlight, 180f, 120f, false, highlightPaint)
            }

            override fun setAlpha(alpha: Int) {
                fillPaint.alpha = alpha
                baseBorderPaint.alpha = alpha
                highlightPaint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                fillPaint.colorFilter = colorFilter
                baseBorderPaint.colorFilter = colorFilter
                highlightPaint.colorFilter = colorFilter
            }

            override fun onBoundsChange(bounds: Rect) {
                super.onBoundsChange(bounds)
                invalidateSelf()
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    fun glassCardHex(isDark: Boolean, radiusPx: Float = 24f, tintHex: String): Drawable =
        glassCard(isDark, radiusPx, runCatching { Color.parseColor(tintHex) }.getOrNull())

    // ==================== LAYER 2: SUBTLE INNER GLASS SURFACE ====================

    fun glassSubtleSurface(isDark: Boolean, radiusPx: Float = 16f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(
                if (isDark) Color.parseColor("#421E293B") else Color.parseColor("#59FFFFFF")
            )
            setStroke(
                1,
                if (isDark) darkGlassHighlight else lightGlassBorder
            )
        }
    }

    // ==================== LAYER 3: TRANSLUCENT ACCENT PILLS & DOCK ====================

    fun glassAccentButton(
        startColor: Int,
        endColor: Int,
        radiusPx: Float = 18f,
        translucent: Boolean = true
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(startColor, endColor)
        ).apply {
            cornerRadius = radiusPx
            if (translucent) {
                alpha = 196
            }
            setStroke(1, Color.parseColor("#40FFFFFF"))
        }
    }

    fun glassAccentButtonHex(
        startHex: String,
        endHex: String,
        radiusPx: Float = 18f,
        translucent: Boolean = true
    ): GradientDrawable {
        val (sColor, eColor) = accentPairForHex(startHex)
        val (_, endOverride) = accentPairForHex(endHex)
        return glassAccentButton(
            sColor,
            if (startHex.equals(endHex, ignoreCase = true)) eColor else endOverride,
            radiusPx,
            translucent
        )
    }

    fun glassNavDock(isDark: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 26f
            setColor(if (isDark) Color.parseColor("#B3090D16") else Color.parseColor("#C7FFFFFF"))
            setStroke(1, if (isDark) darkGlassHighlight else lightGlassHighlight)
        }
    }

    fun styleGlassSectionHeader(tv: TextView, isDark: Boolean) {
        tv.textSize = 16f
        tv.letterSpacing = 0.01f
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        tv.setTextColor(textPrimaryColor(isDark))
    }
}
