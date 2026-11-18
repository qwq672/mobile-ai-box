package com.aibox

import android.os.Bundle
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aibox.ui.nav.AppNav
import com.aibox.infra.startup.StartupGuard
import com.aibox.ui.theme.AIBoxThemeFromSettings

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AIBoxThemeFromSettings {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNav()
                }
            }
        }

        // 首帧回调：用于启动耗时统计与 watchdog 解除。
        Choreographer.getInstance().postFrameCallback {
            StartupGuard.onFirstFrame("main-activity")
        }
    }
}
