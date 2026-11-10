package com.ftork.gamma.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftork.gamma.data.Languages

/** 오프라인 언어 팩 관리 (원본: OfflineManagerActivity). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(vm: TranslateViewModel, onBack: () -> Unit) {
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val busy by vm.busyLanguages.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshDownloaded() }

    val sorted = Languages.all.sortedByDescending { it.code in downloaded }

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text("오프라인 언어 팩") },
            windowInsets = WindowInsets(0),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
        Text(
            "언어 팩을 받아 두면 인터넷 없이 번역할 수 있어요. 언어마다 약 30MB예요.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn {
            items(sorted, key = { it.code }) { lang ->
                val has = lang.code in downloaded
                ListItem(
                    headlineContent = { Text(lang.name) },
                    supportingContent = { Text(if (has) "받음 · 오프라인 사용 가능" else "받지 않음") },
                    trailingContent = {
                        when {
                            lang.code in busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            // 영어 팩은 ML Kit 에 기본으로 들어 있어서 지울 수 없어요.
                            has && lang.code == "en" -> {}
                            has -> IconButton(onClick = { vm.deleteLanguage(lang.code) }) {
                                Icon(Icons.Default.DeleteOutline, "${lang.name} 언어 팩 삭제")
                            }
                            else -> IconButton(onClick = { vm.downloadLanguage(lang.code) }) {
                                Icon(Icons.Default.Download, "${lang.name} 언어 팩 받기")
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
