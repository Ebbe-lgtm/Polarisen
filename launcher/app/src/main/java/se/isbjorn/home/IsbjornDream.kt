package se.isbjorn.home

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Viloläge (screensaver): the home screen without the apps. It continues with the picture that was showing, keeps the
 * centered clock and date, shows the caption bottom-left and adds a slow zoom, so falling asleep looks like the UI
 * simply dissolving away. Fully offline.
 */
class IsbjornDream : DreamService() {
    private val ui = Handler(Looper.getMainLooper())
    private val sv = Locale("sv", "SE")
    private val timeFmt = SimpleDateFormat("HH:mm", sv)
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", sv)
    private var show: Slideshow? = null
    private var zoom: ObjectAnimator? = null
    private var s = 1f
    private lateinit var time: TextView
    private lateinit var date: TextView
    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            time.text = timeFmt.format(now); date.text = dateFmt.format(now).uppercase(sv)
            ui.postDelayed(this, 15_000)
        }
    }

    private fun px(v: Float) = v * s
    private fun text(size: Float, tf: Typeface, color: Int = Color.WHITE, spacing: Float = 0f, shadow: Float = 20f) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); typeface = tf; setTextColor(color); letterSpacing = spacing
        includeFontPadding = false; maxLines = 1; setShadowLayer(px(shadow), 0f, px(1f), 0x80000000.toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        s = resources.displayMetrics.widthPixels / 1280f
        Fonts.init(this)
        // own container: Slideshow reorders the pair with bringToFront(), which must not lift it above the text
        val imgs = FrameLayout(this)
        val views = Array(2) { ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }.also { imgs.addView(it, FrameLayout.LayoutParams(-1, -1)) } }
        time = text(66f, Fonts.light, spacing = -.01f, shadow = 24f)
        date = text(13f, Fonts.semibold, 0xDBFFFFFF.toInt(), .24f, 16f)
        val clock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(time, LinearLayout.LayoutParams(-2, -2)); addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(4f).toInt() })
        }
        val capT = text(30f, Fonts.caption, spacing = .02f)
        val capS = text(12f, Fonts.medium, 0xCCFFFFFF.toInt(), .16f, 10f)
        val caption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; alpha = 0f
            addView(capT, LinearLayout.LayoutParams(-2, -2)); addView(capS, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(5f).toInt() })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); alpha = 0f
            addView(imgs, FrameLayout.LayoutParams(-1, -1)) // the legibility scrim is baked into the images
            addView(clock, FrameLayout.LayoutParams(-1, -2).apply { topMargin = px(34f).toInt() })
            addView(caption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = px(56f).toInt(); bottomMargin = px(60f).toInt()
            })
        }
        setContentView(root)
        root.animate().alpha(1f).setDuration(1200).start()
        tick.run()
        show = Slideshow(resources, listOf(Slideshow.BG to views)) { i, _ ->
            // slow zoom on the picture that is fading in (the old one keeps drifting while it fades out)
            val front = imgs.getChildAt(imgs.childCount - 1)
            zoom = ObjectAnimator.ofPropertyValuesHolder(front,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f))
                .apply { duration = 48_000; interpolator = LinearInterpolator(); start() }
            val (t, sub) = Slideshow.CAPTIONS[i]
            caption.animate().alpha(0f).setDuration(if (caption.alpha == 0f) 0 else 800).withEndAction {
                capT.text = t.uppercase(sv); capS.text = sub.uppercase(sv)
                caption.animate().alpha(1f).setDuration(800).start()
            }.start()
        }.also { it.interval = Prefs.interval(this); it.start(Slideshow.current) }
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(tick)
        zoom?.cancel(); zoom = null
        show?.close(); show = null
        super.onDetachedFromWindow()
    }
}
