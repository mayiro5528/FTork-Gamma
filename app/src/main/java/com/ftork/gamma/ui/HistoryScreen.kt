package com.ftork.gamma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftork.gamma.data.HistoryEntry
import com.ftork.gamma.data.Languages

/**
 * 기록 화면과 저장됨 화면을 함께 그려요.
 * savedOnly=false → 기록 (원본: HistoryFragment)
 * savedOnly=true  → 저장됨 (원본: PhrasebookFragment)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: TranslateViewModel, savedOnly: Boolean, onBack: () -> Unit, onOpen: (HistoryEntry) -> Unit) {
    val all by vm.history.collectAsStateWithLifecycle()
    val list = if (savedOnly) all.filter { it.saved } else all
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text(if (savedOnly) "저장됨" else "기록") },
            windowInsets = WindowInsets(0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "뒤로")
                }
            },
            actions = {
                if (!savedOnly && all.any { !it.saved }) {
                    IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, "기록 모두 지우기") }
                }
            },
        )
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (savedOnly) "번역 결과의 ☆ 를 누르면 여기에 저장돼요." else "아직 번역 기록이 없어요.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn {
                items(list, key = { it.id }) { e ->
                    ListItem(
                        overlineContent = { Text("${Languages.nameOf(e.source)} → ${Languages.nameOf(e.target)}") },
                        headlineContent = { Text(e.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(e.result, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Column {
                                IconButton(onClick = { vm.toggleSaved(e.id) }) {
                                    Icon(if (e.saved) Icons.Default.Star else Icons.Default.StarBorder,
                                        if (e.saved) "저장 취소" else "저장")
                                }
                                if (!savedOnly) {
                                    IconButton(onClick = { vm.deleteEntry(e.id) }) {
                                        Icon(Icons.Default.DeleteOutline, "삭제")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpen(e) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("기록을 모두 지울까요?") },
            text = { Text("별표로 저장한 항목은 남아요.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("지우기") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
        )
    }
}
