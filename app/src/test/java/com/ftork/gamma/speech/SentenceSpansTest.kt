package com.ftork.gamma.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSpansTest {
    @Test fun pauseSplitEndsShortlyAfterLastPartial() {
        val s = SentenceSpans(tailMs = 300, leadMs = 300)
        s.reset(1000)
        s.partial(2000); s.partial(2500)
        // 3400 에 온 부분 결과가 새 문장을 시작 → 경계는 2500 + 300
        val cut = s.splitPoint(3400)
        assertEquals(2800L, cut)
        assertEquals(700L to 2800L, s.take(cut))
        s.partial(3400)
        // 다음 문장은 앞 문장이 끝난 곳에서 시작해서 조용해진 때까지
        assertEquals(2800L to 5000L, s.take(5000))
    }

    @Test fun firstPartialSplitUsesNow() {
        val s = SentenceSpans()
        s.reset(0)
        assertEquals(1200L, s.splitPoint(1200))
    }

    @Test fun neverGoesBackwards() {
        val s = SentenceSpans(leadMs = 0)
        s.reset(5000)
        assertEquals(5000L to 5000L, s.take(4000))
    }
}
