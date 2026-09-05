package com.ztune.libretune.ui.screens.tune_editor.view3d

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phase C (3D): Heightmap surface renderer for table 3D visualization.
 *
 * Converts a 2D table (rows × cols of Double values) into a 3D mesh
 * where X = column index, Z = row index, Y = normalized value (height).
 * Renders with a heatmap gradient (blue→green→red) based on height.
 *
 * Camera: perspective projection with adjustable azimuth/elevation/zoom.
 * Gestures: pinch-to-zoom, two-finger rotate — handled by Table3DView composable.
 */
class HeightmapRenderer : GLSurfaceView.Renderer {

    private var rows: Int = 0
    private var cols: Int = 0
    private var vertices: FloatArray = FloatArray(0)
    private var indices: ShortArray = ShortArray(0)
    private var minVal: Double = 0.0
    private var maxVal: Double = 100.0

    // Camera
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    var azimuth: Float = 0.6f
    var elevation: Float = 0.5f
    var zoom: Float = 3.0f

    private var program = 0
    private var posHandle = 0
    private var heightHandle = 0
    private var mvpHandle = 0
    private var minHandle = 0
    private var maxHandle = 0

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec3 aPosition;
        varying float vHeight;
        uniform float uMin;
        uniform float uMax;
        void main() {
            vHeight = (aPosition.y - uMin) / max(uMax - uMin, 0.001);
            gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying float vHeight;
        void main() {
            float h = clamp(vHeight, 0.0, 1.0);
            vec3 color;
            if (h <= 0.5) {
                float t = h * 2.0;
                color = mix(vec3(0.0, 0.0, 1.0), vec3(0.0, 0.8, 0.0), t);
            } else {
                float t = (h - 0.5) * 2.0;
                color = mix(vec3(0.0, 0.8, 0.0), vec3(1.0, 0.0, 0.0), t);
            }
            gl_FragColor = vec4(color, 1.0);
        }
    """.trimIndent()

    fun setTable(values: List<List<Double>>, min: Double, max: Double) {
        rows = values.size
        if (rows == 0) { cols = 0; return }
        cols = values[0].size
        if (cols == 0) return
        minVal = min
        maxVal = max

        // Generate vertices: x = col/(cols-1) - 0.5, z = row/(rows-1) - 0.5, y = normalized value
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        vertices = FloatArray(rows * cols * 3)
        var vi = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val v = values.getOrNull(r)?.getOrNull(c) ?: 0.0
                vertices[vi++] = c.toFloat() / (cols - 1).coerceAtLeast(1) - 0.5f
                vertices[vi++] = ((v - min) / range).toFloat()
                vertices[vi++] = r.toFloat() / (rows - 1).coerceAtLeast(1) - 0.5f
            }
        }

        // Generate indices: 2 triangles per quad
        val numQuads = (rows - 1) * (cols - 1)
        indices = ShortArray(numQuads * 6)
        var ii = 0
        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                val tl = (r * cols + c).toShort()
                val tr = (r * cols + c + 1).toShort()
                val bl = ((r + 1) * cols + c).toShort()
                val br = ((r + 1) * cols + c + 1).toShort()
                indices[ii++] = tl; indices[ii++] = bl; indices[ii++] = tr
                indices[ii++] = tr; indices[ii++] = bl; indices[ii++] = br
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        heightHandle = GLES20.glGetAttribLocation(program, "aHeight") // not used directly, height is in vertex.y
        mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        minHandle = GLES20.glGetUniformLocation(program, "uMin")
        maxHandle = GLES20.glGetUniformLocation(program, "uMax")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, ratio, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Camera position from azimuth/elevation/zoom
        val cx = (zoom * cos(elevation) * sin(azimuth))
        val cy = (zoom * sin(elevation))
        val cz = (zoom * cos(elevation) * cos(azimuth))
        Matrix.setLookAtM(viewMatrix, 0, cx, cy, cz, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // Model matrix: scale Y to make height visible, center mesh
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.scaleM(modelMatrix, 0, 2f, 1.5f, 2f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        if (rows == 0 || cols == 0 || vertices.isEmpty()) return

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(minHandle, 0f)
        GLES20.glUniform1f(maxHandle, 1f)

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 12, vertices.toFloatBuffer())

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indices.size, GLES20.GL_UNSIGNED_SHORT, indices.toShortBuffer())

        GLES20.glDisableVertexAttribArray(posHandle)
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun FloatArray.toFloatBuffer(): java.nio.FloatBuffer {
        val bb = java.nio.ByteBuffer.allocateDirect(this.size * 4)
        bb.order(java.nio.ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(this)
        fb.position(0)
        return fb
    }

    private fun ShortArray.toShortBuffer(): java.nio.ShortBuffer {
        val bb = java.nio.ByteBuffer.allocateDirect(this.size * 2)
        bb.order(java.nio.ByteOrder.nativeOrder())
        val sb = bb.asShortBuffer()
        sb.put(this)
        sb.position(0)
        return sb
    }
}
