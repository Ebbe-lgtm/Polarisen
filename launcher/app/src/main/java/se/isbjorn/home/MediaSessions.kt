package se.isbjorn.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fortsätt titta. This low-RAM TV refuses notification-listener access (the normal way to read other apps' media
 * sessions) and Google's "Play Next" list is closed to non-system apps, so we read `dumpsys media_session` instead.
 * Needs the development permission DUMP, granted once over adb: pm grant se.isbjorn.home android.permission.DUMP
 */
object MediaSessions {
    /** The raw dump, or null without the DUMP permission (the output is then just a permission error). */
    fun dump(): String? = try {
        val p = ProcessBuilder("dumpsys", "media_session").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text.takeIf { "Sessions Stack" in it }
    } catch (e: Exception) { null }

    /** Records every titled session (except our own) into [History]; true if the list changed. */
    fun capture(ctx: Context): Boolean {
        val sessions = parseSessions(dump() ?: return false).filter { it.pkg != ctx.packageName }
        if (sessions.isEmpty()) return false
        val before = History.load(ctx)
        var list = before
        // the stack is newest first: insert oldest first so the newest ends on top; keep first-seen time for unchanged items
        sessions.asReversed().forEach { s ->
            val old = list.firstOrNull { it.pkg == s.pkg && it.title == s.title }
            list = remember(list, Played(s.pkg, s.title, s.subtitle, null, s.position, 0, old?.time ?: System.currentTimeMillis()))
        }
        if (list == before) return false
        History.save(ctx, list)
        return true
    }
}

/** Newest-first list of what was played, kept in SharedPreferences as JSON. */
object History {
    private const val PREFS = "history"
    private const val KEY = "items"

    fun load(ctx: Context): List<Played> = try {
        val a = JSONArray(ctx.getSharedPreferences(PREFS, 0).getString(KEY, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Played(it.getString("pkg"), it.getString("title"), it.optString("sub"), it.optString("art").ifEmpty { null },
                it.optLong("pos"), it.optLong("dur"), it.optLong("time"))
        }
    } catch (e: Exception) { emptyList() } // corrupt prefs must never break the home screen

    fun save(ctx: Context, list: List<Played>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("pkg", it.pkg).put("title", it.title).put("sub", it.subtitle).put("art", it.art ?: "")
                .put("pos", it.position).put("dur", it.duration).put("time", it.time))
        }
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY, a.toString()).apply()
    }
}
