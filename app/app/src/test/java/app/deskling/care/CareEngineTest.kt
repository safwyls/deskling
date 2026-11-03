package app.deskling.care

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CareEngineTest {
    private val zone = ZoneOffset.UTC
    private fun at(h: Int, m: Int = 0, day: Int = 2) = LocalDateTime.of(2026, 11, day, h, m).toInstant(zone).toEpochMilli()
    private fun engine(start: Long = at(9)) = CareEngine(CareConfig(intArrayOf(45, 20, 50, 60), 8, 20), CareState(), { ZoneId.of("UTC") })
        .also { it.advanceTo(start) }

    @Test fun timersRunOnlyInActiveHours() {
        val e = engine(at(19, 50))
        e.advanceTo(at(21))                       // 10 active minutes, then outside hours
        assertEquals(600L, e.st.elapsed[Need.WATER.ordinal])
        assertEquals(Mood.ASLEEP, e.mood(at(21)))
    }

    @Test fun newActiveDayResetsTimersAndLiftsHappiness() {
        val e = engine(at(19))
        e.st.happy = 20.0
        e.advanceTo(at(19, 59))
        e.advanceTo(at(8, 1, day = 3))
        assertTrue(e.st.elapsed.all { it <= 60 })
        assertTrue(e.st.happy >= 59.0)
    }

    @Test fun moodFollowsTheMostUrgentNeed() {
        val e = engine()
        e.advanceTo(at(9, 21))                    // eyes (20 min) due
        assertEquals(Need.EYES, e.mostUrgent())
        assertEquals(Mood.TIRED, e.mood(at(9, 21)))
        e.done(Need.EYES, at(9, 21))
        assertNull(e.mostUrgent())
    }

    @Test fun scoringMatchesTheDial() {
        val e = engine()
        e.advanceTo(at(9, 21))
        val xp0 = e.st.xp
        e.done(Need.EYES, at(9, 21))              // on time
        assertEquals(10L, e.st.xp - xp0)
        e.done(Need.EYES, at(9, 22))              // straight away again: early
        assertEquals(12L, e.st.xp - xp0)
        assertEquals(2, e.st.today[Need.EYES.ordinal])
    }

    @Test fun doneAtSetsTheTimerToWhenItHappened() {
        val e = engine()
        e.advanceTo(at(9, 50))
        e.doneAt(Need.WATER, 120, at(9, 50))
        assertEquals(120L, e.st.elapsed[Need.WATER.ordinal])
        e.doneAt(Need.STRETCH, 5 * 3600, at(9, 50))    // older than the timer: credited, timer untouched
        assertEquals(3000L, e.st.elapsed[Need.STRETCH.ordinal])
    }

    @Test fun petHasAOneMinuteCooldown() {
        val e = engine()
        assertTrue(e.pet(at(9, 0)))
        assertFalse(e.pet(at(9, 0) + 30_000))
        assertTrue(e.pet(at(9, 1) + 1))
    }

    @Test fun pauseStopsTheTimers() {
        val e = engine()
        e.pause(60, at(9))
        e.advanceTo(at(10))
        assertEquals(0L, e.st.elapsed[0])
        e.advanceTo(at(10, 10))
        assertEquals(600L, e.st.elapsed[0])
    }

    @Test fun streakCountsDaysWithFourBreaks() {
        val e = engine()
        repeat(4) { e.done(Need.WATER, at(10)) }
        e.advanceTo(at(9, 0, day = 3))
        assertEquals(1, e.st.streak)
        e.advanceTo(at(9, 0, day = 4))           // a day with nothing logged breaks it
        assertEquals(0, e.st.streak)
    }

    @Test fun dueAtWalksThroughActiveHours() {
        val e = engine(at(19, 50))
        val t = e.dueAt(Need.EYES, at(19, 50))!!   // 10 active minutes left today, 10 more tomorrow from 8:00
        assertEquals(at(8, 10, day = 3), t)
    }

    @Test fun dialStateReplacesTheLocalOne() {
        val e = engine()
        val needs = JSONArray()
        for ((i, n) in Need.entries.withIndex()) needs.put(JSONObject().put("id", n.key).put("elapsed", 100L * (i + 1)).put("interval", 1800).put("today", i))
        e.applyDialState(JSONObject().put("needs", needs).put("happy", 88).put("xp", 321).put("streak", 5).put("paused", 0), at(9))
        assertEquals(200L, e.st.elapsed[1])
        assertEquals(30, e.config.remindMin[3])
        assertEquals(88, e.happiness())
        assertEquals(321L, e.st.xp)
        assertEquals(Mood.HAPPY, e.mood(at(9)))
    }

    @Test fun stateSurvivesJson() {
        val e = engine()
        e.done(Need.MOVE, at(9))
        val back = CareState.fromJson(JSONObject(e.st.toJson().toString()))
        assertEquals(e.st.xp, back.xp)
        assertEquals(e.st.today.toList(), back.today.toList())
    }
}
