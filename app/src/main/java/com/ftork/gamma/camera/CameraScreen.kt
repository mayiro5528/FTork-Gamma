package com.ftork.gamma.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ftork.gamma.data.Languages
import com.ftork.gamma.ui.LanguageButton
import com.ftork.gamma.ui.LanguagePickerSheet
import com.ftork.gamma.ui.TranslateViewModel
import com.ftork.gamma.ui.hasPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 카메라 번역: 찍거나(갤러리에서 고른) 사진의 글자를 찾아 그 자리에 번역을 덮어 보여 줘요.
 * 언어 설정은 메인 화면(TranslateViewModel)과 같이 써요.
 */
@Composable
fun CameraScreen(main: TranslateViewModel, onBack: () -> Unit, onOpenText: () -> Unit, vm: CameraViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val lang by main.state.collectAsStateWithLifecycle()
    val downloaded by main.downloaded.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<String?>(null) }

    var camGranted by remember { mutableStateOf(ctx.hasPermission(Manifest.permission.CAMERA)) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camGranted = it }
    LaunchedEffect(Unit) { if (!camGranted) askCamera.launch(Manifest.permission.CAMERA) }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { loadBitmap(ctx, uri) }.getOrNull() }
            if (bmp == null) vm.setError("사진을 열지 못했어요.") else vm.process(bmp, lang.source, lang.target)
        }
    }

    // 언어를 바꾸면 이미 찍은 사진을 다시 번역해요.
    LaunchedEffect(lang.source, lang.target) {
        s.photo?.let { vm.process(it, lang.source, lang.target) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- 위: 뒤로 + 언어 ----
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            LanguageButton(
                if (lang.source == Languages.AUTO && s.sourceUsed != null) "${Languages.nameOf(s.sourceUsed!!)} (감지)"
                else Languages.nameOf(lang.source),
                { picker = "source" }, Modifier.weight(1f),
            )
            IconButton(onClick = main::swap, enabled = lang.source != Languages.AUTO) { Icon(Icons.Default.SwapHoriz, "언어 바꾸기") }
            LanguageButton(Languages.nameOf(lang.target), { picker = "target" }, Modifier.weight(1f))
        }

        // ---- 가운데: 미리보기 또는 결과 ----
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val photo = s.photo
            when {
                photo != null -> TranslatedPhoto(photo, s.blocks, s.showOriginal)
                camGranted -> CameraPreview(imageCapture, Modifier.fillMaxSize())
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("카메라 권한을 허용하면 바로 찍어서 번역할 수 있어요.", color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }) { Text("카메라 권한 허용") }
                    Text("갤러리 사진은 권한 없이 쓸 수 있어요.", color = Color.LightGray, modifier = Modifier.padding(top = 12.dp))
                }
            }
            if (s.busy) {
                Column(
                    Modifier.background(Color(0xAA000000), MaterialTheme.shapes.large).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    s.status?.let { Text(it, color = Color.White, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            s.error?.let {
                Text(
                    it, color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                        .background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.medium).padding(12.dp),
                )
            }
        }

        // ---- 아래: 버튼 ----
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (s.photo == null) {
                IconButton(onClick = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Icon(Icons.Default.PhotoLibrary, "갤러리에서 고르기", Modifier.size(32.dp)) }
                IconButton(
                    onClick = { capture(ctx, imageCapture, onPhoto = { vm.process(it, lang.source, lang.target) }, onError = vm::setError) },
                    enabled = camGranted && !s.busy,
                    modifier = Modifier.size(84.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge),
                ) { Icon(Icons.Default.Camera, "찍어서 번역", Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                Spacer(Modifier.size(48.dp))
            } else {
                IconButton(onClick = vm::retake) { Icon(Icons.Default.Refresh, "다시 찍기", Modifier.size(32.dp)) }
                IconButton(onClick = vm::toggleOriginal, enabled = s.blocks.isNotEmpty()) {
                    Icon(if (s.showOriginal) Icons.Default.Translate else Icons.AutoMirrored.Filled.Subject,
                        if (s.showOriginal) "번역 보기" else "원문 보기", Modifier.size(32.dp))
                }
                IconButton(
                    onClick = {
                        val all = s.blocks.joinToString("\n") { it.original }
                        main.onIncoming(all, source = s.sourceUsed ?: lang.source)
                        onOpenText()
                    },
                    enabled = s.blocks.isNotEmpty(),
                ) { Icon(Icons.Default.Translate, "글자를 번역 화면으로 보내기", Modifier.size(32.dp)) }
            }
        }
    }

    picker?.let { which ->
        LanguagePickerSheet(
            title = if (which == "source") "사진 속 언어" else "번역할 언어",
            selected = if (which == "source") lang.source else lang.target,
            includeAuto = which == "source",
            downloaded = downloaded,
            onPick = { code -> if (which == "source") main.setSource(code) else main.setTarget(code); picker = null },
            onDismiss = { picker = null },
        )
    }
}

/** CameraX 미리보기 */
@Composable
private fun CameraPreview(imageCapture: ImageCapture, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    AndroidView(factory = { view }, modifier = modifier)
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get().also { p ->
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                p.unbindAll()
                runCatching { p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture) }
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { provider?.unbindAll() }
    }
}

private fun capture(ctx: Context, imageCapture: ImageCapture, onPhoto: (Bitmap) -> Unit, onError: (String) -> Unit) {
    imageCapture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val rotation = image.imageInfo.rotationDegrees
            val bmp = runCatching { image.toBitmap() }.getOrNull()
            image.close()
            if (bmp == null) onError("사진을 처리하지 못했어요.") else onPhoto(bmp.rotated(rotation).limited(2000))
        }
        override fun onError(exception: ImageCaptureException) = onError("사진을 찍지 못했어요: ${exception.message}")
    })
}

/** 사진을 화면에 맞춰 그리고, 글자 자리마다 번역을 덮어 그려요. */
@Composable
private fun TranslatedPhoto(photo: Bitmap, blocks: List<TranslatedBlock>, showOriginal: Boolean) {
    val image = remember(photo) { photo.asImageBitmap() }
    val measurer = rememberTextMeasurer()
    val boxColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    val textColor = MaterialTheme.colorScheme.onSurface

    Canvas(Modifier.fillMaxSize()) {
        val scale = minOf(size.width / photo.width, size.height / photo.height)
        val dw = photo.width * scale
        val dh = photo.height * scale
        val left = (size.width - dw) / 2
        val top = (size.height - dh) / 2
        drawImage(image, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
        if (showOriginal) return@Canvas

        for (b in blocks) {
            val x = left + b.box.left * scale
            val y = top + b.box.top * scale
            val w = (b.box.width() * scale).coerceAtLeast(8f)
            val h = (b.box.height() * scale).coerceAtLeast(8f)
            drawRect(boxColor, Offset(x, y), Size(w, h))

            // 상자 안에 들어갈 때까지 글자 크기를 줄여요.
            var px = h.coerceAtMost(72f)
            var layout = measurer.measure(b.translated, TextStyle(color = textColor, fontSize = px.toSp()),
                constraints = Constraints(maxWidth = w.toInt()))
            var tries = 0
            while (layout.size.height > h && px > 8f && tries < 12) {
                px *= 0.85f
                layout = measurer.measure(b.translated, TextStyle(color = textColor, fontSize = px.toSp()),
                    constraints = Constraints(maxWidth = w.toInt()))
                tries++
            }
            drawText(layout, topLeft = Offset(x, y))
        }
    }
}

// ---------------------------------------------------------------- 그림 도우미

private fun Bitmap.rotated(degrees: Int): Bitmap =
    if (degrees == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

/** 긴 변이 max 를 넘으면 줄여요. (글자 인식이 빨라지고 메모리를 아껴요) */
private fun Bitmap.limited(max: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= max) return this
    val r = max.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * r).toInt(), (height * r).toInt(), true)
}

private fun loadBitmap(ctx: Context, uri: Uri): Bitmap {
    val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 2000) decoder.setTargetSampleSize(longest / 2000)
        }
    } else {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("decode failed")
    }
    return bmp.limited(2000)
}
