package com.ztune.libretune.ui.screens.tune_editor.view3d

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose wrapper for GLSurfaceView rendering the 3D heightmap.
 *
 * Usage:
 * ```kotlin
 * Table3DView(values = state.values, min = state.min, max = state.max)
 * ```
 *
 * Gestures (pinch-zoom, rotate) are handled inside the GLSurfaceView
 * via onTouchEvent in the wrapper.
 */
@Composable
fun Table3DView(
    values: List<List<Double>>,
    min: Double,
    max: Double,
    modifier: Modifier = Modifier
) {
    val renderer = remember { HeightmapRenderer() }

    AndroidView(
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                setOnTouchListener { v, event ->
                    // Simple gesture: 1-finger drag = rotate, 2-finger pinch = zoom
                    when (event.pointerCount) {
                        1 -> {
                            if (event.action == android.view.MotionEvent.ACTION_MOVE) {
                                val dx = event.x - event.historyX
                                val dy = event.y - event.historyY
                                renderer.azimuth += dx * 0.01f
                                renderer.elevation = (renderer.elevation - dy * 0.01f).coerceIn(-1.2f, 1.2f)
                                requestRender()
                            }
                        }
                        2 -> {
                            if (event.action == android.view.MotionEvent.ACTION_MOVE) {
                                val dist = android.graphics.PointF.length(
                                    event.getX(0) - event.getX(1),
                                    event.getY(0) - event.getY(1)
                                )
                                val prevDist = android.graphics.PointF.length(
                                    event.getHistoricalX(0, 0) - event.getHistoricalX(1, 0),
                                    event.getHistoricalY(0, 0) - event.getHistoricalY(1, 0)
                                )
                                if (prevDist > 0) {
                                    renderer.zoom = (renderer.zoom * prevDist / dist).coerceIn(1f, 10f)
                                    requestRender()
                                }
                            }
                        }
                    }
                    true
                }
            }
        },
        update = { glView ->
            renderer.setTable(values, min, max)
            glView.requestRender()
        },
        modifier = modifier.fillMaxSize()
    )
}
