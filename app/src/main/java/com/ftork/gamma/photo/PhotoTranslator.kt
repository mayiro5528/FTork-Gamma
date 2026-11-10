package com.ftork.gamma.photo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.ftork.gamma.data.MlKitTranslationEngine
import com.ftork.gamma.data.TranslationResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** 사진 속 글자 한 덩어리와 그 번역 */
data class PhotoLine(val box: Rect, val src: String, val dst: String)

/**
 * 사진 번역 (FTolk-G 카메라 번역 방식).
 * ML Kit 글자 인식(기기 안) → 덩어리마다 ML Kit 번역 → 원래 글자 자리에 번역을 덮어 그리기.
 */
class PhotoTranslator(private val engine: MlKitTranslationEngine) {

    private val recognizers = mutableMapOf<String, TextRecognizer>()

    private fun recognizer(language: String): TextRecognizer = recognizers.getOrPut(scriptOf(language)) {
        when (scriptOf(language)) {
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    /** 글자 찾기만 (실시간 카메라용). 상자와 글자 목록 */
    suspend fun scan(photo: Bitmap, source: String): List<Pair<Rect, String>> =
        blocksOf(recognizer(source).process(InputImage.fromBitmap(photo, 0)).await())

    /** 글자 찾기 → 번역. 글자가 없으면 빈 목록. 번역 실패는 예외로 알려요. */
    suspend fun translate(photo: Bitmap, source: String, target: String): List<PhotoLine> {
        val found = recognizer(source).process(InputImage.fromBitmap(photo, 0)).await()
        val blocks = blocksOf(found)
        return blocks.map { (box, text) ->
            when (val r = engine.translate(text, source, target)) {
                is TranslationResult.Success -> PhotoLine(box, text, r.text)
                is TranslationResult.Failure -> throw IllegalStateException(r.message)
            }
        }
    }

    private fun blocksOf(result: Text): List<Pair<Rect, String>> = result.textBlocks.mapNotNull { b ->
        val box = b.boundingBox ?: return@mapNotNull null
        val text = b.lines.joinToString(" ") { it.text }.trim()
        if (text.isEmpty()) null else box to text
    }

    fun close() = recognizers.values.forEach { it.close() }

    companion object {
        private fun scriptOf(language: String) = when (language) { "ko" -> "ko"; "zh" -> "zh"; else -> "latin" }

        /** 원래 글자 자리를 주변 배경색으로 칠하고, 그 안에 번역을 맞춰 넣은 새 사진 */
        fun render(photo: Bitmap, lines: List<PhotoLine>): Bitmap {
            val out = photo.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            for (line in lines) {
                val box = Rect(line.box).apply { inset(-4, -4) }
                box.intersect(0, 0, out.width, out.height)
                if (box.width() < 4 || box.height() < 4) continue
                val bg = edgeColor(photo, box)
                fill.color = bg
                canvas.drawRoundRect(RectF(box), 6f, 6f, fill)
                drawFitted(canvas, line.dst, box, textColorOn(bg))
            }
            return out
        }

        /** 글자 뒤 배경이 어두우면 흰 글씨, 밝으면 검은 글씨 */
        fun textColorOn(bg: Int): Int =
            if (Color.red(bg) * 0.299 + Color.green(bg) * 0.587 + Color.blue(bg) * 0.114 < 140) Color.WHITE else Color.BLACK

        /** [box] 안에 들어가도록 글자 크기를 줄여 가며 가운데 정렬로 그려요. */
        fun drawFitted(canvas: Canvas, text: String, box: Rect, color: Int) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; isFakeBoldText = true }
            val width = max(1, box.width() - 6)
            var size = min(box.height() * 0.8f, 160f)
            var layout: StaticLayout
            while (true) {
                paint.textSize = size
                layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 0.95f)
                    .setIncludePad(false)
                    .build()
                if (layout.height <= box.height() || size <= 8f) break
                size *= 0.9f
            }
            canvas.save()
            canvas.translate(box.left + 3f, box.top + max(0f, (box.height() - layout.height) / 2f))
            canvas.clipRect(0, 0, width, box.height())
            layout.draw(canvas)
            canvas.restore()
        }

        /** 상자 테두리 픽셀의 평균색 = 글자 뒤 배경색으로 봐요. */
        fun edgeColor(bmp: Bitmap, box: Rect): Int {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            fun add(x: Int, y: Int) {
                val c = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
            }
            val stepX = max(1, box.width() / 20); val stepY = max(1, box.height() / 8)
            for (x in box.left until box.right step stepX) { add(x, box.top); add(x, box.bottom - 1) }
            for (y in box.top until box.bottom step stepY) { add(box.left, y); add(box.right - 1, y) }
            return if (n == 0) Color.WHITE else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }

        fun toBase64Jpeg(bmp: Bitmap, quality: Int = 85): String {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        }

        /** 사진 파일 읽기: 가로·세로 최대 [maxSide]px, 사진 방향(EXIF) 바로잡기 */
        fun load(resolver: ContentResolver, uri: Uri, maxSide: Int = 2000): Bitmap {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val s = info.size
                    val big = max(s.width, s.height)
                    if (big > maxSide) decoder.setTargetSize(s.width * maxSide / big, s.height * maxSide / big)
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
                val decoded = resolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("사진을 읽지 못했어요")
                val degrees = resolver.openInputStream(uri).use { s ->
                    if (s == null) 0 else ExifInterface(s).rotationDegrees
                }
                val m = Matrix()
                if (degrees != 0) m.postRotate(degrees.toFloat())
                val big = max(decoded.width, decoded.height)
                if (big > maxSide) m.postScale(maxSide.toFloat() / big, maxSide.toFloat() / big)
                if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            }
            return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        }
    }
}
