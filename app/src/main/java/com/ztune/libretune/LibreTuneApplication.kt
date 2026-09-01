package com.ztune.libretune

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt-enabled Application class for LibreTune.
 *
 * Annotating with [HiltAndroidApp] triggers Hilt's code generation,
 * including the singleton component that [di.AppModule] installs into.
 */
@HiltAndroidApp
class LibreTuneApplication : Application()
