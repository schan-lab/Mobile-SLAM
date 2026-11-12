package com.example.mobilendt

import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.cos
import kotlin.math.sin

/**
 * GLSurfaceView with an orbit camera around a target point (Z up).
 *  1 finger drag : rotate     pinch : zoom     2 finger drag : pan     double tap : [onDoubleTap]
 */
abstract class OrbitGLView(context: Context) : GLSurfaceView(context) {

    // camera (accessed from UI + GL threads; small races are harmless)
    @Volatile var yaw = -90f        // deg, around world Z
    @Volatile var pitch = 50f       // deg, elevation
    @Volatile var dist = 12f
    @Volatile var tx = 0f
    @Volatile var ty = 0f
    @Volatile var tz = 0f
    var minDist = 0.3f
    var maxDist = 500f

    init { setEGLContextClientVersion(3) }

    /** Called when the user pans (e.g. to stop following). */
    protected open fun onUserPan() {}
    protected open fun onDoubleTap() {}

    private var lastX = 0f; private var lastY = 0f; private var lastN = 0
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            dist = (dist / d.scaleFactor).coerceIn(minDist, maxDist); return true
        }
    })
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { this@OrbitGLView.onDoubleTap(); return true }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        val n = e.pointerCount
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += e.getX(i); cy += e.getY(i) }
        cx /= n; cy /= n
        if (e.actionMasked == MotionEvent.ACTION_DOWN || n != lastN ||
            e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            lastX = cx; lastY = cy; lastN = n
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = cx - lastX; val dy = cy - lastY
            if (n == 1) {
                yaw -= dx * 0.3f
                pitch = (pitch + dy * 0.3f).coerceIn(-89f, 89.9f)
            } else if (n == 2) {
                onUserPan()
                val k = dist * 0.0015f
                val yr = Math.toRadians(yaw.toDouble())
                val rx = -sin(yr).toFloat(); val ry = cos(yr).toFloat()      // camera right
                val fx = -cos(yr).toFloat(); val fy = -sin(yr).toFloat()     // camera forward (ground)
                tx -= (rx * dx - fx * dy) * k
                ty -= (ry * dx - fy * dy) * k
            }
            lastX = cx; lastY = cy
        }
        return true
    }

    /** Projection matrix last used by the renderer (renderers copy theirs here). */
    @Volatile var sharedProj = FloatArray(16)

    /** Intersects the ray through screen pixel (sx, sy) with the plane z = zPlane. Returns world xyz or null. */
    fun screenToGround(sx: Float, sy: Float, zPlane: Float): FloatArray? {
        if (width == 0 || height == 0) return null
        val mvp = FloatArray(16); computeMvp(sharedProj, mvp)
        val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, mvp, 0)) return null
        val nx = 2f * sx / width - 1f; val ny = 1f - 2f * sy / height
        val a = FloatArray(4); val b = FloatArray(4)
        Matrix.multiplyMV(a, 0, inv, 0, floatArrayOf(nx, ny, -1f, 1f), 0)
        Matrix.multiplyMV(b, 0, inv, 0, floatArrayOf(nx, ny, 1f, 1f), 0)
        for (i in 0..2) { a[i] /= a[3]; b[i] /= b[3] }
        val dz = b[2] - a[2]
        if (kotlin.math.abs(dz) < 1e-6f) return null
        val t = (zPlane - a[2]) / dz
        if (t < 0f) return null
        return floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, zPlane)
    }

    /** Fills [mvp] = proj * view for the current camera. */
    fun computeMvp(proj: FloatArray, mvp: FloatArray) {
        val view = FloatArray(16)   // local: called from both UI and GL threads
        val yr = Math.toRadians(yaw.toDouble()); val pr = Math.toRadians(pitch.toDouble())
        val ex = tx + (dist * cos(pr) * cos(yr)).toFloat()
        val ey = ty + (dist * cos(pr) * sin(yr)).toFloat()
        val ez = tz + (dist * sin(pr)).toFloat()
        // near top-down the Z up-vector degenerates; use the heading direction instead
        if (pitch > 88f) Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, -cos(yr).toFloat(), -sin(yr).toFloat(), 0f)
        else Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, 0f, 0f, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
    }
}
