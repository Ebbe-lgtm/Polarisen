package se.isbjorn.home

import org.junit.Assert.assertEquals
import org.junit.Test

class NavTest {
    private val n = 14 // 6 dock + 8 grid (row of 6 + row of 2)

    @Test fun dockStaysInsideDock() {
        assertEquals(Nav(Zone.DOCK, 5), Nav(Zone.DOCK, 5).go('r', n, 0))
        assertEquals(Nav(Zone.DOCK, 0), Nav(Zone.DOCK, 0).go('l', n, 0))
        assertEquals(Nav(Zone.DOCK, 2), Nav(Zone.DOCK, 2).go('r', 3, 0)) // only 3 apps
    }

    @Test fun downFromDockKeepsColumn() {
        assertEquals(Nav(Zone.GRID, 9), Nav(Zone.DOCK, 3).go('d', n, 0))
        assertEquals(Nav(Zone.DOCK, 3), Nav(Zone.DOCK, 3).go('d', 6, 0)) // no grid
    }

    @Test fun gridEdgesAndPartialLastRow() {
        assertEquals(Nav(Zone.GRID, 13), Nav(Zone.GRID, 7).go('d', n, 0)) // col 1 -> row 2 col 1
        assertEquals(Nav(Zone.GRID, 13), Nav(Zone.GRID, 9).go('d', n, 0)) // col 3 -> clamps to last item
        assertEquals(Nav(Zone.GRID, 13), Nav(Zone.GRID, 13).go('r', n, 0))
        assertEquals(Nav(Zone.GRID, 12), Nav(Zone.GRID, 12).go('l', n, 0))
        assertEquals(Nav(Zone.DOCK, 4), Nav(Zone.GRID, 10).go('u', n, 0))
        assertEquals(Nav(Zone.GRID, 7), Nav(Zone.GRID, 13).go('u', n, 0))
    }

    @Test fun shelfFromDockOnlyWhenNotMoving() {
        assertEquals(Nav(Zone.SHELF, 2, 0), Nav(Zone.DOCK, 2).go('u', n, 3))
        assertEquals(Nav(Zone.DOCK, 2), Nav(Zone.DOCK, 2).go('u', n, 3, moving = true))
        assertEquals(Nav(Zone.SHELF, 2, 2), Nav(Zone.SHELF, 2, 2).go('r', n, 3))
        assertEquals(Nav(Zone.SHELF, 2, 0), Nav(Zone.SHELF, 2, 0).go('r', n, 0)) // empty row
        assertEquals(Nav(Zone.DOCK, 2, 1), Nav(Zone.SHELF, 2, 1).go('d', n, 3))
    }

    @Test fun mergeOrderFirstRunAndNewApps() {
        val fixed = listOf("src", "set")
        // first run: defaults that are installed, then the rest in installed order, fixed tiles last
        assertEquals(listOf("yt", "svt", "a", "b", "src", "set"),
            mergeOrder(emptyList(), listOf("a", "svt", "b", "yt"), listOf("yt", "svt", "nflx"), fixed))
        // saved order kept, removed app dropped, new app before the fixed tail
        assertEquals(listOf("b", "yt", "new", "src", "set"),
            mergeOrder(listOf("b", "gone", "yt", "src", "set"), listOf("yt", "b", "new"), emptyList(), fixed))
        // fixed tiles moved by the user stay where they are; missing fixed tile re-added
        assertEquals(listOf("src", "yt", "new", "set"),
            mergeOrder(listOf("src", "yt"), listOf("yt", "new"), emptyList(), fixed))
    }

    @Test fun rememberDedupsAndCaps() {
        fun p(pkg: String, t: String, time: Long) = Played(pkg, t, "", null, 0, 0, time)
        var list = emptyList<Played>()
        for (i in 1..10) list = remember(list, p("app", "show$i", i.toLong()))
        assertEquals(8, list.size)
        assertEquals("show10", list.first().title)
        list = remember(list, p("app", "show5", 99))
        assertEquals(listOf("show5", "show10", "show9"), list.take(3).map { it.title })
        assertEquals(1, list.count { it.title == "show5" })
    }

    // Trimmed real output from the TV (2026-10-20)
    private val dump = """
        Media button session is com.netflix.ninja/Netflix media session (userId=0)
        Sessions Stack - have 2 sessions:
          Netflix media session com.netflix.ninja/Netflix media session (userId=0)
            package=com.netflix.ninja
            state=PlaybackState {state=1, position=0, buffered position=0, speed=1.0, updated=281557383, actions=1049468}
            metadata:size=0, description=null
          spotify-android-tv-media-session com.spotify.tv.android/spotify-android-tv-media-session (userId=0)
            package=com.spotify.tv.android
            state=PlaybackState {state=0, position=123380, buffered position=0, speed=1.0, updated=274905890}
            metadata:size=8, description=Veteraner, Bolaget, Veteraner
    """.trimIndent()

    @Test fun parsesMediaSessionDump() {
        assertEquals(listOf(Session("com.spotify.tv.android", "Veteraner", "Bolaget", 123380)), parseSessions(dump))
        assertEquals("com.netflix.ninja", mediaButtonPackage(dump))
        assertEquals(null, mediaButtonPackage("Media button session is null"))
        assertEquals(emptyList<Session>(), parseSessions("Permission Denial: can't dump MediaSessionService"))
    }
}
