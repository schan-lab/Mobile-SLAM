package com.example.mobilendt

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/**
 * Network settings of a Mid-360 (IP of the LiDAR and the destination of its data), read and written over the
 * Livox SDK2 control protocol ("Livox LiDAR Communication Protocol -- Mid360"):
 *
 * - 0x0101 parameter inquire / 0x0100 parameter configuration, keys
 *   0x0004 lidar_ipcfg (ip[4] mask[4] gateway[4]),
 *   0x0005 / 0x0006 / 0x0007 state info / point cloud / IMU destination (ip[4] dst_port u16 src_port u16)
 * - 0x0200 reboot (needed for a new LiDAR IP; the destination settings apply immediately)
 *
 * Control commands are unicast UDP to <lidar ip>:56100, so the phone must be on the LiDAR's current subnet.
 * Pure JVM (no Android classes) so it can be unit-tested; the caller binds the socket to the Ethernet network.
 */
object Mid360Config {

    data class Network(
        val lidarIP: String,
        val mask: String,
        val gateway: String,
        /** Destination of point cloud / IMU / state info. */
        val hostIP: String,
    )

    sealed class Failure : IOException() {
        class BadAddress(val address: String) : Failure()
        class Socket(val reason: String) : Failure()
        object NoResponse : Failure() { private fun readResolve(): Any = NoResponse }
        class Rejected(val code: Int, val key: Int) : Failure()
        object Malformed : Failure() { private fun readResolve(): Any = Malformed }
    }

    const val LIDAR_CMD_PORT = 56100
    const val HOST_CMD_PORT = 56101
    // LiDAR-side source ports (spec defaults), sent together with the destination ports
    const val LIDAR_PUSH_PORT = 56200
    const val LIDAR_POINT_PORT = 56300
    const val LIDAR_IMU_PORT = 56400

    const val CMD_SET_PARAMS = 0x0100
    const val CMD_GET_PARAMS = 0x0101
    const val CMD_REBOOT = 0x0200

    const val KEY_LIDAR_IP = 0x0004
    const val KEY_STATE_HOST = 0x0005
    const val KEY_POINT_HOST = 0x0006
    const val KEY_IMU_HOST = 0x0007

    /** ret_code: the parameters take effect after a reboot. */
    private const val RET_REBOOT_EFFECT = 0x21

    // ------------------------------------------------------------------ operations (blocking; call off the main thread)

    /** Reads the current network settings (0x0101). [bindNet] may bind the socket to a network before use. */
    fun read(lidarIP: String, bindNet: (DatagramSocket) -> Unit = {}): Network {
        val keys = intArrayOf(KEY_LIDAR_IP, KEY_POINT_HOST)
        val d = le(4 + keys.size * 2).putShort(keys.size.toShort()).putShort(0)
        keys.forEach { d.putShort(it.toShort()) }
        val ack = request(lidarIP, CMD_GET_PARAMS, d.array(), bindNet)
        if (ack.size < 3) throw Failure.Malformed
        if (ack[0].toInt() != 0) throw Failure.Rejected(ack[0].toInt() and 0xFF, 0)
        val b = ByteBuffer.wrap(ack).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getShort(1).toInt() and 0xFFFF
        var off = 3
        var lidar = normalize(lidarIP); var mask = ""; var gw = ""; var host = ""
        repeat(n) {
            if (off + 4 > ack.size) throw Failure.Malformed
            val key = b.getShort(off).toInt() and 0xFFFF
            val len = b.getShort(off + 2).toInt() and 0xFFFF
            off += 4
            if (off + len > ack.size) throw Failure.Malformed
            if (key == KEY_LIDAR_IP && len >= 12) {
                lidar = ipString(ack, off); mask = ipString(ack, off + 4); gw = ipString(ack, off + 8)
            } else if (key == KEY_POINT_HOST && len >= 4) {
                host = ipString(ack, off)
            }
            off += len
        }
        if (mask.isEmpty()) throw Failure.Malformed
        return Network(lidar, mask, gw, host)
    }

    /**
     * Writes the destination IP (applied immediately) and, if [changeLidarIP], the LiDAR IP followed by a reboot.
     * Returns true when the LiDAR was rebooted.
     */
    fun write(currentLidarIP: String, net: Network, changeLidarIP: Boolean, bindNet: (DatagramSocket) -> Unit = {}): Boolean {
        val host = ipBytes(net.hostIP)
        val kvs = mutableListOf<Pair<Int, ByteArray>>()
        if (changeLidarIP) kvs += KEY_LIDAR_IP to (ipBytes(net.lidarIP) + ipBytes(net.mask) + ipBytes(net.gateway))
        for ((key, dst, src) in listOf(
            Triple(KEY_STATE_HOST, Livox.HOST_PUSH_PORT, LIDAR_PUSH_PORT),
            Triple(KEY_POINT_HOST, Livox.HOST_POINT_PORT, LIDAR_POINT_PORT),
            Triple(KEY_IMU_HOST, Livox.HOST_IMU_PORT, LIDAR_IMU_PORT),
        )) {
            kvs += key to le(8).put(host).putShort(dst.toShort()).putShort(src.toShort()).array()
        }
        val d = le(4 + kvs.sumOf { 4 + it.second.size }).putShort(kvs.size.toShort()).putShort(0)
        for ((k, v) in kvs) d.putShort(k.toShort()).putShort(v.size.toShort()).put(v)
        val ack = request(currentLidarIP, CMD_SET_PARAMS, d.array(), bindNet)
        if (ack.isEmpty()) throw Failure.Malformed
        val code = ack[0].toInt() and 0xFF
        val errKey = if (ack.size >= 3) ByteBuffer.wrap(ack).order(ByteOrder.LITTLE_ENDIAN).getShort(1).toInt() and 0xFFFF else 0
        val needsReboot = code == RET_REBOOT_EFFECT
        if (code != 0 && !needsReboot) throw Failure.Rejected(code, errKey)
        if (!changeLidarIP && !needsReboot) return false
        val r = le(2).putShort(100).array()   // reboot after 100 ms (same as the SDK)
        val rack = request(currentLidarIP, CMD_REBOOT, r, bindNet)
        if (rack.isEmpty()) throw Failure.Malformed
        if (rack[0].toInt() != 0) throw Failure.Rejected(rack[0].toInt() and 0xFF, 0)
        return true
    }

    // ------------------------------------------------------------------ framing

    /**
     * Builds a control frame: sof, version, length, seq, cmd_id, cmd_type (REQ), sender_type (host), resv[6],
     * crc16 over the first 18 bytes, crc32 over the data (0 when there is none), data. All fields little endian.
     */
    fun frame(cmd: Int, seq: Int, data: ByteArray): ByteArray {
        val b = le(24 + data.size)
        b.put(0xAA.toByte()).put(0).putShort((24 + data.size).toShort()).putInt(seq).putShort(cmd.toShort())
        b.put(0).put(0)   // REQ, host
        b.put(ByteArray(6))
        val arr = b.array()
        b.putShort(18, Livox.crc16Ccitt(arr, 0, 18).toShort())
        b.putInt(20, if (data.isEmpty()) 0 else crc32(data, 0, data.size).toInt())
        System.arraycopy(data, 0, arr, 24, data.size)
        return arr
    }

    fun crc32(d: ByteArray, off: Int, len: Int): Long = CRC32().apply { update(d, off, len) }.value

    /** Parses an ACK frame for (cmd, seq); returns its data, or null if it is something else. */
    fun parseAck(p: ByteArray, len: Int, cmd: Int, seq: Int): ByteArray? {
        if (len < 24 || (p[0].toInt() and 0xFF) != 0xAA) return null
        val b = ByteBuffer.wrap(p, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val flen = b.getShort(2).toInt() and 0xFFFF
        if (flen < 24 || flen > len) return null
        if (Livox.crc16Ccitt(p, 0, 18) != (b.getShort(18).toInt() and 0xFFFF)) return null
        if (b.getInt(4) != seq || (b.getShort(8).toInt() and 0xFFFF) != cmd || p[10].toInt() != 1) return null
        val data = p.copyOfRange(24, flen)
        if (data.isNotEmpty() && crc32(data, 0, data.size) != (b.getInt(20).toLong() and 0xFFFFFFFFL)) return null
        return data
    }

    // ------------------------------------------------------------------ addresses

    /** Typed address in ASCII: full-width digits / dots (e.g. from a Japanese keyboard) are converted. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).replace('。', '.').trim()

    private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

    /** Dotted IPv4 → 4 bytes (network order). Throws [Failure.BadAddress]. */
    fun ipBytes(s: String): ByteArray {
        val m = IPV4.matchEntire(normalize(s)) ?: throw Failure.BadAddress(s)
        return ByteArray(4) {
            val v = m.groupValues[it + 1].toInt()
            if (v > 255) throw Failure.BadAddress(s)
            v.toByte()
        }
    }

    fun isValid(s: String) = runCatching { ipBytes(s) }.isSuccess

    fun ipString(d: ByteArray, off: Int) = (0 until 4).joinToString(".") { (d[off + it].toInt() and 0xFF).toString() }

    /** True when both addresses are valid and in the same subnet under [mask]. */
    fun sameSubnet(a: String, b: String, mask: String): Boolean {
        val x = runCatching { ipBytes(a) }.getOrNull() ?: return false
        val y = runCatching { ipBytes(b) }.getOrNull() ?: return false
        val m = runCatching { ipBytes(mask) }.getOrNull() ?: return false
        return (0 until 4).all { (x[it].toInt() and m[it].toInt()) == (y[it].toInt() and m[it].toInt()) }
    }

    /** Dotted netmask for a prefix length (e.g. 24 → 255.255.255.0). */
    fun prefixToMask(prefix: Int): String {
        val m = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix.coerceAtMost(32))) and 0xFFFFFFFFL
        return (3 downTo 0).joinToString(".") { ((m shr (8 * it)) and 0xFF).toString() }
    }

    /** Directed broadcast address of [ip]/[prefix] (e.g. 10.0.3.7/16 → 10.0.255.255), or null for /0, /31, /32. */
    fun broadcast(ip: String, prefix: Int): String? {
        if (prefix !in 1..30) return null
        val a = runCatching { ipBytes(ip) }.getOrNull() ?: return null
        val m = runCatching { ipBytes(prefixToMask(prefix)) }.getOrNull() ?: return null
        return (0 until 4).joinToString(".") { ((a[it].toInt() or m[it].toInt().inv()) and 0xFF).toString() }
    }

    // ------------------------------------------------------------------ transport

    private val seqCounter = AtomicInteger((1..0xFFFF).random())

    /** Sends a command and waits for its ACK (3 tries, 1 s each). */
    private fun request(lidarIP: String, cmd: Int, data: ByteArray, bindNet: (DatagramSocket) -> Unit): ByteArray {
        val dst = InetAddress.getByAddress(ipBytes(lidarIP))
        val seq = seqCounter.incrementAndGet()
        val pkt = frame(cmd, seq, data)
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 200
                // recommended host command port; any port works (the LiDAR replies to the sender)
                try { bind(InetSocketAddress(HOST_CMD_PORT)) } catch (_: IOException) { bind(InetSocketAddress(0)) }
            }
        } catch (e: IOException) {
            throw Failure.Socket(e.message ?: e.javaClass.simpleName)
        }
        s.use {
            try { bindNet(s) } catch (e: Exception) { /* e.g. VPN: fall back to the default route */ }
            val buf = ByteArray(1500)
            val rx = DatagramPacket(buf, buf.size)
            repeat(3) {
                try {
                    s.send(DatagramPacket(pkt, pkt.size, dst, LIDAR_CMD_PORT))
                } catch (e: IOException) {
                    val msg = e.message ?: ""
                    if ("ENETUNREACH" in msg || "EHOSTUNREACH" in msg || "unreachable" in msg) throw Failure.NoResponse
                    throw Failure.Socket(msg.ifEmpty { e.javaClass.simpleName })
                }
                val deadline = System.nanoTime() + 1_000_000_000L
                while (System.nanoTime() < deadline) {
                    try {
                        rx.length = buf.size
                        s.receive(rx)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (e: IOException) {
                        break
                    }
                    parseAck(buf, rx.length, cmd, seq)?.let { return it }
                }
            }
        }
        throw Failure.NoResponse
    }

    private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
}
