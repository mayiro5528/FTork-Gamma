package com.ftork.gamma.speech

import android.os.ParcelFileDescriptor

/**
 * 인식기에 넣을 소리 통로 + 지나간 소리 꺼내기.
 * 앱이 직접 마이크 소리를 받아서(LiveMic) 기기 인식기에 넘기고, 같은 소리를 남겨 두었다가
 * 문장이 끝나면 그 구간을 Google Cloud 음성 인식으로 다시 받아써요.
 * 시각은 모두 SystemClock.elapsedRealtime() 기준(ms), 소리는 16kHz 모노 16비트.
 */
interface AudioFeed {
    /** 인식기(EXTRA_AUDIO_SOURCE)에 넘길 읽기 통로. 부를 때마다 새 통로 */
    fun open(): ParcelFileDescriptor
    fun close()
    /** [fromMs, toMs] 사이에 들어온 소리. 남아 있지 않으면 null */
    fun clip(fromMs: Long, toMs: Long): ShortArray?
}
