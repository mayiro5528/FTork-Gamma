package com.ftork.gamma.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** 동그란 버튼 + 아래 이름표 (메인 화면의 실시간 번역 / 마이크 / 카메라) */
@Composable
fun CircleAction(
    icon: ImageVector,
    label: String?,
    onClick: () -> Unit,
    size: Dp = 64.dp,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.42f))
            }
        }
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** 큰 언어 버튼 */
@Composable
fun LanguageButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
    }
}

fun Context.hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * 권한이 있으면 바로, 없으면 물어본 뒤 허락되면 action 을 실행해요.
 * 사용: val ask = rememberPermissionAction(RECORD_AUDIO, onDenied = {...}); ask { 시작() }
 */
@Composable
fun rememberPermissionAction(permission: String, onDenied: () -> Unit): ((() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending[0]
        pending[0] = null
        if (granted) action?.invoke() else onDenied()
    }
    return remember(permission) {
        { action: () -> Unit ->
            if (ctx.hasPermission(permission)) action()
            else {
                pending[0] = action
                launcher.launch(permission)
            }
        }
    }
}
