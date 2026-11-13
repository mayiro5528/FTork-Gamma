package com.ftork.gamma.speech

/**
 * 양방향 연속 듣기의 문장 나누기 (안드로이드와 무관한 순수 계산 → 단위 테스트로 검증해요).
 *
 * 기기 안 인식기(두 언어 자동 전환)는 말이 끝나도 "확정"을 보내지 않고, 한 구간 안에서 글자를 계속 이어 붙여요.
 * 그래서 중간 글자(partial)의 변화만 보고 문장 경계를 찾아요.
 *  ① 쉼 나누기: 새 단어가 [pauseSplitMs] 넘게 쉰 뒤에 붙으면 → 앞부분을 한 문장으로 확정
 *     (실측: 같은 문장 안의 단어 간격 ≤ 0.5초, 문장 사이 0.7~1초)
 *  ② 새 구간: 글자가 완전히 새로 시작되면(인식기가 새 구간을 시작) → 앞 문장 확정
 *  ③ 다시 보내기: 인식기가 구간을 마치면 앞에 띄어쓰기를 붙여 같은 글자를 한 번 더 보내요 → 바로 확정
 *  ④ 멈춤: 글자가 [silenceDelay] 동안 그대로면 확정 (부르는 쪽이 타이머로 [onSilence] 호출)
 *     문장 끝말(-요, -니다, không, nhé …)로 끝나면 짧게, 아니면 길게 기다려요.
 *
 * 문장을 짧게 끊을수록 기기 번역(ML Kit)이 훨씬 정확해지고, 대화 기록·자동 읽기도 빨라져요.
 */
class SentenceSegmenter(
    private val pauseSplitMs: Long = 650,
    private val silenceMs: Long = 1000,
    private val endSilenceMs: Long = 550,
    private val longWords: Int = 8,
    private val longPauseMs: Long = 330,
    /** 중간 글자 사이 간격이 이보다 길면 인식기가 밀린 것으로 보고 [stallCountMs] 만 쉼으로 세요 */
    private val beatMaxMs: Long = 550,
    private val stallCountMs: Long = 250,
    /** 이만큼 중간 글자가 아예 안 오면 인식기가 밀린 것 (말이 멈춘 게 아님) */
    private val stallMs: Long = 400,
    /** 인식기가 밀려도 이 시간이 지나면 확정 */
    private val maxSilenceMs: Long = 2500,
    /** 확정 직후 인식기가 같은 문장을 다시 받아쓰는 "메아리"를 거르는 시간 */
    private val echoMs: Long = 1500,
) {
    // 쉼 재기: 말이 멈춰 있으면 인식기는 같은 글자를 0.1~0.2초마다 계속 보내요.
    // 느린 폰에서는 인식기가 잠깐 밀렸다가 여러 단어를 한꺼번에 보내기도 해요(그 사이엔 아무것도 안 와요).
    // 그래서 "같은 글자가 계속 오는 시간"만 쉼으로 세요. 간격이 beatMaxMs 보다 길면(밀림) 조금만 세서, 밀림을 쉼으로 착각하지 않아요.
    // (실측: 폰은 쉬는 동안 0.1~0.5초 간격, 느린 에뮬레이터는 밀릴 때 0.7~0.9초 동안 아무것도 안 옴)
    data class Sentence(val text: String, val lang: String?)

    /** 아직 확정하지 않은 지금 문장 */
    var shown = ""; private set
    /** 지금 문장의 언어 (ko, vi …) */
    var lang: String? = null; private set

    /** 인식기의 지금 구간에서 이미 확정한 앞부분 (원문 그대로) */
    private var committed = ""
    /** 인식기가 마지막으로 보낸 글자 전체 */
    private var raw = ""
    /** 지금 문장 글자가 마지막으로 바뀐 시각 */
    private var lastChange = 0L
    /** 마지막으로 중간 글자가 온 시각 */
    private var lastBeat = 0L
    /** 글자가 마지막으로 바뀐 뒤 들은 쉼 (같은 글자가 계속 온 시간) */
    private var heard = 0L

    fun reset() {
        shown = ""; lang = null; committed = ""; raw = ""; lastChange = 0L; lastBeat = 0L; heard = 0L
        lastSentence = ""; lastSentenceAt = 0L
    }

    /**
     * 중간 글자가 들어왔어요. 지금 확정할 문장들을 돌려줘요 (보통 0개, 경계를 찾으면 1개).
     * 돌려준 뒤의 [shown] 이 화면에 보여 줄 지금 문장이에요.
     * @return changed 가 false 면 화면에 보여 줄 글자가 그대로예요 (타이머를 다시 걸 필요 없음).
     */
    fun onPartial(p: String, now: Long, detected: String?): Result {
        val out = ArrayList<Sentence>(1)
        val d = if (lastBeat == 0L) 0 else now - lastBeat
        val gap = heard + if (d <= beatMaxMs) d else stallCountMs
        lastBeat = now
        heard = gap // 글자가 바뀌면 아래에서 0 으로 (lastChange 와 함께)
        val prevRaw = raw
        val hadCommitted = committed.isNotEmpty()
        val text = strip(p)
        // 인식기가 새 구간을 시작했는지 (같은 구간 안에서 단어를 고치는 건 새 문장이 아니에요: thôi là ↔ tôi là)
        val newSegment = if (hadCommitted) committed.isEmpty() else !sameSegment(prevRaw, p)
        raw = p
        if (text.isEmpty()) return Result(out, false)

        // ③ 인식기가 끝낸 구간을 다시 보냄 → 기다리지 않고 바로 확정
        if (p.startsWith(" ") && shown.isNotEmpty() && words(text) == words(shown)) {
            commitShown(out)
            committed = p.trim()
            return Result(out, true)
        }

        val tw = words(text); val sw = words(shown)
        // ① 쉼 뒤에 새 단어가 붙음 → 앞부분 확정, 새 단어는 다음 문장
        //    이미 긴 문장(8단어 이상)이면 짧은 쉼에서도 끊어요 (긴 문장은 기계 번역이 크게 틀려요)
        val pause = (sw.size >= 2 && gap >= pauseSplitMs) || (sw.size >= longWords && gap >= longPauseMs)
        if (pause && tw.size > sw.size && prefixMatches(tw, sw)) {
            commitShown(out)
            val tokens = tokens(p)
            val keep = tokens.size - (tw.size - sw.size)
            committed = tokens.take(keep).joinToString(" ")
            startSentence(tokens.drop(keep).joinToString(" "), now, detected)
            return Result(out, true)
        }

        // ② 글자가 완전히 새로 시작 → 앞 문장 확정, 새 글자는 다음 문장
        //    - 인식기가 새 구간을 시작함 (한 단어짜리 헛들음 "tham"→"감사합니다" 는 확정 안 해요)
        //    - 같은 구간인데 3단어 이상 문장을 통째로 다른 말로 바꿈 ("anh đã đi thăm hồ"→"chưa": 들은 말을 잃지 않게)
        //      단, 첫 단어만 고쳐 듣는 건 같은 문장이에요 (thôi là người ↔ tôi là người)
        if (shown.isNotEmpty() && !sameStart(shown, text)) {
            val tw0 = tw.firstOrNull() ?: ""
            val split = if (newSegment) sw.size >= 2 || shown.length >= 6
                        else sw.size >= 3 && !similar(sw[0], tw0)
            if (split) {
                commitShown(out)
                val tokens = tokens(p)
                committed = if (newSegment) "" else tokens.take(tokens.size - tokens(text).size).joinToString(" ")
                startSentence(text, now, detected)
                return Result(out, true)
            }
        }

        if (text == shown) return Result(out, false)
        if (shown.isEmpty()) lang = null
        shown = text
        lang = detected ?: lang
        lastChange = now; heard = 0
        return Result(out, true)
    }

    class Result(val commits: List<Sentence>, val changed: Boolean)

    /** 글자가 그대로인 채로 기다릴 시간. 문장 끝말로 끝나면 짧게. */
    fun silenceDelay(): Long = if (endsSentence(shown)) endSilenceMs else silenceMs

    /**
     * 멈춤 타이머가 울렸을 때: 0 이면 지금 확정, 아니면 그만큼 더 기다려요.
     * 중간 글자가 한동안 아예 안 왔으면 인식기가 밀린 것이라 조금 더 기다려요 (최대 maxSilenceMs).
     */
    fun silenceWait(now: Long): Long =
        if (now - lastBeat > stallMs && now - lastChange < maxSilenceMs) 300 else 0

    /** ④ 말이 멈춤 → 지금 문장 확정 (인식기는 같은 구간에 이어 붙일 수 있어서 지금까지를 "확정한 앞부분"으로 기억) */
    fun onSilence(): Sentence? {
        val out = ArrayList<Sentence>(1)
        commitShown(out)
        committed = raw.trim()
        return out.firstOrNull()
    }

    /** 인식기가 스스로 최종 결과를 줬어요 (구간 끝). 아직 확정하지 않은 부분만 돌려줘요. */
    fun onFinal(text: String): Sentence? {
        val rest = strip(text).ifBlank { shown }
        val l = lang
        reset()
        return if (rest.isBlank()) null else Sentence(rest, l)
    }

    private fun startSentence(text: String, now: Long, detected: String?) {
        shown = text; lang = detected; lastChange = now; heard = 0
    }

    private fun commitShown(out: MutableList<Sentence>) {
        if (shown.isNotBlank()) {
            out += Sentence(shown, lang)
            lastSentence = shown; lastSentenceAt = lastBeat
        }
        shown = ""; lang = null
    }

    /** 마지막으로 확정한 문장과 그때 시각 (메아리 거르기용) */
    private var lastSentence = ""
    private var lastSentenceAt = 0L

    /** 이미 확정한 앞부분을 떼어 내요. (인식기가 앞 단어를 조금 고쳐 보내도 같은 앞부분으로 봐요) */
    private fun strip(p: String): String {
        val c = committed
        if (c.isNotEmpty()) {
            val pw = words(p); val cw = words(c)
            val end = committedEnd(pw, cw)
            if (end != null) return tokens(p).drop(end).joinToString(" ")
            committed = "" // 인식기가 새 구간을 시작했어요
            // 메아리: 인식기가 확정 직후 새 구간에서 방금 문장을 처음부터 다시 받아쓰기도 해요 (실측 0.5초 뒤)
            //  → 1.5초 안에 새 구간이 방금 문장으로 시작하면 그 부분은 이미 확정한 것으로 봐요
            if (lastSentence.isNotEmpty() && lastBeat - lastSentenceAt <= echoMs) {
                val lw = words(lastSentence)
                val e = committedEnd(pw, lw)
                if (e != null && e > 0) {
                    committed = lastSentence // 이 구간에서는 방금 문장 전체가 이미 확정한 앞부분
                    return tokens(p).drop(e).joinToString(" ")
                }
            }
        }
        return p.trim()
    }

    /**
     * p 의 단어 중 이미 확정한 부분이 어디서 끝나는지. 새 구간이면 null.
     * 인식기는 같은 구간을 다시 보내면서 앞 단어를 고치거나 빼고 더하기도 해요 (단어 수가 바뀜).
     * 그래서 위치가 아니라 "확정한 부분의 마지막 3단어"를 기대 위치 근처에서 찾아요.
     */
    private fun committedEnd(pw: List<String>, cw: List<String>): Int? {
        if (cw.isEmpty()) return 0
        val common = commonPrefix(pw, cw)
        if (common >= cw.size) return cw.size
        val tail = cw.takeLast(minOf(3, cw.size))
        var best: Int? = null
        for (e in (cw.size - 4)..(cw.size + 4)) {
            if (e < tail.size || e > pw.size) continue
            var diff = 0
            for (i in tail.indices) {
                val x = pw[e - tail.size + i]; val y = tail[i]
                if (!(x == y || (i == tail.lastIndex && (x.startsWith(y) || y.startsWith(x))))) diff++
            }
            if (diff <= (if (tail.size >= 3) 1 else 0) && (best == null || kotlin.math.abs(e - cw.size) < kotlin.math.abs(best - cw.size))) best = e
        }
        if (best != null) return best
        // 인식기가 확정한 부분의 끝을 거둬들이고 다른 말로 바꿨어요 (실측: "…du lịch anh đã đi thăm hồ" → "…du lịch chưa rồi")
        // 같은 구간이에요(앞부분이 같음) → 갈라진 곳 뒤만 새 말. 확정한 부분만 다시 보냈으면 남는 게 없어요.
        if (common >= 3 || common >= cw.size / 2 || common >= pw.size) return common
        return null
    }

    /** 앞에서부터 같은 단어 수. 한 단어만 바뀐 경우(근처에→근처의)와 아직 말하는 중인 마지막 단어는 같다고 봐요. */
    private fun commonPrefix(pw: List<String>, cw: List<String>): Int {
        val n = minOf(pw.size, cw.size)
        var i = 0
        while (i < n) {
            val x = pw[i]; val y = cw[i]
            val same = x == y ||
                (i == n - 1 && (x.startsWith(y) || y.startsWith(x))) ||
                (i + 1 < n && pw[i + 1] == cw[i + 1]) // 한 단어만 바뀜
            if (!same) break
            i++
        }
        return i
    }

    companion object {
        /** 띄어쓰기로 나눈 원문 조각 */
        fun tokens(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        /** 비교용 단어 (소문자, 문장부호 없이). [tokens] 와 개수·순서가 같아요. */
        fun words(s: String): List<String> =
            tokens(s).map { it.lowercase().replace(Regex("[.,!?;:。、，？！¿¡\"'…]"), "") }

        /**
         * a 가 prefix 로 시작하는지. 인식기는 앞 단어를 가끔 고쳐요(근처에→근처의, 기→기차역은) →
         * 4단어마다 1개까지 달라도 같다고 봐요. 마지막 단어는 아직 말하는 중일 수 있어서 앞부분만 같아도 돼요.
         */
        fun prefixMatches(a: List<String>, prefix: List<String>): Boolean {
            if (prefix.isEmpty()) return true
            if (a.size < prefix.size) return false
            var diff = 0
            for (i in prefix.indices) {
                val x = a[i]; val y = prefix[i]
                val same = x == y || (i == prefix.lastIndex && (x.startsWith(y) || y.startsWith(x)))
                if (!same) diff++
            }
            return diff <= prefix.size / 4
        }

        /**
         * 인식기가 같은 구간을 계속 보내는 중인지 (앞 글자를 고친 정도면 같은 구간).
         * 단어 절반 이상이 같은 자리에 있거나, 짧은 글자(2단어 이하)의 끝 단어만 바뀐 경우.
         */
        fun sameSegment(prev: String, p: String): Boolean {
            val a = words(prev); val b = words(p)
            if (a.isEmpty() || b.isEmpty()) return a.isEmpty()
            val n = minOf(a.size, b.size)
            var same = 0
            for (i in 0 until n) if (a[i] == b[i] || (i == n - 1 && (a[i].startsWith(b[i]) || b[i].startsWith(a[i])))) same++
            if (same * 2 >= n && same > 0) return true
            // 2단어 이하: 첫 단어가 같은 자리에서 고쳐지는 중 (thôi ↔ tôi 처럼 한두 글자 차이)
            return n <= 2 && kotlin.math.abs(a.size - b.size) <= 1 && similar(a[0], b[0])
        }

        /** 두 단어가 한두 글자만 다른지 (인식기가 발음을 고쳐 듣는 경우) */
        fun similar(x: String, y: String): Boolean {
            if (x == y) return true
            if (kotlin.math.abs(x.length - y.length) > 1) return false
            val m = minOf(x.length, y.length)
            var diff = kotlin.math.abs(x.length - y.length)
            // 앞에서 맞추고 뒤에서 맞춰서 다른 글자 수를 대략 세요
            var i = 0; while (i < m && x[i] == y[i]) i++
            var j = 0; while (j < m - i && x[x.length - 1 - j] == y[y.length - 1 - j]) j++
            diff = maxOf(diff, maxOf(x.length, y.length) - i - j)
            return diff <= 1
        }

        /** 두 글자의 앞 3글자가 같으면 같은 문장 (인식기가 끝 단어를 고치는 건 같은 문장) */
        fun sameStart(a: String, b: String): Boolean {
            val n = minOf(3, a.length, b.length)
            return a.regionMatches(0, b, 0, n, ignoreCase = true)
        }

        private val koEnd = listOf("니다", "습니까", "입니까", "합니까", "됩니까", "죠", "까요")
        private val viEnd = setOf("không", "nhé", "nhá", "nha", "ạ", "à", "ư", "nhỉ", "vậy", "chưa", "hả", "chứ", "thôi", "đâu")
        private val zhEnd = listOf("吗", "呢", "吧", "谢谢")

        /** 문장 끝말로 끝나는지 (짧게 기다려도 되는 경우) */
        fun endsSentence(s: String): Boolean {
            val t = s.trim()
            if (t.isEmpty()) return false
            if (t.last() in "?!.。？！") return true
            val last = words(t).lastOrNull() ?: return false
            if (last.length >= 2 && last.endsWith("요")) return true
            if (koEnd.any { last.endsWith(it) }) return true
            if (last in viEnd) return true
            if (zhEnd.any { last.endsWith(it) }) return true
            return false
        }
    }
}
