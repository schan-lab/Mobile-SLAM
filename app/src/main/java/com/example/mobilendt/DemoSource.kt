package com.example.mobilendt

import java.util.concurrent.locks.LockSupport

/**
 * Streams synthetic Mid-360 packets (a simulated room scanned while walking, cpp/Mid360Synth.hpp) in real time,
 * so the app can be tried without the sensor. [sink] gets (type 1 = point / 2 = IMU, buf, len) on the demo thread,
 * the same as packets received over UDP.
 */
class DemoSource(private val sink: (Int, ByteArray, Int) -> Unit) {

    private var handle = 0L
    private var thread: Thread? = null
    @Volatile private var quit = false

    fun start() {
        if (thread != null) return
        handle = nativeCreate()
        quit = false
        thread = Thread({ run() }, "mid360-demo").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops and joins the thread: [sink] is not called after this returns. */
    fun stop() {
        val t = thread ?: return
        quit = true
        t.interrupt()
        t.join()
        thread = null
        nativeDestroy(handle)
        handle = 0
    }

    fun restartMotion() { if (handle != 0L) nativeRestartMotion(handle) }

    private fun run() {
        val buf = ByteArray(2048)
        var t0 = System.nanoTime()
        while (!quit) {
            val t = nativeNext(handle, buf)
            if (t < 0) return
            val due = t0 + (t * 1e9).toLong()
            val wait = due - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
            else if (wait < -500_000_000L) t0 -= wait   // fell behind (e.g. paused): don't burst to catch up
            if (quit) return
            val len = (buf[1].toInt() and 0xFF) or ((buf[2].toInt() and 0xFF) shl 8)
            sink(if (buf[10].toInt() == Livox.DATA_TYPE_IMU) 2 else 1, buf, len)
        }
    }

    private companion object {
        init { System.loadLibrary("mobilendt") }
        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeDestroy(h: Long)
        @JvmStatic external fun nativeRestartMotion(h: Long)
        @JvmStatic external fun nativeNext(h: Long, buf: ByteArray): Double
    }
}
