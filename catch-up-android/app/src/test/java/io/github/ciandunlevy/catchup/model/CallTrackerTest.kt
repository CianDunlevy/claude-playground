package io.github.ciandunlevy.catchup.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTrackerTest {
    private val s = 1000L

    @Test fun shortCallIsShortEvenIfAppTimestampIsOld() {
        val t = CallTracker(graceMs = 8 * s)
        t.posted("k", "Messenger", "Mam", null, connected = true, now = 100 * s)
        t.removed("k", now = 110 * s)
        assertTrue(t.finished(now = 112 * s).isEmpty()) // still within grace
        val done = t.finished(now = 119 * s).single()
        assertEquals(10L, done.seconds)
        assertEquals(100 * s, done.start)
    }

    @Test fun ringingTimeDoesNotCountOnceConnected() {
        val t = CallTracker()
        t.posted("ring", "WhatsApp", "Mam", null, connected = false, now = 0)
        t.posted("ring", "WhatsApp", "Mam", null, connected = true, now = 20 * s) // answered after 20 s
        t.removed("ring", now = 200 * s)
        assertEquals(180L, t.finished(now = 300 * s).single().seconds)
    }

    @Test fun swappedNotificationsAreOneCall() {
        val t = CallTracker(graceMs = 8 * s)
        t.posted("calling", "Messenger", "Dave", null, connected = false, now = 0)
        t.removed("calling", now = 5 * s)                 // "Calling…" goes away
        t.posted("ongoing", "Messenger", "Dave", null, connected = true, now = 6 * s) // "Ongoing call" appears
        assertTrue(t.finished(now = 20 * s).isEmpty())
        t.removed("ongoing", now = 306 * s)
        val done = t.finished(now = 320 * s).single()
        assertEquals(300L, done.seconds)
    }

    @Test fun differentPeopleAreSeparateCalls() {
        val t = CallTracker(graceMs = 0)
        t.posted("a", "WhatsApp", "Mam", null, true, 0)
        t.posted("b", "WhatsApp", "Dave", null, true, 0)
        t.removed("a", 60 * s)
        assertEquals(listOf("Mam"), t.finished(60 * s).map { it.name })
        t.removed("b", 120 * s)
        assertEquals(120L, t.finished(120 * s).single().seconds)
    }
}
