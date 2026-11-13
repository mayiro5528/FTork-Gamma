package com.ftork.gamma.speech

/**
 * 빠진 구간 되살리기: 말소리는 들리는데 기기 인식기가 글자를 하나도 안 낸 구간을
 * 소리가 잦아들 때까지 기다렸다가 Google Cloud 음성 인식으로 받아써요.
 * (2026-11 시험: 틀린 곳 대부분이 빠른 말·배경음악에서 인식기가 몇 초씩 아무것도 안 낸 경우)
 * 그 사이 인식기가 글자를 다시 내면 취소해요 — 그 글자로 확정되는 문장 구간에 빠진 소리도 들어가서 함께 다시 받아써져요.
 * 안드로이드에 기대지 않아 PC 에서 시험해요.
 */
class MissedRescue(
    private val loudDb: Float = 5f,
    private val quietMs: Long = 800,
    private val maxMs: Long = 15_000,
) {
    private var armedAt = -1L
    private var quietSince = -1L

    val armed: Boolean get() = armedAt >= 0

    /** 빠진 구간 알림이 떴을 때 */
    fun arm(now: Long) { armedAt = now; quietSince = -1 }

    /** 인식기가 글자를 다시 냈을 때 */
    fun cancel() { armedAt = -1 }

    /** 소리 크기가 올 때마다. 지금 받아써야 하면 true (한 번만) */
    fun level(db: Float, now: Long): Boolean {
        if (armedAt < 0) return false
        if (db >= loudDb) quietSince = -1 else if (quietSince < 0) quietSince = now
        val quiet = quietSince >= 0 && now - quietSince >= quietMs
        if (!quiet && now - armedAt < maxMs) return false
        armedAt = -1
        return true
    }
}

/** 16kHz 모노 소리 도우미 */
object Pcm {
    /** 가장 조용한 winMs 구간의 가운데 위치(ms, pcm 시작 기준). 문장 사이 쉼을 찾을 때 */
    fun quietestMs(pcm: ShortArray, winMs: Int = 120): Int? {
        val k = winMs / 20
        val frames = pcm.size / 320
        if (frames < k || k < 1) return null
        val e = DoubleArray(frames) { f -> var s = 0.0; for (i in f * 320 until f * 320 + 320) s += pcm[i].toDouble() * pcm[i]; s }
        var sum = 0.0; for (f in 0 until k) sum += e[f]
        var best = sum; var at = 0
        for (f in k until frames) { sum += e[f] - e[f - k]; if (sum < best) { best = sum; at = f - k + 1 } }
        return at * 20 + winMs / 2
    }

    /** 말소리가 있을 만한지: 20ms 조각 중 크기(RMS)가 minRms 이상인 것이 minFrames 개 이상 */
    fun loud(pcm: ShortArray, minRms: Double = 600.0, minFrames: Int = 15): Boolean {
        var n = 0
        var i = 0
        while (i + 320 <= pcm.size) {
            var s = 0.0
            for (k in i until i + 320) s += pcm[k].toDouble() * pcm[k]
            if (Math.sqrt(s / 320) >= minRms && ++n >= minFrames) return true
            i += 320
        }
        return false
    }
}
