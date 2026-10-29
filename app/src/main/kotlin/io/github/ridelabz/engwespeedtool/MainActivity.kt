package io.github.ridelabz.engwespeedtool

import android.graphics.Color
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ridelabz.engwespeedtool.ui.SpeedToolApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Debug builds log what the main thread shouldn't do (disk, network, slow calls and others) and leaked
        // resources, so that a wait that could block the screen shows up while developing. Built on the
        // platform's own policies, so nothing it enforces is relaxed.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder(StrictMode.getThreadPolicy())
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy
                    .Builder(StrictMode.getVmPolicy())
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build(),
            )
        }
        if (TestBuildLabel.current == TestBuildLabel.None) {
            enableEdgeToEdge()
        } else {
            // Dark status-bar icons, readable on the amber test-build banner in light and dark mode.
            enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        }
        super.onCreate(savedInstanceState)
        setContent { TestBuildFrame { SpeedToolApp() } }
    }
}
