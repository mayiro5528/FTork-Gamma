package com.ftork.gamma.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실제 기기에서 받은 인식기 중간 글자(시각 포함)를 그대로 다시 넣어서 문장 나누기를 검증해요.
 *  - phone_run1: 갤럭시 폰(Android 17) · 한국어 → 베트남어 대화 → 한국어 2문장
 *  - run4: 에뮬레이터(Android 17) · 같은 순서
 */
class SentenceSegmenterTest {

    data class Commit(val atMs: Long, val text: String, val lang: String?, val lastChangeMs: Long)

    /** 앱과 똑같이: 중간 글자마다 onPartial, 글자가 멈춰 있으면 silenceDelay 뒤 onSilence */
    private fun replay(name: String): List<Commit> {
        val lines = javaClass.classLoader!!.getResource("${name}_partials.tsv")!!.readText(Charsets.UTF_8)
            .lines().filter { it.isNotEmpty() }
            .map { val i = it.indexOf('\t'); it.substring(0, i).toLong() to it.substring(i + 1) }
        val seg = SentenceSegmenter()
        val out = ArrayList<Commit>()
        var deadline = Long.MAX_VALUE
        var lastChange = 0L
        fun lang(s: String) = if (s.any { it in '가'..'힣' }) "ko" else "vi"
        for ((t, p) in lines) {
            while (deadline <= t) {
                val more = seg.silenceWait(deadline)
                if (more > 0) { deadline += more; continue }
                seg.onSilence()?.let { out += Commit(deadline, it.text, it.lang, lastChange) }
                deadline = Long.MAX_VALUE
            }
            val r = seg.onPartial(p, t, lang(p.trim().split(" ").last()))
            r.commits.forEach { out += Commit(t, it.text, it.lang, lastChange) }
            if (r.changed) { lastChange = t; deadline = if (seg.shown.isBlank()) Long.MAX_VALUE else t + seg.silenceDelay() }
        }
        seg.onSilence()?.let { out += Commit(minOf(deadline, lines.last().first + 1000), it.text, it.lang, lastChange) }
        println("== $name: ${out.size} 문장")
        out.forEach { println("  [${it.lang}] +${it.atMs - it.lastChangeMs}ms  ${it.text}") }
        return out
    }

    private fun norm(s: String) = SentenceSegmenter.words(s).joinToString(" ")

    private fun checkCommon(c: List<Commit>) {
        val texts = c.map { norm(it.text) }
        // 같은 문장이 두 번 나오지 않아요
        assertEquals("중복 확정", texts.size, texts.toSet().size)
        // 한국어 문장은 각각 하나의 문장으로
        assertTrue(texts.contains("기차역은 어디에 있나요"))
        assertTrue(texts.contains("걸어서 얼마나 걸리나요"))
        assertTrue(texts.any { it.matches(Regex("이 근처[에의] 맛있는 식당이 있나요")) })
        // 베트남어 대화는 짧은 문장으로 나뉘어요 (긴 덩어리는 기계 번역이 엉망이 돼요)
        assertTrue(texts.any { it.matches(Regex("chị lan làm? người ở đâu.*")) }) // 에뮬레이터는 "làm" 으로 받아써요
        // (에뮬레이터는 인식기가 "hồ gươm" 에서 1초 넘게 멈칫해서 "anh đã đi thăm hồ" 까지만 한 문장)
        assertTrue(texts.any { it.startsWith("anh đã đi thăm hồ") })
        assertTrue(texts.any { it.startsWith("bây giờ tôi muốn uống một ly cà phê") })
        assertTrue(texts.contains("vậy chúng ta cùng đi uống cà phê nhé"))
        assertTrue(texts.contains("cảm ơn chị nhiều chúng ta đi thôi"))
        val longest = texts.maxOf { it.split(" ").size }
        assertTrue("가장 긴 문장 $longest 단어", longest <= 16)
        // 한 문장 안에 한국어와 베트남어가 섞이지 않아요
        c.forEach { s ->
            val hangul = s.text.any { it in '가'..'힣' }
            val latin = s.text.any { it in 'a'..'z' }
            assertFalse("언어 섞임: ${s.text}", hangul && latin)
        }
    }

    @Test fun phone() = checkCommon(replay("phone_run1"))

    @Test fun emulator() = checkCommon(replay("run4"))

    /** 폰(v3.4): 인식기가 0.1초 사이에 "thôi là" ↔ "tôi là" 로 고쳐 보냄 → 고칠 때마다 조각 문장이 확정되면 안 돼요 */
    @Test fun phoneWordFlips() {
        val texts = replay("phone34_a").map { norm(it.text) }
        assertEquals("중복 확정", texts.size, texts.toSet().size)
        assertFalse(texts.toString(), texts.any { it == "thôi là" || it == "tôi là" || it == "thôi là người" })
        assertTrue(texts.any { it.matches(Regex("(thôi|tôi) là người hà nội")) })
        assertTrue(texts.contains("anh đã đi thăm hồ gươm chưa"))
        assertTrue(texts.contains("이 근처에 맛있는 식당이 있나요"))
    }

    /** 폰(v3.5) 대화 전체: 19문장, 중복·조각 없음 */
    @Test fun phoneDialogue35() = checkCommon(replay("phone35_b"))

    /** 폰(v3.5) 길 묻기: 확정 직후 인식기가 같은 문장을 새 구간에서 다시 받아씀 → 메아리를 거르고 새 말만 */
    @Test fun phoneDirectionsEcho() {
        val texts = replay("phone35_d").map { norm(it.text) }
        assertEquals("중복 확정", texts.size, texts.toSet().size)
        assertTrue(texts.toString(), texts.any { it.startsWith("sau đó tôi đi tiếp") })
        assertFalse(texts.toString(), texts.any { it.startsWith("sẽ phải ở ngã tư sau") })
        val longest = texts.maxOf { it.split(" ").size }
        assertTrue("가장 긴 문장 $longest 단어", longest <= 17) // 한 문장짜리 긴 질문 (17단어)
    }

    /** 에뮬레이터가 느려서 인식기가 0.7~0.8초씩 밀렸다가 단어를 한꺼번에 보낸 기록 → 밀림을 쉼으로 착각하면 안 돼요 */
    @Test fun emulatorStalls() {
        val texts = replay("emu_run5").map { norm(it.text) }
        assertEquals("중복 확정", texts.size, texts.toSet().size)
        assertTrue(texts.any { it.matches(Regex("이 근처[에의] 맛있는 식당이 있나요")) })
        assertTrue(texts.contains("걸어서 얼마나 걸리나요"))
        assertTrue(texts.any { it.matches(Regex("chị lan làm? người ở đâu.*")) })
        assertTrue(texts.any { it.startsWith("vậy chúng ta cùng đi uống cà phê nhé") })
    }

    @Test fun endWordsShortenWait() {
        assertTrue(SentenceSegmenter.endsSentence("기차역은 어디에 있나요"))
        assertTrue(SentenceSegmenter.endsSentence("감사합니다"))
        assertTrue(SentenceSegmenter.endsSentence("có xa không"))
        assertTrue(SentenceSegmenter.endsSentence("chúng ta đi uống cà phê nhé"))
        assertFalse(SentenceSegmenter.endsSentence("기차역은 어디"))
        assertFalse(SentenceSegmenter.endsSentence("tôi muốn uống một ly"))
        val s = SentenceSegmenter()
        s.onPartial("걸어서 얼마나 걸리나요", 0, "ko")
        assertEquals(550, s.silenceDelay())
        s.onPartial("걸어서 얼마나 걸리나요 그리고", 100, "ko")
        assertEquals(1000, s.silenceDelay())
    }

    @Test fun pauseSplitKeepsCommittedPrefixOut() {
        val s = SentenceSegmenter()
        s.onPartial("xin chào", 0, "vi")
        s.onPartial("xin chào tôi tên là lan", 300, "vi")
        // 0.9초 쉼: 인식기는 쉬는 동안에도 같은 글자를 계속 보내요
        for (t in 450L..1050L step 150) s.onPartial("xin chào tôi tên là lan", t, "vi")
        val r = s.onPartial("xin chào tôi tên là lan bạn khỏe không", 1200, "vi")
        assertEquals(listOf("xin chào tôi tên là lan"), r.commits.map { it.text })
        assertEquals("bạn khỏe không", s.shown)
        // 인식기가 0.9초 밀렸다가 단어를 한꺼번에 보냄 → 쉼이 아니라서 나누지 않아요
        val s2 = SentenceSegmenter()
        s2.onPartial("xin chào", 0, "vi")
        s2.onPartial("xin chào tôi tên là lan", 300, "vi")
        assertTrue(s2.onPartial("xin chào tôi tên là lan bạn khỏe không", 1200, "vi").commits.isEmpty())
        // 인식기가 구간을 마치며 전체를 다시 보냄 → 남은 부분만 바로 확정, 앞부분은 다시 나오지 않아요
        val r2 = s.onPartial(" xin chào tôi tên là lan bạn khỏe không", 1500, "vi")
        assertEquals(listOf("bạn khỏe không"), r2.commits.map { it.text })
        assertEquals("", s.shown)
    }

    @Test fun shortGapDoesNotSplit() {
        val s = SentenceSegmenter()
        s.onPartial("걸어서", 0, "ko")
        s.onPartial("걸어서 얼마나", 480, "ko")
        val r = s.onPartial("걸어서 얼마나 걸리나요", 950, "ko")
        assertTrue(r.commits.isEmpty())
        assertEquals("걸어서 얼마나 걸리나요", s.shown)
    }
}
