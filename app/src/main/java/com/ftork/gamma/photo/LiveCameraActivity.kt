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
import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.core.view.WindowCompat
import com.ftork.gamma.R
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
import com.ftork.gamma.data.SmartTranslationEngine
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

    /** 메인 화면과 같은 번역 설정: Google 키가 있으면 Google 서버 번역, 없으면 ML Kit */
    private val engine by lazy { SmartTranslationEngine(this, MlKitTranslationEngine().apply { fix = false }) }
    private val scanner by lazy { PhotoTranslator(engine) }
    private var camera: androidx.camera.core.Camera? = null
    private var torchOn = false
    private lateinit var srcPill: TextView
    private lateinit var dstPill: TextView
    private lateinit var flashBtn: ImageView
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
        pal = runCatching {
            val o = JSONObject(intent.getStringExtra("options") ?: "{}")
            Palette(o.optString("theme", "dark") != "light", o.optString("style", "simple") == "simple")
        }.getOrDefault(Palette(true, true))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = pal.bg != 0xFF202124.toInt()
            isAppearanceLightNavigationBars = pal.bg != 0xFF202124.toInt()
        }
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

    /** FTalk 화면 설정(다크/라이트 · 컬러/심플)과 같은 색 */
    private inner class Palette(dark: Boolean, val simple: Boolean) {
        val bg = if (dark) 0xFF202124.toInt() else 0xFFF8F9FA.toInt()
        val surface = if (dark) 0xFF303134.toInt() else 0xFFFFFFFF.toInt()
        val text = if (dark) 0xFFF4F6F8.toInt() else 0xFF202124.toInt()
        val muted = if (dark) 0xFFB6C0CD.toInt() else 0xFF5F6368.toInt()
        val stroke = if (dark) 0xFF606773.toInt() else 0xFFCED3DA.toInt()
        val accent = if (simple) 0xFF7B8B9D.toInt() else 0xFF36ABE0.toInt()
    }
    private lateinit var pal: Palette

    /** 둥근 아이콘 버튼 (FTalk 의 roundbtn 모양) */
    private fun iconButton(icon: Int, label: String, sizeDp: Float, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(pal.text)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = label
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(pal.surface)
            if (!pal.simple) setStroke(dp(1f), pal.stroke)
        }
        layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener { onClick() }
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = OverlayView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // 위: 뒤로 · 제목 · 플래시 (Google 렌즈 배치)
        val top = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        val back = iconButton(R.drawable.ic_live_back, "뒤로", 44f) { done("exit") }
        top.addView(back, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.START or Gravity.CENTER_VERTICAL))
        rotatable += back
        title = TextView(this).apply {
            setTextColor(pal.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Typeface.DEFAULT_BOLD
            text = "사진 번역"
        }
        top.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        flashBtn = iconButton(R.drawable.ic_live_flash_off, "플래시 켜기", 44f) { toggleTorch() }
        top.addView(flashBtn, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(top, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // 아래: 안내 문구, 그 아래 [갤러리(왼쪽) · 셔터(정가운데)]
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }
        status = TextView(this).apply {
            setTextColor(pal.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER
        }
        val row = FrameLayout(this)
        val gallery = iconButton(R.drawable.ic_live_gallery, "갤러리에서 고르기", 52f) { done("gallery") }
        row.addView(gallery, FrameLayout.LayoutParams(dp(52f), dp(52f), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(28f) })
        rotatable += gallery
        // 셔터: 얇은 테두리 + 안쪽 원 (심플)
        shutter = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(3f), pal.text) }
            contentDescription = "사진 찍고 번역본 저장"
            setOnClickListener { shoot() }
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (pal.simple) pal.text else pal.accent) }
            }, FrameLayout.LayoutParams(dp(58f), dp(58f), Gravity.CENTER))
        }
        row.addView(shutter, FrameLayout.LayoutParams(dp(72f), dp(72f), Gravity.CENTER))

        // 맨 아래: [원문 언어] ⇄ [번역 언어] — 누르면 언어 목록 (Google 렌즈 배치)
        val langs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun pill(onClick: () -> Unit) = TextView(this).apply {
            setTextColor(pal.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat(); setColor(pal.surface)
                if (!pal.simple) setStroke(dp(1f), pal.stroke)
            }
            setOnClickListener { onClick() }
        }
        srcPill = pill { pickLanguage(source = true) }
        dstPill = pill { pickLanguage(source = false) }
        val swapIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_live_swap); imageTintList = ColorStateList.valueOf(pal.accent)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "번역 방향 바꾸기"
            setOnClickListener { swap() }
        }
        langs.addView(srcPill, LinearLayout.LayoutParams(0, dp(48f), 1f))
        langs.addView(swapIcon, LinearLayout.LayoutParams(dp(52f), dp(48f)))
        langs.addView(dstPill, LinearLayout.LayoutParams(0, dp(48f), 1f))

        bottom.addView(status, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(row, LinearLayout.LayoutParams(-1, dp(92f)))
        bottom.addView(langs, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bottom, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(dp(12f), dp(10f) + bars.top, dp(12f), dp(10f))
            bottom.setPadding(dp(16f), dp(10f), dp(16f), dp(8f) + bars.bottom)
            insets
        }
        renderTitle()
        setStatus("글자를 비추면 바로 번역돼요 · 셔터를 누르면 번역본을 저장해요")
        return root
    }

    private fun renderTitle() {
        srcPill.text = nameOf(src); dstPill.text = nameOf(dst)
        srcPill.contentDescription = "원문 언어: ${nameOf(src)} (바꾸기)"
        dstPill.contentDescription = "번역 언어: ${nameOf(dst)} (바꾸기)"
    }

    /** 언어 목록에서 고르기. 반대쪽과 같은 언어를 고르면 서로 바꿔요. */
    private fun pickLanguage(source: Boolean) {
        val codes = LANGS
        val names = codes.map { nameOf(it) }.toTypedArray()
        val current = codes.indexOf(if (source) src else dst)
        android.app.AlertDialog.Builder(this, if (pal.bg == 0xFF202124.toInt()) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(if (source) "원문 언어" else "번역할 언어")
            .setSingleChoiceItems(names, current) { d, which ->
                val code = codes[which]
                if (source) { if (code == dst) dst = src; src = code } else { if (code == src) src = dst; dst = code }
                d.dismiss()
                resetLanguages()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun resetLanguages() {
        synchronized(cache) { cache.clear() }
        generation++
        tracks.clear(); translating = false
        overlay.show(emptyList(), 1, 1)
        lastScanAt = 0L; scanLuma = null
        renderTitle()
    }

    /** 플래시(손전등) 켜고 끄기: 어두운 곳의 메뉴판·간판용 */
    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) { setStatus("이 폰은 플래시를 쓸 수 없어요"); return }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        flashBtn.setImageResource(if (torchOn) R.drawable.ic_live_flash else R.drawable.ic_live_flash_off)
        flashBtn.contentDescription = if (torchOn) "플래시 끄기" else "플래시 켜기"
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        shooting = false
        if (::shutter.isInitialized) shutter.alpha = 1f
    }
    private fun setStatus(t: String) { status.text = t }

    private fun swap() {
        val t = src; src = dst; dst = t
        resetLanguages()
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
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis, cap)
                if (camera?.cameraInfo?.hasFlashUnit() != true) flashBtn.visibility = View.GONE
            } catch (e: Exception) {
                done("error")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ================= Google 번역 앱처럼 차분한 실시간 번역 =================
    // 1) 카메라가 움직이는 동안: 번역을 살짝 숨겨요(흐리게). 글자를 새로 읽지 않아요.
    // 2) 0.4초 동안 멈추면: 그 장면을 한 번 읽어요.
    // 3) 같은 장면이면 1.5초에 한 번만 다시 확인하고, 같은 글자·같은 자리면 번역을 그대로 둬요(다시 그리지 않음).
    // 4) 글자를 한 번 놓쳐도 바로 지우지 않고 한 번 더 기다려요.
    // 5) 새로 나타난 번역은 부드럽게 나타나요(페이드).

    // ---- 폰을 든 방향 (0 세로 · 90 왼쪽이 위 · 180 거꾸로 · 270 오른쪽이 위) ----
    // 화면은 세로로 고정이지만, 가로로 들고 찍으면 글자가 옆으로 누워 있어요.
    // 그래서 글자를 읽을 때와 찍은 사진을 저장할 때 그 각도만큼 돌려서 바로 세워요.
    @Volatile private var deviceDeg = 0
    private var lastScanDeg = 0
    private val rotatable = ArrayList<View>()
    private val orientationListener by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val d = when {
                    o >= 315 || o < 45 -> 0
                    o < 135 -> 90
                    o < 225 -> 180
                    else -> 270
                }
                if (d != deviceDeg) {
                    deviceDeg = d
                    val r = when (d) { 90 -> -90f; 180 -> 180f; 270 -> 90f; else -> 0f }
                    rotatable.forEach { it.animate().rotation(r).setDuration(200).start() }
                }
            }
        }
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
    }

    private var prevLuma: IntArray? = null
    @Volatile private var scanLuma: IntArray? = null
    @Volatile private var lastMoveAt = 0L
    @Volatile private var lastScanAt = 0L
    @Volatile private var hiddenForMotion = false
    private val tracks = ArrayList<Track>()   // 메인 스레드에서만 써요
    private var nextTrackId = 1
    private var translating = false
    private var lastStatus = ""

    /** 장면을 40×30 칸으로 줄인 밝기 (움직임 비교용, 아주 가벼움) */
    private fun lumaGrid(image: ImageProxy): IntArray {
        val plane = image.planes[0]; val buf = plane.buffer
        val rs = plane.rowStride; val ps = plane.pixelStride
        val w = image.width; val h = image.height
        val out = IntArray(GW * GH)
        for (gy in 0 until GH) {
            val y = (gy * 2 + 1) * h / (GH * 2)
            for (gx in 0 until GW) {
                val x = (gx * 2 + 1) * w / (GW * 2)
                val i = y * rs + x * ps
                val r = buf.get(i).toInt() and 0xFF; val g = buf.get(i + 1).toInt() and 0xFF; val b = buf.get(i + 2).toInt() and 0xFF
                out[gy * GW + gx] = (r * 3 + g * 6 + b) / 10
            }
        }
        return out
    }

    private fun diff(a: IntArray?, b: IntArray): Int {
        if (a == null) return 255
        var s = 0
        for (i in b.indices) s += kotlin.math.abs(a[i] - b[i])
        return s / b.size
    }

    private fun analyze(image: ImageProxy) {
        if (shooting) { image.close(); return }
        val now = System.currentTimeMillis()
        val grid = try { lumaGrid(image) } catch (e: Exception) { image.close(); return }
        val motion = diff(prevLuma, grid)
        prevLuma = grid
        val sceneDiff = diff(scanLuma, grid)

        if (motion > MOVE_THRESHOLD) {
            lastMoveAt = now
            if (!hiddenForMotion) { hiddenForMotion = true; runOnUiThread { overlay.fadeTo(0.15f) } }
        }
        val steady = now - lastMoveAt > STEADY_MS
        // 움직였다가 같은 장면으로 돌아오면 다시 보여 줘요.
        if (hiddenForMotion && steady && sceneDiff <= SCENE_THRESHOLD && lastScanAt > 0) {
            hiddenForMotion = false; runOnUiThread { overlay.fadeTo(1f) }
        }
        val deg = deviceDeg
        val sceneChanged = sceneDiff > SCENE_THRESHOLD || deg != lastScanDeg
        val due = lastScanAt == 0L || sceneChanged || now - lastScanAt > RECHECK_MS
        if (!steady || !due || !busy.compareAndSet(false, true)) { image.close(); return }

        lastScanAt = now
        scanLuma = grid
        val frame = try { upright(image.toBitmap(), image.imageInfo.rotationDegrees) } catch (e: Exception) {
            busy.set(false); return
        } finally { image.close() }
        val gen = generation
        val s = src
        lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.Default) {
                    // 폰을 돌려 든 만큼 장면을 돌려서 글자를 바로 세운 뒤 읽고, 상자는 화면 방향으로 되돌려요.
                    val ocrFrame = rotate(frame, deg)
                    scanner.scan(ocrFrame, s).map { sc ->
                        val p = mapBack(RectF(sc.box), deg, frame.width, frame.height)
                        val bgRect = Rect(p.left.toInt(), p.top.toInt(), p.right.toInt(), p.bottom.toInt()).apply { inset(-3, -3); intersect(0, 0, frame.width, frame.height) }
                        val bg = PhotoTranslator.edgeColor(frame, bgRect)
                        // 글자 모양(가운데·기울기)도 화면 방향으로 되돌려요: 각도는 폰을 돌린 만큼 빼요.
                        val c = mapBackPoint(sc.geo.cx, sc.geo.cy, deg, frame.width, frame.height)
                        val geo = sc.geo.copy(cx = c.first, cy = c.second, angle = sc.geo.angle - deg)
                        Found(p, normalize(sc.text), bg, deg, geo, PhotoTranslator.textColorIn(frame, bgRect, bg))
                    }
                }
                if (gen != generation || shooting) return@launch
                if (deg != lastScanDeg) { tracks.clear(); lastScanDeg = deg }
                reconcile(found, sceneChanged)
                overlay.show(tracks.toList(), frame.width, frame.height)
                hiddenForMotion = false
                overlay.fadeTo(1f)
                translatePending()
                status(if (found.isEmpty()) "글자를 화면 가운데에 비추고 잠시 멈춰 주세요" else "셔터를 누르면 번역본을 사진 앱에 저장해요")
            } catch (e: Exception) {
                // 한 장면 실패는 무시하고 다음 장면을 읽어요.
            } finally {
                busy.set(false)
            }
        }
    }

    private fun status(t: String) { if (t != lastStatus) { lastStatus = t; setStatus(t) } }

    /** 새로 읽은 글자 덩어리를 이전 것과 짝지어서, 같은 글자면 번역을 그대로 유지해요. */
    private fun reconcile(found: List<Found>, sceneChanged: Boolean) {
        val used = BooleanArray(tracks.size)
        val next = ArrayList<Track>()
        for (f in found) {
            var best = -1; var bestScore = 0f
            for (i in tracks.indices) {
                if (used[i]) continue
                val t = tracks[i]
                val overlap = iou(t.box, f.box)
                val sim = similarity(t.src, f.text)
                val ok = overlap > 0.3f || (sim > 0.8f && centerDistance(t.box, f.box) < max(f.box.height(), 40f) * 2)
                val score = overlap * 0.6f + sim * 0.4f
                if (ok && score > bestScore) { best = i; bestScore = score }
            }
            if (best >= 0) {
                used[best] = true
                val t = tracks[best]
                // 자리는 부드럽게 따라가고(떨림 줄이기), 장면이 바뀌었으면 바로 옮겨요.
                t.box = lerp(t.box, f.box, if (sceneChanged) 1f else 0.35f)
                t.bg = f.bg; t.fg = f.fg; t.missed = 0; t.deg = f.deg
                t.geo = if (sceneChanged || t.geo == null) f.geo else lerpGeo(t.geo!!, f.geo!!, 0.35f)
                // 글자가 조금 다르게 읽힌 정도(인식 흔들림)면 번역을 바꾸지 않아요.
                if (similarity(t.src, f.text) < 0.85f) { t.src = f.text; t.pending = true }
                next += t
            } else {
                next += Track(nextTrackId++, RectF(f.box), f.text, null, f.bg).also { it.deg = f.deg; it.geo = f.geo; it.fg = f.fg }
            }
        }
        // 이번에 못 찾은 것: 같은 장면이면 한 번은 그대로 둬요(깜빡임 방지).
        for (i in tracks.indices) {
            val t = tracks[i]
            if (!used[i] && !sceneChanged && t.missed < 1 && t.dst != null) { t.missed++; next += t }
        }
        tracks.clear(); tracks.addAll(next)
    }

    /** 번역이 필요한 칸만, 하나씩 차례로 번역해요. 번역 중에는 이전 번역을 계속 보여 줘요. */
    private fun translatePending() {
        if (translating) return
        val t = tracks.firstOrNull { it.pending } ?: return
        val cached = synchronized(cache) { cache[t.src] }
        if (cached != null) { apply(t, t.src, cached); translatePending(); return }
        translating = true
        val text = t.src; val s = src; val d = dst; val gen = generation
        lifecycleScope.launch {
            val r = engine.translate(PhotoTranslator.prepareForTranslation(text), s, d)
            translating = false
            if (gen != generation) return@launch
            when (r) {
                is TranslationResult.Success -> { synchronized(cache) { cache[text] = r.text }; apply(t, text, r.text) }
                is TranslationResult.Failure -> { t.pending = false; status("번역 모델을 받는 중이에요 (처음 한 번, 인터넷 필요)") }
            }
            translatePending()
        }
    }

    private fun apply(t: Track, text: String, translated: String) {
        if (t.src != text) return // 그사이 글자가 바뀌었으면 다음 차례에 다시
        t.pending = false
        if (t.dst != translated) {
            if (t.dst == null) t.shownAt = android.os.SystemClock.uptimeMillis()
            t.dst = translated
        }
        overlay.invalidate()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun shoot() {
        val cap = capture ?: return
        if (shooting) return
        shooting = true
        if (haptic) shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        shutter.alpha = 0.4f
        setStatus("찍는 중…")
        val deg = deviceDeg // 가로로 들고 찍었으면 사진도 가로로 바로 세워 저장해요.
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                lifecycleScope.launch {
                    val bmp = try {
                        withContext(Dispatchers.Default) { limit(rotate(upright(image.toBitmap(), image.imageInfo.rotationDegrees), deg), 2000) }
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
        /** 고를 수 있는 언어 (FTalk 화면과 같은 4개) */
        private val LANGS = listOf("ko", "vi", "en", "zh")

        /** 돌린 사진의 한 점을 돌리기 전 사진(w×h) 좌표로 */
        fun mapBackPoint(x: Float, y: Float, deg: Int, w: Int, h: Int): Pair<Float, Float> = when (deg) {
            90 -> y to (h - x)
            180 -> (w - x) to (h - y)
            270 -> (w - y) to x
            else -> x to y
        }

        fun lerpGeo(a: Geo, b: Geo, k: Float) = b.copy(
            cx = a.cx + (b.cx - a.cx) * k, cy = a.cy + (b.cy - a.cy) * k,
            w = a.w + (b.w - a.w) * k, h = a.h + (b.h - a.h) * k,
        )

        private const val GW = 40
        private const val GH = 30
        /** 프레임끼리 밝기 차이가 이보다 크면 "움직이는 중" */
        private const val MOVE_THRESHOLD = 7
        /** 마지막으로 읽은 장면과 차이가 이보다 크면 "다른 장면" */
        private const val SCENE_THRESHOLD = 14
        /** 이만큼 멈춰 있으면 읽기 시작 */
        private const val STEADY_MS = 400L
        /** 같은 장면은 이 간격으로만 다시 확인 */
        private const val RECHECK_MS = 1500L

        /** 인식 흔들림 줄이기: 앞뒤 공백, 여러 칸 공백 정리 */
        fun normalize(s: String) = s.trim().replace(Regex("\\s+"), " ")

        /** 두 글자가 얼마나 같은지 0~1 (편집 거리 기준) */
        fun similarity(a: String, b: String): Float {
            if (a == b) return 1f
            if (a.isEmpty() || b.isEmpty()) return 0f
            val x = a.lowercase().take(200); val y = b.lowercase().take(200)
            var prev = IntArray(y.length + 1) { it }
            for (i in 1..x.length) {
                val cur = IntArray(y.length + 1); cur[0] = i
                for (j in 1..y.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return 1f - prev[y.length].toFloat() / max(x.length, y.length)
        }

        fun iou(a: RectF, b: RectF): Float {
            val l = max(a.left, b.left); val t = max(a.top, b.top)
            val r = kotlin.math.min(a.right, b.right); val bt = kotlin.math.min(a.bottom, b.bottom)
            val inter = max(0f, r - l) * max(0f, bt - t)
            val union = a.width() * a.height() + b.width() * b.height() - inter
            return if (union <= 0f) 0f else inter / union
        }

        fun centerDistance(a: RectF, b: RectF): Float =
            kotlin.math.hypot(a.centerX() - b.centerX(), a.centerY() - b.centerY())

        fun lerp(a: RectF, b: RectF, k: Float) = RectF(
            a.left + (b.left - a.left) * k, a.top + (b.top - a.top) * k,
            a.right + (b.right - a.right) * k, a.bottom + (b.bottom - a.bottom) * k,
        )

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

        /** 시계 방향으로 deg 만큼 돌린 사진 */
        fun rotate(b: Bitmap, deg: Int): Bitmap {
            if (deg == 0) return b
            val m = Matrix().apply { postRotate(deg.toFloat()) }
            return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }

        /** 돌린 사진(시계 방향 deg)에서 찾은 상자를, 돌리기 전 사진(w×h) 좌표로 되돌려요. */
        fun mapBack(r: RectF, deg: Int, w: Int, h: Int): RectF = when (deg) {
            90 -> RectF(r.top, h - r.right, r.bottom, h - r.left)
            180 -> RectF(w - r.right, h - r.bottom, w - r.left, h - r.top)
            270 -> RectF(w - r.bottom, r.left, w - r.top, r.right)
            else -> RectF(r)
        }

        private fun limit(b: Bitmap, maxSide: Int): Bitmap {
            val big = max(b.width, b.height)
            if (big <= maxSide) return b
            return Bitmap.createScaledBitmap(b, b.width * maxSide / big, b.height * maxSide / big, true)
        }
    }
}

/** 한 번 읽은 글자 덩어리 (deg: 그때 폰을 돌려 든 각도) */
data class Found(val box: RectF, val text: String, val bg: Int, val deg: Int = 0, val geo: Geo? = null, val fg: Int = Color.WHITE)

/** 화면에 계속 붙어 있는 번역 칸 하나 (좌표는 분석한 장면 기준) */
class Track(val id: Int, var box: RectF, var src: String, var dst: String?, var bg: Int) {
    var pending = true
    var missed = 0
    var shownAt = 0L
    var deg = 0
    /** 원래 글자 모양(가운데·기울기·줄 높이) — Google 렌즈처럼 그 모양대로 그려요 */
    var geo: Geo? = null
    /** 원래 글자 색 */
    var fg: Int = Color.WHITE
}

/** 분석 장면 좌표를 화면 좌표로 바꿔서(FILL_CENTER) 번역 칸을 그려요. 새 칸은 0.25초 동안 서서히 나타나요. */
class OverlayView(context: Context) : View(context) {
    private var tracks: List<Track> = emptyList()
    private var imgW = 1
    private var imgH = 1
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(list: List<Track>, w: Int, h: Int) {
        tracks = list; imgW = max(1, w); imgH = max(1, h); invalidate()
    }

    /** 전체를 흐리게/선명하게 (움직일 때 숨기기) */
    fun fadeTo(target: Float) {
        if (kotlin.math.abs(alpha - target) < 0.01f) return
        animate().alpha(target).setDuration(if (target < alpha) 120L else 220L).start()
    }

    override fun onDraw(canvas: Canvas) {
        if (tracks.isEmpty()) return
        val scale = max(width.toFloat() / imgW, height.toFloat() / imgH)
        val dx = (width - imgW * scale) / 2f
        val dy = (height - imgH * scale) / 2f
        val now = android.os.SystemClock.uptimeMillis()
        var animating = false
        for (t in tracks) {
            val text = t.dst ?: continue
            val a = ((now - t.shownAt) / 250f).coerceIn(0f, 1f)
            if (a < 1f) animating = true
            val g = t.geo
            if (g != null) {
                // Google 렌즈 방식: 원래 글자의 기울기·줄 높이·글자 색 그대로
                PhotoTranslator.drawBlock(canvas, g.scaled(scale, dx, dy), text, t.bg, t.fg, a)
                continue
            }
            val r = RectF(t.box.left * scale + dx, t.box.top * scale + dy, t.box.right * scale + dx, t.box.bottom * scale + dy)
            r.inset(-4f, -4f)
            fill.color = (t.bg and 0x00FFFFFF) or ((0xF0 * a).toInt() shl 24)
            canvas.drawRoundRect(r, 8f, 8f, fill)
            val c = PhotoTranslator.textColorOn(t.bg)
            val color = (c and 0x00FFFFFF) or ((0xFF * a).toInt() shl 24)
            if (t.deg == 0) {
                PhotoTranslator.drawFitted(canvas, text, Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()), color)
            } else {
                // 폰을 돌려 든 사람이 바로 읽게 글자를 같이 돌려 그려요.
                val sideways = t.deg == 90 || t.deg == 270
                val tw = if (sideways) r.height() else r.width()
                val th = if (sideways) r.width() else r.height()
                canvas.save()
                canvas.rotate(-t.deg.toFloat(), r.centerX(), r.centerY())
                val box = Rect((r.centerX() - tw / 2).toInt(), (r.centerY() - th / 2).toInt(), (r.centerX() + tw / 2).toInt(), (r.centerY() + th / 2).toInt())
                PhotoTranslator.drawFitted(canvas, text, box, color)
                canvas.restore()
            }
        }
        if (animating) postInvalidateOnAnimation()
    }
}
