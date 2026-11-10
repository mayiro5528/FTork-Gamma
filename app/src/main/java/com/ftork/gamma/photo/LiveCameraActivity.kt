package com.ftork.gamma.photo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ftork.gamma.data.MlKitTranslationEngine
import com.ftork.gamma.data.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** 찍은 사진을 메인 화면으로 넘기는 자리 (사진이 커서 Intent 에 못 실어요) */
object LiveCameraResult {
    @Volatile private var bitmap: Bitmap? = null
    fun put(b: Bitmap) { bitmap = b }
    fun take(): Bitmap? = bitmap.also { bitmap = null }
}

/**
 * 실시간 카메라 번역 (Google 번역 앱의 카메라 화면처럼).
 * - 비추면: 0.5초마다 글자를 찾아 번역을 미리보기 위에 바로 덮어요. (ML Kit 글자 인식 + ML Kit 번역, 기기 안)
 * - 찍으면: 고화질 사진을 메인 화면으로 넘겨서 번역하고, 원본·번역본을 사진 앱에 저장해요.
 *
 * 결과(Intent extra "action"): shot · exit · gallery · denied · error, 그리고 "src", "dst"
 */
class LiveCameraActivity : ComponentActivity() {

    private var src = "vi"
    private var dst = "ko"
    private var haptic = true

    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var shutter: View

    private val engine = MlKitTranslationEngine()
    private val scanner = PhotoTranslator(engine)
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
    }
    private lateinit var analysisExecutor: ExecutorService
    private val busy = AtomicBoolean(false)
    @Volatile private var lastRun = 0L
    @Volatile private var generation = 0
    private var capture: ImageCapture? = null
    private var shooting = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else done("denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            val o = JSONObject(intent.getStringExtra("options") ?: "{}")
            src = o.optString("src", src); dst = o.optString("dst", dst)
            haptic = o.optBoolean("haptic", true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(buildLayout())
        analysisExecutor = Executors.newSingleThreadExecutor()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = done("exit")
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        scanner.close()
        engine.close()
        super.onDestroy()
    }

    // ---------------- 화면 ----------------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun roundButton(label: String, size: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
        layoutParams = LinearLayout.LayoutParams(dp(size.toFloat()), dp(size.toFloat()))
        setOnClickListener { onClick() }
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = OverlayView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // 위: 뒤로 · 언어 · 바꾸기
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setBackgroundColor(0x88000000.toInt())
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        top.addView(roundButton("←", 44) { done("exit") })
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(roundButton("⇄", 44) { swap() })
        root.addView(top, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // 아래: 안내 · 갤러리 · 셔터
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(20f))
            setBackgroundColor(0x88000000.toInt())
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10f))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val gallery = roundButton("🖼", 52) { done("gallery") }
        shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(5f), 0xFF8AB4F8.toInt())
            }
            contentDescription = "사진 찍고 번역 저장"
            setOnClickListener { shoot() }
        }
        val spacer = View(this)
        row.addView(gallery)
        row.addView(View(this), LinearLayout.LayoutParams(dp(36f), 1))
        row.addView(shutter, LinearLayout.LayoutParams(dp(76f), dp(76f)))
        row.addView(View(this), LinearLayout.LayoutParams(dp(36f), 1))
        row.addView(spacer, LinearLayout.LayoutParams(dp(52f), dp(52f)))
        bottom.addView(status)
        bottom.addView(row)
        root.addView(bottom, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(dp(12f), dp(10f) + bars.top, dp(12f), dp(10f))
            bottom.setPadding(dp(16f), dp(8f), dp(16f), dp(20f) + bars.bottom)
            insets
        }
        renderTitle()
        setStatus("글자를 비추면 바로 번역돼요 · 셔터를 누르면 번역본을 저장해요")
        return root
    }

    private fun renderTitle() { title.text = "${nameOf(src)}  →  ${nameOf(dst)}" }
    private fun setStatus(t: String) { status.text = t }

    private fun swap() {
        val t = src; src = dst; dst = t
        synchronized(cache) { cache.clear() }
        generation++
        overlay.show(emptyList(), 1, 1)
        renderTitle()
    }

    private fun done(action: String) {
        setResult(RESULT_OK, Intent().putExtra("action", action).putExtra("src", src).putExtra("dst", dst))
        finish()
    }

    // ---------------- 카메라 ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val ratio = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                val p = Preview.Builder().setResolutionSelector(ratio).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ratio)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build().also { it.setAnalyzer(analysisExecutor, ::analyze) }
                val cap = ImageCapture.Builder()
                    .setResolutionSelector(ratio)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                capture = cap
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis, cap)
            } catch (e: Exception) {
                done("error")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 미리보기 한 장면: 0.5초에 한 번만, 앞 장면 처리가 끝났을 때만 읽어요. */
    private fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (shooting || now - lastRun < 500 || !busy.compareAndSet(false, true)) { image.close(); return }
        lastRun = now
        val frame = try { upright(image.toBitmap(), image.imageInfo.rotationDegrees) } catch (e: Exception) {
            busy.set(false); return
        } finally { image.close() }
        val gen = generation
        val s = src; val d = dst
        lifecycleScope.launch {
            try {
                val blocks = withContext(Dispatchers.Default) { scanner.scan(frame, s) }
                val boxes = ArrayList<LiveBox>()
                var missing = false
                for ((box, text) in blocks) {
                    val cached = synchronized(cache) { cache[text] }
                    val translated = cached ?: when (val r = engine.translate(text, s, d)) {
                        is TranslationResult.Success -> r.text.also { synchronized(cache) { cache[text] = it } }
                        is TranslationResult.Failure -> { missing = true; null }
                    }
                    if (translated != null) {
                        val bg = PhotoTranslator.edgeColor(frame, Rect(box).apply { inset(-3, -3); intersect(0, 0, frame.width, frame.height) })
                        boxes += LiveBox(box, translated, bg)
                    }
                }
                if (gen == generation && !shooting) {
                    overlay.show(boxes, frame.width, frame.height)
                    setStatus(when {
                        missing -> "번역 모델을 받는 중이에요 (처음 한 번, 인터넷 필요)"
                        blocks.isEmpty() -> "글자를 화면 가운데에 비춰 주세요"
                        else -> "셔터를 누르면 번역본을 사진 앱에 저장해요"
                    })
                }
            } catch (e: Exception) {
                // 한 장면 실패는 무시하고 다음 장면을 읽어요.
            } finally {
                busy.set(false)
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun shoot() {
        val cap = capture ?: return
        if (shooting) return
        shooting = true
        if (haptic) shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        shutter.alpha = 0.4f
        setStatus("찍는 중…")
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                lifecycleScope.launch {
                    val bmp = try {
                        withContext(Dispatchers.Default) { limit(upright(image.toBitmap(), image.imageInfo.rotationDegrees), 2000) }
                    } catch (e: Exception) { null } finally { image.close() }
                    if (bmp == null) { failShot(); return@launch }
                    LiveCameraResult.put(bmp)
                    done("shot")
                }
            }
            override fun onError(exception: ImageCaptureException) = failShot()
        })
    }

    private fun failShot() {
        shooting = false; shutter.alpha = 1f
        setStatus("찍지 못했어요. 다시 눌러 주세요.")
    }

    companion object {
        fun intent(context: Context, optionsJson: String): Intent =
            Intent(context, LiveCameraActivity::class.java).putExtra("options", optionsJson)

        private fun nameOf(code: String) = when (code) {
            "ko" -> "한국어"; "vi" -> "베트남어"; "en" -> "영어"; "zh" -> "중국어"; else -> code
        }

        private fun upright(b: Bitmap, degrees: Int): Bitmap {
            val argb = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
            if (degrees == 0) return argb
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(argb, 0, 0, argb.width, argb.height, m, true)
        }

        private fun limit(b: Bitmap, maxSide: Int): Bitmap {
            val big = max(b.width, b.height)
            if (big <= maxSide) return b
            return Bitmap.createScaledBitmap(b, b.width * maxSide / big, b.height * maxSide / big, true)
        }
    }
}

/** 미리보기 위에 덮을 번역 한 칸 (좌표는 분석한 장면 기준) */
data class LiveBox(val box: Rect, val text: String, val bg: Int)

/** 분석 장면 좌표를 화면 좌표로 바꿔서(FILL_CENTER) 번역 칸을 그려요. */
class OverlayView(context: Context) : View(context) {
    private var boxes: List<LiveBox> = emptyList()
    private var imgW = 1
    private var imgH = 1
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(list: List<LiveBox>, w: Int, h: Int) {
        boxes = list; imgW = max(1, w); imgH = max(1, h); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (boxes.isEmpty()) return
        val scale = max(width.toFloat() / imgW, height.toFloat() / imgH)
        val dx = (width - imgW * scale) / 2f
        val dy = (height - imgH * scale) / 2f
        for (b in boxes) {
            val r = RectF(b.box.left * scale + dx, b.box.top * scale + dy, b.box.right * scale + dx, b.box.bottom * scale + dy)
            r.inset(-4f, -4f)
            fill.color = (b.bg and 0x00FFFFFF) or 0xF0000000.toInt()
            canvas.drawRoundRect(r, 8f, 8f, fill)
            val rect = Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
            PhotoTranslator.drawFitted(canvas, b.text, rect, PhotoTranslator.textColorOn(b.bg))
        }
    }
}
