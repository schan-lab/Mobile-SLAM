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

    /** Copies current points into [outXyz]; returns the point count. */
    @Synchronized
    fun snapshot(outXyz: FloatArray): Int {
        System.arraycopy(xyz, 0, outXyz, 0, size * 3)
        return size
    }
}
