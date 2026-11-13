package com.ftork.gamma.speech

/**
 * 빠진 구간 알림: 말소리는 계속 들리는데 인식기가 글자를 하나도 안 내놓는 경우를 찾아요.
 * (2026-11 방송·드라마 음성 시험: 인식 오류의 대부분이 빠르거나 배경음악이 깔린 말에서 인식기가 몇 초씩 아무것도 안 낸 경우)
 * 그럴 때 "잘 안 들렸어요, 다시 말해 주세요"를 띄워서, 상대가 그냥 넘어가지 않게 해요.
 *
 * 인식기의 소리 크기(onRmsChanged, dB)와 글자가 바뀐 시각만 써요. 안드로이드에 기대지 않아 PC 에서 시험해요.
 * @param loudDb   이 크기 이상이면 "소리 있음"
 * @param needLoudMs 글자가 없는 동안 소리가 이만큼 쌓이면 알림
 * @param gapMs    마지막 글자 뒤 최소 이만큼 지나야 알림 (말 끝의 짧은 지연은 빼려고)
 * @param repeatMs 알림 사이 최소 간격
 */
class MissedSpeech(
    private val loudDb: Float = 5f,
    private val needLoudMs: Long = 1500,
    private val gapMs: Long = 2500,
    private val repeatMs: Long = 8000,
) {
    private var lastText = 0L
    private var lastLevel = -1L
    private var loudMs = 0L
    private var fired = false
    private var lastAlert = -100_000L

    /** 듣기를 새로 시작할 때 */
    fun reset(now: Long) { lastText = now; lastLevel = -1; loudMs = 0; fired = false }

    /** 인식기가 글자를 냈거나 바꿨을 때 */
    fun text(now: Long) { lastText = now; loudMs = 0; fired = false }

    /** 소리 크기가 올 때마다. 알림을 띄워야 하면 true (글자 없는 한 구간에 한 번만) */
    fun level(db: Float, now: Long): Boolean {
        val dt = if (lastLevel < 0) 0 else (now - lastLevel).coerceIn(0, 200)
        lastLevel = now
        if (db >= loudDb) loudMs += dt
        if (fired || loudMs < needLoudMs || now - lastText < gapMs || now - lastAlert < repeatMs) return false
        fired = true; lastAlert = now
        return true
    }
}
