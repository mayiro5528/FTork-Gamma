package com.ftork.gamma.live

import android.Manifest
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ftork.gamma.data.Languages
import com.ftork.gamma.ui.LanguageButton
import com.ftork.gamma.ui.LanguagePickerSheet
import com.ftork.gamma.ui.rememberPermissionAction

/** 실시간 번역 (원본: openmic 패키지). 설정 화면 → 시작하면 모드별 대화 화면. */
@Composable
fun LiveScreen(downloaded: Set<String>, onClose: () -> Unit, vm: LiveViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    // 대화하는 동안 화면이 꺼지지 않게
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(s.running) {
        if (s.running) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler(enabled = s.running) { vm.stop() }

    if (!s.running) {
        LiveSetup(s, vm, downloaded, onClose)
    } else if (s.mode == LiveMode.FACE_TO_FACE) {
        FaceToFace(s, vm)
    } else {
        Transcript(s, vm)
    }
}

// ---------------------------------------------------------------- 설정

@Composable
private fun LiveSetup(s: LiveState, vm: LiveViewModel, downloaded: Set<String>, onClose: () -> Unit) {
    var picker by remember { mutableStateOf<String?>(null) } // "partner" | "mine"
    var micDenied by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(48.dp))
            Text("실시간 번역", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "닫기") }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    LanguageButton(Languages.nameOf(s.partner), { picker = "partner" }, Modifier.fillMaxWidth().height(56.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Icon(Icons.AutoMirrored.Filled.VolumeOff, null, Modifier.size(16.dp))
                        Text(" 상대방 언어", style = MaterialTheme.typography.labelLarge)
                    }
                }
                IconButton(onClick = vm::swapLanguages, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Default.SwapHoriz, "언어 바꾸기")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    LanguageButton(Languages.nameOf(s.mine), { picker = "mine" }, Modifier.fillMaxWidth().height(56.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Icon(Icons.Default.Smartphone, null, Modifier.size(16.dp))
                        Text(" 내 언어", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // 꾸밈 판: 여러 언어의 인사말
            Box(
                Modifier.fillMaxWidth().height(120.dp)
                    .background(
                        Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)),
                        RoundedCornerShape(48.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "안녕하세요 · Xin chào · Hello · こんにちは · 你好 · Hola · Bonjour",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(24.dp),
                )
            }

            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    LiveMode.entries.forEach { mode ->
                        val selected = mode == s.mode
                        Surface(
                            onClick = { vm.setMode(mode) },
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.Top) {
                                Icon(mode.icon, null, Modifier.padding(top = 2.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                    Text(mode.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(mode.description, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (selected) Icon(Icons.Default.Check, "선택됨")
                            }
                        }
                    }
                }
            }

            if (micDenied) Text("마이크 권한을 허용해야 실시간 번역을 쓸 수 있어요.", color = MaterialTheme.colorScheme.error)
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            s.status?.let { Text(it) }
            if (s.phase == LiveState.Phase.PREPARING) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        val askMicChecked = rememberPermissionAction(Manifest.permission.RECORD_AUDIO) { micDenied = true }
        Button(
            onClick = { micDenied = false; askMicChecked { vm.start() } },
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(64.dp),
            shape = RoundedCornerShape(32.dp),
        ) {
            Icon(Icons.Default.Mic, null)
            Text("  시작", style = MaterialTheme.typography.titleLarge)
        }
    }

    picker?.let { which ->
        LanguagePickerSheet(
            title = if (which == "partner") "상대방 언어" else "내 언어",
            selected = if (which == "partner") s.partner else s.mine,
            includeAuto = false,
            downloaded = downloaded,
            onPick = { code ->
                if (which == "partner") vm.setPartner(code) else vm.setMine(code)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
}

// ---------------------------------------------------------------- 듣기 / 대화 / 텍스트만

@Composable
private fun Transcript(s: LiveState, vm: LiveViewModel) {
    val list = rememberLazyListState()
    LaunchedEffect(s.turns.size, s.partial) {
        val last = s.turns.size + (if (s.partial.isNotBlank()) 1 else 0) - 1
        if (last >= 0) list.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::stop) { Icon(Icons.Default.Close, "끝내기") }
            Column(Modifier.weight(1f)) {
                Text(s.mode.title, style = MaterialTheme.typography.titleLarge)
                Text("${Languages.nameOf(s.partner)} ↔ ${Languages.nameOf(s.mine)}", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (s.mode == LiveMode.LISTEN) {
            Text(
                "휴대전화를 귀에 대세요. 상대방 말의 번역이 통화 스피커로 들려요.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            items(s.turns, key = { it.id }) { t -> Bubble(t) }
            if (s.partial.isNotBlank()) {
                item(key = "partial") {
                    Text(s.partial, style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = if (s.partnerTurn) TextAlign.Start else TextAlign.End,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }

        s.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        PhaseLine(s)

        if (s.mode == LiveMode.CONVERSATION) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpeakerButton(Languages.nameOf(s.partner), "상대방", s.partnerTurn, Modifier.weight(1f)) { vm.selectSpeaker(true) }
                SpeakerButton(Languages.nameOf(s.mine), "나", !s.partnerTurn, Modifier.weight(1f)) { vm.selectSpeaker(false) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = vm::finishUtterance,
                enabled = s.phase == LiveState.Phase.LISTENING,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("말 끝내기") }
            Button(
                onClick = vm::stop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Icon(Icons.Default.Stop, null); Text(" 멈추기") }
        }
    }
}

@Composable
private fun Bubble(t: Turn) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (t.fromPartner) Alignment.CenterStart else Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (t.fromPartner) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(t.translated, style = MaterialTheme.typography.titleLarge)
                Text(t.original, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PhaseLine(s: LiveState) {
    val who = if (s.partnerTurn) "상대방(${Languages.nameOf(s.partner)})" else "나(${Languages.nameOf(s.mine)})"
    val text = when (s.phase) {
        LiveState.Phase.LISTENING -> "$who 말을 듣는 중…"
        LiveState.Phase.TRANSLATING -> "번역 중…"
        LiveState.Phase.SPEAKING -> "번역을 읽는 중…"
        LiveState.Phase.PREPARING -> "준비 중…"
        LiveState.Phase.IDLE -> ""
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (s.phase == LiveState.Phase.LISTENING) {
            LinearProgressIndicator(
                progress = { (s.level.coerceIn(0f, 10f) / 10f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SpeakerButton(language: String, who: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.height(72.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Mic, null)
            Column(Modifier.padding(start = 8.dp)) {
                Text(language, style = MaterialTheme.typography.titleMedium)
                Text(who, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------------------------------------------------------- 실시간 대화 (마주 보기)

/** 원본의 OpenMicBattleshipModeFragment 처럼 화면을 반으로 나눠 위쪽을 뒤집어 보여 줘요. */
@Composable
private fun FaceToFace(s: LiveState, vm: LiveViewModel) {
    Column(Modifier.fillMaxSize()) {
        // 상대방 쪽 (180도 뒤집힘) — 모든 말을 상대방 언어로 보여 줘요.
        HalfPane(
            language = s.partner,
            lines = s.turns.map { if (it.fromPartner) it.original else it.translated },
            partial = if (s.partnerTurn) s.partial else "",
            active = s.partnerTurn,
            listening = s.partnerTurn && s.phase == LiveState.Phase.LISTENING,
            onMic = { vm.selectSpeaker(true) },
            modifier = Modifier.weight(1f).rotate(180f),
        )
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (s.phase) {
                    LiveState.Phase.TRANSLATING -> "번역 중…"
                    LiveState.Phase.SPEAKING -> "읽는 중…"
                    else -> s.status ?: s.error ?: ""
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::stop) { Icon(Icons.Default.Close, "끝내기") }
        }
        // 내 쪽 — 모든 말을 내 언어로 보여 줘요.
        HalfPane(
            language = s.mine,
            lines = s.turns.map { if (it.fromPartner) it.translated else it.original },
            partial = if (!s.partnerTurn) s.partial else "",
            active = !s.partnerTurn,
            listening = !s.partnerTurn && s.phase == LiveState.Phase.LISTENING,
            onMic = { vm.selectSpeaker(false) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HalfPane(
    language: String,
    lines: List<String>,
    partial: String,
    active: Boolean,
    listening: Boolean,
    onMic: () -> Unit,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        Text(Languages.nameOf(language), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(lines) { Text(it, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 4.dp)) }
            if (partial.isNotBlank()) item {
                Text(partial, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                onClick = onMic,
                shape = RoundedCornerShape(50),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Mic, if (listening) "듣는 중" else "말하기", Modifier.size(32.dp))
                }
            }
        }
    }
}
