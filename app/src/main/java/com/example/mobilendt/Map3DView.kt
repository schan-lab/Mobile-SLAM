package com.example.mobilendt

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** Live 3D view of the SLAM map (or raw sensor points while no map exists). */
class Map3DView(
    context: Context,
    private val slam: SlamNative,
    private val live: PointBuffer,
) : OrbitGLView(context) {

    @Volatile private var follow = true
    /** Point colouring: [Settings.COLOR_INTENSITY] or [Settings.COLOR_HEIGHT]. */
    @Volatile var colorMode = Settings.COLOR_INTENSITY

    init {
        setRenderer(R())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onUserPan() { follow = false }
    override fun onDoubleTap() { if (!pickMode) { yaw = -90f; pitch = 50f; dist = 12f; follow = true } }

    // ---------------- initial pose picking (touch position, drag heading) ----------------
    /** Current initial guess to draw: x, y, yaw (rad) or NaN yaw = unknown heading. */
    @Volatile var guess: FloatArray? = null
    /** Called with x, y, yaw (NaN if the user did not drag a heading). */
    var onPosePicked: ((Float, Float, Float) -> Unit)? = null
    @Volatile var pickMode = false
        set(v) {
            field = v
            if (v) { follow = false; pitch = 89.9f }
        }
    @Volatile private var dragFrom: FloatArray? = null
    @Volatile private var dragTo: FloatArray? = null

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!pickMode) return super.onTouchEvent(e)
        if (e.pointerCount > 1) { dragFrom = null; dragTo = null; return super.onTouchEvent(e) }
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragFrom = screenToGround(e.x, e.y, 0f); dragTo = null
            }
            android.view.MotionEvent.ACTION_MOVE -> if (dragFrom != null) dragTo = screenToGround(e.x, e.y, 0f)
            android.view.MotionEvent.ACTION_UP -> {
                val a = dragFrom; val b = dragTo
                if (a != null) {
                    val dx = (b?.get(0) ?: a[0]) - a[0]; val dy = (b?.get(1) ?: a[1]) - a[1]
                    // heading only if the drag is long enough on screen (~5% of the visible extent)
                    val yaw = if (kotlin.math.hypot(dx, dy) > dist * 0.05f) kotlin.math.atan2(dy, dx) else Float.NaN
                    guess = floatArrayOf(a[0], a[1], yaw)
                    onPosePicked?.let { cb -> post { cb(a[0], a[1], yaw) } }
                }
                dragFrom = null; dragTo = null
            }
        }
        return true
    }

    private inner class R : Renderer {
        private val gp = GlPoints()
        private lateinit var vbo: IntArray  // map, scan, traj, pose, live

        private val maxMap = 800_000
        private val mapArr = FloatArray(maxMap * 4)          // x, y, z, intensity
        private val mapBuf = GlPoints.floatBuffer(maxMap * 4)
        private var mapN = 0
        private val scanArr = FloatArray(30_000 * 3)
        private val scanBuf = GlPoints.floatBuffer(30_000 * 3)
        private val trajArr = FloatArray(50_000 * 3)
        private val trajBuf = GlPoints.floatBuffer(50_000 * 3)
        private val poseBuf = GlPoints.floatBuffer(4 * 3)
        private val guessBuf = GlPoints.floatBuffer(8 * 3)
        private val st = DoubleArray(SlamNative.S.SIZE)
        private val liveArr = FloatArray(live.capacity * 4)
        private val liveBuf = GlPoints.floatBuffer(live.capacity * 4)
        private var lastPull = 0L

        private val proj = FloatArray(16); private val mvp = FloatArray(16)

        override fun onSurfaceCreated(gl: GL10?, cfg: EGLConfig?) {
            glClearColor(0.043f, 0.051f, 0.071f, 1f)
            glEnable(GL_DEPTH_TEST)
            gp.init()
            vbo = gp.genBuffers(6)
            lastPull = 0
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            glViewport(0, 0, w, h)
            Matrix.perspectiveM(proj, 0, 60f, w.toFloat() / h, 0.05f, 1000f)
            sharedProj = proj.copyOf()
        }

        override fun onDrawFrame(gl: GL10?) {
            slam.status(st)
            val px = st[SlamNative.S.PX].toFloat(); val py = st[SlamNative.S.PY].toFloat()
            val pz = st[SlamNative.S.PZ].toFloat(); val pyaw = st[SlamNative.S.YAW].toFloat()
            // Live mode: SLAM not producing a map (idle, or still initialising) -> show raw sensor points.
            val slamRunning = st[SlamNative.S.RUNNING] > 0.5
            val liveMode = (slamRunning && st[SlamNative.S.STATUS].toInt() != 2) ||
                (!slamRunning && st[SlamNative.S.VIS_SIZE] <= 0.0)
            if (follow) {
                if (liveMode) { tx = 0f; ty = 0f; tz = 0f } else { tx = px; ty = py; tz = pz }
            }

            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            computeMvp(proj, mvp)

            if (liveMode) {
                val liveN = live.snapshot(liveArr)
                gp.upload(vbo[4], liveBuf, liveArr, liveN, comps = 4)
                gp.begin(mvp, -1.0f, 2.5f)
                gp.draw(vbo[4], GL_POINTS, liveN, colorMode, 3f, comps = 4)
                return
            }

            val now = System.currentTimeMillis()
            if (now - lastPull > 500) {
                mapN = slam.map(mapArr, maxMap)
                gp.upload(vbo[0], mapBuf, mapArr, mapN, comps = 4)
                lastPull = now
            }
            val scanN = slam.scan(scanArr, 30_000)
            gp.upload(vbo[1], scanBuf, scanArr, scanN)
            val trajN = slam.trajectory(trajArr, 50_000)
            gp.upload(vbo[2], trajBuf, trajArr, trajN)
            // pose marker: small triangle in the XY plane pointing along heading
            val c = cos(pyaw); val s = sin(pyaw); val L = 0.4f
            val pose = floatArrayOf(
                px + c * L, py + s * L, pz,
                px - c * L * 0.5f - s * L * 0.5f, py - s * L * 0.5f + c * L * 0.5f, pz,
                px - c * L * 0.5f + s * L * 0.5f, py - s * L * 0.5f - c * L * 0.5f, pz)
            gp.upload(vbo[3], poseBuf, pose, 3)

            gp.begin(mvp, pz - 1.5f, pz + 2.0f)
            gp.draw(vbo[0], GL_POINTS, mapN, colorMode, 3f, comps = 4)    // map: intensity / height colour
            gp.draw(vbo[1], GL_POINTS, scanN, 1, 4f, 1f, 1f, 1f)          // current scan: white
            glLineWidth(4f)
            gp.draw(vbo[2], GL_LINE_STRIP, trajN, 1, 1f, 1f, 0.2f, 1f)    // trajectory: magenta
            glDisable(GL_DEPTH_TEST)
            gp.draw(vbo[3], GL_TRIANGLES, 3, 1, 1f, 1f, 0.1f, 0.1f)       // pose: red
            drawGuess()
            glEnable(GL_DEPTH_TEST)
        }

        /** Initial-pose marker (orange): arrow if heading known, else a cross; plus the drag line. */
        private fun drawGuess() {
            glDisable(GL_DEPTH_TEST)
            val a = dragFrom; val b = dragTo
            val g = if (a != null) floatArrayOf(a[0], a[1],
                if (b != null && kotlin.math.hypot(b[0] - a[0], b[1] - a[1]) > dist * 0.05f)
                    kotlin.math.atan2(b[1] - a[1], b[0] - a[0]) else Float.NaN) else guess
            if (g == null) return
            val x = g[0]; val y = g[1]; val L = maxOf(0.35f, dist * 0.04f)
            val v: FloatArray
            val prim: Int
            if (g[2].isNaN()) {
                v = floatArrayOf(x - L, y, 0f, x + L, y, 0f, x, y - L, 0f, x, y + L, 0f)
                prim = GL_LINES
            } else {
                val c = cos(g[2]); val s = sin(g[2])
                v = floatArrayOf(
                    x + c * L * 1.6f, y + s * L * 1.6f, 0f,
                    x - c * L * 0.6f - s * L * 0.7f, y - s * L * 0.6f + c * L * 0.7f, 0f,
                    x - c * L * 0.6f + s * L * 0.7f, y - s * L * 0.6f - c * L * 0.7f, 0f)
                prim = GL_TRIANGLES
            }
            gp.upload(vbo[5], guessBuf, v, v.size / 3)
            glLineWidth(6f)
            gp.draw(vbo[5], prim, v.size / 3, 1, 1f, 1f, 0.6f, 0.1f)
        }
    }
}
