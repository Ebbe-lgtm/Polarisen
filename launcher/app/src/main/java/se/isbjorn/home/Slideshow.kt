package se.isbjorn.home

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Background rotation: the bear first, then the winter images, 45 s each with a 2.5 s crossfade.
 * Each "layer" is a pair of ImageViews (front/back) fed from its own resource list (bg, baked dock glass, baked grid blur),
 * so the glass always matches the picture behind it. Decoding happens off the main thread as RGB_565;
 * only the front and back bitmaps of each layer are alive.
 */
class Slideshow(
    private val res: Resources,
    private val layers: List<Pair<IntArray, Array<ImageView>>>,
    private val onShow: (index: Int, bg: Bitmap) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var front = 1
    private var request = 0
    var index = -1; private set
    private val next = Runnable { show((index + 1) % BG.size); schedule() }

    fun start(first: Int = 0) { if (request == 0) show(first); schedule() }
    fun stop() = main.removeCallbacks(next)
    fun close() { stop(); exec.shutdown() }
    /** Time per picture; set from Polarisen's settings. */
    var interval = 45_000L
    private fun schedule() { main.removeCallbacks(next); main.postDelayed(next, interval) }

    fun show(i: Int, fade: Long = 2500) {
        val req = ++request
        exec.execute {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bmps = layers.map { BitmapFactory.decodeResource(res, it.first[i], opts) }
            main.post { if (req == request) swap(i, bmps, fade) } // a newer request wins
        }
    }

    private fun swap(i: Int, bmps: List<Bitmap>, fade: Long) {
        val back = 1 - front
        layers.forEachIndexed { k, (_, views) ->
            val b = views[back]; val f = views[front]
            b.animate().cancel(); f.animate().cancel()
            b.setImageBitmap(bmps[k]); b.bringToFront()
            b.alpha = 0f
            b.animate().alpha(1f).setDuration(fade).withEndAction { f.setImageDrawable(null); f.alpha = 0f }.start()
        }
        front = back; index = i; current = i
        onShow(i, bmps[0])
    }

    companion object {
        /** Picture last shown on the home screen, so the screensaver continues with the same one. */
        @Volatile var current = 0
        // Order and captions as in the approved mockup (image 00 = the user's own polar bear wallpaper)
        val BG = intArrayOf(R.drawable.bg_00, R.drawable.bg_01, R.drawable.bg_02, R.drawable.bg_04, R.drawable.bg_03,
            R.drawable.bg_05, R.drawable.bg_08, R.drawable.bg_06, R.drawable.bg_07, R.drawable.bg_11, R.drawable.bg_09,
            R.drawable.bg_13, R.drawable.bg_10, R.drawable.bg_12, R.drawable.bg_14)
        val DOCK_GLASS = intArrayOf(R.drawable.dock_00, R.drawable.dock_01, R.drawable.dock_02, R.drawable.dock_04, R.drawable.dock_03,
            R.drawable.dock_05, R.drawable.dock_08, R.drawable.dock_06, R.drawable.dock_07, R.drawable.dock_11, R.drawable.dock_09,
            R.drawable.dock_13, R.drawable.dock_10, R.drawable.dock_12, R.drawable.dock_14)
        val GRID_BLUR = intArrayOf(R.drawable.grid_00, R.drawable.grid_01, R.drawable.grid_02, R.drawable.grid_04, R.drawable.grid_03,
            R.drawable.grid_05, R.drawable.grid_08, R.drawable.grid_06, R.drawable.grid_07, R.drawable.grid_11, R.drawable.grid_09,
            R.drawable.grid_13, R.drawable.grid_10, R.drawable.grid_12, R.drawable.grid_14)
        val CAPTIONS = arrayOf(
            "Isbjörn" to "Ursus maritimus", "Isbjörn" to "I polarnatten", "Norrsken" to "Island", "Snöskog" to "Finland",
            "Isbjörn" to "Alaska", "Isberg" to "Grönland", "Norrsken" to "Island", "Ung isbjörn" to "På havsisen",
            "Nanga Parbat" to "Pakistan", "Havsis" to "Grönland", "Isbjörn" to "Vila i snön", "Matterhorn" to "Zermatt",
            "Hintersee" to "Bayern", "Isbjörn" to "Under ytan", "Norrsken" to "Över skogen",
        )
    }
}
