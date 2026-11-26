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
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object GlassUiTheme {

    // ==================== LIGHT & DARK GLASS TOKENS ====================

    // Light Theme: Frosted Soft White Glass
    val lightCanvasStart = Color.parseColor("#E8EEF8")
    val lightCanvasEnd = Color.parseColor("#DCE5F4")
    val lightGlassSurface = Color.parseColor("#8CFFFFFF")      // ~55% frosted white glass
    val lightGlassBorder = Color.parseColor("#99CBD5E1")
    val lightGlassHighlight = Color.parseColor("#E6FFFFFF")
    val lightTextPrimary = Color.parseColor("#0F172A")
    val lightTextSecondary = Color.parseColor("#334155")
    val textSecondaryLightGlass = Color.parseColor("#1E293B")

    // Dark Theme: Smoked Deep Velvet Glass
    val darkCanvasStart = Color.parseColor("#090D16")
    val darkCanvasEnd = Color.parseColor("#05070F")
    val backdropBaseDark = Color.parseColor("#060914")
    val darkGlassSurface = Color.parseColor("#540F172A")       // ~33% smoked slate glass
    val cardFillDark = Color.parseColor("#6610172A")           // ~40% frosted slate card
    val navDockDark = Color.parseColor("#800D1322")            // ~50% translucent glass dock
    val darkGlassBorder = Color.parseColor("#26FFFFFF")
    val darkGlassHighlight = Color.parseColor("#4D94A3B8")
    val darkTextPrimary = Color.parseColor("#FFFFFF")
    val darkTextSecondary = Color.parseColor("#CBD5E1")
    val textSecondaryDark = Color.parseColor("#CBD5E1")

    // Shared Accents & Sub-Surfaces
    val orbIndigo = Color.parseColor("#6366F1")
    val orbCyan = Color.parseColor("#06B6D4")
    val orbViolet = Color.parseColor("#8B5CF6")

    val subtleSurfaceDark = Color.parseColor("#421E293B")      // ~26% inner glass sub-layer
    val subtleSurfaceLightGlass = Color.parseColor("#66FFFFFF") // ~40% inner frosted sub-layer

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

    // Glassy Status Tint Tokens
    val statusSuccessTint = Color.parseColor("#4D064E3B")
    val statusWarningTint = Color.parseColor("#4D451A03")
    val statusDangerTint = Color.parseColor("#4D450A0A")
    val statusInfoTint = Color.parseColor("#4D1E1B4B")
    val statusHolidayTint = Color.parseColor("#4D2E1065")

    // High-Contrast Status Text Tokens
    val statusSuccessText: Int = Color.parseColor("#34D399")
    val statusWarningText: Int = Color.parseColor("#FBBF24")
    val statusDangerText: Int = Color.parseColor("#F87171")
    val statusInfoText: Int = Color.parseColor("#A5B4FC")
    val statusHolidayText: Int = Color.parseColor("#C4B5FD")

    fun statusSuccessText(isDark: Boolean): Int =
        Color.parseColor(if (isDark) "#34D399" else "#064E3B")

    fun statusWarningText(isDark: Boolean): Int =
        Color.parseColor(if (isDark) "#FBBF24" else "#78350F")

    fun statusDangerText(isDark: Boolean): Int =
        Color.parseColor(if (isDark) "#F87171" else "#7F1D1D")

    fun statusInfoText(isDark: Boolean): Int =
        Color.parseColor(if (isDark) "#A5B4FC" else "#1E1B4B")

    fun statusHolidayText(isDark: Boolean): Int =
        Color.parseColor(if (isDark) "#C4B5FD" else "#4C1D95")

    fun statusTint(isDark: Boolean, kind: String): Int {
        return when (kind) {
            "success" -> if (isDark) statusSuccessTint else Color.parseColor("#A6D1FAE5")
            "warning" -> if (isDark) statusWarningTint else Color.parseColor("#A6FEF3C7")
            "danger" -> if (isDark) statusDangerTint else Color.parseColor("#A6FEE2E2")
            "holiday" -> if (isDark) statusHolidayTint else Color.parseColor("#A6EDE9FE")
            else -> if (isDark) statusInfoTint else Color.parseColor("#A6E0E7FF")
        }
    }

    // ==================== CENTRALIZED ACCESSORS ====================

    fun textPrimaryColor(isDark: Boolean): Int =
        if (isDark) darkTextPrimary else lightTextPrimary

    fun textSecondaryColor(isDark: Boolean): Int =
        if (isDark) darkTextSecondary else lightTextSecondary

    fun borderColor(isDark: Boolean): Int =
        if (isDark) darkGlassHighlight else lightGlassBorder

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

            val orbAlphaBase = if (isDark) 96 else 78
            val cx1 = w * (0.18f + 0.10f * sin(phase.toDouble()).toFloat())
            val cy1 = h * (0.16f + 0.06f * cos(phase.toDouble()).toFloat())
            orbPaint.color = withAlpha(orbIndigo, orbAlphaBase)
            canvas.drawCircle(cx1, cy1, minDim * 0.42f, orbPaint)

            val cx2 = w * (0.85f + 0.09f * cos((phase * 0.85f).toDouble()).toFloat())
            val cy2 = h * (0.48f + 0.08f * sin((phase * 0.85f).toDouble()).toFloat())
            orbPaint.color = withAlpha(orbCyan, (orbAlphaBase * 0.88f).roundToInt())
            canvas.drawCircle(cx2, cy2, minDim * 0.38f, orbPaint)

            val cx3 = w * (0.34f + 0.12f * sin((phase * 0.65f + 1.4f).toDouble()).toFloat())
            val cy3 = h * (0.85f + 0.06f * cos((phase * 0.65f + 1.4f).toDouble()).toFloat())
            orbPaint.color = withAlpha(orbViolet, (orbAlphaBase * 0.92f).roundToInt())
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

    // ==================== LAYER 1: FLOATING FROSTED GLASS CARD ====================

    fun glassCard(
        isDark: Boolean,
        radiusPx: Float = 24f,
        tintColor: Int? = null
    ): Drawable {
        return object : Drawable() {
            private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor(if (isDark) "#2E000000" else "#140F172A")
                maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
            }
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
                strokeWidth = 1.5f
                color = if (isDark) darkGlassHighlight else lightGlassHighlight
            }

            override fun draw(canvas: Canvas) {
                val rect = RectF(
                    bounds.left + 4f,
                    bounds.top + 3f,
                    bounds.right - 4f,
                    bounds.bottom - 5f
                )
                val shadowRect = RectF(
                    rect.left,
                    rect.top + 4f,
                    rect.right,
                    rect.bottom + 4f
                )
                canvas.drawRoundRect(shadowRect, radiusPx, radiusPx, shadowPaint)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, baseBorderPaint)

                val sheenStartX = rect.left + radiusPx * 0.6f
                val sheenEndX = (rect.right - radiusPx * 0.6f).coerceAtLeast(sheenStartX + 8f)
                canvas.drawLine(sheenStartX, rect.top + 1f, sheenEndX, rect.top + 1f, highlightPaint)
            }

            override fun setAlpha(alpha: Int) {
                shadowPaint.alpha = alpha
                fillPaint.alpha = alpha
                baseBorderPaint.alpha = alpha
                highlightPaint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                shadowPaint.colorFilter = colorFilter
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
            setColor(if (isDark) subtleSurfaceDark else subtleSurfaceLightGlass)
            setStroke(
                1,
                if (isDark) darkGlassHighlight else lightGlassBorder
            )
        }
    }

    // ==================== LAYER 3: SEE-THROUGH FROSTED ACCENT PILLS & DOCK ====================

    fun glassAccentButton(
        startColor: Int,
        endColor: Int,
        radiusPx: Float = 18f,
        translucent: Boolean = true
    ): GradientDrawable {
        val sGlass = if (translucent) {
            Color.argb(132, Color.red(startColor), Color.green(startColor), Color.blue(startColor))
        } else {
            startColor
        }
        val eGlass = if (translucent) {
            Color.argb(132, Color.red(endColor), Color.green(endColor), Color.blue(endColor))
        } else {
            endColor
        }
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(sGlass, eGlass)
        ).apply {
            cornerRadius = radiusPx
            setStroke(1, lightGlassHighlight)
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
            setColor(if (isDark) navDockDark else lightGlassSurface)
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
