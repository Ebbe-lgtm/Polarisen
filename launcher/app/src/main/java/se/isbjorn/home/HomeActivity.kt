package se.isbjorn.home

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Isbjörn home screen, laid out in 1280x720 design pixels (scaled by [s]) exactly like the approved mockup:
 * Top Shelf image + Liquid Glass dock (first 6 apps) + 6-column grid; Up = Fortsätt titta, Down = all apps.
 * Focus is handled here (no Android focus system) so the remote's D-pad maps 1:1 to the mockup behaviour.
 */
class HomeActivity : Activity() {
    private var s = 1f
    private fun px(v: Float) = v * s
    private fun pxi(v: Float) = (v * s).roundToInt()

    private val prefs by lazy { getSharedPreferences("home", 0) }
    private val ui = Handler(Looper.getMainLooper())
    private val sv = Locale("sv", "SE")
    private val timeFmt = SimpleDateFormat("HH:mm", sv)
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", sv)

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var bgLayer: FrameLayout
    private lateinit var gridLayer: FrameLayout
    private lateinit var shelfScrim: View
    private lateinit var clockBox: LinearLayout
    private lateinit var time: TextView
    private lateinit var date: TextView
    private lateinit var captionBox: FrameLayout
    private lateinit var caption: LinearLayout
    private lateinit var capT: TextView
    private lateinit var capS: TextView
    private lateinit var mini: TextView
    private lateinit var dock: FrameLayout
    private lateinit var dockGlass: GlassDrawable
    private lateinit var hint: TextView
    private lateinit var label: TextView
    private lateinit var toastView: TextView
    private lateinit var shelf: LinearLayout
    private lateinit var track: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var intro: FrameLayout
    private lateinit var introImg: ImageView
    private lateinit var mark: TextView
    private lateinit var slideshow: Slideshow

    private class Entry(val key: String, val label: String, val banner: Drawable?, val icon: Drawable?, val launch: Intent?)
    private class Tile(val key: String, val view: FrameLayout, val gloss: GlossDrawable) { var baseX = 0f; var baseY = 0f }
    private class Card(val col: LinearLayout, val card: FrameLayout, val gloss: GlossDrawable, val t: TextView, val st: TextView)

    private val entries = HashMap<String, Entry>()
    private val tiles = HashMap<String, Tile>()
    private var order = mutableListOf<String>()
    private var nav = Nav()
    private var moving = false
    private var wiggle: ObjectAnimator? = null
    private var spotAnim: ValueAnimator? = null
    private var shelfItems = emptyList<Played>()
    private val cards = mutableListOf<Card>()
    private lateinit var focusShadow: ImageView

    private var resumed = false
    private var started = false
    private var appsDirty = false
    private var wakeIntro = false
    private var introRunning = false
    private var introAnim: AnimatorSet? = null
    private var swallowUp = -1

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = resources.displayMetrics.widthPixels / 1280f
        Fonts.init(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = false }
        val bgA = image(); val bgB = image()
        val gridA = image(); val gridB = image()
        val dockA = image(ImageView.ScaleType.FIT_XY); val dockB = image(ImageView.ScaleType.FIT_XY)
        buildViews(arrayOf(bgA, bgB), arrayOf(gridA, gridB), arrayOf(dockA, dockB))
        setContentView(root)
        slideshow = Slideshow(resources, listOf(
            Slideshow.BG to arrayOf(bgA, bgB), Slideshow.DOCK_GLASS to arrayOf(dockA, dockB), Slideshow.GRID_BLUR to arrayOf(gridA, gridB),
        )) { i, bg -> onImage(i, bg) }
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        loadApps()
        if (!introPlayed) { introPlayed = true; playIntro() }
    }

    override fun onStart() {
        super.onStart()
        started = true
        slideshow.interval = Prefs.interval(this)
        clockBox.visibility = if (Prefs.clock(this)) View.VISIBLE else View.INVISIBLE
        slideshow.start()
        registerReceiver(clock, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        tick()
        if (appsDirty) loadApps()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshShelf()
        Thread { if (MediaSessions.capture(this)) runOnUiThread { if (!isDestroyed) refreshShelf() } }.start()
        if (wakeIntro) { wakeIntro = false; playIntro() }
    }

    override fun onPause() { resumed = false; super.onPause() }

    override fun onStop() {
        started = false
        slideshow.stop()
        unregisterReceiver(clock)
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(packages); unregisterReceiver(screenOn)
        slideshow.close()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** HOME pressed while we're already home: back to the top, like Apple TV. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (moving) drop()
        if (!introRunning && nav != Nav()) setNav(Nav())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {} // the launcher never closes

    private val clock = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = tick() }
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (started) loadApps() else appsDirty = true }
    }
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (resumed) playIntro() else wakeIntro = true }
    }

    private fun tick() {
        val now = Date()
        time.text = timeFmt.format(now); mini.text = time.text
        date.text = dateFmt.format(now).uppercase(sv)
    }

    // ---------------------------------------------------------------- views

    // Containers we fade: without this, any alpha < 1 makes Android render them into an offscreen buffer every frame
    private fun flatFrame() = object : FrameLayout(this) { override fun hasOverlappingRendering() = false }
    private fun flatLinear() = object : LinearLayout(this) { override fun hasOverlappingRendering() = false }

    private fun image(type: ImageView.ScaleType = ImageView.ScaleType.CENTER_CROP) =
        ImageView(this).apply { scaleType = type; alpha = 0f }

    private fun <T : View> T.at(x: Float, y: Float, w: Float, h: Float): T = apply {
        layoutParams = FrameLayout.LayoutParams(if (w < 0) -2 else pxi(w), if (h < 0) -2 else pxi(h)).apply {
            leftMargin = pxi(x); topMargin = pxi(y)
        }
    }

    private fun text(size: Float, tf: Typeface, color: Int = Color.WHITE, spacing: Float = 0f) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); typeface = tf; setTextColor(color)
        letterSpacing = spacing; includeFontPadding = false; maxLines = 1
    }

    private fun TextView.shadow(r: Float = 12f, a: Int = 0xA6) = apply { setShadowLayer(px(r), 0f, px(1f), a shl 24) }

    private fun pill(t: TextView, radius: Float) = t.apply {
        background = GlassDrawable(px(radius), s, pill = true)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun View.round(r: Float) {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, px(r))
        }
        clipToOutline = true
    }

    private fun buildViews(bg: Array<ImageView>, grid: Array<ImageView>, dockImgs: Array<ImageView>) {
        val full = FrameLayout.LayoutParams(-1, -1)
        // the legibility scrim is baked into the bg images (images/tools/glass.ps1)
        bgLayer = FrameLayout(this).apply { bg.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)) } }
        root.addView(bgLayer, full)
        gridLayer = flatFrame().apply { alpha = 0f; grid.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)) } }
        root.addView(gridLayer, FrameLayout.LayoutParams(-1, -1))
        // Fortsätt titta: Apple's clear-glass dimming over bright media + a floor gradient
        // Fortsätt titta dims only a soft band behind the row. It is a tiny pre-drawn gradient bitmap stretched by the GPU:
        // live gradients over big areas are what made this transition stutter (a full-screen dim cost ~6 ms a frame).
        val band = Bitmap.createBitmap(1, 64, Bitmap.Config.ARGB_8888).also { b ->
            Canvas(b).drawRect(0f, 0f, 1f, 64f, Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 0f, 64f, intArrayOf(0, 0x99000000.toInt(), 0x99000000.toInt(), 0),
                    floatArrayOf(0f, .33f, .66f, 1f), android.graphics.Shader.TileMode.CLAMP)
            })
        }
        shelfScrim = ImageView(this).apply { alpha = 0f; scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(band) }
        root.addView(shelfScrim.at(0f, 180f, 1280f, 360f))

        // big centered clock + date
        time = text(66f, Fonts.light, spacing = -.01f).shadow(24f, 0x73)
        date = text(13f, Fonts.semibold, 0xDBFFFFFF.toInt(), .24f).shadow(16f, 0x73)
        clockBox = flatLinear().apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(time, LinearLayout.LayoutParams(-2, -2)); addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxi(4f) })
        }
        root.addView(clockBox, FrameLayout.LayoutParams(-1, -2).apply { topMargin = pxi(34f) })

        // caption bottom-left above the dock
        capT = text(38f, Fonts.caption, spacing = .02f).shadow(20f, 0x80)
        capS = text(12f, Fonts.medium, 0xCCFFFFFF.toInt(), .16f).shadow(10f, 0x99)
        caption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; alpha = 0f
            // wrap_content, or the title keeps its old width when the text changes and gets clipped
            addView(capT, LinearLayout.LayoutParams(-2, -2)); addView(capS, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxi(5f) })
        }
        captionBox = FrameLayout(this).apply { addView(caption) }
        root.addView(captionBox, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = pxi(56f); bottomMargin = pxi(250f)
        })

        mini = pill(text(15f, Fonts.medium).apply { setPadding(pxi(15f), 0, pxi(15f), 0) }, 17f).apply { alpha = 0f; translationY = -px(6f) }
        root.addView(mini, FrameLayout.LayoutParams(-2, pxi(34f), Gravity.TOP or Gravity.END).apply { topMargin = pxi(40f); rightMargin = pxi(54f) })

        // Fortsätt titta row
        track = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        emptyHint = pill(text(14f, Fonts.medium, 0xE6FFFFFF.toInt()).apply {
            text = "Det du spelar dyker upp här"; setPadding(pxi(16f), 0, pxi(16f), 0)
        }, 18f)
        shelf = flatLinear().apply {
            orientation = LinearLayout.VERTICAL; alpha = 0f; translationY = px(24f); clipChildren = false; clipToPadding = false
            // no text shadows in the shelf: it slides in over a dark scrim, and moving shadowed text re-blurs every frame
            addView(text(13f, Fonts.semibold, 0xE6FFFFFF.toInt(), .16f).apply { text = "FORTSÄTT TITTA" },
                LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = pxi(12f) })
            addView(track, LinearLayout.LayoutParams(-2, -2))
            addView(emptyHint, LinearLayout.LayoutParams(-2, pxi(36f)))
        }
        root.addView(shelf.at(54f, 252f, 1173f, -1f))

        // content: dock glass, hint, tiles, label (moves up as a whole in grid mode)
        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        dockGlass = GlassDrawable(px(26f), s, pill = false)
        dock = flatFrame().apply {
            // own container: Slideshow reorders the pair with bringToFront(), which must not lift it above the glass overlay
            addView(FrameLayout(this@HomeActivity).apply { dockImgs.forEach { addView(it, FrameLayout.LayoutParams(-1, -1)) } }, FrameLayout.LayoutParams(-1, -1))
            addView(View(this@HomeActivity).apply { background = dockGlass }, FrameLayout.LayoutParams(-1, -1))
            alpha = 0f
        }
        content.addView(dock.at(40f, 496f, 1201f, 125f))
        dock.round(26f)
        hint = pill(text(12f, Fonts.semibold, spacing = .12f).apply {
            text = "FORTSÄTT TITTA"; setPadding(pxi(10f), 0, pxi(13f), 0)
            setCompoundDrawables(getDrawable(R.drawable.ic_chevron_up)!!.apply { setBounds(0, 0, pxi(16f), pxi(16f)) }, null, null, null)
            compoundDrawablePadding = pxi(7f)
        }, 14f).apply { alpha = 0f }
        content.addView(hint, FrameLayout.LayoutParams(-2, pxi(28f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = pxi(454f) })
        // one pre-blurred shadow that follows the focused tile (live elevation shadows cost ~30 ms a frame on this GPU)
        focusShadow = ImageView(this).apply { setImageBitmap(shadowBitmap()); alpha = 0f }
        content.addView(focusShadow.at(0f, 0f, 173f + 2 * SH_PAD, 97f + 2 * SH_PAD))
        label = text(14f, Fonts.medium).shadow(10f, 0xB3).apply { gravity = Gravity.CENTER; alpha = 0f }
        content.addView(label.at(0f, 0f, 221f, -1f))

        toastView = pill(text(15f, Fonts.medium).apply { setPadding(pxi(20f), 0, pxi(20f), 0) }, 20f).apply { alpha = 0f; elevation = px(20f) }
        root.addView(toastView, FrameLayout.LayoutParams(-2, pxi(40f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = pxi(40f) })

        // intro overlay
        introImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        mark = text(26f, Fonts.regular, spacing = .55f).apply { text = "POLARISEN"; alpha = 0f; gravity = Gravity.CENTER }.shadow(28f, 0xE6)
        intro = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE; elevation = px(40f)
            addView(introImg, FrameLayout.LayoutParams(-1, -1))
            addView(mark, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = pxi(92f) })
        }
        root.addView(intro, full)
    }

    // ---------------------------------------------------------------- apps & tiles

    private fun loadApps() {
        appsDirty = false
        Thread {
            val pm = packageManager
            val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName !in HIDDEN }
                .distinctBy { it.activityInfo.packageName }
                .map { ri ->
                    val ai = ri.activityInfo
                    Entry(ai.packageName, ri.loadLabel(pm).toString(), ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm), ri.loadIcon(pm),
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setClassName(ai.packageName, ai.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { if (!isDestroyed) applyApps(list) }
        }.start()
    }

    private fun applyApps(list: List<Entry>) {
        entries.clear()
        list.forEach { entries[it.key] = it }
        entries[KEY_SOURCES] = Entry(KEY_SOURCES, "Källor", null, getDrawable(R.drawable.ic_sources),
            Intent(this, PolarSettingsActivity::class.java).putExtra(PolarSettingsActivity.EXTRA_PAGE, PolarSettingsActivity.PAGE_SOURCES))
        entries[KEY_SETTINGS] = Entry(KEY_SETTINGS, "Inställningar", null, getDrawable(R.drawable.ic_settings),
            Intent(this, PolarSettingsActivity::class.java))
        val saved = prefs.getString("order", "")!!.split('\n').filter { it.isNotEmpty() }
        order = mergeOrder(saved, list.map { it.key }, DEFAULTS, listOf(KEY_SOURCES, KEY_SETTINGS)).toMutableList()

        if (moving) drop()
        tiles.values.forEach { content.removeView(it.view) }
        tiles.clear()
        order.forEach { k -> tiles[k] = makeTile(entries.getValue(k)) }
        if (nav.f >= order.size) nav = nav.copy(f = order.size - 1, zone = if (order.size - 1 < DOCK) Zone.DOCK else Zone.GRID)
        layoutTiles(animate = false)
        order.forEachIndexed { pos, k -> tiles.getValue(k).view.alpha = if (introRunning) 0f else tileAlpha(pos) }
        currentTile()?.let { focusTile(it, true) }
        moveSpot(animate = false)
        updateLabel()
    }

    private fun makeTile(e: Entry): Tile {
        val gloss = GlossDrawable(px(12f), s)
        val v = flatFrame() // tiles fade between .75 and 1; no offscreen buffer per tile
        if (e.banner != null) {
            v.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageDrawable(e.banner) }, FrameLayout.LayoutParams(-1, -1))
        } else {
            // glass tile: icon/glyph + name (fixed tiles and apps without a TV banner)
            v.background = LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(0x59141A20) },
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x3DFFFFFF, 0x1AFFFFFF)),
            ))
            v.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(ImageView(this@HomeActivity).apply { setImageDrawable(e.icon) }, LinearLayout.LayoutParams(pxi(32f), pxi(32f)))
                addView(text(13f, Fonts.medium).apply { text = e.label; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = pxi(5f) })
            }, FrameLayout.LayoutParams(-1, -1))
        }
        v.foreground = gloss
        content.addView(v.at(0f, 0f, 173f, 97f))
        v.round(12f)
        return Tile(e.key, v, gloss)
    }

    private fun tileAlpha(pos: Int) = if (pos < DOCK || nav.zone == Zone.GRID) 1f else .75f

    private fun layoutTiles(animate: Boolean) {
        order.forEachIndexed { pos, key ->
            val t = tiles[key] ?: return@forEachIndexed
            val g = pos - DOCK
            t.baseX = px(54f + (if (pos < DOCK) pos else g % COLS) * 200f)
            t.baseY = px(if (pos < DOCK) 510f else 674f + (g / COLS) * 164f)
            val ty = t.baseY - if (isFocused(pos)) px(6f) else 0f
            if (animate) t.view.animate().translationX(t.baseX).translationY(ty).setDuration(250).setInterpolator(EASE).start()
            else { t.view.translationX = t.baseX; t.view.translationY = ty }
            if (isFocused(pos)) { // the shadow travels with the focused tile (move mode)
                if (animate) focusShadow.animate().translationX(t.baseX - px(SH_PAD)).translationY(ty - px(SH_PAD)).setDuration(250).setInterpolator(EASE).start()
                else { focusShadow.translationX = t.baseX - px(SH_PAD); focusShadow.translationY = ty - px(SH_PAD) }
            }
        }
    }

    private fun isFocused(pos: Int) = nav.zone != Zone.SHELF && pos == nav.f
    private fun currentTile() = if (nav.zone == Zone.SHELF) null else order.getOrNull(nav.f)?.let { tiles[it] }

    private fun focusTile(t: Tile, on: Boolean) {
        // Pure 2D lift (scale + rise) with a soft overshoot: only cheap properties animate, so every frame stays on the GPU
        val v = t.view
        if (on) {
            v.animate().scaleX(1.15f).scaleY(1.15f).translationX(t.baseX).translationY(t.baseY - px(6f))
                .setDuration(POP_MS).setInterpolator(POP).start()
            focusShadow.animate().cancel()
            focusShadow.translationX = t.baseX - px(SH_PAD); focusShadow.translationY = t.baseY - px(SH_PAD)
            focusShadow.scaleX = 1f; focusShadow.scaleY = 1f; focusShadow.alpha = 0f
            focusShadow.animate().alpha(1f).scaleX(1.15f).scaleY(1.15f).translationY(t.baseY - px(SH_PAD) - px(6f))
                .setDuration(POP_MS).setInterpolator(POP).start()
        } else {
            v.animate().scaleX(1f).scaleY(1f).translationX(t.baseX).translationY(t.baseY).setDuration(200).setInterpolator(EASE).start()
            focusShadow.animate().alpha(0f).setDuration(150).start() // re-placed at once if another tile takes focus
        }
    }

    /** Soft drop shadow for the focused tile, blurred once in software. */
    private fun shadowBitmap(): Bitmap {
        val pad = px(SH_PAD); val w = px(173f); val h = px(97f)
        val b = Bitmap.createBitmap((w + 2 * pad).toInt(), (h + 2 * pad).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(b).drawRoundRect(pad + px(4f), pad + px(12f), pad + w - px(4f), pad + h + px(6f), px(12f), px(12f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); maskFilter = BlurMaskFilter(px(16f), BlurMaskFilter.Blur.NORMAL) })
        return b
    }

    /** The dock's specular spot glides to the focused app. */
    private fun moveSpot(animate: Boolean = true) {
        if (nav.zone == Zone.GRID || nav.f >= DOCK) return
        val target = px(54f - 40f + nav.f * 200f + 86f)
        spotAnim?.cancel()
        if (!animate) { dockGlass.hx = target; return }
        spotAnim = ValueAnimator.ofFloat(dockGlass.hx, target).apply {
            duration = 550; interpolator = EASE
            addUpdateListener { dockGlass.hx = it.animatedValue as Float }
            start()
        }
    }

    private fun updateLabel() {
        val t = currentTile()
        val show = nav.zone == Zone.GRID && t != null
        if (show) {
            label.text = entries[t!!.key]?.label
            label.translationX = t.baseX - px(24f); label.translationY = t.baseY + px(113f)
        }
        label.animate().alpha(if (show) 1f else 0f).setDuration(200).start()
    }

    // ---------------------------------------------------------------- navigation

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (introRunning) {
            if (e.action == KeyEvent.ACTION_DOWN) { swallowUp = e.keyCode; endIntro() }
            return true // the key that skips the intro does nothing else
        }
        if (e.action == KeyEvent.ACTION_UP && e.keyCode == swallowUp) { swallowUp = -1; return true }
        return super.dispatchKeyEvent(e)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> step('l')
            KeyEvent.KEYCODE_DPAD_RIGHT -> step('r')
            KeyEvent.KEYCODE_DPAD_UP -> step('u')
            KeyEvent.KEYCODE_DPAD_DOWN -> step('d')
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                if (e.repeatCount == 0) e.startTracking()
            KeyEvent.KEYCODE_BACK -> back()
            // the remote's settings and source keys open Polarisen's menus instead of TCL's (TCL only acts on unhandled keys)
            PolarSettingsActivity.KEY_TCL_SETTINGS -> if (e.repeatCount == 0) startActivity(Intent(this, PolarSettingsActivity::class.java))
            KeyEvent.KEYCODE_TV_INPUT -> if (e.repeatCount == 0) startActivity(Intent(this, PolarSettingsActivity::class.java)
                .putExtra(PolarSettingsActivity.EXTRA_PAGE, PolarSettingsActivity.PAGE_SOURCES))
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, e: KeyEvent): Boolean {
        if (isOk(keyCode) && nav.zone != Zone.SHELF && !moving && currentTile() != null) {
            moving = true
            currentTile()?.view?.let { v ->
                wiggle = ObjectAnimator.ofFloat(v, View.ROTATION, -1.4f, 1.4f).apply {
                    duration = 220; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
                }
            }
            toast("Flytta med pilarna · OK släpper")
            return true
        }
        return super.onKeyLongPress(keyCode, e)
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        if (isOk(keyCode)) {
            if (e.isTracking && !e.isCanceled) { if (moving) drop() else activate() }
            return true
        }
        // consume the release as well, or TCL still pops up its own menu
        if (keyCode == PolarSettingsActivity.KEY_TCL_SETTINGS || keyCode == KeyEvent.KEYCODE_TV_INPUT) return true
        return super.onKeyUp(keyCode, e)
    }

    private fun isOk(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A

    private fun step(dir: Char) {
        val next = nav.go(dir, order.size, shelfItems.size, moving)
        if (moving && next.f != nav.f && next.zone != Zone.SHELF) {
            Collections.swap(order, nav.f, next.f)
            val zoneChanged = next.zone != nav.zone
            nav = next
            if (zoneChanged) applyZone()
            layoutTiles(animate = true)
            moveSpot(); updateLabel()
            return
        }
        setNav(next)
    }

    private fun setNav(n: Nav) {
        val old = nav
        if (n == old) return
        val oldTile = currentTile()
        nav = n
        val newTile = currentTile()
        if (old.zone != n.zone) applyZone()
        if (oldTile !== newTile) { oldTile?.let { focusTile(it, false) }; newTile?.let { focusTile(it, true) } }
        moveSpot(); updateLabel()
        val wasCard = if (old.zone == Zone.SHELF) old.s else -1
        val isCard = if (n.zone == Zone.SHELF) n.s else -1
        if (wasCard != isCard) { cards.getOrNull(wasCard)?.let { focusCard(it, false) }; cards.getOrNull(isCard)?.let { focusCard(it, true) } }
        track.animate().translationX(-maxOf(0, n.s - 3) * px(291f)).setDuration(400).setInterpolator(EASE).start()
    }

    /** Dock / all apps / Fortsätt titta: what slides, fades and blurs (all values from the mockup). */
    private fun applyZone() {
        val z = nav.zone
        val grid = z == Zone.GRID; val sh = z == Zone.SHELF
        content.animate().translationY(if (grid) -px(406f) else 0f).setDuration(500).setInterpolator(EASE).start()
        dock.animate().alpha(if (grid) 0f else 1f).translationY(if (grid) -px(20f) else 0f).setDuration(450).setInterpolator(EASE).start()
        // once the blurred grid backdrop fully covers the picture, stop drawing the picture (one full-screen pass less per frame)
        gridLayer.animate().alpha(if (grid) 1f else 0f).setDuration(500).setInterpolator(EASE)
            .withStartAction { if (!grid) bgLayer.visibility = View.VISIBLE }
            .withEndAction { if (grid && nav.zone == Zone.GRID) bgLayer.visibility = View.INVISIBLE }.start()
        // fade only: scaling/moving the clock forces its soft text shadow to be re-blurred on the CPU every frame
        clockBox.animate().alpha(if (grid) 0f else 1f).setDuration(350).setInterpolator(EASE).start()
        mini.animate().alpha(if (grid) 1f else 0f).translationY(if (grid) 0f else -px(6f)).setStartDelay(if (grid) 120 else 0)
            .setDuration(450).setInterpolator(EASE).start()
        captionBox.animate().alpha(if (z == Zone.DOCK) 1f else 0f).setDuration(350).start()
        hint.animate().alpha(if (z == Zone.DOCK) 1f else 0f).setDuration(300).start()
        // short and compact: on this GPU frame cost follows the area that changes, so keep it small and brief
        shelfScrim.animate().alpha(if (sh) 1f else 0f).setDuration(300).start()
        shelf.animate().alpha(if (sh) 1f else 0f).translationY(if (sh) 0f else px(16f)).setDuration(300).setInterpolator(EASE).start()
        order.forEachIndexed { pos, k -> if (pos >= DOCK) tiles[k]?.view?.animate()?.alpha(tileAlpha(pos))?.setDuration(450)?.start() }
    }

    private fun back() {
        when {
            moving -> drop()
            nav.zone == Zone.GRID -> setNav(Nav(Zone.DOCK, 0, nav.s))
            nav.zone == Zone.SHELF -> setNav(nav.copy(zone = Zone.DOCK))
        }
    }

    private fun drop() {
        moving = false
        wiggle?.cancel(); wiggle = null
        tiles.values.forEach { it.view.rotation = 0f }
        prefs.edit().putString("order", order.joinToString("\n")).apply()
        toast("Ordningen sparad")
    }

    private fun activate() {
        if (nav.zone == Zone.SHELF) { shelfItems.getOrNull(nav.s)?.let { play(it) }; return }
        val e = entries[order.getOrNull(nav.f) ?: return] ?: return
        try { startActivity(e.launch) } catch (x: Exception) { if (e.key == KEY_SOURCES) launchPkg("com.tcl.tv") }
    }

    private fun launchPkg(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg) ?: return
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (x: Exception) {}
    }

    private var toastHide: Runnable? = null
    private fun toast(msg: String) {
        toastView.text = msg
        toastView.animate().alpha(1f).setDuration(200).start()
        toastHide?.let { ui.removeCallbacks(it) }
        toastHide = Runnable { toastView.animate().alpha(0f).setDuration(200).start() }.also { ui.postDelayed(it, 1700) }
    }

    // ---------------------------------------------------------------- Fortsätt titta

    private fun refreshShelf() {
        val items = History.load(this)
        if (items == shelfItems && cards.isNotEmpty() == items.isNotEmpty()) return
        shelfItems = items
        track.removeAllViews(); cards.clear()
        items.forEach { cards += makeCard(it) }
        emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        track.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (nav.s >= items.size) nav = nav.copy(s = maxOf(0, items.size - 1))
        if (nav.zone == Zone.SHELF) cards.getOrNull(nav.s)?.let { focusCard(it, true) }
    }

    private fun makeCard(p: Played): Card {
        val gloss = GlossDrawable(px(12f), s)
        val appName = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p.pkg, 0)).toString() } catch (e: Exception) { p.pkg }
        val art = p.art?.let { BitmapFactory.decodeFile(File(filesDir, it).path) }
        val card = FrameLayout(this).apply {
            if (art != null) addView(ImageView(this@HomeActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(art) }, FrameLayout.LayoutParams(-1, -1))
            else {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2A4560.toInt(), 0xFF0D1621.toInt()))
                addView(text(30f, Fonts.caption).apply { text = p.title.uppercase(sv); maxLines = 2; gravity = Gravity.CENTER; setPadding(pxi(16f), 0, pxi(16f), 0) },
                    FrameLayout.LayoutParams(-1, -1))
            }
            addView(text(11f, Fonts.semibold).apply {
                text = appName; setPadding(pxi(8f), pxi(3f), pxi(8f), pxi(3f))
                background = GradientDrawable().apply { setColor(0x73000000); cornerRadius = px(6f) }
            }, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = pxi(10f); topMargin = pxi(10f) })
            if (p.duration > 0) {
                val frac = (p.position.toFloat() / p.duration).coerceIn(0f, 1f)
                addView(View(this@HomeActivity).apply { background = GradientDrawable().apply { setColor(0x47FFFFFF); cornerRadius = px(2f) } }.at(12f, 135f, 240f, 4f))
                addView(View(this@HomeActivity).apply { background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = px(2f) } }.at(12f, 135f, 240f * frac, 4f))
            }
            foreground = gloss
        }
        val t = text(15f, Fonts.medium).apply { text = p.title; alpha = 0f; ellipsize = android.text.TextUtils.TruncateAt.END }
        val st = text(13f, Fonts.regular, 0xA8FFFFFF.toInt()).apply { text = p.subtitle; alpha = 0f; ellipsize = android.text.TextUtils.TruncateAt.END }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            addView(card, LinearLayout.LayoutParams(pxi(264f), pxi(149f)))
            addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pxi(16f) })
            addView(st, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pxi(3f) })
        }
        card.round(12f)
        track.addView(col, LinearLayout.LayoutParams(pxi(264f), -2).apply { if (track.childCount > 0) leftMargin = pxi(27f) })
        return Card(col, card, gloss, t, st)
    }

    private fun focusCard(c: Card, on: Boolean) {
        if (on) c.card.animate().scaleX(1.08f).scaleY(1.08f).setDuration(POP_MS).setInterpolator(POP).start()
        else c.card.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(EASE).start()
        c.t.animate().alpha(if (on) 1f else 0f).setDuration(200).start()
        c.st.animate().alpha(if (on) 1f else 0f).setDuration(200).start()
    }

    /** Open the app; if it still owns the media keys, press PLAY for it so it resumes where it was. */
    private fun play(p: Played) {
        launchPkg(p.pkg)
        Thread {
            // only when that app holds the media button session, otherwise PLAY could start another app (e.g. Spotify)
            if (mediaButtonPackage(MediaSessions.dump() ?: return@Thread) != p.pkg) return@Thread
            Thread.sleep(1500)
            val am = getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        }.start()
    }

    // ---------------------------------------------------------------- background + intro

    private fun onImage(i: Int, bg: Bitmap) {
        val (t, sub) = Slideshow.CAPTIONS[i]
        if (caption.alpha == 0f) { capT.text = t.uppercase(sv); capS.text = sub.uppercase(sv); caption.animate().alpha(1f).setDuration(800).start() }
        else caption.animate().alpha(0f).setDuration(800).withEndAction {
            capT.text = t.uppercase(sv); capS.text = sub.uppercase(sv)
            caption.animate().alpha(1f).setDuration(800).start()
        }.start()
        if (introRunning && i == 0 && introAnim == null) startIntroAnim(bg)
    }

    /** Black → polar bear fading in with a slow zoom, wordmark, then dissolve to home (~2 s). Any key skips. */
    private fun playIntro() {
        if (!Prefs.intro(this)) { introRunning = true; endIntro(); return } // turned off in Polarisen: just reveal home
        introRunning = true
        introAnim?.cancel(); introAnim = null
        intro.animate().cancel(); intro.alpha = 1f; intro.visibility = View.VISIBLE
        introImg.alpha = 0f; mark.alpha = 0f
        if (moving) drop()
        if (nav != Nav()) setNav(Nav())
        (listOf(dock, hint, focusShadow) + tiles.values.map { it.view }).forEach { it.animate().cancel(); it.alpha = 0f }
        slideshow.show(0, fade = 0)
        ui.postDelayed(endIntroRunnable, 3000) // safety net if the image never arrives
    }

    private val endIntroRunnable = Runnable { endIntro() }

    private fun startIntroAnim(bg: Bitmap) {
        introImg.setImageBitmap(bg)
        introImg.scaleX = 1.1f; introImg.scaleY = 1.1f
        introAnim = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(introImg, View.ALPHA, 0f, 1f).setDuration(720),
                ObjectAnimator.ofPropertyValuesHolder(introImg,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1.1f, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.1f, 1f))
                    .setDuration(1800).apply { interpolator = EASE },
                // fade only: animating letter spacing re-shapes the text and re-blurs its shadow every frame
                ObjectAnimator.ofFloat(mark, View.ALPHA, 0f, 1f, 1f, 0f).setDuration(1400).apply { startDelay = 350 },
            )
            start()
        }
        ui.removeCallbacks(endIntroRunnable)
        ui.postDelayed(endIntroRunnable, 1600)
    }

    private fun endIntro() {
        if (!introRunning) return
        introRunning = false
        ui.removeCallbacks(endIntroRunnable)
        introAnim?.cancel(); introAnim = null
        intro.animate().alpha(0f).setDuration(400).withEndAction { intro.visibility = View.GONE; introImg.setImageDrawable(null) }.start()
        // staggered reveal with standalone animators (a ViewPropertyAnimator would keep the start delay for later focus moves)
        fun reveal(v: View, to: Float, delay: Long) = ObjectAnimator.ofFloat(v, View.ALPHA, to).apply { startDelay = delay; duration = 450; start() }
        reveal(dock, 1f, 60); reveal(hint, 1f, 200)
        order.forEachIndexed { pos, k -> tiles[k]?.let { reveal(it.view, tileAlpha(pos), 120L + pos * 30) } }
        currentTile()?.let { focusTile(it, true) }
    }

    companion object {
        private var introPlayed = false // once per process ≈ once per boot for a launcher
        const val KEY_SOURCES = "isbjorn:sources"
        const val KEY_SETTINGS = "isbjorn:settings"
        const val SH_PAD = 28f
        const val POP_MS = 260L
        // quick rise with a slight settle; short enough to keep up with a held D-pad
        val POP = android.view.animation.PathInterpolator(.25f, .9f, .3f, 1.04f)
        // "TCL Channel" (TCL's content/ad channel); the package also runs remote-key features, so it stays enabled, just hidden
        val HIDDEN = setOf("com.tcl.partnercustomizer")
        val DEFAULTS = listOf("com.google.android.youtube.tv", "se.svt.android.svtplay", "com.netflix.ninja",
            "com.disney.disneyplus", "com.apple.atve.androidtv.appletv", "com.spotify.tv.android")
    }
}
