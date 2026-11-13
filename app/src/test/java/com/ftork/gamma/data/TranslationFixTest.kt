package com.ftork.gamma.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11 실제 대화 시험에서 나온 기기 번역 실수 */
class TranslationFixTest {

    @Test fun englishInsteadOfKorean() {
        assertTrue(TranslationFix.wrongScript("Walking more fun", "ko"))
        assertFalse(TranslationFix.wrongScript("걸어다니는 게 더 재밌어요.", "ko"))
        // 한국어 문장 속 영어 단어(taxi)는 괜찮아요
        assertFalse(TranslationFix.wrongScript("taxi 타고 호텔로 가요", "ko"))
        // 베트남어·영어끼리는 글자로 못 가려서 검사 안 해요
        assertFalse(TranslationFix.wrongScript("Walking more fun", "vi"))
        assertFalse(TranslationFix.wrongScript("OK", "ko"))
    }

    @Test fun variantsDifferFromOriginal() {
        val v = TranslationFix.variants("đi bộ xem vui hơn")
        assertTrue(v.isNotEmpty())
        assertFalse(v.contains("đi bộ xem vui hơn"))
        assertTrue(v.contains("đi bộ xem vui hơn."))
        assertTrue(v.contains("Đi bộ xem vui hơn."))
    }

    @Test fun vietnameseQuestions() {
        listOf("Anh đã đến đó chưa", "Em ăn cơm chưa", "Cái này bao nhiêu", "Ngon không", "Anh đi đâu",
            "Nhà vệ sinh ở đâu", "Đi được không", "Vậy à", "Chị Lan là người ở đâu vậy", "Có quán phở không ạ", "Làm như thế nào", "Tại sao anh đến muộn", "Anh tên gì", "Mấy giờ rồi?")
            .forEach { assertTrue(it, TranslationFix.isQuestion(it, "vi")) }
        listOf("Không có gì", "Không sao", "Cảm ơn anh", "Tôi không biết gì cả", "Đi bộ xem vui hơn", "Tôi không đi", "à", "nhỉ", "Mày kể cho hết còn đi về nào", "Mỗi người một cái tay nào")
            .forEach { assertFalse(it, TranslationFix.isQuestion(it, "vi")) }
    }

    @Test fun koreanQuestions() {
        listOf("가 보셨나요", "얼마예요?", "이거 뭐예요", "같이 갈까요", "맛있습니까", "어디 가냐", "괜찮은가요")
            .forEach { assertTrue(it, TranslationFix.isQuestion(it, "ko")) }
        listOf("감사합니다", "우리 언니예요", "학교에 가요", "맛있어요")
            .forEach { assertFalse(it, TranslationFix.isQuestion(it, "ko")) }
    }

    @Test fun keepQuestionMark() {
        // 시험 보고서 예: "가보셨나요?" 가 "방문했습니다" 로 나옴
        assertEquals("방문했습니까?", TranslationFix.keepQuestion("Anh đã đến đó chưa", "방문했습니다.", "vi", "ko"))
        assertEquals("맛있어요?", TranslationFix.keepQuestion("Ngon không", "맛있어요", "vi", "ko"))
        assertEquals("이미 물음표?", TranslationFix.keepQuestion("Ngon không", "이미 물음표?", "vi", "ko"))
        assertEquals("Bao nhiêu tiền?", TranslationFix.keepQuestion("얼마예요", "Bao nhiêu tiền", "ko", "vi"))
        // 질문이 아니면 그대로
        assertEquals("감사합니다.", TranslationFix.keepQuestion("Cảm ơn anh", "감사합니다.", "vi", "ko"))
    }
}
