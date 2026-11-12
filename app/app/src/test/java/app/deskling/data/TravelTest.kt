package app.deskling.data

import app.deskling.care.CareEngine
import app.deskling.care.CareState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the companion is: merging what a dial says with what the phone knows, and the record that moves. */
class TravelTest {
    private val cid = "0123456789abcdef"
    private fun dial(here: Boolean, epoch: Long, where: String = "", id: String = cid) = DialTravel(id, epoch, here, where, Pet.WISP)

    @Test fun aPhoneFromBeforeTravelTakesUpWithTheDial() {
        val m = Travels.merge(Travel("", 0, false, "Deskling-1"), "Deskling-1", dial(true, 1), "Pixel")
        assertEquals(Travel(cid, 1, false, "Deskling-1"), m.travel)
        assertTrue(m.atDial)
    }

    @Test fun atTheDialItsStateIsTheCompanions() {
        val m = Travels.merge(Travel(cid, 3, false, "Deskling-1"), "Deskling-1", dial(true, 4), "Pixel")
        assertTrue(m.atDial)
        assertEquals(4, m.travel.epoch)
    }

    @Test fun onThePhoneTheDialIsJustAPlaceItWas() {
        val mine = Travel(cid, 5, true)
        val m = Travels.merge(mine, "Deskling-1", dial(false, 5, "Pixel"), "Pixel")
        assertFalse(m.atDial)
        assertEquals(mine, m.travel)
        assertNull(m.tellWhere)
    }

    @Test fun aDialThatMissedLaterMovesHearsTheNews() {
        // dial 1 -> phone (5) -> dial 2 (6) -> phone (7): dial 1 still thinks the phone has it from move 5
        val m = Travels.merge(Travel(cid, 7, false, "Deskling-2"), "Deskling-1", dial(false, 5, "Pixel"), "Pixel")
        assertEquals("Deskling-2", m.tellWhere)
        assertFalse(m.atDial)
        val m2 = Travels.merge(Travel(cid, 7, true), "Deskling-1", dial(false, 5, "Pixel"), "Pixel")
        assertEquals("Pixel", m2.tellWhere)
    }

    @Test fun aPhoneThatMissedLaterMovesLearnsFromTheDial() {
        val m = Travels.merge(Travel(cid, 4, false, "Deskling-1"), "Deskling-1", dial(false, 6, "Pixel 9"), "Pixel")
        assertEquals(Travel(cid, 6, false, "Pixel 9"), m.travel)
    }

    @Test fun aDialWithItsOwnCompanionChangesNothing() {
        val mine = Travel(cid, 2, true)
        val m = Travels.merge(mine, "Deskling-1", dial(true, 1, id = "ffffffffffffffff"), "Pixel")
        assertEquals(mine, m.travel)
        assertFalse(m.atDial)
    }

    @Test fun theRecordRoundTripsThroughALink() {
        val e = CareEngine()
        val now = 1_800_000_000_000L
        val st = CareState().apply {
            elapsed[0] = 1200; today[1] = 3; xp = 450; streak = 2; happy = 81.5; standing = true; postureElapsed = 300; standsToday = 1
            pauseUntil = now + 90_000
        }
        val r = CompanionRecord.of(cid, Pet.SPRIG, 9, st, now)
        val back = CompanionRecord.fromLink(r.link())
        assertNotNull(back)
        assertEquals(cid, back!!.cid); assertEquals(Pet.SPRIG, back.pet); assertEquals(9, back.epoch)
        val s2 = back.careState(e, now + 1000)
        assertEquals(1200, s2.elapsed[0]); assertEquals(3, s2.today[1]); assertEquals(450, s2.xp); assertEquals(2, s2.streak)
        assertEquals(81.5, s2.happy, 0.001); assertTrue(s2.standing); assertEquals(300, s2.postureElapsed); assertEquals(1, s2.standsToday)
        assertEquals(now + 1000 + 90_000, s2.pauseUntil)
        // the app's own link, and the code on its own
        assertNotNull(CompanionRecord.fromLink("deskling://companion?r=" + CompanionRecord.encode(r.toJson())))
        assertNotNull(CompanionRecord.fromLink(CompanionRecord.encode(r.toJson())))
    }

    @Test fun aDialsRecordReadsTheSame() {
        // as firmware api.cpp put_companion writes it
        val o = org.json.JSONObject("""{"cid":"$cid","pet":"crumb","epoch":4,"care":{"elapsed":[10,20,30,40],"today":[1,0,2,0],
            "happy":66.5,"xp":120,"day":20261111,"streak":1,"bedtime":false,"standing":false,"postSec":60,"stands":0,"pause":0}}""")
        val r = CompanionRecord.fromJson(o)!!
        assertEquals(Pet.CRUMB, r.pet)
        assertEquals(30, r.careState(CareEngine(), 0).elapsed[2])
    }

    @Test fun nonsenseIsNotACompanion() {
        assertNull(CompanionRecord.fromLink("https://example.com/#companion=bm9wZQ"))
        assertNull(CompanionRecord.fromLink("hello"))
        assertNull(CompanionRecord.fromJson(org.json.JSONObject().put("cid", "xyz").put("pet", "wisp").put("epoch", 1)))
    }
}
