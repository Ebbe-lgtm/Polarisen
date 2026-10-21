package se.isbjorn.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

val EASE = PathInterpolator(.2f, 0f, 0f, 1f)
val SETTLE = PathInterpolator(.2f, .8f, .25f, 1.1f) // slight overshoot, like the mockup's settle

/** Inter is one variable font; weights are picked with font variation settings (API 26+). */
object Fonts {
    lateinit var light: Typeface; lateinit var regular: Typeface; lateinit var medium: Typeface
    lateinit var semibold: Typeface; lateinit var caption: Typeface
    fun init(ctx: Context) {
        if (::light.isInitialized) return
        val a = ctx.assets
        fun inter(w: Int, opsz: Int = 14) = Typeface.Builder(a, "fonts/inter.ttf").setFontVariationSettings("'wght' $w, 'opsz' $opsz").build()
        light = inter(300, 32); regular = inter(400); medium = inter(500); semibold = inter(600)
        caption = Typeface.createFromAsset(a, "fonts/barlow_condensed_bold.ttf")
    }
}

/** CSS-style angled linear gradient (deg clockwise from "to top") across a w x h box. */
fun cssLinear(w: Float, h: Float, deg: Double, colors: IntArray, stops: FloatArray): LinearGradient {
    val r = Math.toRadians(deg)
    val dx = sin(r).toFloat(); val dy = -cos(r).toFloat()
    val half = (abs(w * dx) + abs(h * dy)) / 2
    return LinearGradient(w / 2 - dx * half, h / 2 - dy * half, w / 2 + dx * half, h / 2 + dy * half, colors, stops, Shader.TileMode.CLAMP)
}

/** The light-catching rim of Liquid Glass: bright top-left, faint middle, brighter bottom-right. */
private fun rimPaint(w: Float, h: Float, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; strokeWidth = width
    shader = cssLinear(w, h, 125.0, intArrayOf(0xD9FFFFFF.toInt(), 0x1FFFFFFF, 0x08FFFFFF, 0x73FFFFFF), floatArrayOf(0f, .26f, .55f, .92f))
}

abstract class SimpleDrawable : Drawable() {
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Glass surface drawn over a pre-baked blurred/lensed backdrop (dock) or on its own (pills):
 * adaptive tint, inner top highlight, rim, and optionally a specular spot at [hx] that follows focus.
 */
class GlassDrawable(private val radius: Float, private val s: Float, private val pill: Boolean) : SimpleDrawable() {
    var hx = 0f
        set(v) { field = v; spotMatrix.setScale(1f, 70f / 150f); spotMatrix.postTranslate(v, spotY); spot.shader?.setLocalMatrix(spotMatrix); invalidateSelf() }
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73141A20 } // pills have no blur behind them on Android 9
    private val top = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spotMatrix = Matrix()
    private var spotY = 0f
    private var rim = Paint()

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat(); val h = b.height().toFloat()
        rect.set(0f, 0f, w, h)
        fill.shader = if (pill) LinearGradient(0f, 0f, 0f, h, 0x29FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, 0f, h, intArrayOf(0x1FFFFFFF, 0x05FFFFFF, 0x24000000), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        top.strokeWidth = 1.5f * s
        top.shader = LinearGradient(0f, 0f, 0f, 6f * s, 0x8CFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        rim = rimPaint(w, h, 1.4f * s)
        spotY = -0.12f * h
        spot.shader = RadialGradient(0f, 0f, 150f * s, intArrayOf(0x6BFFFFFF, 0x00FFFFFF), floatArrayOf(0f, .72f), Shader.TileMode.CLAMP)
        hx = hx
        baked = null
    }

    // Static layers are baked once into a bitmap; only the moving spot is drawn live, and only inside its own bounds
    // (gradients over large areas are what this GPU struggles with).
    private var baked: Bitmap? = null

    override fun draw(c: Canvas) {
        val w = rect.width().toInt(); val h = rect.height().toInt()
        if (w <= 0 || h <= 0) return
        val b = baked ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { paintStatic(Canvas(it)); baked = it }
        c.drawBitmap(b, bounds.left.toFloat(), bounds.top.toFloat(), null)
        if (!pill) {
            val r = .72f * 150f * s
            c.drawRect(maxOf(0f, hx - r), 0f, minOf(rect.right, hx + r), minOf(rect.bottom, spotY + r * 70f / 150f), spot)
        }
    }

    private fun paintStatic(c: Canvas) {
        if (pill) c.drawRoundRect(rect, radius, radius, base)
        c.drawRoundRect(rect, radius, radius, fill)
        val i = .75f * s
        c.drawRoundRect(rect.left + i, rect.top + i, rect.right - i, rect.bottom - i, radius, radius, top)
        c.drawRoundRect(rect.left + .7f * s, rect.top + .7f * s, rect.right - .7f * s, rect.bottom - .7f * s, radius, radius, rim)
    }
}

/** Static foreground of app tiles / cards: top-left gloss, bottom inner shade and rim (no sweep: it cost frames). */
class GlossDrawable(private val radius: Float, private val s: Float) : SimpleDrawable() {
    private val rect = RectF()
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rim = Paint()

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat(); val h = b.height().toFloat()
        rect.set(0f, 0f, w, h)
        // radial-gradient(120% 75% at 28% -18%, white .30, transparent 55%)
        gloss.shader = RadialGradient(0f, 0f, 1.2f * w, intArrayOf(0x4DFFFFFF, 0x00FFFFFF), floatArrayOf(0f, .55f), Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(Matrix().apply { setScale(1f, .75f * h / (1.2f * w)); postTranslate(.28f * w, -.18f * h) }) }
        shade.shader = LinearGradient(0f, h - 18f * s, 0f, h, 0x00000000, 0x38000000, Shader.TileMode.CLAMP)
        rim = rimPaint(w, h, 1.4f * s)
        baked = null
    }

    private companion object { val cache = HashMap<Long, Bitmap>() } // main thread only

    // Rendered once per tile size into a shared bitmap: one texture draw per tile instead of three gradient passes
    private var baked: Bitmap? = null
    override fun draw(c: Canvas) {
        val w = rect.width().toInt(); val h = rect.height().toInt()
        if (w <= 0 || h <= 0) return
        val b = baked ?: cache.getOrPut((w.toLong() shl 32) or h.toLong()) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { paint(Canvas(it)) }
        }.also { baked = it }
        c.drawBitmap(b, bounds.left.toFloat(), bounds.top.toFloat(), null)
    }

    private fun paint(c: Canvas) {
        c.drawRoundRect(rect, radius, radius, gloss)
        c.drawRect(rect, shade)
        c.drawRoundRect(rect.left + .7f * s, rect.top + .7f * s, rect.right - .7f * s, rect.bottom - .7f * s, radius, radius, rim)
    }
}
