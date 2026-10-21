package se.isbjorn.home

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Polarisen's own settings, read by the home screen and the screensaver every time they become visible. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("polarisen", 0)
    fun interval(c: Context) = p(c).getLong("interval", 45_000L)
    fun intro(c: Context) = p(c).getBoolean("intro", true)
    fun clock(c: Context) = p(c).getBoolean("clock", true)
    fun put(c: Context, k: String, v: Long) = p(c).edit().putLong(k, v).apply()
    fun put(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
}

/**
 * Polarisen settings, tvOS style: the system "icon" (current picture in glass) on the left, a list on the right with a
 * glass highlight that glides to the focused row. Polarisen's own options and the system settings it is allowed to write
 * (screensaver/sleep timers, animation speed) take effect at once; network, Bluetooth, apps, picture & sound open the
 * Android / TCL screens, which Polarisen cannot restyle without root.
 * System writes need, once over adb: pm grant se.isbjorn.home android.permission.WRITE_SECURE_SETTINGS
 * and appops set se.isbjorn.home WRITE_SETTINGS allow.
 */
class PolarSettingsActivity : Activity() {
    private class Row(val title: String, val value: () -> String = { "" }, val more: Boolean = false, val ok: (() -> Unit)? = null)
    private class Page(val title: String, val rows: List<Row>, var f: Int = 0)

    private val sv = Locale("sv", "SE")
    private var s = 1f
    private val stack = ArrayList<Page>()
    private lateinit var heading: TextView
    private lateinit var panel: FrameLayout
    private lateinit var highlight: View
    private var rowViews = listOf<Pair<TextView, TextView>>()

    private fun px(v: Float) = v * s
    private fun pxi(v: Float) = px(v).toInt()
    private fun text(size: Float, tf: Typeface, color: Int = Color.WHITE, spacing: Float = 0f) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); typeface = tf; setTextColor(color); letterSpacing = spacing
        includeFontPadding = false; maxLines = 1
    }
    private fun <T : View> T.at(x: Float, y: Float, w: Float, h: Float): T = apply {
        layoutParams = FrameLayout.LayoutParams(if (w < 0) -2 else pxi(w), if (h < 0) -2 else pxi(h)).apply { leftMargin = pxi(x); topMargin = pxi(y) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        s = resources.displayMetrics.widthPixels / 1280f
        Fonts.init(this)
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
        val i = Slideshow.current
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(BitmapFactory.decodeResource(resources, Slideshow.BG[i], opts))
            foreground = GlossDrawable(px(18f), s)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, px(18f))
            }
            clipToOutline = true
        }
        heading = text(13f, Fonts.semibold, 0xE6FFFFFF.toInt(), .16f)
        highlight = View(this).apply { background = GlassDrawable(px(14f), s, pill = true) }
        panel = FrameLayout(this).apply { clipChildren = false; addView(highlight.at(0f, 0f, 560f, 48f)) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(ImageView(this@PolarSettingsActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(BitmapFactory.decodeResource(resources, Slideshow.GRID_BLUR[i], opts))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(icon.at(120f, 196f, 400f, 225f))
            addView(text(48f, Fonts.light).apply { text = "Polarisen"; gravity = Gravity.CENTER }.at(80f, 452f, 480f, -1f))
            addView(text(15f, Fonts.medium, 0xB3FFFFFF.toInt()).apply { text = "Version ${version()}"; gravity = Gravity.CENTER }.at(80f, 516f, 480f, -1f))
            addView(heading.at(660f, 92f, 540f, -1f))
            addView(panel.at(640f, 124f, 560f, 560f))
        })
        open(if (intent.getStringExtra(EXTRA_PAGE) == PAGE_SOURCES) sourcesPage() else mainPage())
    }

    override fun onResume() { super.onResume(); if (stack.isNotEmpty()) render() } // values may have changed in Android's screens

    // ------------------------------------------------------------------ pages

    private fun mainPage() = Page("Inställningar", listOf(
        Row("Hemskärm", more = true) { open(homePage()) },
        Row("Viloläge", more = true) { open(sleepPage()) },
        Row("Animationer", { animLabel() }, more = true) { open(animPage()) },
        Row("Nätverk och internet", more = true) { launch(Intent(Settings.ACTION_WIFI_SETTINGS)) },
        // Android TV 9 has no standalone Bluetooth page; this is its "add remote or accessory" screen
        Row("Lägg till fjärrkontroll eller tillbehör", more = true) {
            launch(Intent().setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity")))
        },
        Row("Bild och ljud", more = true) { launch(Intent().setComponent(ComponentName("com.tcl.settings", "com.tcl.settings.MainActivity"))) },
        Row("Appar", more = true) { launch(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) }, // the generic action opens a chooser on this TV
        Row("Datum och tid", more = true) { launch(Intent(Settings.ACTION_DATE_SETTINGS)) },
        Row("Om Polarisen", more = true) { open(aboutPage()) },
        Row("Alla systeminställningar", more = true) {
            launch(Intent(Settings.ACTION_SETTINGS).setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")))
        },
    ))

    /** Källval: the TV's real inputs (antenna, HDMI, AV) with their names and cable state, plus the USB media player. */
    private fun sourcesPage(): Page {
        val tim = getSystemService(TvInputManager::class.java)
        val current = try { // hidden API, greylisted on Android 9; only used for the tick
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "persist.sys.curInput") as String
        } catch (e: Exception) { "" }
        val inputs = tim?.tvInputList.orEmpty().filter { !it.isHidden(this) }
            .sortedWith(compareBy({ if (it.type == TvInputInfo.TYPE_TUNER) 0 else if (it.type == TvInputInfo.TYPE_HDMI) 1 else 2 },
                { it.loadLabel(this).toString() }))
        val rows = inputs.map { info ->
            val name = (info.loadCustomLabel(this) ?: info.loadLabel(this)).toString()
            // no cable state: TCL reports empty HDMI/AV ports as connected, so only the active input gets a tick
            Row(name, { if (info.id == current) "✓" else "" }) {
                val uri = if (info.isPassthroughInput) TvContract.buildChannelUriForPassthroughInput(info.id)
                    else TvContract.buildChannelsUriForInput(info.id)
                launch(Intent(Intent.ACTION_VIEW, uri)); finish()
            }
        } + Row("Mediacenter (USB)", more = true) {
            packageManager.getLeanbackLaunchIntentForPackage("com.tcl.ui_mediaCenter")?.let { launch(it); finish() }
        }
        return Page("Källor", rows, maxOf(0, inputs.indexOfFirst { it.id == current }))
    }

    private fun homePage() = Page("Hemskärm", listOf(
        Row("Bildbyte", { duration(Prefs.interval(this)) }, more = true) {
            open(choose("Bildbyte", listOf(30_000L, 45_000L, 60_000L, 120_000L, 300_000L), Prefs.interval(this), ::duration) {
                Prefs.put(this, "interval", it); true
            })
        },
        Row("Öppningssekvens", { if (Prefs.intro(this)) "På" else "Av" }) { Prefs.put(this, "intro", !Prefs.intro(this)); render() },
        Row("Klocka och datum", { if (Prefs.clock(this)) "Visa" else "Dölj" }) { Prefs.put(this, "clock", !Prefs.clock(this)); render() },
        Row("Flytta appar", { "Håll in OK på en app" }),
    ))

    private fun sleepPage() = Page("Viloläge", listOf(
        Row("Starta viloläget efter", { duration(screenOff()) }, more = true) {
            open(choose("Starta viloläget efter", listOf(120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L, 3_600_000L, NEVER), screenOff(), ::duration) {
                write { Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, if (it == NEVER) Int.MAX_VALUE else it.toInt()) }
            })
        },
        Row("Stäng av TV:n efter", { duration(sleepTimeout()) }, more = true) {
            open(choose("Stäng av TV:n efter", listOf(1_800_000L, 3_600_000L, 7_200_000L, 14_400_000L, NEVER), sleepTimeout(), ::duration) {
                write { Settings.Secure.putInt(contentResolver, "sleep_timeout", if (it == NEVER) -1 else it.toInt()) }
            })
        },
        Row("Visa viloläget nu") { launch(Intent().setComponent(ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))) },
    ))

    private fun animPage() = choose("Animationer", listOf(0.5f, 1f, 0f), animScale(), { animName(it) }) { v ->
        write {
            Settings.Global.putFloat(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, v)
            Settings.Global.putFloat(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, v)
        }
    }

    private fun aboutPage(): Page {
        val am = getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val disk = StatFs(Environment.getDataDirectory().path)
        return Page("Om Polarisen", listOf(
            Row("System", { "Polarisen ${version()}" }),
            Row("Bygger på", { "Android ${Build.VERSION.RELEASE}" }),
            Row("Enhet", { "TCL 40ES565" }), // this build is made for the user's TV; Build.MODEL only says "UnionTV"
            Row("Processor", { "${Runtime.getRuntime().availableProcessors()} kärnor · ARMv7" }),
            Row("Minne", { "${gb(mem.totalMem)} · ${mb(mem.availMem)} ledigt" }),
            Row("Lagring", { "${gb(disk.availableBytes)} ledigt av ${gb(disk.totalBytes)}" }),
            Row("Säkerhetsuppdatering", { Build.VERSION.SECURITY_PATCH }),
            Row("Namn i nätverket", { Settings.Global.getString(contentResolver, "device_name") ?: Build.MODEL }),
        ))
    }

    /** A picker: the current value is ticked and focused; OK applies it and goes back. */
    private fun <T> choose(title: String, options: List<T>, current: T, name: (T) -> String, set: (T) -> Boolean) =
        Page(title, options.map { v -> Row(name(v), { if (v == current) "✓" else "" }) { if (set(v)) back() } },
            maxOf(0, options.indexOf(current)))

    // ------------------------------------------------------------------ values

    private fun version() = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "1.0" }
    private fun screenOff() = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 1_800_000).toLong()
        .let { if (it >= Int.MAX_VALUE) NEVER else it }
    private fun sleepTimeout() = Settings.Secure.getInt(contentResolver, "sleep_timeout", -1).toLong().let { if (it < 0) NEVER else it }
    private fun animScale() = Settings.Global.getFloat(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
    private fun animName(v: Float) = when { v == 0f -> "Av"; v < 1f -> "Snabba"; else -> "Normala" }
    private fun animLabel() = animName(animScale())

    private fun duration(ms: Long): String = when {
        ms == NEVER -> "Aldrig"
        ms < 60_000 -> "${ms / 1000} s"
        ms < 3_600_000 -> "${ms / 60_000} min"
        else -> "${ms / 3_600_000} h"
    }
    private fun gb(b: Long) = String.format(sv, "%.1f GB", b / 1e9)
    private fun mb(b: Long) = "${b / 1_000_000} MB"

    /** Writes a system setting; false (with a message) if Polarisen hasn't been given the permission. */
    private fun write(block: () -> Unit): Boolean = try { block(); true } catch (e: Exception) {
        Toast.makeText(this, "Polarisen saknar behörighet för den här inställningen", Toast.LENGTH_LONG).show(); false
    }

    private fun launch(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
            Toast.makeText(this, "Kunde inte öppna den inställningen", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ list + navigation

    private fun open(p: Page) { stack += p; render() }
    private fun back() { if (stack.size > 1) { stack.removeAt(stack.size - 1); render() } else finish() }
    private val page get() = stack.last()

    private fun render() {
        val p = page
        heading.text = p.title.uppercase(sv)
        rowViews.forEach { (t, v) -> panel.removeView(t.parent as View) }
        rowViews = p.rows.mapIndexed { i, r ->
            val t = text(19f, Fonts.regular).apply { text = r.title }
            val v = text(17f, Fonts.regular, 0xA6FFFFFF.toInt()).apply { text = r.value() + if (r.more) "  ›" else ""; gravity = Gravity.END }
            panel.addView(FrameLayout(this).apply {
                addView(t, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = pxi(20f) })
                addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = pxi(20f) })
            }.at(0f, i * 52f, 560f, 48f))
            t to v
        }
        focus(p.f, animate = false)
    }

    private fun focus(i: Int, animate: Boolean = true) {
        page.f = i
        val y = px(i * 52f)
        if (animate) highlight.animate().translationY(y).setDuration(220).setInterpolator(EASE).start() else highlight.translationY = y
        rowViews.forEachIndexed { k, (t, _) -> t.typeface = if (k == i) Fonts.medium else Fonts.regular }
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (page.f > 0) focus(page.f - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (page.f < page.rows.size - 1) focus(page.f + 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                page.rows[page.f].ok?.invoke()
            KeyEvent.KEYCODE_DPAD_RIGHT -> page.rows[page.f].let { if (it.more) it.ok?.invoke() }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> back()
            KEY_TCL_SETTINGS -> finish() // the remote's settings key toggles Polarisen settings
            KeyEvent.KEYCODE_TV_INPUT -> if (page.title == "Källor") finish() else open(sourcesPage())
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    // Consume the release too: TCL opens its own menus for any of these keys an app leaves unhandled
    override fun onKeyUp(keyCode: Int, e: KeyEvent) =
        keyCode == KEY_TCL_SETTINGS || keyCode == KeyEvent.KEYCODE_TV_INPUT || super.onKeyUp(keyCode, e)

    companion object {
        private const val NEVER = -1L
        const val EXTRA_PAGE = "page"
        const val PAGE_SOURCES = "sources"
        /** TCL's own key code for the remote's settings (gear) key; the source key is the standard KEYCODE_TV_INPUT. */
        const val KEY_TCL_SETTINGS = 4077
    }
}
