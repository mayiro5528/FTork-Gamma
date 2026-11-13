package com.ftork.gamma.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 진짜 마이크 (Google Cloud 음성 인식을 켰을 때만 써요, Android 13+).
 * 마이크 소리를 기기 인식기에 그대로 넘기면서(실시간 글자는 그대로), 최근 [keepSec]초를 남겨 둬요.
 * 인식기가 듣기를 멈추면(통로가 닫히면) 녹음도 멈춰요. 남겨 둔 소리는 다음 듣기에도 이어져요.
 */
class LiveMic(keepSec: Int = 60) : AudioFeed {
    private val chunk = 320 // 20ms
    private val slots = keepSec * 50
    private val ring = ShortArray(slots * chunk)
    private val times = LongArray(slots) // 각 20ms 조각이 끝난 시각
    private var count = 0L               // 지금까지 받은 조각 수
    private var worker: Thread? = null
    private var writeEnd: ParcelFileDescriptor? = null

    @SuppressLint("MissingPermission") // 마이크 권한은 듣기 전에 화면이 받아요
    @Synchronized
    override fun open(): ParcelFileDescriptor {
        close()
        val (read, write) = ParcelFileDescriptor.createPipe()
        writeEnd = write
        worker = Thread {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 2 * 10))
            }.getOrNull()
            val out = FileOutputStream(write.fileDescriptor)
            val buf = ShortArray(chunk)
            val bytes = ByteBuffer.allocate(chunk * 2).order(ByteOrder.LITTLE_ENDIAN)
            try {
                if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) return@Thread
                rec.startRecording()
                while (!Thread.currentThread().isInterrupted) {
                    var n = 0
                    while (n < chunk) { val r = rec.read(buf, n, chunk - n); if (r <= 0) return@Thread; n += r }
                    synchronized(ring) {
                        val slot = (count % slots).toInt()
                        buf.copyInto(ring, slot * chunk)
                        times[slot] = SystemClock.elapsedRealtime()
                        count++
                    }
                    bytes.clear(); bytes.asShortBuffer().put(buf)
                    out.write(bytes.array()) // 인식기가 통로를 닫으면 여기서 오류 → 녹음 끝
                }
            } catch (_: Exception) {
            } finally {
                runCatching { rec?.stop() }; runCatching { rec?.release() }
                runCatching { out.close() }
            }
        }.apply { isDaemon = true; name = "LiveMic"; start() }
        return read
    }

    @Synchronized
    override fun close() {
        worker?.interrupt(); worker = null
        runCatching { writeEnd?.close() }; writeEnd = null
    }

    override fun clip(fromMs: Long, toMs: Long): ShortArray? = synchronized(ring) {
        val first = maxOf(0L, count - slots)
        val picked = ArrayList<Int>()
        for (i in first until count) {
            val slot = (i % slots).toInt()
            val end = times[slot]
            if (end - 20 >= fromMs && end <= toMs + 20) picked += slot
        }
        if (picked.isEmpty()) return null
        val outArr = ShortArray(picked.size * chunk)
        picked.forEachIndexed { k, slot -> ring.copyInto(outArr, k * chunk, slot * chunk, slot * chunk + chunk) }
        outArr
    }
}
