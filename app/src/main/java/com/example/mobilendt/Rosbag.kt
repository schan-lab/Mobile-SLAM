package com.example.mobilendt

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Playback state of a ROS 2 bag (built by the native code, cpp/rosbag_jni.cpp). */
class BagStatus(
    /** 0 playing, 1 finished, 2 error. */
    val state: Int,
    /** 0..1 (bytes read). */
    val progress: Double,
    /** s of bag time played. */
    val elapsed: Double,
    /** s, 0 when the bag has no summary. */
    val duration: Double,
    val pointMsgs: Long,
    val imuMsgs: Long,
    val lidarTopic: String,
    val imuTopic: String,
    val error: String,
)

/** Something that stores incoming packets (type 1 = point, 2 = IMU). Thread safe. */
interface PacketRecording {
    val file: File
    val bytes: Long
    fun write(type: Int, buf: ByteArray, len: Int)
    fun close()
}

/** Records a ROS 2 bag (MCAP): /livox/imu and /livox/lidar (PointCloud2 with per-point timestamps). */
class RosbagRecorder(override val file: File) : PacketRecording {
    private var h = nativeOpen(file.absolutePath)

    init {
        if (h == 0L) throw java.io.IOException(file.name)
    }

    override val bytes: Long
        @Synchronized get() = if (h != 0L) nativeBytes(h) else 0

    @Synchronized
    override fun write(type: Int, buf: ByteArray, len: Int) {
        if (h != 0L) nativePush(h, type, buf, len)
    }

    @Synchronized
    override fun close() {
        if (h != 0L) nativeClose(h)
        h = 0
    }

    private companion object {
        init { System.loadLibrary("mobilendt") }
        @JvmStatic external fun nativeOpen(path: String): Long
        @JvmStatic external fun nativePush(h: Long, type: Int, buf: ByteArray, len: Int)
        @JvmStatic external fun nativeBytes(h: Long): Long
        @JvmStatic external fun nativeClose(h: Long)
    }
}

/** Raw packets as received (format: [type u8][len u16 LE][bytes]). */
class RawRecorder(override val file: File) : PacketRecording {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 20)
    @Volatile override var bytes = 0L
        private set

    @Synchronized
    override fun write(type: Int, buf: ByteArray, len: Int) {
        try {
            out.write(type)
            out.write(len and 0xFF)
            out.write((len shr 8) and 0xFF)
            out.write(buf, 0, len)
            bytes += len + 3L
        } catch (e: Exception) {
            android.util.Log.w(LidarReceiver.TAG, "record: ${e.message}")
        }
    }

    @Synchronized
    override fun close() {
        runCatching {
            out.flush()
            out.close()
        }
    }
}

/**
 * Plays a ROS 2 bag in real time (scaled by speed) on a native thread, converted to Livox packets passed to
 * [sink] (type 1 = point, 2 = IMU), like packets received over UDP.
 */
class RosbagPlayer(file: File, speed: Double, private val sink: (Int, ByteArray, Int) -> Unit) {
    private var h = nativeStart(file.absolutePath, speed)

    /** Called on the player thread. */
    @Suppress("unused")
    private fun onPacket(type: Int, buf: ByteArray, len: Int) = sink(type, buf, len)

    fun status(): BagStatus? = if (h != 0L) nativeStatus(h) else null

    /** Joins the thread: [sink] is not called after this returns. */
    fun stop() {
        if (h != 0L) nativeStop(h)
        h = 0
    }

    companion object {
        init { System.loadLibrary("mobilendt") }

        /** Finds the LiDAR and IMU topics; state 2 (with error) if the bag cannot be played. */
        fun probe(file: File): BagStatus = nativeProbe(file.absolutePath)

        @JvmStatic private external fun nativeProbe(path: String): BagStatus
        @JvmStatic private external fun nativeStatus(h: Long): BagStatus
        @JvmStatic private external fun nativeStop(h: Long)
    }

    private external fun nativeStart(path: String, speed: Double): Long
}

/**
 * Recordings (ROS 2 bags and raw packets) live in the app's own folder on shared storage
 * (Android/data/<package>/files), so the native code can open them by path. Imported bags are copied there too.
 */
object AppFiles {
    fun recordDir(c: Context): File = c.getExternalFilesDir(null) ?: c.filesDir

    fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** ROS 2 bags (MCAP), newest first. */
    fun listBags(c: Context): List<File> =
        (recordDir(c).listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".mcap", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }

    /** Display name of a content Uri. */
    fun displayName(c: Context, uri: Uri): String =
        runCatching {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"

    /** Copies an opened document into [recordDir] (a free name is chosen). Returns the new file. */
    fun importFile(c: Context, uri: Uri): File {
        val name = displayName(c, uri).replace('/', '_')
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var dst = File(recordDir(c), name)
        var k = 1
        while (dst.exists()) {
            dst = File(recordDir(c), if (ext.isEmpty()) "${base}_$k" else "${base}_$k.$ext")
            k++
        }
        try {
            c.contentResolver.openInputStream(uri)!!.use { input -> dst.outputStream().use { input.copyTo(it, 1 shl 20) } }
        } catch (e: Exception) {
            dst.delete()
            throw e
        }
        return dst
    }
}
