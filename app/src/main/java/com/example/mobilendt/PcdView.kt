package com.example.mobilendt

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sqrt

/** Static 3D viewer for a loaded PCD. Double tap fits the whole cloud. */
class PcdView(context: Context) : OrbitGLView(context) {

    @Volatile private var data: PcdData? = null
    @Volatile private var dirty = false
    @Volatile var pointSize = 2.5f
    /** Point colouring: [Settings.COLOR_INTENSITY] or [Settings.COLOR_HEIGHT]. */
    @Volatile var colorMode = Settings.COLOR_INTENSITY

    init {
        setRenderer(R())
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setData(d: PcdData) {
        data = d
        dirty = true
        fit()
    }

    fun fit() {
        val d = data ?: return
        tx = (d.min[0] + d.max[0]) / 2
        ty = (d.min[1] + d.max[1]) / 2
        tz = (d.min[2] + d.max[2]) / 2
        val ex = d.max[0] - d.min[0]
        val ey = d.max[1] - d.min[1]
        val ez = d.max[2] - d.min[2]
        val r = max(0.5f, sqrt(ex * ex + ey * ey + ez * ez) / 2)
        dist = r * 1.9f
        maxDist = max(500f, r * 10)
        yaw = -90f
        pitch = 55f
        requestRender()
    }

    override fun onDoubleTap() = fit()

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val r = super.onTouchEvent(e)
        requestRender()
        return r
    }

    private inner class R : Renderer {
        private val gp = GlPoints()
        private lateinit var vbo: IntArray   // cloud, axes
        private var n = 0
        private var zLo = 0f
        private var zHi = 1f
        private var comps = 3
        private var iHi = 255f
        private val proj = FloatArray(16)
        private val mvp = FloatArray(16)
        private val axisBuf = GlPoints.floatBuffer(6 * 3)

        override fun onSurfaceCreated(gl: GL10?, cfg: EGLConfig?) {
            glClearColor(0.043f, 0.051f, 0.071f, 1f)
            glEnable(GL_DEPTH_TEST)
            gp.init()
            vbo = gp.genBuffers(2)
            dirty = true   // context (re)created: re-upload
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            glViewport(0, 0, w, h)
            Matrix.perspectiveM(proj, 0, 55f, w.toFloat() / h, 0.05f, 5000f)
            sharedProj = proj.copyOf()
        }

        override fun onDrawFrame(gl: GL10?) {
            val d = data
            if (dirty && d != null) {
                val inten = d.inten
                if (inten != null) {
                    // interleave x, y, z, intensity straight into the upload buffer
                    val buf = GlPoints.floatBuffer(d.n * 4)
                    for (i in 0 until d.n) {
                        buf.put(d.xyz, 3 * i, 3)
                        buf.put(inten[i])
                    }
                    buf.flip()
                    gp.uploadBuffer(vbo[0], buf, d.n, GL_STATIC_DRAW, comps = 4)
                    comps = 4
                } else {
                    val buf = GlPoints.floatBuffer(d.n * 3)
                    gp.upload(vbo[0], buf, d.xyz, d.n, GL_STATIC_DRAW)
                    comps = 3
                }
                n = d.n
                zLo = d.zLo
                zHi = d.zHi
                iHi = d.iHi
                val axes = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
                gp.upload(vbo[1], axisBuf, axes, 6)
                dirty = false
            }
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            if (n == 0) return
            computeMvp(proj, mvp)
            gp.begin(mvp, zLo, zHi)
            gp.setIntensityMax(iHi)
            gp.draw(vbo[0], GL_POINTS, n, if (comps == 4) colorMode else 0, pointSize, comps = comps)
            // origin axes (1 m): X red, Y green, Z blue
            glLineWidth(5f)
            gp.draw(vbo[1], GL_LINES, 2, 1, 1f, 1f, 0.3f, 0.35f, first = 0)
            gp.draw(vbo[1], GL_LINES, 2, 1, 1f, 0f, 0.8f, 0.45f, first = 2)
            gp.draw(vbo[1], GL_LINES, 2, 1, 1f, 0.25f, 0.55f, 1f, first = 4)
        }
    }
}
