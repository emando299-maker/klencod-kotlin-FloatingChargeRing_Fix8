package com.floatingchargering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Round neon charging ring. All content is drawn inside the view's own square bounds
 * (the glow fades to transparent before the edge), so it looks like a round window.
 */
class RingView(context: Context) : View(context) {

    private val reader = BatteryReader(context)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private var charging = false
    private var levelFloat = -1f
    private var textInt = "--"
    private var textDec: String? = null
    private var textPower = "-- W"

    private var lastReadMs = 0L
    private var lastFrameMs = 0L
    private var phase = 0f
    private var ringR = 0f
    private var auraShader: Shader? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val path = Path()

    private val tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val tfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var theme: RingTheme = Themes.byId(null)
    private var colBlue = theme.primary
    private var colViolet = theme.secondary
    private var colCore = theme.core
    private var colYellow = theme.accent

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (lastReadMs == 0L || now - lastReadMs >= 1000L) readBattery(now)
            val dtMs = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(1L, 200L)
            lastFrameMs = now
            phase = (phase + dtMs / 1000f * (if (charging) 90f else 24f)) % 360f
            invalidate()
            handler.postDelayed(this, if (charging) 33L else 100L)
        }
    }

    /** Applies a colour theme immediately. */
    fun setTheme(value: RingTheme) {
        theme = value
        colBlue = value.primary
        colViolet = value.secondary
        colCore = value.core
        colYellow = value.accent
        buildAura(width, height)
        invalidate()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun buildAura(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val s = min(w, h).toFloat()
        auraShader = RadialGradient(
            w / 2f, h / 2f, max(s / 2f, 1f),
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(colViolet, 0),
                withAlpha(colViolet, 120),
                withAlpha(colBlue, 55),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.60f, 0.76f, 0.90f, 1f), Shader.TileMode.CLAMP
        )
    }

    /** Starts or stops animation and battery polling (stop it when hidden or screen is off). */
    fun setRunning(value: Boolean) {
        if (value == running) return
        running = value
        handler.removeCallbacks(tick)
        if (value) {
            lastReadMs = 0L
            lastFrameMs = 0L
            handler.post(tick)
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun readBattery(now: Long) {
        val s = reader.read()
        lastReadMs = now
        charging = s.state == PowerState.CHARGING

        val precise = s.levelPrecise
        if (precise != null) {
            val parts = String.format(Locale.US, "%.2f", precise).split('.')
            textInt = parts[0]
            textDec = parts.getOrNull(1)
            levelFloat = precise.toFloat()
        } else {
            textInt = if (s.levelPercent >= 0) s.levelPercent.toString() else "--"
            textDec = null
            levelFloat = s.levelPercent.toFloat()
        }
        // Measured battery-side power, not the charger's rating.
        textPower = s.powerW?.let { String.format(Locale.US, "%.1f W", it) } ?: "-- W"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = min(w, h).toFloat()
        ringR = s * 0.36f
        buildAura(w, h)
    }

    override fun onDraw(c: Canvas) {
        val r = ringR
        if (r <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val glow = if (charging) 1f else 0.45f
        val pulse = if (charging) 0.75f + 0.25f * sin(Math.toRadians((phase * 4f).toDouble())).toFloat() else 0.7f

        // Aura.
        paint.style = Paint.Style.FILL
        paint.shader = auraShader
        paint.alpha = (255 * pulse).toInt()
        c.drawCircle(cx, cy, min(width, height) / 2f, paint)
        paint.shader = null

        // Dark translucent disc so text stays readable on any app.
        paint.color = Color.argb(235, 2, 3, 12)
        c.drawCircle(cx, cy, r, paint)

        // Radial light streaks around the ring.
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(1f, r * 0.012f)
        for (i in 0 until 40) {
            val a = Math.toRadians((i * 9f + phase * 0.25f).toDouble())
            val k = ((i * 53) % 11) / 10f
            val flicker = 0.5f + 0.5f * sin(Math.toRadians((phase * 3f + i * 47f).toDouble())).toFloat()
            val r0 = r * 1.10f
            val r1 = r * (1.14f + 0.20f * k)
            paint.color = if (i % 2 == 0) colBlue else colViolet
            paint.alpha = ((60 + 140 * flicker * (0.4f + 0.6f * k)) * glow).toInt().coerceIn(0, 255)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * r1, cy + sa * r1, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT

        // Layered neon ring: haze, glow, bright core.
        paint.strokeWidth = r * 0.26f
        paint.color = colViolet
        paint.alpha = (36 * glow * pulse).toInt()
        c.drawCircle(cx, cy, r, paint)
        paint.strokeWidth = r * 0.13f
        paint.color = colBlue
        paint.alpha = (95 * glow * pulse + 25).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, r, paint)
        paint.strokeWidth = max(1.5f, r * 0.045f)
        paint.color = colCore
        paint.alpha = (255 * (0.8f + 0.2f * pulse)).toInt()
        c.drawCircle(cx, cy, r, paint)

        // Rotating inner arcs.
        for (i in 0..2) {
            val rr = r * (0.84f - i * 0.12f)
            paint.strokeWidth = r * 0.055f
            paint.color = if (i == 1) colViolet else colBlue
            paint.alpha = (115 * glow).toInt() + 20
            oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(oval, phase * (if (i % 2 == 0) 1f else -1.3f) + i * 40f, 200f, false, paint)
        }

        // Moving highlights on the ring.
        paint.style = Paint.Style.FILL
        for (i in 0..3) {
            val a = Math.toRadians((phase * 1.7f + i * 90f).toDouble())
            paint.color = if (i % 2 == 0) theme.highlightA else theme.highlightB
            paint.alpha = 220
            c.drawCircle(cx + cos(a).toFloat() * r, cy + sin(a).toFloat() * r,
                max(1.5f, if (i == 0) r * 0.035f else r * 0.02f), paint)
        }
        paint.alpha = 255

        drawTexts(c, cx, cy, r)
    }

    private fun drawTexts(c: Canvas, cx: Float, cy: Float, r: Float) {
        val decText = textDec?.let { ".$it" }
        textPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.letterSpacing = 0f

        textPaint.typeface = tfLight
        textPaint.textSize = r * 0.60f
        val wInt = textPaint.measureText(textInt)
        textPaint.typeface = tfMedium
        textPaint.textSize = r * 0.24f
        val wDec = if (decText != null) textPaint.measureText(decText) else 0f
        val wPct = textPaint.measureText("%")

        var x = cx - (wInt + wDec + wPct) / 2f
        val baseY = cy + r * 0.05f
        textPaint.typeface = tfLight
        textPaint.textSize = r * 0.60f
        textPaint.color = Color.WHITE
        c.drawText(textInt, x, baseY, textPaint)
        x += wInt
        textPaint.typeface = tfMedium
        textPaint.textSize = r * 0.24f
        if (decText != null) {
            c.drawText(decText, x, baseY, textPaint)
            x += wDec
        }
        textPaint.color = Color.rgb(190, 195, 215)
        c.drawText("%", x, baseY, textPaint)

        // Measured battery power in yellow.
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textPaint.textSize = r * 0.19f
        textPaint.letterSpacing = 0.08f
        textPaint.color = colYellow
        c.drawText(textPower, cx, cy + r * 0.40f, textPaint)
        textPaint.letterSpacing = 0f

        // Bolt while charging.
        if (charging) {
            val bs = r * 0.30f
            val bx = cx - bs * 0.5f
            val by = cy + r * 0.50f
            path.reset()
            path.moveTo(bx + bs * 0.62f, by)
            path.lineTo(bx + bs * 0.18f, by + bs * 0.58f)
            path.lineTo(bx + bs * 0.46f, by + bs * 0.58f)
            path.lineTo(bx + bs * 0.34f, by + bs)
            path.lineTo(bx + bs * 0.82f, by + bs * 0.38f)
            path.lineTo(bx + bs * 0.54f, by + bs * 0.38f)
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = colYellow
            c.drawPath(path, paint)
        }
    }
}
