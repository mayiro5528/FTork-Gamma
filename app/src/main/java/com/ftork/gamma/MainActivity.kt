package com.ftork.gamma

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ftork.gamma.ui.AppNav
import com.ftork.gamma.ui.FTorkTheme
import com.ftork.gamma.ui.TranslateViewModel

/**
 * 앱의 유일한 Activity.
 * 다른 앱에서 들어오는 글도 여기서 받아요 (원본: TranslateActivity + TapToTranslateActivity).
 *  - 공유 메뉴      : ACTION_SEND, EXTRA_TEXT
 *  - 글자 선택 메뉴 : ACTION_PROCESS_TEXT, EXTRA_PROCESS_TEXT
 *  - 딥링크         : ftorkgamma://translate?text=...&to=ko
 */
class MainActivity : ComponentActivity() {

    private val vm: TranslateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            FTorkTheme {
                Surface(Modifier.fillMaxSize()) { AppNav(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.onIncoming(it) }

            Intent.ACTION_PROCESS_TEXT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let { vm.onIncoming(it) }
            }

            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                uri.getQueryParameter("text")?.let { vm.onIncoming(it, uri.getQueryParameter("to")) }
            }
        }
    }
}
