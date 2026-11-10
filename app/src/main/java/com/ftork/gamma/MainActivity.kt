package com.ftork.gamma

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File

/**
 * 앱의 유일한 Activity.
 * 화면은 FTalk Beta 의 index.html 을 그대로 띄우고(WebView),
 * 음성 인식·번역·읽기·사진 번역은 [FriendlyBridge] 가 FTolk-G 엔진으로 처리해요.
 *
 * 다른 앱에서 들어오는 글도 받아요.
 *  - 공유 메뉴      : ACTION_SEND
 *  - 글자 선택 메뉴 : ACTION_PROCESS_TEXT
 *  - 딥링크         : ftorkgamma://translate?text=...
 */
class MainActivity : ComponentActivity() {

    lateinit var web: WebView
        private set
    private lateinit var bridge: FriendlyBridge
    private var pageReady = false
    private val pendingJs = ArrayList<String>()
    private var pendingIncoming: String? = null

    // ---- 권한 · 사진 ----
    private var micCallback: ((Boolean) -> Unit)? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micCallback?.invoke(granted); micCallback = null
    }
    private var captureUri: Uri? = null
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri
        if (ok && uri != null) bridge.onPhoto(uri, fromCamera = true) else bridge.onPhotoCancelled()
    }
    private val pickPicture = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) bridge.onPhoto(uri, fromCamera = false) else bridge.onPhotoCancelled()
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) launchCamera() else {
            bridge.onPhotoCancelled()
            callJs("friendlyNativeNotice", "카메라 권한이 없어요. 갤러리에서 골라 주세요.")
        }
    }

    /** 실시간 카메라 화면이 닫힐 때: 찍었으면 번역·저장, 아니면 화면에 알려요. */
    private val liveCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        val action = data?.getStringExtra("action") ?: "exit"
        val src = data?.getStringExtra("src")
        val dst = data?.getStringExtra("dst")
        when (action) {
            "shot" -> bridge.onLiveShot(src ?: "vi", dst ?: "ko")
            "gallery" -> { callJs("friendlyNativeLive", "gallery", src, dst); launchGallery() }
            else -> callJs("friendlyNativeLive", action, src, dst)
        }
    }

    fun launchLive(optionsJson: String) {
        liveCamera.launch(com.ftork.gamma.photo.LiveCameraActivity.intent(this, optionsJson))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#202124")
        window.navigationBarColor = Color.parseColor("#202124")

        bridge = FriendlyBridge(this)
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#202124"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(bridge, "FriendlyNative")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assets.shouldInterceptRequest(request.url)

                // 화면 안의 링크는 앱 밖(브라우저)으로 열어요.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == "appassets.androidplatform.net") return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
            }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#202124"))
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // Android 15 는 화면 끝까지 그려서, 상태 표시줄·키보드만큼 안쪽으로 밀어 줘요.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!pageReady) { finish(); return }
                web.evaluateJavascript("window.ftolkBack?window.ftolkBack():'exit'") { r ->
                    if (r == null || r.contains("exit")) finish()
                }
            }
        })

        if (savedInstanceState == null) handleIntent(intent)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        bridge.shutdown()
        web.destroy()
        super.onDestroy()
    }

    // ---- 화면(JS) 호출 ----

    /** 화면이 준비됐을 때 FriendlyBridge.pageReady() 가 불러요. */
    fun onPageReady() {
        pageReady = true
        pendingJs.forEach { web.evaluateJavascript(it, null) }
        pendingJs.clear()
        pendingIncoming?.let { showIncoming(it) }
        pendingIncoming = null
    }

    /** JS 함수 부르기. 값은 JSON 으로 안전하게 넣어요. (메인 스레드에서 부르세요) */
    fun callJs(function: String, vararg args: Any?) {
        val list = args.joinToString(",") { a ->
            when (a) {
                null -> "null"
                is Boolean, is Int, is Long, is Double -> a.toString()
                else -> JSONObject.quote(a.toString())
            }
        }
        val code = "try{window.$function&&window.$function($list)}catch(e){console.error(e)}"
        if (pageReady) web.evaluateJavascript(code, null) else pendingJs += code
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() else null
            Intent.ACTION_VIEW -> intent.data?.getQueryParameter("text")
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return
        if (pageReady) showIncoming(text) else pendingIncoming = text
    }

    private fun showIncoming(text: String) = callJs("ftolkIncoming", text, "partner")

    // ---- 권한 · 사진 · 화면 ----

    fun withMicPermission(then: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            then(true); return
        }
        micCallback = then
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun launchCamera() {
        // 카메라 권한을 선언한 앱은, 휴대폰 카메라 앱을 부를 때도 권한이 있어야 해요.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA); return
        }
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        captureUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: Exception) {
            bridge.onPhotoCancelled()
            callJs("friendlyNativeNotice", "카메라 앱을 열 수 없어요. 갤러리에서 골라 주세요.")
        }
    }

    fun launchGallery() {
        pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
