package com.ftork.gamma.photo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.ftork.gamma.data.TranslationEngine
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
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 글자 덩어리의 모양 (Google 렌즈처럼 원래 글자에 맞춰 그리기 위한 정보)
 * cx, cy: 가운데 · w, h: 글자 방향 기준 너비·높이 · angle: 기울기(도, 시계 방향 +)
 * lineH: 원래 한 줄 높이 · lines: 원래 줄 수
 */
data class Geo(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float, val lineH: Float, val lines: Int) {
    /** 화면에 맞게 늘리고 옮긴 모양 */
    fun scaled(s: Float, dx: Float, dy: Float) = Geo(cx * s + dx, cy * s + dy, w * s, h * s, angle, lineH * s, lines)
}

/** 사진에서 찾은 글자 덩어리 하나 (box: 기울기를 감싼 수평 상자) */
data class Scanned(val box: Rect, val text: String, val geo: Geo)

/** 사진 속 글자 한 덩어리와 그 번역, 그리고 원래 글자 색 */
data class PhotoLine(val box: Rect, val src: String, val dst: String, val geo: Geo)

/**
 * 사진 번역 (Google 렌즈 사진 번역 방식).
 * ML Kit 글자 인식(기기 안) → 덩어리(문단)마다 번역 → 원래 글자 자리에
 * 원래 기울기 · 원래 줄 높이 · 원래 글자 색으로 번역을 다시 써요.
 */
class PhotoTranslator(private val engine: TranslationEngine) {

    private val recognizers = mutableMapOf<String, TextRecognizer>()

    private fun recognizer(language: String): TextRecognizer = recognizers.getOrPut(scriptOf(language)) {
        when (scriptOf(language)) {
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    /** 글자 찾기만 (실시간 카메라용) */
    suspend fun scan(photo: Bitmap, source: String): List<Scanned> =
        blocksOf(recognizer(source).process(InputImage.fromBitmap(photo, 0)).await())

    /** 글자 찾기 → 번역. 글자가 없으면 빈 목록. 번역 실패는 예외로 알려요. */
    suspend fun translate(photo: Bitmap, source: String, target: String): List<PhotoLine> =
        scan(photo, source).map { b ->
            when (val r = engine.translate(prepareForTranslation(b.text), source, target)) {
                is TranslationResult.Success -> PhotoLine(b.box, b.text, r.text, b.geo)
                is TranslationResult.Failure -> throw IllegalStateException(r.message)
            }
        }

    private fun blocksOf(result: Text): List<Scanned> = result.textBlocks.mapNotNull { b ->
        val box = b.boundingBox ?: return@mapNotNull null
        val text = b.lines.joinToString(" ") { it.text }.trim()
        if (text.isEmpty()) return@mapNotNull null
        Scanned(box, text, geoOf(b.cornerPoints, box, b.lines))
    }

    fun close() = recognizers.values.forEach { it.close() }

    companion object {
        private fun scriptOf(language: String) = when (language) { "ko" -> "ko"; "zh" -> "zh"; else -> "latin" }

        private fun dist(a: Point, b: Point) = hypot((a.x - b.x).toFloat(), (a.y - b.y).toFloat())

        /** 꼭짓점 4개(왼위·오위·오아래·왼아래)로 기울기와 크기를 구해요. 없으면 수평 상자로. */
        private fun geoOf(pts: Array<Point>?, box: Rect, lines: List<Text.Line>): Geo {
            val lineHeights = lines.mapNotNull { l ->
                val p = l.cornerPoints
                if (p != null && p.size == 4) dist(p[1], p[2]) else l.boundingBox?.height()?.toFloat()
            }.sorted()
            val lineH = if (lineHeights.isEmpty()) box.height().toFloat() else lineHeights[lineHeights.size / 2]
            if (pts == null || pts.size != 4) {
                return Geo(box.exactCenterX(), box.exactCenterY(), box.width().toFloat(), box.height().toFloat(), 0f, lineH, max(1, lines.size))
            }
            val w = dist(pts[0], pts[1]); val h = dist(pts[1], pts[2])
            var angle = Math.toDegrees(atan2((pts[1].y - pts[0].y).toDouble(), (pts[1].x - pts[0].x).toDouble())).toFloat()
            if (kotlin.math.abs(angle) < 2f) angle = 0f // 거의 수평이면 그냥 수평
            val cx = pts.sumOf { it.x }.toFloat() / 4f; val cy = pts.sumOf { it.y }.toFloat() / 4f
            return Geo(cx, cy, w, h, angle, lineH, max(1, lines.size))
        }

        /** 원래 글자 자리를 배경색으로 칠하고, 원래 기울기·줄 높이·글자 색으로 번역을 다시 쓴 새 사진 */
        fun render(photo: Bitmap, lines: List<PhotoLine>): Bitmap {
            val out = photo.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            for (line in lines) {
                val bounds = Rect(line.box).apply { inset(-3, -3); intersect(0, 0, photo.width, photo.height) }
                if (bounds.width() < 4 || bounds.height() < 4) continue
                val bg = edgeColor(photo, bounds)
                val fg = textColorIn(photo, bounds, bg)
                drawBlock(canvas, line.geo, line.dst, bg, fg, 1f)
            }
            return out
        }

        /**
         * 번역 한 덩어리 그리기 (사진 · 실시간 카메라 공통).
         * 원래 줄 높이에 가까운 글자 크기로 쓰고, 넘치면 조금씩 줄여요. 여러 줄이면 왼쪽 정렬로 자연스럽게 줄바꿈해요.
         */
        fun drawBlock(canvas: Canvas, g: Geo, text: String, bg: Int, fg: Int, alpha: Float) {
            val pad = max(3f, g.lineH * 0.12f)
            val r = RectF(g.cx - g.w / 2 - pad, g.cy - g.h / 2 - pad, g.cx + g.w / 2 + pad, g.cy + g.h / 2 + pad)
            val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.save()
            if (g.angle != 0f) canvas.rotate(g.angle, g.cx, g.cy)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (bg and 0x00FFFFFF) or (((0xF2 * a) / 255) shl 24) }
            canvas.drawRoundRect(r, pad, pad, fill)

            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (fg and 0x00FFFFFF) or (a shl 24)
                isFakeBoldText = g.lineH > 18f
            }
            val width = max(1, (r.width() - pad).toInt())
            val align = if (g.lines > 1) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER
            var size = min(g.lineH * 0.82f, r.height() * 0.9f).coerceAtLeast(8f)
            var layout: StaticLayout
            while (true) {
                paint.textSize = size
                layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(align).setLineSpacing(0f, 1.0f).setIncludePad(false).build()
                if (layout.height <= r.height() || size <= 7f) break
                size *= 0.92f
            }
            canvas.translate(r.left + pad / 2, r.top + max(0f, (r.height() - layout.height) / 2f))
            canvas.clipRect(0f, 0f, width.toFloat(), r.height())
            layout.draw(canvas)
            canvas.restore()
        }

        /**
         * 간판처럼 전부 대문자인 글은 번역이 잘 안 돼요. 문장 첫 글자만 대문자로 바꿔서 번역해요.
         * (예: "KINH DOANH CÓ TRÁCH NHIỆM" → "Kinh doanh có trách nhiệm")
         */
        fun prepareForTranslation(text: String): String {
            val letters = text.filter { it.isLetter() }
            if (letters.length < 4) return text
            val upper = letters.count { it.isUpperCase() }
            if (upper < letters.length * 0.8f) return text
            return text.lowercase().replaceFirstChar { it.titlecase() }
        }

        /** 글자 뒤 배경이 어두우면 흰 글씨, 밝으면 검은 글씨 */
        fun textColorOn(bg: Int): Int =
            if (Color.red(bg) * 0.299 + Color.green(bg) * 0.587 + Color.blue(bg) * 0.114 < 140) Color.WHITE else Color.BLACK

        private fun colorDist(a: Int, b: Int): Int =
            kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))

        /**
         * 원래 글자 색: 상자 안 픽셀 중 배경색과 가장 다른 것들의 평균.
         * (흰 바탕 빨간 글씨면 빨간색, 파란 바탕 흰 글씨면 흰색) 대비가 약하면 흰/검정으로.
         */
        fun textColorIn(bmp: Bitmap, box: Rect, bg: Int): Int {
            val samples = ArrayList<Int>()
            val sx = max(1, box.width() / 24); val sy = max(1, box.height() / 10)
            var y = box.top + sy / 2
            while (y < box.bottom) {
                var x = box.left + sx / 2
                while (x < box.right) {
                    samples += bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1)); x += sx
                }
                y += sy
            }
            val far = samples.filter { colorDist(it, bg) > 120 }.sortedByDescending { colorDist(it, bg) }
            if (far.size < 3) return textColorOn(bg)
            val top = far.take(max(3, far.size / 3))
            val c = Color.rgb(top.sumOf { Color.red(it) } / top.size, top.sumOf { Color.green(it) } / top.size, top.sumOf { Color.blue(it) } / top.size)
            return if (colorDist(c, bg) < 160) textColorOn(bg) else c
        }

        /** [box] 안에 들어가도록 글자 크기를 줄여 가며 가운데 정렬로 그려요. */
        fun drawFitted(canvas: Canvas, text: String, box: Rect, color: Int) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; isFakeBoldText = true }
            val width = max(1, box.width() - 6)
            var size = min(box.height() * 0.8f, 160f)
            var layout: StaticLayout
            while (true) {
                paint.textSize = size
                layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 0.95f).setIncludePad(false).build()
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
