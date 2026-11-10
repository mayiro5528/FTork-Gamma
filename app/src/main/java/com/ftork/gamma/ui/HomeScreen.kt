package com.ftork.gamma.ui

import android.Manifest
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftork.gamma.BuildConfig
import com.ftork.gamma.data.Languages

/**
 * 메인 화면 (원본: HomeFragmentV2 + TextTranslationFragmentV2).
 * 위: 큰 입력·결과 판 / 아래: 언어 버튼, 실시간 번역 · 마이크 · 카메라.
 */
@Composable
fun HomeScreen(
    vm: TranslateViewModel,
    onSaved: () -> Unit,
    onHistory: () -> Unit,
    onOffline: () -> Unit,
    onLive: () -> Unit,
    onCamera: () -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var picker by remember { mutableStateOf<String?>(null) } // "source" | "target"
    var menu by remember { mutableStateOf(false) }

    val isSaved = s.currentId?.let { id -> history.firstOrNull { it.id == id }?.saved } == true
    val spokenSource = if (s.source == Languages.AUTO) s.detected else s.source
    val askMic = rememberPermissionAction(Manifest.permission.RECORD_AUDIO) {
        vm.showMessage("마이크 권한을 허용해야 음성으로 입력할 수 있어요.")
    }

    Column(Modifier.fillMaxSize()) {
        // ---- 위쪽 판: 제목 줄 + 입력 + 결과 ----
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSaved) { Icon(Icons.Default.Star, "저장됨") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("FTork-Gamma", style = MaterialTheme.typography.titleLarge)
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "메뉴") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("기록") },
                                leadingIcon = { Icon(Icons.Default.History, null) },
                                onClick = { menu = false; onHistory() },
                            )
                            DropdownMenuItem(
                                text = { Text("오프라인 언어 팩") },
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = { menu = false; onOffline() },
                            )
                        }
                    }
                }

                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                ) {
                    TextField(
                        value = s.input,
                        onValueChange = vm::onInput,
                        placeholder = {
                            Text(
                                if (s.listening) "말씀하세요…" else "번역할 텍스트를 입력하세요",
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        },
                        textStyle = MaterialTheme.typography.headlineMedium,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.input.isNotBlank()) {
                            IconButton(
                                onClick = { spokenSource?.let { vm.speak(s.input, it) } },
                                enabled = spokenSource != null,
                            ) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "원문 듣기") }
                        } else {
                            IconButton(onClick = {
                                clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let(vm::onInput)
                            }) { Icon(Icons.Default.ContentPaste, "붙여넣기") }
                        }
                        Spacer(Modifier.weight(1f))
                        if (s.input.isNotEmpty()) {
                            IconButton(onClick = vm::clearInput) { Icon(Icons.Default.Close, "지우기") }
                        }
                    }

                    if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    s.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    if (s.output.isNotBlank()) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(
                            Languages.nameOf(s.target),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                        SelectionContainer {
                            Text(
                                s.output,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                        Row {
                            IconButton(onClick = { vm.speak(s.output, s.target) }) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, "번역 듣기")
                            }
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(s.output))
                                vm.showMessage("복사했어요.")
                            }) { Icon(Icons.Default.ContentCopy, "복사") }
                            IconButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, s.output)
                                ctx.startActivity(Intent.createChooser(send, "번역 공유"))
                            }) { Icon(Icons.Default.Share, "공유") }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { s.currentId?.let(vm::toggleSaved) }, enabled = s.currentId != null) {
                                Icon(
                                    if (isSaved) Icons.Default.Star else Icons.Default.StarBorder,
                                    if (isSaved) "저장 취소" else "저장",
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        // ---- 아래: 언어 버튼 ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LanguageButton(
                label = if (s.source == Languages.AUTO && s.detected != null)
                    "${Languages.nameOf(s.detected!!)} (감지)" else Languages.nameOf(s.source),
                onClick = { picker = "source" },
                modifier = Modifier.weight(1f).height(56.dp),
            )
            IconButton(onClick = vm::swap, enabled = s.source != Languages.AUTO || s.detected != null) {
                Icon(Icons.Default.SwapHoriz, "언어 바꾸기")
            }
            LanguageButton(
                label = Languages.nameOf(s.target),
                onClick = { picker = "target" },
                modifier = Modifier.weight(1f).height(56.dp),
            )
        }

        // ---- 아래: 실시간 번역 · 마이크 · 카메라 ----
        val pulse by animateFloatAsState(
            targetValue = if (s.listening) 1f + (s.micLevel.coerceIn(0f, 10f) / 40f) else 1f,
            label = "mic",
        )
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAction(Icons.Default.RecordVoiceOver, "실시간 번역", onLive)
            CircleAction(
                icon = if (s.listening) Icons.Default.Stop else Icons.Default.Mic,
                label = null,
                onClick = { askMic { vm.toggleDictation() } },
                size = 96.dp,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.scale(pulse),
            )
            CircleAction(Icons.Default.PhotoCamera, "카메라", onCamera)
        }
    }

    picker?.let { which ->
        LanguagePickerSheet(
            title = if (which == "source") "출발 언어" else "도착 언어",
            selected = if (which == "source") s.source else s.target,
            includeAuto = which == "source",
            downloaded = downloaded,
            onPick = { code ->
                if (which == "source") vm.setSource(code) else vm.setTarget(code)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
}
