package com.ftork.gamma.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissedRescueTest {
    private fun run(r: MissedRescue, dbs: List<Float>, start: Long = 0): Long? {
        dbs.forEachIndexed { i, db -> if (r.level(db, start + i * 100L)) return start + i * 100L }
        return null
    }

    @Test fun firesAfterSoundQuiets() {
        val r = MissedRescue(quietMs = 800)
        r.arm(0)
        // 1초 더 말소리, 그 뒤 조용 → 조용해진 지 0.8초에
        assertEquals(1800L, run(r, List(10) { 10f } + List(20) { 0f }))
        assertFalse(r.armed)
    }

    @Test fun cancelledWhenTextComes() {
        val r = MissedRescue()
        r.arm(0); r.cancel()
        assertEquals(null, run(r, List(30) { 0f }))
    }

    @Test fun firesAtMaxWhileStillLoud() {
        val r = MissedRescue(maxMs = 2000)
        r.arm(0)
        assertEquals(2000L, run(r, List(40) { 10f }))
    }

    @Test fun notArmedNeverFires() {
        assertEquals(null, run(MissedRescue(), List(30) { 0f }))
    }

    @Test fun loudness() {
        assertFalse(Pcm.loud(ShortArray(16000)))
        val voice = ShortArray(16000) { i -> (3000 * Math.sin(i * 0.1)).toInt().toShort() }
        assertTrue(Pcm.loud(voice))
        // 0.2초만 소리 → 부족
        val blip = ShortArray(16000).also { voice.copyInto(it, 0, 0, 3200) }
        assertFalse(Pcm.loud(blip))
    }
}

class QuietestTest {
    @Test fun findsPauseBetweenWords() {
        val pcm = ShortArray(16000) { i -> if (i in 8000 until 11200) 0 else (3000 * Math.sin(i * 0.1)).toInt().toShort() }
        // 500ms~700ms 가 조용 → 가운데 근처
        val at = Pcm.quietestMs(pcm)!!
        assertTrue("at=$at", at in 520..680)
    }
}
