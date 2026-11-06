package com.oliver.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.random.Random

class BodyZonesTest {
    @Test fun knownPoints() {
        assertEquals(Zone.CHIN, BodyZones.zoneAt(585f, 700f))
        assertEquals(Zone.EYES, BodyZones.zoneAt(528f, 557f))
        assertEquals(Zone.EYES, BodyZones.zoneAt(658f, 595f))
        assertEquals(Zone.FOREHEAD, BodyZones.zoneAt(580f, 450f))
        assertEquals(Zone.CHEEKS, BodyZones.zoneAt(430f, 600f))
        assertEquals(Zone.EARS, BodyZones.zoneAt(760f, 490f))
        assertEquals(Zone.TAIL, BodyZones.zoneAt(130f, 1078f))
        assertEquals(Zone.CHEST, BodyZones.zoneAt(470f, 840f))
        assertEquals(Zone.BELLY, BodyZones.zoneAt(470f, 1060f))
        assertEquals(Zone.BACK, BodyZones.zoneAt(240f, 850f))
        assertEquals(Zone.PAWS, BodyZones.zoneAt(200f, 1290f))
        assertEquals(Zone.NONE, BodyZones.zoneAt(950f, 300f))   // the wall
        assertEquals(Zone.NONE, BodyZones.zoneAt(540f, 1700f))  // the couch
    }

    @Test fun catWeight() {
        assertEquals(1f, BodyZones.catWeight(470f, 900f), 0.001f)
        assertEquals(0f, BodyZones.catWeight(1000f, 200f), 0.001f)
    }
}

class PettingEngineTest {
    private fun engine() = PettingEngine(Random(1))

    /** Stroke a zone gently for [ms] starting at [t0]; returns all events. */
    private fun stroke(e: PettingEngine, zone: Zone, t0: Long, ms: Long): List<CatEvent> {
        val ev = mutableListOf<CatEvent>()
        ev += e.tick(t0)
        ev += e.onDown(zone, t0)
        var t = t0
        while (t < t0 + ms) {
            t += 16
            ev += e.onMove(zone, t, 8f, 16)      // 500 px/s = gentle
            ev += e.tick(t)
        }
        ev += e.onUp(t)
        return ev
    }

    @Test fun gentleStrokeLeadsToPurring() {
        val e = engine()
        stroke(e, Zone.CHIN, 0, 3000)
        assertTrue("purr=${e.purrLevel}", e.purrLevel > 0.3f)
        assertEquals(Mood.PURRING, e.mood)
    }

    @Test fun tailIsAnnoying() {
        val e = engine()
        e.tick(0)
        val ev = e.onDown(Zone.TAIL, 10)
        assertTrue(CatEvent.TAIL_SWISH in ev)
        assertTrue(CatEvent.ANNOYED_MEOW in ev)
        assertTrue(e.annoyance > 0.25f)
    }

    @Test fun rapidTapsMakeHimAngry_thenHiss() {
        val e = engine()
        e.tick(0)
        val ev = mutableListOf<CatEvent>()
        var t = 100L
        repeat(5) { ev += e.onDown(Zone.BACK, t); ev += e.onUp(t + 60); t += 200 }
        assertTrue(CatEvent.ANNOYED_MEOW in ev || CatEvent.HISS in ev)
        assertTrue(e.annoyance > 0.6f)
        val ev2 = mutableListOf<CatEvent>()
        repeat(5) { ev2 += e.onDown(Zone.BACK, t); ev2 += e.onUp(t + 60); t += 200 }
        assertTrue(CatEvent.HISS in ev2)
    }

    @Test fun singleTapIsAFriendlyMrrp() {
        val e = engine()
        e.tick(0)
        e.onDown(Zone.FOREHEAD, 100)
        assertTrue(CatEvent.MRRP in e.onUp(180))
    }

    @Test fun roughStrokingIsAnnoying() {
        val e = engine()
        e.tick(0)
        e.onDown(Zone.BACK, 0)
        val ev = mutableListOf<CatEvent>()
        var t = 0L
        repeat(40) { t += 16; ev += e.onMove(Zone.BACK, t, 80f, 16); ev += e.tick(t) } // 5000 px/s
        assertTrue(CatEvent.ANNOYED_MEOW in ev || CatEvent.HISS in ev)
    }

    @Test fun tooMuchPettingMakesHimLeaveAndReturn() {
        val e = engine()
        val ev = mutableListOf<CatEvent>()
        e.tick(0); e.onDown(Zone.CHEEKS, 0)
        var t = 0L
        while (CatEvent.LEAVE !in ev && t < 60_000L) {
            t += 16; ev += e.onMove(Zone.CHEEKS, t, 8f, 16); ev += e.tick(t)
        }
        assertTrue("left at $t", t in 30_000L..50_000L)
        assertTrue(CatEvent.WARNING in ev)
        assertTrue(e.isAway)
        assertEquals(Mood.AWAY, e.mood)
        // ignores touches while away
        assertTrue(e.onDown(Zone.CHIN, t + 100).isEmpty())
        var back = false
        while (t < 80_000L && !back) { t += 100; back = CatEvent.RETURN in e.tick(t) }
        assertTrue(back)
        assertFalse(e.isAway)
        assertEquals(0f, e.overstim, 0.001f)
    }

    @Test fun breaksLetHimRecover() {
        val e = engine()
        var t = 0L
        repeat(6) {
            stroke(e, Zone.CHIN, t, 20_000); t += 20_000
            var s = t; while (s < t + 25_000) { s += 100; e.tick(s) }; t = s
        }
        assertFalse(e.isAway)
    }
}
