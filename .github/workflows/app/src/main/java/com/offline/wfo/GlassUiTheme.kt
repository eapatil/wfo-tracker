package com.offline.wfo

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object GlassUiTheme {

    enum class AccentRole {
        PRIMARY,
        SECONDARY,
        SUCCESS,
        WARNING,
        DANGER,
        SPECIAL,
        NEUTRAL
    }

    enum class StatusKind {
        SUCCESS,
        WARNING,
        DANGER,
        INFO,
        HOLIDAY
    }

    private const val UI_PREFS_NAME = "officepace_ui_prefs"
    private const val KEY_THEME_MODE = "app_theme_mode"

    // ==================== DEPTH, RADIUS, ALPHA & BLUR TOKENS ====================

    const val cardRadiusPx = 24f
    const val bannerRadiusPx = 18f
    const val subtleRadiusPx = 16f
    const val buttonRadiusPx = 18f
    const val chipRadiusPx = 15f
    const val cellRadiusPx = 16f
    const val navDockRadiusPx = 26f
    const val restoreBlurRadiusPx = 18f
    const val weekendDimAlpha = 0.66f

    // ==================== LIGHT THEME: FROSTED GLACIER & IRIS MIST ====================

    private val lightCanvasStart = Color.parseColor("#E9EFF8")
    private val lightCanvasEnd = Color.parseColor("#DCE6F5")
    private val lightGlassSurfaceTop = Color.parseColor("#DCFFFFFF")
    private val lightGlassSurfaceBottom = Color.parseColor("#A6F8FAFC")
    private val lightGlassSurface = Color.parseColor("#BCFFFFFF")
    private val lightGlassBorder = Color.parseColor("#8CA3B8CC")
    private val lightGlassHighlight = Color.parseColor("#FAFFFFFF")
    private val lightGlassShimmer = Color.parseColor("#66FFFFFF")
    private val lightSubtleSurface = Color.parseColor("#8CF1F5F9")
    private val lightNavDock = Color.parseColor("#E0FFFFFF")
    private val lightTextPrimary = Color.parseColor("#0A101D")
    private val lightTextSecondary = Color.parseColor("#374660")
    private val lightShadow = Color.parseColor("#181E293B")

    private val lightOrbOne = Color.parseColor("#6366F1")   // Iris Indigo
    private val lightOrbTwo = Color.parseColor("#0D9488")   // Deep Mint Teal
    private val lightOrbThree = Color.parseColor("#8B5CF6") // Soft Amethyst
    private val lightOrbFour = Color.parseColor("#0284C7")  // Glacier Blue

    // ==================== DARK THEME: NOCTURNE AURORA & SMOKED SLATE ====================

    private val darkCanvasStart = Color.parseColor("#070B14")
    private val darkCanvasEnd = Color.parseColor("#03050A")
    private val darkGlassSurfaceTop = Color.parseColor("#8F141E33")
    private val darkGlassSurfaceBottom = Color.parseColor("#6B0A101C")
    private val darkGlassSurface = Color.parseColor("#7D0E1626")
    private val darkGlassBorder = Color.parseColor("#24FFFFFF")
    private val darkGlassHighlight = Color.parseColor("#5494A3B8")
    private val darkGlassShimmer = Color.parseColor("#26E2E8F0")
    private val darkSubtleSurface = Color.parseColor("#63162033")
    private val darkNavDock = Color.parseColor("#C4090E1A")
    private val darkTextPrimary = Color.parseColor("#F8FAFC")
    private val darkTextSecondary = Color.parseColor("#9FB0C7")
    private val darkShadow = Color.parseColor("#40000000")

    private val darkOrbOne = Color.parseColor("#4338CA")    // Royal Indigo
    private val darkOrbTwo = Color.parseColor("#0891B2")    // Electric Cyan-Teal
    private val darkOrbThree = Color.parseColor("#6D28D9")  // Velvet Violet
    private val darkOrbFour = Color.parseColor("#059669")   // Aurora Emerald

    // Widget Glass Tokens
    private val lightWidgetTop = Color.parseColor("#E6FFFFFF")
    private val lightWidgetBottom = Color.parseColor("#D4EEF4FB")
    private val darkWidgetTop = Color.parseColor("#EB0B1220")
    private val darkWidgetBottom = Color.parseColor("#E2060A13")
    private val darkWidgetBorder = Color.parseColor("#5594A3B8")
    private val darkWidgetHighlight = Color.parseColor("#44CBD5E1")

    // Shared / Compatibility Aliases
    val backdropBaseDark = darkCanvasStart
    val cardFillDark = darkGlassSurface
    val navDockDark = darkNavDock
    val subtleSurfaceDark = darkSubtleSurface
    val subtleSurfaceLightGlass = lightSubtleSurface
    val textSecondaryDark = darkTextSecondary
    val textSecondaryLightGlass = lightTextSecondary
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

    val statusSuccessTint = Color.parseColor("#54065F46")
    val statusWarningTint = Color.parseColor("#5478350F")
    val statusDangerTint = Color.parseColor("#547F1D1D")
    val statusInfoTint = Color.parseColor("#54312E81")
    val statusHolidayTint = Color.parseColor("#544C1D95")

    val statusSuccessText: Int = Color.parseColor("#6EE7B7")
    val statusWarningText: Int = Color.parseColor("#FCD34D")
    val statusDangerText: Int = Color.parseColor("#FCA5A5")
    val statusInfoText: Int = Color.parseColor("#C7D2FE")
    val statusHolidayText: Int = Color.parseColor("#DDD6FE")

    // ==================== THEME COLOR RESOLVERS ====================

    fun textPrimaryColor(isDark: Boolean): Int =
        if (isDark) darkTextPrimary else lightTextPrimary

    fun textSecondaryColor(isDark: Boolean): Int =
        if (isDark) darkTextSecondary else lightTextSecondary

    fun borderColor(isDark: Boolean): Int =
        if (isDark) darkGlassHighlight else lightGlassBorder

    fun borderHex(isDark: Boolean): String =
        String.format("#%08X", borderColor(isDark))

    fun accentColors(isDark: Boolean, role: AccentRole): Pair<Int, Int> {
        return if (isDark) {
            when (role) {
                AccentRole.PRIMARY -> Color.parseColor("#3730A3") to Color.parseColor("#6366F1")
                AccentRole.SECONDARY -> Color.parseColor("#0E7490") to Color.parseColor("#22D3EE")
                AccentRole.SUCCESS -> Color.parseColor("#047857") to Color.parseColor("#34D399")
                AccentRole.WARNING -> Color.parseColor("#B45309") to Color.parseColor("#FBBF24")
                AccentRole.DANGER -> Color.parseColor("#B91C1C") to Color.parseColor("#F87171")
                AccentRole.SPECIAL -> Color.parseColor("#5B21B6") to Color.parseColor("#A78BFA")
                AccentRole.NEUTRAL -> Color.parseColor("#334155") to Color.parseColor("#64748B")
            }
        } else {
            when (role) {
                AccentRole.PRIMARY -> Color.parseColor("#3730A3") to Color.parseColor("#4F46E5")
                AccentRole.SECONDARY -> Color.parseColor("#0F766E") to Color.parseColor("#0D9488")
                AccentRole.SUCCESS -> Color.parseColor("#065F46") to Color.parseColor("#059669")
                AccentRole.WARNING -> Color.parseColor("#92400E") to Color.parseColor("#D97706")
                AccentRole.DANGER -> Color.parseColor("#991B1B") to Color.parseColor("#DC2626")
                AccentRole.SPECIAL -> Color.parseColor("#5B21B6") to Color.parseColor("#7C3AED")
                AccentRole.NEUTRAL -> Color.parseColor("#334155") to Color.parseColor("#475569")
            }
        }
    }

    fun accentRoleForHex(hex: String): AccentRole {
        return when (hex.trim().uppercase(Locale.US)) {
            "#4F46E5", "#6366F1", "#4338CA", "#3730A3" -> AccentRole.PRIMARY
            "#0284C7", "#0EA5E9", "#0369A1", "#0E7490", "#22D3EE", "#0F766E", "#0D9488" -> AccentRole.SECONDARY
            "#10B981", "#059669", "#047857", "#34D399", "#065F46" -> AccentRole.SUCCESS
            "#F59E0B", "#D97706", "#B45309", "#FBBF24", "#92400E" -> AccentRole.WARNING
            "#EF4444", "#DC2626", "#B91C1C", "#F87171", "#991B1B" -> AccentRole.DANGER
            "#8B5CF6", "#7C3AED", "#6D28D9", "#A78BFA", "#5B21B6" -> AccentRole.SPECIAL
            else -> AccentRole.NEUTRAL
        }
    }

    fun accentPairForHex(hex: String, isDark: Boolean = true): Pair<Int, Int> {
        val role = accentRoleForHex(hex)
        val mapped = accentColors(isDark, role)
        if (role != AccentRole.NEUTRAL || hex.trim().uppercase(Locale.US) in listOf("#334155", "#475569", "#64748B")) {
            return mapped
        }
        val parsed = runCatching { Color.parseColor(hex) }.getOrDefault(mapped.first)
        return parsed to parsed
    }

    fun statusTextColor(isDark: Boolean, kind: StatusKind): Int {
        return if (isDark) {
            when (kind) {
                StatusKind.SUCCESS -> Color.parseColor("#6EE7B7")
                StatusKind.WARNING -> Color.parseColor("#FCD34D")
                StatusKind.DANGER -> Color.parseColor("#FCA5A5")
                StatusKind.INFO -> Color.parseColor("#C7D2FE")
                StatusKind.HOLIDAY -> Color.parseColor("#DDD6FE")
            }
        } else {
            when (kind) {
                StatusKind.SUCCESS -> Color.parseColor("#064E3B")
                StatusKind.WARNING -> Color.parseColor("#78350F")
                StatusKind.DANGER -> Color.parseColor("#7F1D1D")
                StatusKind.INFO -> Color.parseColor("#1E1B4B")
                StatusKind.HOLIDAY -> Color.parseColor("#4C1D95")
            }
        }
    }

    fun statusTintColor(isDark: Boolean, kind: StatusKind): Int {
        return if (isDark) {
            when (kind) {
                StatusKind.SUCCESS -> Color.parseColor("#54065F46")
                StatusKind.WARNING -> Color.parseColor("#5478350F")
                StatusKind.DANGER -> Color.parseColor("#547F1D1D")
                StatusKind.INFO -> Color.parseColor("#54312E81")
                StatusKind.HOLIDAY -> Color.parseColor("#544C1D95")
            }
        } else {
            when (kind) {
                StatusKind.SUCCESS -> Color.parseColor("#D1FAE5")
                StatusKind.WARNING -> Color.parseColor("#FEF3C7")
                StatusKind.DANGER -> Color.parseColor("#FEE2E2")
                StatusKind.INFO -> Color.parseColor("#E0E7FF")
                StatusKind.HOLIDAY -> Color.parseColor("#EDE9FE")
            }
        }
    }

    fun statusSuccessText(isDark: Boolean): Int = statusTextColor(isDark, StatusKind.SUCCESS)
    fun statusWarningText(isDark: Boolean): Int = statusTextColor(isDark, StatusKind.WARNING)
    fun statusDangerText(isDark: Boolean): Int = statusTextColor(isDark, StatusKind.DANGER)
    fun statusInfoText(isDark: Boolean): Int = statusTextColor(isDark, StatusKind.INFO)
    fun statusHolidayText(isDark: Boolean): Int = statusTextColor(isDark, StatusKind.HOLIDAY)

    fun statusTint(isDark: Boolean, kind: String): Int {
        val mapped = when (kind.lowercase(Locale.US)) {
            "success" -> StatusKind.SUCCESS
            "warning" -> StatusKind.WARNING
            "danger" -> StatusKind.DANGER
            "holiday" -> StatusKind.HOLIDAY
            else -> StatusKind.INFO
        }
        return statusTintColor(isDark, mapped)
    }

    fun ringAccentColor(
        isDark: Boolean,
        remainingDays: Int,
        plannedCoversTarget: Boolean,
        bufferDays: Int
    ): Int {
        return when {
            remainingDays == 0 || plannedCoversTarget -> accentColors(isDark, AccentRole.SUCCESS).second
            bufferDays <= 0 -> accentColors(isDark, AccentRole.DANGER).second
            else -> accentColors(isDark, AccentRole.PRIMARY).second
        }
    }

    fun ringTrackColor(isDark: Boolean): Int =
        if (isDark) Color.parseColor("#3823324D") else Color.parseColor("#80CBD5E1")

    fun trendTargetLineColor(isDark: Boolean): Int =
        accentColors(isDark, AccentRole.WARNING).second

    fun trendBarColor(isDark: Boolean, metTarget: Boolean): Int =
        if (metTarget) {
            accentColors(isDark, AccentRole.SUCCESS).second
        } else {
            accentColors(isDark, AccentRole.SPECIAL).second
        }

    // ==================== BLUR HELPERS (ANDROID 12+) ====================

    fun applyGlassBlur(view: View, radiusPx: Float = restoreBlurRadiusPx) {
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

    // ==================== LAYER 0: MULTI-COLOR DRIFTING & ZOOMING BACKDROP ====================

    fun ambientBackdrop(isDark: Boolean): Drawable = CanvasBackdropDrawable(isDark)

    private class CanvasBackdropDrawable(private val isDark: Boolean) : Drawable() {
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(220f, BlurMaskFilter.Blur.NORMAL)
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

            val orbAlphaBase = if (isDark) 92 else 68
            val o1 = if (isDark) darkOrbOne else lightOrbOne
            val o2 = if (isDark) darkOrbTwo else lightOrbTwo
            val o3 = if (isDark) darkOrbThree else lightOrbThree
            val o4 = if (isDark) darkOrbFour else lightOrbFour

            val zoom1 = 0.92f + 0.14f * sin(phase.toDouble()).toFloat()
            val cx1 = w * (0.18f + 0.12f * sin(phase.toDouble()).toFloat())
            val cy1 = h * (0.16f + 0.08f * cos(phase.toDouble()).toFloat())
            orbPaint.color = withAlpha(o1, orbAlphaBase)
            canvas.drawCircle(cx1, cy1, minDim * 0.43f * zoom1, orbPaint)

            val zoom2 = 0.94f + 0.12f * cos((phase * 0.85f + 0.7f).toDouble()).toFloat()
            val cx2 = w * (0.85f + 0.10f * cos((phase * 0.85f).toDouble()).toFloat())
            val cy2 = h * (0.48f + 0.09f * sin((phase * 0.85f).toDouble()).toFloat())
            orbPaint.color = withAlpha(o2, (orbAlphaBase * 0.90f).roundToInt())
            canvas.drawCircle(cx2, cy2, minDim * 0.40f * zoom2, orbPaint)

            val zoom3 = 0.93f + 0.13f * sin((phase * 0.65f + 1.4f).toDouble()).toFloat()
            val cx3 = w * (0.34f + 0.13f * sin((phase * 0.65f + 1.4f).toDouble()).toFloat())
            val cy3 = h * (0.85f + 0.07f * cos((phase * 0.65f + 1.4f).toDouble()).toFloat())
            orbPaint.color = withAlpha(o3, (orbAlphaBase * 0.92f).roundToInt())
            canvas.drawCircle(cx3, cy3, minDim * 0.45f * zoom3, orbPaint)

            val zoom4 = 0.90f + 0.14f * cos((phase * 0.55f + 2.2f).toDouble()).toFloat()
            val cx4 = w * (0.72f + 0.11f * sin((phase * 0.55f + 2.2f).toDouble()).toFloat())
            val cy4 = h * (0.28f + 0.09f * cos((phase * 0.55f + 2.2f).toDouble()).toFloat())
            orbPaint.color = withAlpha(o4, (orbAlphaBase * 0.76f).roundToInt())
            canvas.drawCircle(cx4, cy4, minDim * 0.35f * zoom4, orbPaint)
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

    // ==================== LAYER 1: SHIMMERING DUAL-COLOR FROSTED GLASS CARD ====================

    fun glassCard(
        isDark: Boolean,
        radiusPx: Float = cardRadiusPx,
        tintColor: Int? = null
    ): Drawable {
        return object : Drawable() {
            private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = if (isDark) darkShadow else lightShadow
                maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
            }
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            private val baseBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = if (isDark) darkGlassBorder else lightGlassBorder
            }
            private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.6f
                color = if (isDark) darkGlassHighlight else lightGlassHighlight
            }

            private var shimmerProgress: Float = 0.25f
            private var shimmerAnimator: ValueAnimator? = null

            init {
                startShimmer()
            }

            private fun startShimmer() {
                if (shimmerAnimator != null) return
                val selfRef = WeakReference(this)
                shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 7500L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { va ->
                        val target = selfRef.get()
                        if (target == null) {
                            va.cancel()
                            return@addUpdateListener
                        }
                        target.shimmerProgress = va.animatedValue as Float
                        target.invalidateSelf()
                    }
                    start()
                }
            }

            private fun updateShaders(rect: RectF) {
                val topCol = tintColor ?: if (isDark) darkGlassSurfaceTop else lightGlassSurfaceTop
                val botCol = if (tintColor != null) {
                    Color.argb(
                        (Color.alpha(tintColor) * 0.78f).roundToInt().coerceIn(20, 255),
                        Color.red(tintColor),
                        Color.green(tintColor),
                        Color.blue(tintColor)
                    )
                } else {
                    if (isDark) darkGlassSurfaceBottom else lightGlassSurfaceBottom
                }
                fillPaint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
                    topCol,
                    botCol,
                    Shader.TileMode.CLAMP
                )

                val bandW = (rect.width() * 0.45f).coerceAtLeast(60f)
                val sweepStartX = rect.left - bandW + (rect.width() + bandW * 2f) * shimmerProgress
                val sweepEndX = sweepStartX + bandW
                val shimmerCol = if (isDark) darkGlassShimmer else lightGlassShimmer
                sheenPaint.shader = LinearGradient(
                    sweepStartX,
                    rect.top,
                    sweepEndX,
                    rect.bottom,
                    intArrayOf(Color.TRANSPARENT, shimmerCol, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
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
                updateShaders(rect)
                canvas.drawRoundRect(shadowRect, radiusPx, radiusPx, shadowPaint)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, sheenPaint)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, baseBorderPaint)

                val sheenStartX = rect.left + radiusPx * 0.6f
                val sheenEndX = (rect.right - radiusPx * 0.6f).coerceAtLeast(sheenStartX + 8f)
                canvas.drawLine(sheenStartX, rect.top + 1.2f, sheenEndX, rect.top + 1.2f, highlightPaint)
            }

            override fun setAlpha(alpha: Int) {
                shadowPaint.alpha = alpha
                fillPaint.alpha = alpha
                sheenPaint.alpha = alpha
                baseBorderPaint.alpha = alpha
                highlightPaint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                shadowPaint.colorFilter = colorFilter
                fillPaint.colorFilter = colorFilter
                sheenPaint.colorFilter = colorFilter
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

    fun glassCardHex(isDark: Boolean, radiusPx: Float = cardRadiusPx, tintHex: String): Drawable =
        glassCard(isDark, radiusPx, runCatching { Color.parseColor(tintHex) }.getOrNull())

    // ==================== LAYER 2: RECESSED SUBTLE GLASS SURFACE ====================

    fun glassSubtleSurface(isDark: Boolean, radiusPx: Float = subtleRadiusPx): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(if (isDark) darkSubtleSurface else lightSubtleSurface)
            setStroke(
                1,
                if (isDark) darkGlassHighlight else lightGlassBorder
            )
        }
    }

    // ==================== LAYER 3: DUAL-COLOR TRANSLUCENT ACCENT PILLS & DOCK ====================

    fun glassAccentButton(
        startColor: Int,
        endColor: Int,
        radiusPx: Float = buttonRadiusPx,
        translucent: Boolean = true
    ): GradientDrawable {
        val alphaVal = if (translucent) 156 else 255
        val sGlass = Color.argb(alphaVal, Color.red(startColor), Color.green(startColor), Color.blue(startColor))
        val eGlass = Color.argb(alphaVal, Color.red(endColor), Color.green(endColor), Color.blue(endColor))
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
        radiusPx: Float = buttonRadiusPx,
        translucent: Boolean = true,
        isDark: Boolean = true
    ): GradientDrawable {
        val (sColor, eColor) = accentPairForHex(startHex, isDark)
        val (_, endOverride) = accentPairForHex(endHex, isDark)
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
            cornerRadius = navDockRadiusPx
            setColor(if (isDark) darkNavDock else lightNavDock)
            setStroke(1, if (isDark) darkGlassHighlight else lightGlassBorder)
        }
    }

    fun styleGlassSectionHeader(tv: TextView, isDark: Boolean) {
        tv.textSize = 16f
        tv.letterSpacing = 0.01f
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        tv.setTextColor(textPrimaryColor(isDark))
    }

    // ==================== CALENDAR & SHAPE HELPERS ====================

    fun calendarCellDrawable(
        isDark: Boolean,
        type: DayType,
        isToday: Boolean
    ): Drawable {
        val todayRing = accentColors(isDark, AccentRole.SECONDARY).second
        val strokeW = if (isToday) 6 else if (type == DayType.PLANNED) 4 else 2

        return when (type) {
            DayType.OFFICE -> {
                val (startCol, endCol) = accentColors(isDark, AccentRole.SUCCESS)
                val fill = Color.argb(162, Color.red(endCol), Color.green(endCol), Color.blue(endCol))
                val border = if (isToday) todayRing else startCol
                roundedShape(fill, border, cellRadiusPx, strokeW)
            }
            DayType.PLANNED -> {
                val (_, accent) = accentColors(isDark, AccentRole.PRIMARY)
                val fill = statusTintColor(isDark, StatusKind.INFO)
                dashedShape(fill, if (isToday) todayRing else accent, cellRadiusPx, strokeW)
            }
            DayType.LEAVE -> {
                val (startCol, endCol) = accentColors(isDark, AccentRole.WARNING)
                val fill = Color.argb(162, Color.red(endCol), Color.green(endCol), Color.blue(endCol))
                val border = if (isToday) todayRing else startCol
                roundedShape(fill, border, cellRadiusPx, strokeW)
            }
            DayType.HOLIDAY -> {
                val (startCol, endCol) = accentColors(isDark, AccentRole.SPECIAL)
                val fill = Color.argb(162, Color.red(endCol), Color.green(endCol), Color.blue(endCol))
                val border = if (isToday) todayRing else startCol
                roundedShape(fill, border, cellRadiusPx, strokeW)
            }
            DayType.NONE -> glassSubtleSurface(isDark, cellRadiusPx)
        }
    }

    fun roundedShape(
        fillColor: Int,
        strokeColor: Int,
        radiusPx: Float,
        strokeWidthPx: Int = 2
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            setStroke(strokeWidthPx, strokeColor)
            cornerRadius = radiusPx
        }
    }

    fun dashedShape(
        fillColor: Int,
        strokeColor: Int,
        radiusPx: Float,
        strokeWidthPx: Int = 4
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            setStroke(strokeWidthPx, strokeColor, 12f, 8f)
            cornerRadius = radiusPx
        }
    }

    fun createDashEffect(intervals: FloatArray = floatArrayOf(18f, 12f)): DashPathEffect =
        DashPathEffect(intervals, 0f)

    // ==================== WIDGET GLASS HELPERS ====================

    fun resolveWidgetIsDark(context: Context): Boolean {
        val raw = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM"
        return when (raw) {
            "LIGHT" -> false
            "DARK" -> true
            else -> (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun widgetStatusPillTextColor(isDark: Boolean, kind: StatusKind?): Int {
        return if (isDark) {
            when (kind) {
                StatusKind.SUCCESS -> Color.parseColor("#A7F3D0")
                StatusKind.WARNING -> Color.parseColor("#FDE68A")
                StatusKind.DANGER -> Color.parseColor("#FECACA")
                StatusKind.INFO -> Color.parseColor("#E0E7FF")
                StatusKind.HOLIDAY -> Color.parseColor("#EDE9FE")
                null -> Color.parseColor("#E2E8F0")
            }
        } else {
            if (kind == null) Color.parseColor("#334155")
            else statusTextColor(false, kind)
        }
    }

    fun widgetDayTextColor(isDark: Boolean, type: DayType, isToday: Boolean): Int {
        return when (type) {
            DayType.OFFICE, DayType.LEAVE, DayType.HOLIDAY -> textOnAccent
            DayType.PLANNED -> if (isDark) Color.parseColor("#E0E7FF") else statusTextColor(false, StatusKind.INFO)
            DayType.NONE -> if (isToday) {
                if (isDark) Color.parseColor("#FFFFFF") else Color.parseColor("#0A101D")
            } else {
                if (isDark) Color.parseColor("#CBD5E1") else Color.parseColor("#475569")
            }
        }
    }

    fun widgetProgressColor(
        isDark: Boolean,
        remainingDays: Int,
        plannedCoversTarget: Boolean,
        bufferDays: Int
    ): Int = ringAccentColor(isDark, remainingDays, plannedCoversTarget, bufferDays)

    fun createWidgetPillBitmap(
        widthPx: Int,
        heightPx: Int,
        isDark: Boolean,
        kind: StatusKind?,
        radiusPx: Float = chipRadiusPx
    ): Bitmap {
        val w = widthPx.coerceAtLeast(48)
        val h = heightPx.coerceAtLeast(28)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f)

        val (fillCol, strokeCol) = if (isDark) {
            when (kind) {
                StatusKind.SUCCESS -> Color.parseColor("#D9064E3B") to Color.parseColor("#9934D399")
                StatusKind.WARNING -> Color.parseColor("#D978350F") to Color.parseColor("#99FBBF24")
                StatusKind.DANGER -> Color.parseColor("#D97F1D1D") to Color.parseColor("#99F87171")
                StatusKind.INFO -> Color.parseColor("#D9312E81") to Color.parseColor("#99A5B4FC")
                StatusKind.HOLIDAY -> Color.parseColor("#D94C1D95") to Color.parseColor("#99C4B5FD")
                null -> Color.parseColor("#D91E293B") to Color.parseColor("#7794A3B8")
            }
        } else {
            when (kind) {
                StatusKind.SUCCESS -> Color.parseColor("#E6D1FAE5") to Color.parseColor("#99059669")
                StatusKind.WARNING -> Color.parseColor("#E6FEF3C7") to Color.parseColor("#99D97706")
                StatusKind.DANGER -> Color.parseColor("#E6FEE2E2") to Color.parseColor("#99DC2626")
                StatusKind.INFO -> Color.parseColor("#E6E0E7FF") to Color.parseColor("#994F46E5")
                StatusKind.HOLIDAY -> Color.parseColor("#E6EDE9FE") to Color.parseColor("#997C3AED")
                null -> Color.parseColor("#E6F1F5F9") to Color.parseColor("#9994A3B8")
            }
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillCol
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            color = strokeCol
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, strokePaint)
        return bmp
    }

    fun createWidgetDayBitmap(
        sizePx: Int,
        isDark: Boolean,
        type: DayType,
        isToday: Boolean
    ): Bitmap {
        val s = sizePx.coerceAtLeast(36)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = s / 2f
        val radius = (s / 2f) - 2.5f

        val (fillCol, strokeCol, strokeW) = when (type) {
            DayType.OFFICE -> {
                val c = accentColors(isDark, AccentRole.SUCCESS).second
                Triple(
                    Color.argb(225, Color.red(c), Color.green(c), Color.blue(c)),
                    if (isToday) accentColors(isDark, AccentRole.SECONDARY).second else c,
                    if (isToday) 3f else 1.5f
                )
            }
            DayType.PLANNED -> {
                val c = accentColors(isDark, AccentRole.PRIMARY).second
                Triple(
                    if (isDark) Color.parseColor("#CC312E81") else Color.parseColor("#E6E0E7FF"),
                    c,
                    if (isToday) 3f else 2f
                )
            }
            DayType.LEAVE -> {
                val c = accentColors(isDark, AccentRole.WARNING).second
                Triple(
                    Color.argb(225, Color.red(c), Color.green(c), Color.blue(c)),
                    c,
                    1.5f
                )
            }
            DayType.HOLIDAY -> {
                val c = accentColors(isDark, AccentRole.SPECIAL).second
                Triple(
                    Color.argb(225, Color.red(c), Color.green(c), Color.blue(c)),
                    c,
                    1.5f
                )
            }
            DayType.NONE -> {
                Triple(
                    if (isDark) Color.parseColor("#CC1E293B") else Color.parseColor("#E6F1F5F9"),
                    if (isToday) {
                        accentColors(isDark, AccentRole.SECONDARY).second
                    } else {
                        if (isDark) Color.parseColor("#5594A3B8") else Color.parseColor("#99CBD5E1")
                    },
                    if (isToday) 3f else 1.5f
                )
            }
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillCol
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = strokeCol
        }
        canvas.drawCircle(center, center, radius, fillPaint)
        canvas.drawCircle(center, center, radius, strokePaint)
        return bmp
    }

    fun createWidgetGlassBitmap(
        widthPx: Int,
        heightPx: Int,
        isDark: Boolean,
        radiusPx: Float = cardRadiusPx
    ): Bitmap {
        val w = widthPx.coerceAtLeast(160)
        val h = heightPx.coerceAtLeast(96)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val rect = RectF(2f, 2f, w - 2f, h - 2f)
        val topGlass = if (isDark) darkWidgetTop else lightWidgetTop
        val botGlass = if (isDark) darkWidgetBottom else lightWidgetBottom

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), topGlass, botGlass, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, basePaint)

        val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(95f, BlurMaskFilter.Blur.NORMAL)
        }
        val alphaPool = if (isDark) 58 else 36
        fun poolColor(col: Int) = Color.argb(alphaPool, Color.red(col), Color.green(col), Color.blue(col))

        orbPaint.color = poolColor(if (isDark) darkOrbOne else lightOrbOne)
        canvas.drawCircle(w * 0.16f, h * 0.22f, minOf(w, h) * 0.55f, orbPaint)

        orbPaint.color = poolColor(if (isDark) darkOrbTwo else lightOrbTwo)
        canvas.drawCircle(w * 0.86f, h * 0.72f, minOf(w, h) * 0.50f, orbPaint)

        orbPaint.color = poolColor(if (isDark) darkOrbThree else lightOrbThree)
        canvas.drawCircle(w * 0.55f, h * 0.15f, minOf(w, h) * 0.44f, orbPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = if (isDark) darkWidgetBorder else lightGlassBorder
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, borderPaint)

        val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = if (isDark) darkWidgetHighlight else lightGlassHighlight
        }
        canvas.drawLine(
            rect.left + radiusPx * 0.7f,
            rect.top + 1.5f,
            (rect.right - radiusPx * 0.7f).coerceAtLeast(rect.left + 12f),
            rect.top + 1.5f,
            sheenPaint
        )
        return bmp
    }
}
