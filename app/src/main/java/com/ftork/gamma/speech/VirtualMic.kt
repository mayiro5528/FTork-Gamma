package com.ftork.gamma.speech

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 시험용 가상 마이크 (디버그 빌드에서만 켜져요).
 * 음성 파일을 실제 시간에 맞춰 "틀어 두고", 음성 인식이 시작될 때마다 그 시점부터의 소리를 인식기에 넣어요.
 * 진짜 마이크처럼 인식기가 쉬는 동안 지나간 소리는 들리지 않아요.
 */
class VirtualMic(private val pcm: ShortArray) {
    private var startedAt = 0L
    private var writer: Thread? = null
    private var writeEnd: ParcelFileDescriptor? = null

    val durationMs: Long get() = pcm.size / 16L

    fun play(delayMs: Long = 0) { startedAt = SystemClock.elapsedRealtime() + delayMs }

    /** 지금 재생 위치 (샘플). 아직 시작 전이면 0 */
    private fun position(): Int = ((SystemClock.elapsedRealtime() - startedAt) * 16).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    val finished: Boolean get() = startedAt > 0 && position() >= pcm.size

    /** 인식기에 넘길 소리 통로. 끝난 뒤에는 조용한 소리를 계속 보내요. */
    @Synchronized
    fun open(): ParcelFileDescriptor {
        close()
        val (read, write) = ParcelFileDescriptor.createPipe()
        writeEnd = write
        writer = Thread {
            val out = FileOutputStream(write.fileDescriptor)
            val chunk = 320 // 20ms
            val buf = ByteBuffer.allocate(chunk * 2).order(ByteOrder.LITTLE_ENDIAN)
            var pos = position()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    // 실제 시간보다 앞서 나가지 않게
                    while (pos + chunk > position() && startedAt > 0) SystemClock.sleep(5)
                    buf.clear()
                    for (i in 0 until chunk) buf.putShort(if (pos + i < pcm.size) pcm[pos + i] else 0)
                    out.write(buf.array())
                    pos += chunk
                }
            } catch (_: Exception) {
            } finally {
                runCatching { out.close() }
            }
        }.apply { isDaemon = true; start() }
        return read
    }

    @Synchronized
    fun close() {
        writer?.interrupt(); writer = null
        runCatching { writeEnd?.close() }; writeEnd = null
    }

    companion object {
        /** 여러 파일을 이어 붙여요 (사이에 gapMs 만큼 조용히). 16kHz 모노로 바꿔요. */
        fun load(files: List<File>, gapMs: Int = 1500): VirtualMic {
            val parts = files.map { decode(it) }
            val gap = ShortArray(gapMs * 16)
            val all = ArrayList<ShortArray>()
            all += ShortArray(500 * 16)
            parts.forEachIndexed { i, p -> if (i > 0) all += gap; all += p }
            val out = ShortArray(all.sumOf { it.size })
            var o = 0
            for (p in all) { p.copyInto(out, o); o += p.size }
            return VirtualMic(out)
        }

        private fun decode(file: File): ShortArray {
            val ex = MediaExtractor().apply { setDataSource(file.path) }
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var mono = ShortArray(1 shl 20); var mn = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            while (true) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                } else if (o >= 0) {
                    val b = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = (info.size / 2) / ch
                    for (f in 0 until frames) {
                        var s = 0
                        for (c in 0 until ch) s += b.get(f * ch + c)
                        if (mn == mono.size) mono = mono.copyOf(mono.size * 2); mono[mn++] = (s / ch).toShort()
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            codec.stop(); codec.release(); ex.release()
            // 16kHz 로 (선형 보간)
            mono = mono.copyOf(mn)
            val n = (mono.size.toLong() * 16000 / rate).toInt()
            return ShortArray(n) { i ->
                val x = i.toDouble() * rate / 16000
                val a = x.toInt().coerceAtMost(mono.size - 1)
                val b = (a + 1).coerceAtMost(mono.size - 1)
                val t = x - a
                (mono[a] * (1 - t) + mono[b] * t).toInt().toShort()
            }
        }
    }
}
