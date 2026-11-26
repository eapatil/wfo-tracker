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

    // ==================== CENTRALIZED COLOR TOKENS ====================

    val backdropBaseDark = Color.parseColor("#060914")
    val backdropBaseLightGlass = Color.parseColor("#090E1C")

    val orbIndigo = Color.parseColor("#4F46E5")
    val orbCyan = Color.parseColor("#0891B2")
    val orbViolet = Color.parseColor("#7E22CE")

    val cardFillDark = Color.parseColor("#B310172A")
    val cardFillLightGlass = Color.parseColor("#A8141D34")

    val subtleSurfaceDark = Color.parseColor("#7A1E2B48")
    val subtleSurfaceLightGlass = Color.parseColor("#73223152")

    val navDockDark = Color.parseColor("#CC0D1322")
    val navDockLightGlass = Color.parseColor("#C411182B")

    val textPrimaryDark = Color.parseColor("#F1F5F9")
    val textPrimaryLightGlass = Color.parseColor("#E2E8F0")

    val textSecondaryDark = Color.parseColor("#94A3B8")
    val textSecondaryLightGlass = Color.parseColor("#A5B4FC")

    val textOnAccent = Color.parseColor("#F8FAFC")

    val borderDark = Color.parseColor("#38818CF8")
    val borderLightGlass = Color.parseColor("#3394A3B8")
    val innerSheenStroke = Color.parseColor("#1F94A3B8")
    val subtleStroke = Color.parseColor("#2E94A3B8")
    val accentStroke = Color.parseColor("#38C7D2FE")
    val dockStroke = Color.parseColor("#33818CF8")

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

    val statusSuccessText = Color.parseColor("#6EE7B7")
    val statusSuccessTint = Color.parseColor("#8C064E3B")
    val statusWarningText = Color.parseColor("#FCD34D")
    val statusWarningTint = Color.parseColor("#8C451A03")
    val statusDangerText = Color.parseColor("#FCA5A5")
    val statusDangerTint = Color.parseColor("#8C450A0A")
    val statusInfoText = Color.parseColor("#C7D2FE")
    val statusInfoTint = Color.parseColor("#8C1E1B4B")
    val statusHolidayTint = Color.parseColor("#8C2E1065")
    val statusHolidayText = Color.parseColor("#DDD6FE")

    // ==================== CENTRALIZED ACCESSORS ====================

    fun textPrimaryColor(isDark: Boolean): Int =
        if (isDark) textPrimaryDark else textPrimaryLightGlass

    fun textSecondaryColor(isDark: Boolean): Int =
        if (isDark) textSecondaryDark else textSecondaryLightGlass

    fun borderColor(isDark: Boolean): Int =
        if (isDark) borderDark else borderLightGlass

    fun borderHex(isDark: Boolean): String =
        String.format("#%08X", borderColor(isDark))

    fun accentPairForHex(hex: String): Pair<Int, Int> {
        return when (hex.trim().uppercase()) {
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

    fun ambientBackdrop(isDark: Boolean): Drawable = MovingAuraBackdropDrawable(isDark)

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

        private fun withOrbAlpha(baseColor: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat().coerceAtLeast(1f)
            val h = bounds.height().toFloat().coerceAtLeast(1f)
            val minDim = minOf(w, h)

            basePaint.color = if (isDark) backdropBaseDark else backdropBaseLightGlass
            canvas.drawRect(0f, 0f, w, h, basePaint)

            val cx1 = w * (0.18f + 0.10f * sin(phase.toDouble()).toFloat())
            val cy1 = h * (0.16f + 0.06f * cos(phase.toDouble()).toFloat())
            orbPaint.color = withOrbAlpha(orbIndigo, if (isDark) 110 else 120)
            canvas.drawCircle(cx1, cy1, minDim * 0.42f, orbPaint)

            val cx2 = w * (0.85f + 0.09f * cos((phase * 0.85f).toDouble()).toFloat())
            val cy2 = h * (0.46f + 0.08f * sin((phase * 0.85f).toDouble()).toFloat())
            orbPaint.color = withOrbAlpha(orbCyan, if (isDark) 95 else 105)
            canvas.drawCircle(cx2, cy2, minDim * 0.38f, orbPaint)

            val cx3 = w * (0.34f + 0.12f * sin((phase * 0.65f + 1.4f).toDouble()).toFloat())
            val cy3 = h * (0.84f + 0.06f * cos((phase * 0.65f + 1.4f).toDouble()).toFloat())
            orbPaint.color = withOrbAlpha(orbViolet, if (isDark) 102 else 112)
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

    fun glassCard(
        isDark: Boolean,
        radiusPx: Float = 26f,
        tintColor: Int? = null
    ): Drawable {
        return object : Drawable() {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = tintColor ?: if (isDark) cardFillDark else cardFillLightGlass
            }
            private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = innerSheenStroke
            }
            private val outerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = borderColor(isDark)
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

    fun glassCardHex(isDark: Boolean, radiusPx: Float = 26f, tintHex: String): Drawable =
        glassCard(isDark, radiusPx, runCatching { Color.parseColor(tintHex) }.getOrNull())

    // ==================== LAYER 2: LIFTED / RECESSED GLASS SUB-SURFACE ====================

    fun glassSubtleSurface(isDark: Boolean, radiusPx: Float = 16f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(if (isDark) subtleSurfaceDark else subtleSurfaceLightGlass)
            setStroke(1, subtleStroke)
        }
    }

    // ==================== LAYER 3: TRANSLUCENT GRADIENT PILLS & DOCK ====================

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
                alpha = 205
            }
            setStroke(1, accentStroke)
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
        return glassAccentButton(sColor, if (startHex == endHex) eColor else endOverride, radiusPx, translucent)
    }

    fun glassNavDock(isDark: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            setColor(if (isDark) navDockDark else navDockLightGlass)
            setStroke(1, dockStroke)
        }
    }

    fun styleGlassSectionHeader(tv: TextView, isDark: Boolean) {
        tv.textSize = 16f
        tv.letterSpacing = 0.01f
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        tv.setTextColor(textPrimaryColor(isDark))
    }
}
