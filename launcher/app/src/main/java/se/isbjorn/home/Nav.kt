package se.isbjorn.home

// Pure home-screen logic (no Android types) so it can be unit tested on the JVM.

const val DOCK = 6
const val COLS = 6

enum class Zone { DOCK, GRID, SHELF }

/** Focus: [f] = index in the app order (first [DOCK] are the dock), [s] = index in the Fortsätt titta row. */
data class Nav(val zone: Zone = Zone.DOCK, val f: Int = 0, val s: Int = 0)

/** Where focus goes on a D-pad press [dir] ('l','r','u','d') with [n] apps and [shelf] continue-watching items. */
fun Nav.go(dir: Char, n: Int, shelf: Int, moving: Boolean = false): Nav {
    if (n == 0) return this
    if (zone == Zone.SHELF) return when (dir) {
        'l' -> copy(s = maxOf(0, s - 1))
        'r' -> copy(s = maxOf(0, minOf(shelf - 1, s + 1)))
        'd' -> copy(zone = Zone.DOCK)
        else -> this
    }
    var t = f
    if (f < DOCK) {
        when (dir) {
            'l' -> t = maxOf(0, f - 1)
            'r' -> t = minOf(minOf(DOCK, n) - 1, f + 1)
            'd' -> if (n > DOCK) t = minOf(DOCK + f, n - 1)
            'u' -> if (!moving) return copy(zone = Zone.SHELF)
        }
    } else {
        val g = f - DOCK
        val col = g % COLS
        val row = g / COLS
        val last = (n - 1 - DOCK) / COLS
        when (dir) {
            'l' -> if (col > 0) t = f - 1
            'r' -> if (col < COLS - 1 && f + 1 < n) t = f + 1
            'd' -> if (row < last) t = minOf(f + COLS, n - 1)
            'u' -> t = if (row == 0) minOf(col, DOCK - 1) else f - COLS
        }
    }
    return copy(zone = if (t < DOCK) Zone.DOCK else Zone.GRID, f = t)
}

/**
 * App order: the saved order (minus uninstalled apps), or [defaults] + the rest on first run.
 * New apps are inserted before the trailing block of [fixed] tiles (Källor, Inställningar), which always exist.
 */
fun mergeOrder(saved: List<String>, installed: List<String>, defaults: List<String>, fixed: List<String>): List<String> {
    val valid = installed.toSet() + fixed
    val base = if (saved.isEmpty()) defaults.filter { it in installed } + fixed else saved.filter { it in valid }.distinct()
    val out = base.toMutableList()
    var at = out.size
    while (at > 0 && out[at - 1] in fixed) at--
    out.addAll(at, installed.filter { it !in base })
    fixed.filter { it !in out }.forEach { out.add(it) }
    return out
}

/** Something that was playing, as recorded from a media session. */
data class Played(
    val pkg: String, val title: String, val subtitle: String, val art: String?,
    val position: Long, val duration: Long, val time: Long,
)

/** Newest first, one entry per app+title, at most [max]. */
fun remember(list: List<Played>, item: Played, max: Int = 8): List<Played> =
    (listOf(item) + list.filterNot { it.pkg == item.pkg && it.title == item.title }).take(max)

/** A media session from `dumpsys media_session` that exposes a title. */
data class Session(val pkg: String, val title: String, val subtitle: String, val position: Long)

/** Parses the Android 9 `dumpsys media_session` text: package=…, state=PlaybackState {…position=…}, metadata:… description=… */
fun parseSessions(dump: String): List<Session> {
    val out = mutableListOf<Session>()
    var pkg: String? = null
    var pos = 0L
    for (raw in dump.lineSequence()) {
        val l = raw.trim()
        when {
            l.startsWith("package=") -> { pkg = l.removePrefix("package="); pos = 0 }
            l.startsWith("state=PlaybackState") -> pos = Regex("position=(-?\\d+)").find(l)?.groupValues?.get(1)?.toLong() ?: 0
            l.startsWith("metadata:") -> {
                // shortcut: MediaDescription.toString() joins title, subtitle, description with ", " so a title containing ", " is cut; fine for this row
                val d = l.substringAfter("description=", "null").split(", ")
                val p = pkg
                if (p != null && d[0].isNotBlank() && d[0] != "null")
                    out += Session(p, d[0], d.getOrNull(1)?.takeIf { it != "null" } ?: "", maxOf(0, pos))
                pkg = null
            }
        }
    }
    return out
}

/** The app that currently receives media keys ("Media button session is <pkg>/<tag> …"), or null. */
fun mediaButtonPackage(dump: String): String? =
    dump.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Media button session is ") }
        ?.removePrefix("Media button session is ")?.substringBefore('/')?.takeIf { it != "null" && it.isNotBlank() }
