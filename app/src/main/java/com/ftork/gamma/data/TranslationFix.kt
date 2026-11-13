package com.ftork.gamma.data

/**
 * 기기 번역(ML Kit)의 흔한 실수를 고쳐요. (2026-11 실제 대화 시험에서 찾은 문제)
 *  1) 목표 언어가 아닌 글자로 나옴: "đi bộ xem vui hơn" → "Walking more fun" (한국어여야 하는데 영어)
 *  2) 질문이 평서문으로 바뀜: "Anh đi chưa?" → "가셨습니다." (물음표가 사라져 대화에서 오해가 생김)
 * 안드로이드에 기대지 않는 순수 코드라 PC 단위 테스트로 확인해요.
 */
object TranslationFix {

    /** 결과가 목표 언어의 글자로 되어 있는지. 라틴 글자 언어(vi, en)끼리는 구분이 어려워 검사하지 않아요. */
    fun wrongScript(out: String, target: String): Boolean {
        val letters = out.filter { it.isLetter() }
        if (letters.length < 4) return false
        val own = when (target) {
            "ko" -> letters.count { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' }
            "zh" -> letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            else -> return false
        }
        // 고유명사·약어(taxi, OK)가 섞이는 건 괜찮아요. 목표 글자가 아예 없거나 아주 적을 때만.
        return own * 4 < letters.length
    }

    /** 다시 번역할 때 쓸 조금 바꾼 문장들 (ML Kit 은 끝 문장부호·첫 글자 대소문자에 따라 결과가 달라져요) */
    fun variants(text: String): List<String> {
        val t = text.trim()
        val out = LinkedHashSet<String>()
        val core = t.trimEnd('.', '!', '?', '…', ',')
        out += "$core."
        out += core.replaceFirstChar { it.uppercaseChar() } + "."
        out += core.lowercase()
        out.remove(t)
        return out.toList()
    }

    // ---- 질문 ----

    private val viQuestionEnd = listOf(
        "không", "chưa", "à", "hả", "hở", "hông", "nhỉ", "chứ", "sao", "gì", "ai",
        // "nào" 혼자는 "đi nào"(가자)처럼 권유라서, 묻는 말과 붙은 것만
        "thế nào", "như nào", "cái nào", "chỗ nào", "lúc nào", "người nào", "bên nào", "ở đâu", "đi đâu", "đâu rồi",
        "bao nhiêu", "mấy", "mấy giờ", "phải không", "được không", "có không", "đúng không",
    )
    /** 질문말처럼 보여도 질문이 아닌 굳은 말 */
    private val viNotQuestion = listOf(
        "không có gì", "không sao", "gì cả", "không đâu", "đâu có", "chưa đâu", "sao cũng được",
    )
    private val viQuestionStart = listOf("ai ", "bao giờ ", "khi nào ", "tại sao ", "vì sao ", "sao ", "có phải ", "làm sao ")

    private fun words(s: String) = s.lowercase().trim().trimEnd('.', '!', '?', '…', ',', ' ', '~')

    /** 원문이 질문인지 (음성 인식 결과는 물음표가 없는 경우가 많아서 질문말로도 봐요) */
    fun isQuestion(text: String, lang: String): Boolean {
        val raw = text.trim()
        if (raw.endsWith("?") || raw.endsWith("？")) return true
        val w = words(raw)
        if (w.isEmpty()) return false
        return when (lang) {
            "vi" -> {
                // 끝의 높임·느낌 말은 떼고 봐요: "ở đâu vậy", "có ... không ạ"
                var w = w
                while (true) {
                    val cut = listOf(" ạ", " vậy", " thế", " á").firstOrNull { w.endsWith(it) } ?: break
                    w = w.dropLast(cut.length).trimEnd()
                }
                // "à", "nhỉ" 같은 한 마디는 맞장구·조각이라 질문으로 보지 않아요
                if (!w.contains(' ')) false
                else if (viNotQuestion.any { w.endsWith(it) || w == it }) false
                else viQuestionEnd.any { w == it || w.endsWith(" $it") } || viQuestionStart.any { w.startsWith(it) }
            }
            "ko" -> {
                val last = w.substringAfterLast(' ')
                w.split(" ").takeLast(2).any { x -> listOf("얼마", "어디", "언제", "누구", "무엇", "뭐", "왜", "몇").any { x.startsWith(it) } } ||
                    last.endsWith("까") || last.endsWith("까요") || last.endsWith("나요") || last.endsWith("냐") ||
                    last.endsWith("인가요") || last.endsWith("는가요") || last.endsWith("은가요") || last.endsWith("던가요") ||
                    w.endsWith("어때") || w.endsWith("어때요") || w.endsWith("뭐야") || w.endsWith("뭐예요") || w.endsWith("뭐에요")
            }
            "en" -> w.split(' ').firstOrNull() in setOf("what", "where", "when", "who", "why", "how", "is", "are", "do", "does", "did", "can", "could", "will", "would")
            else -> false
        }
    }

    /** 원문이 질문인데 번역이 평서문이면 질문으로 바꿔요. (끝 "." → "?", 한국어 "~니다" → "~니까?") */
    fun keepQuestion(source: String, out: String, from: String, to: String): String {
        if (!isQuestion(source, from)) return out
        val t = out.trimEnd()
        if (t.isEmpty() || t.endsWith("?") || t.endsWith("？")) return out
        val core = t.trimEnd('.', '!', '。', '…')
        return when (to) {
            "ko" -> when {
                core.endsWith("습니다") -> core.dropLast(3) + "습니까?"
                core.endsWith("니다") -> core.dropLast(2) + "니까?"
                else -> "$core?"
            }
            "zh" -> "$core？"
            else -> "$core?"
        }
    }
}
