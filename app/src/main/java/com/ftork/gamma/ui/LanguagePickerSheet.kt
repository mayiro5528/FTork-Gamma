package com.ftork.gamma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ftork.gamma.data.Language
import com.ftork.gamma.data.Languages

/** 언어 고르기 (원본: LanguagePickerActivity). 받아 둔 언어 팩은 아이콘으로 표시해요. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerSheet(
    title: String,
    selected: String,
    includeAuto: Boolean,
    downloaded: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val items = remember(query, includeAuto) {
        val base = if (includeAuto) listOf(Language(Languages.AUTO, Languages.nameOf(Languages.AUTO))) + Languages.all
        else Languages.all
        if (query.isBlank()) base
        else base.filter { it.name.contains(query, ignoreCase = true) || it.code.startsWith(query, ignoreCase = true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("언어 검색") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn {
                items(items, key = { it.code }) { lang ->
                    ListItem(
                        headlineContent = { Text(lang.name) },
                        supportingContent = { if (lang.code != Languages.AUTO) Text(lang.code) },
                        leadingContent = {
                            when {
                                lang.code == selected -> Icon(Icons.Default.Check, "선택됨", tint = MaterialTheme.colorScheme.primary)
                                lang.code == Languages.AUTO -> Icon(Icons.Default.AutoAwesome, null)
                            }
                        },
                        trailingContent = {
                            if (lang.code in downloaded) Icon(Icons.Default.DownloadDone, "언어 팩 있음")
                        },
                        modifier = Modifier.clickable { onPick(lang.code) },
                    )
                }
            }
        }
    }
}
