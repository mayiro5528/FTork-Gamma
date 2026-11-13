package com.ftork.gamma.speech

/**
 * 확정된 문장마다 "소리 구간"(언제부터 언제까지)을 정해요. Google 음성 인식으로 그 구간만 다시 받아쓰려고.
 * 인식기 글자는 소리보다 조금 늦게 와요. 그래서
 *  - 쉼에서 나뉜 문장: 앞 문장의 마지막 글자가 온 때 + tailMs 를 경계로 (다음 문장 첫 글자보다 앞)
 *  - 조용해서 확정·말 끝내기: 지금 시각까지
 * 구간은 겹치지 않고 이어져요(다음 문장은 앞 문장이 끝난 곳에서 시작). 안드로이드에 기대지 않아 PC 에서 시험해요.
 */
class SentenceSpans(private val tailMs: Long = 300, private val leadMs: Long = 300) {
    private var mark = 0L
    private var lastPartial = 0L

    /** 듣기를 새로 시작할 때 (소리가 들어오기 시작한 시각) */
    fun reset(now: Long) { mark = now - leadMs; lastPartial = 0L }

    /** 이번 부분 결과로 문장이 나뉘었을 때의 경계 (바뀐 글자를 기록하기 전에 불러요) */
    fun splitPoint(now: Long): Long = if (lastPartial > 0) minOf(now, lastPartial + tailMs) else now

    /** 글자가 바뀐 부분 결과가 왔을 때 */
    fun partial(now: Long) { lastPartial = now }

    /** 문장 하나를 [이전 경계, to] 로 잘라 가요 */
    fun take(to: Long): Pair<Long, Long> {
        val from = mark
        val end = maxOf(from, to)
        mark = end
        return from to end
    }
}
