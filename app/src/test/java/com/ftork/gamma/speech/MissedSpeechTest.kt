package com.ftork.gamma.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class MissedSpeechTest {
    /** 0.1초마다 소리 크기를 넣고, 알림이 뜬 시각들을 돌려줘요 */
    private fun run(m: MissedSpeech, from: Long, to: Long, db: (Long) -> Float, text: (Long) -> Boolean = { false }): List<Long> {
        val out = ArrayList<Long>()
        var t = from
        while (t <= to) { if (text(t)) m.text(t); if (m.level(db(t), t)) out += t; t += 100 }
        return out
    }

    @Test fun loudWithoutTextAlertsOnce() {
        val m = MissedSpeech(); m.reset(0)
        val a = run(m, 0, 6000, { 8f })
        assertEquals(listOf(2500L), a)
    }

    @Test fun quietNeverAlerts() {
        val m = MissedSpeech(); m.reset(0)
        assertEquals(emptyList<Long>(), run(m, 0, 10_000, { 1f }))
    }

    @Test fun textKeepsComingNoAlert() {
        val m = MissedSpeech(); m.reset(0)
        assertEquals(emptyList<Long>(), run(m, 0, 10_000, { 8f }, { it % 700 == 0L }))
    }

    @Test fun alertsAgainAfterTextButNotTooOften() {
        val m = MissedSpeech(); m.reset(0)
        // 0~4초 글자 없음 → 2.5초에 알림, 4초에 글자, 그 뒤 다시 글자 없음 → 8초 간격 지켜서 10.5초에
        val a = run(m, 0, 14_000, { 8f }, { it == 4000L })
        assertEquals(listOf(2500L, 10_500L), a)
    }
}
