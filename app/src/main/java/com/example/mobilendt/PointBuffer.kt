package com.example.mobilendt

/** Ring buffer holding the most recent N points (x, y, z in metres + reflectivity). */
class PointBuffer(val capacity: Int) {
    private val xyz = FloatArray(capacity * 3)
    private val refl = ByteArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun add(x: Float, y: Float, z: Float, r: Int) {
        val i = head * 3
        xyz[i] = x; xyz[i + 1] = y; xyz[i + 2] = z
        refl[head] = r.toByte()
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** Copies current points into [out] as x, y, z, reflectivity (0..255); returns the point count. */
    @Synchronized
    fun snapshot(out: FloatArray): Int {
        for (i in 0 until size) {
            out[4 * i] = xyz[3 * i]; out[4 * i + 1] = xyz[3 * i + 1]; out[4 * i + 2] = xyz[3 * i + 2]
            out[4 * i + 3] = (refl[i].toInt() and 0xFF).toFloat()
        }
        return size
    }
}
