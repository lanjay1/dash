package com.ztune.libretune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ztune.libretune.core.AppSettings
import com.ztune.libretune.core.ThemeMode
import com.ztune.libretune.ui.navigation.LibreTuneApp
import com.ztune.libretune.ui.theme.LibreTuneTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-Activity entry point for LibreTune.
 *
 * Annotated with [AndroidEntryPoint] so Hilt can inject dependencies into
 * this Activity and any Compose ViewModels that use `@HiltViewModel`.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by settings.themeMode.collectAsState()

            LibreTuneTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            ) {
                LibreTuneApp()
            }
        }
    }
}
