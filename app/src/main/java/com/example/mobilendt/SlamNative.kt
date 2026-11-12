package com.example.mobilendt

/** Thin wrapper over the native LIO engine (core/lio.cpp). */
class SlamNative {
    private var handle: Long = create()

    /** mode: 0 = mapping (SLAM), 1 = localisation (NDT against the prior map). */
    fun start(mode: Int) = start(handle, mode)
    /** inten: optional per-point intensity (display only). */
    fun setPriorMap(xyz: FloatArray, inten: FloatArray?, n: Int): Boolean = setPriorMap(handle, xyz, inten, n)
    /** Points closer than [m] metres are ignored. Applies from the next scan. */
    fun setBlind(m: Float) = setBlind(handle, m)
    /** Switch mode while stopped (resets state). */
    fun prepare(mode: Int) = prepare(handle, mode)
    /** Initial guess for localisation; hasYaw=false searches all headings. */
    fun setInitialPose(x: Float, y: Float, z: Float, yaw: Float, hasYaw: Boolean) =
        setInitialPose(handle, x, y, z, yaw, hasYaw)
    fun stop() = stop(handle)
    fun push(type: Int, buf: ByteArray, len: Int) = push(handle, type, buf, len)
    fun status(out: DoubleArray) = status(handle, out)
    /** Fills [out] with x, y, z, intensity per point (intensity < 0 = unknown). */
    fun map(out: FloatArray, maxPts: Int): Int = map(handle, out, maxPts)
    fun trajectory(out: FloatArray, maxPts: Int): Int = trajectory(handle, out, maxPts)
    fun scan(out: FloatArray, maxPts: Int): Int = scan(handle, out, maxPts)
    fun savePcd(path: String): Boolean = savePcd(handle, path)

    fun release() {
        if (handle != 0L) { destroy(handle); handle = 0 }
    }

    /** Indices into the status() array. */
    object S {
        const val STATUS = 0; const val SCANS = 1; const val DROPPED_SCANS = 2; const val DROPPED_PKTS = 3
        const val QUEUE = 4; const val LAST_MS = 5; const val AVG_MS = 6; const val EFFECTIVE = 7
        const val DS_POINTS = 8; const val MAP_SIZE = 9; const val VIS_SIZE = 10
        const val PX = 11; const val PY = 12; const val PZ = 13; const val YAW = 14
        const val DEGENERATE = 15; const val RUNNING = 16
        const val MODE = 17; const val LOC_STATE = 18; const val MATCH = 19; const val NDT_MS = 20
        const val HAS_PRIOR = 21; const val SIZE = 22
    }

    companion object {
        init { System.loadLibrary("mobilendt") }
        @JvmStatic private external fun create(): Long
        @JvmStatic private external fun destroy(h: Long)
        @JvmStatic private external fun start(h: Long, mode: Int)
        @JvmStatic private external fun setPriorMap(h: Long, xyz: FloatArray, inten: FloatArray?, n: Int): Boolean
        @JvmStatic private external fun setBlind(h: Long, m: Float)
        @JvmStatic private external fun prepare(h: Long, mode: Int)
        @JvmStatic private external fun setInitialPose(h: Long, x: Float, y: Float, z: Float, yaw: Float, hasYaw: Boolean)
        @JvmStatic private external fun stop(h: Long)
        @JvmStatic private external fun push(h: Long, type: Int, buf: ByteArray, len: Int)
        @JvmStatic private external fun status(h: Long, out: DoubleArray)
        @JvmStatic private external fun map(h: Long, out: FloatArray, maxPts: Int): Int
        @JvmStatic private external fun trajectory(h: Long, out: FloatArray, maxPts: Int): Int
        @JvmStatic private external fun scan(h: Long, out: FloatArray, maxPts: Int): Int
        @JvmStatic private external fun savePcd(h: Long, path: String): Boolean
    }
}
