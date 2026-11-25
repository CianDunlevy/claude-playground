package io.github.ciandunlevy.catchup.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LogicTest {
    private val dublin = ZoneId.of("Europe/Dublin")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(dublin).toInstant().toEpochMilli()

    private fun person(last: Long, interval: Int = 7, phones: List<String> = emptyList()) =
        Person(id = "a", name = "Mam", intervalDays = interval, lastCaughtUp = last, phones = phones)

    @Test fun countsCalendarDaysNotHours() {
        // 23:30 one evening to 00:30 the next morning is one day, not zero.
        assertEquals(1, Logic.daysBetween(at(2026, 11, 1, 23, 30), at(2026, 11, 2, 0, 30), dublin))
    }

    @Test fun daylightSavingDoesNotShiftCounts() {
        // Clocks go back on 25 Oct 2026 in Ireland: that day is 25 hours long.
        assertEquals(2, Logic.daysBetween(at(2026, 10, 24, 0, 10), at(2026, 10, 26, 0, 5), dublin))
        // Clocks go forward on 29 Mar 2026.
        assertEquals(1, Logic.daysBetween(at(2026, 3, 28, 23, 50), at(2026, 3, 29, 23, 50), dublin))
    }

    @Test fun statusLevels() {
        val now = at(2026, 11, 24)
        assertEquals(Level.OK, Logic.status(person(at(2026, 11, 20)), now, dublin).level)       // 3 left of 7
        assertEquals(Level.SOON, Logic.status(person(at(2026, 11, 18)), now, dublin).level)     // 1 left
        assertEquals(0, Logic.status(person(at(2026, 11, 17)), now, dublin).left)               // due today
        assertEquals(Level.SOON, Logic.status(person(at(2026, 11, 17)), now, dublin).level)
        val over = Logic.status(person(at(2026, 11, 15)), now, dublin)
        assertEquals(Level.OVER, over.level)
        assertEquals(-2, over.left)
    }

    @Test fun sortedMostOverdueFirst() {
        val now = at(2026, 11, 24)
        val a = person(at(2026, 11, 23)).copy(id = "a", name = "Fine")
        val b = person(at(2026, 11, 10)).copy(id = "b", name = "Late")
        assertEquals(listOf("b", "a"), Logic.sorted(listOf(a, b), now, dublin).map { it.id })
    }

    @Test fun logCatchUpMovesForwardAndKeepsOnePerDay() {
        val p = person(at(2026, 11, 10))
        val once = Logic.logCatchUp(p, at(2026, 11, 24, 9), dublin)
        val twice = Logic.logCatchUp(once, at(2026, 11, 24, 18), dublin)
        assertEquals(at(2026, 11, 24, 18), twice.lastCaughtUp)
        assertEquals(2, twice.history.size)
        // An older call adds history but doesn't move lastCaughtUp back.
        val older = Logic.logCatchUp(twice, at(2026, 11, 15), dublin)
        assertEquals(at(2026, 11, 24, 18), older.lastCaughtUp)
        assertEquals(3, older.history.size)
        assertEquals(older, Logic.logCatchUp(older, at(2026, 11, 15), dublin))
    }

    @Test fun phoneNumbersMatchAcrossFormats() {
        assertTrue(Logic.numbersMatch("+353 87 123 4567", "087 123 4567"))
        assertTrue(Logic.numbersMatch("00353871234567", "(087) 123-4567"))
        assertFalse(Logic.numbersMatch("087 123 4567", "087 123 4568"))
        assertFalse(Logic.numbersMatch("", "087"))
        assertTrue(Logic.numbersMatch("50000", "50000"))
        val people = listOf(person(0, phones = listOf("+353871234567")))
        assertEquals("a", Logic.personForNumber(people, "0871234567")?.id)
        assertNull(Logic.personForNumber(people, "0861234567"))
    }

    @Test fun backupRoundTripAndWebFormats() {
        val p = person(at(2026, 11, 20), phones = listOf("0871234567")).copy(note = "garden")
        val parsed = Backup.parse(Backup.toJson(listOf(p))).people.single()
        assertEquals(p, parsed)
        // The other web version used "lastContact"; ISO strings are accepted too.
        val other = """{"version":1,"people":[{"id":"x","name":"Dave","intervalDays":30,"lastContact":1763805600000,"history":[1763805600000]},
            {"name":"Iso","intervalDays":"14","lastCaughtUp":"2026-11-01T10:00:00Z"},{"name":"","intervalDays":7,"lastCaughtUp":1}]}"""
        val r = Backup.parse(other)
        assertEquals(listOf("Dave", "Iso"), r.people.map { it.name })
        assertEquals(1, r.skipped)
        assertEquals(1763805600000L, r.people[0].lastCaughtUp)
    }

    @Test fun mergeCombinesHistory() {
        val cur = person(at(2026, 11, 10))
        val inc = person(at(2026, 11, 20)).copy(history = listOf(at(2026, 11, 20), at(2026, 11, 1)), name = "Mam!")
        val r = Logic.merge(listOf(cur), listOf(inc, inc.copy(id = "b")))
        assertEquals(1, r.added)
        assertEquals(1, r.updated)
        assertEquals("Mam!", r.people[0].name)
        assertEquals(3, r.people[0].history.size)
    }

    @Test fun callNamesMatchPeople() {
        val people = listOf(
            Person("m", "Mam", 7, lastCaughtUp = 0, aliases = listOf("Mary Dunlevy")),
            Person("d", "Dave", 30, lastCaughtUp = 0),
            Person("a", "Aoife K", 14, lastCaughtUp = 0),
        )
        assertEquals("m", Logic.personForCall(people, "Mam ❤️")?.id)
        assertEquals("m", Logic.personForCall(people, "mary  dunlevy")?.id)
        assertEquals("d", Logic.personForCall(people, "Dave Smith")?.id)
        assertEquals("a", Logic.personForCall(people, "Aoife")?.id)
        assertNull(Logic.personForCall(people, "Davina"))
        assertNull(Logic.personForCall(people, "Group chat"))
        // Two Daves: never guess.
        val twoDaves = listOf(Person("d1", "Dave Smith", 30, lastCaughtUp = 0), Person("d2", "Dave Murphy", 30, lastCaughtUp = 0))
        assertNull(Logic.personForCall(twoDaves, "Dave"))
        assertEquals("d2", Logic.personForCall(twoDaves, "Dave Murphy")?.id)
        // A number in the notification wins.
        val withPhone = listOf(Person("p", "Sam", 7, lastCaughtUp = 0, phones = listOf("0871234567")))
        assertEquals("p", Logic.personForCall(withPhone, "Unknown", "+353871234567")?.id)
    }

    @Test fun recognisesOngoingCallsOnly() {
        // CallStyle (Android 12+): 2 = ongoing, 1 = ringing.
        assertTrue(Logic.isOngoingCall("call", true, 2, true, ""))
        assertFalse(Logic.isOngoingCall("call", true, 1, false, "Incoming voice call"))
        // Older style notifications.
        assertTrue(Logic.isOngoingCall("call", true, 0, true, "00:42"))
        assertTrue(Logic.isOngoingCall(null, true, 0, false, "Ongoing voice call"))
        assertTrue(Logic.isOngoingCall("call", true, 0, false, "Ongoing video call"))
        assertFalse(Logic.isOngoingCall("call", false, 0, false, "Missed voice call"))
        assertFalse(Logic.isOngoingCall("msg", true, 0, false, "Connected to WhatsApp Web"))
        assertFalse(Logic.isOngoingCall("msg", false, 0, false, "Hey, are you on a call?"))
    }

    @Test fun aliasesRoundTrip() {
        val p = Person("x", "Mam", 7, lastCaughtUp = 5, aliases = listOf("Mary Dunlevy"))
        assertEquals(p, Backup.parse(Backup.toJson(listOf(p))).people.single())
    }
}
