package com.ftork.gamma.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ftork.gamma.camera.CameraScreen
import com.ftork.gamma.live.LiveScreen

/** 화면 경로 */
object Routes {
    const val HOME = "home"
    const val LIVE = "live"
    const val CAMERA = "camera"
    const val HISTORY = "history"
    const val SAVED = "saved"
    const val OFFLINE = "offline"
}

/**
 * 화면 이동. 메인 화면에서 실시간 번역·카메라·기록·저장됨·언어 팩으로 들어가요.
 * (원본은 Activity 하나 + Fragment 여러 개, 여기서는 Activity 하나 + Compose 화면 여러 개)
 */
@Composable
fun AppNav(vm: TranslateViewModel, nav: NavHostController = rememberNavController()) {
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    fun back() { nav.popBackStack() }
    fun openHomeWith() { nav.popBackStack(Routes.HOME, inclusive = false) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        vm = vm,
                        onSaved = { nav.navigate(Routes.SAVED) },
                        onHistory = { nav.navigate(Routes.HISTORY) },
                        onOffline = { nav.navigate(Routes.OFFLINE) },
                        onLive = { nav.navigate(Routes.LIVE) },
                        onCamera = { nav.navigate(Routes.CAMERA) },
                    )
                }
                composable(Routes.LIVE) { LiveScreen(downloaded = downloaded, onClose = ::back) }
                composable(Routes.CAMERA) { CameraScreen(main = vm, onBack = ::back, onOpenText = ::openHomeWith) }
                composable(Routes.HISTORY) {
                    HistoryScreen(vm, savedOnly = false, onBack = ::back) { vm.openEntry(it); openHomeWith() }
                }
                composable(Routes.SAVED) {
                    HistoryScreen(vm, savedOnly = true, onBack = ::back) { vm.openEntry(it); openHomeWith() }
                }
                composable(Routes.OFFLINE) { OfflineScreen(vm, onBack = ::back) }
            }
        }
    }
}
