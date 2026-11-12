package com.example.mobilendt

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives Livox Mid-360 point cloud / IMU over the Ethernet network only.
 * Sockets are bound to the Ethernet [Network] so traffic never goes via Wi-Fi/LTE/VPN.
 */
class LidarReceiver(context: Context, val points: PointBuffer) {

    companion object { const val TAG = "Mid360" }

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    // ---- status (read from UI thread) ----
    @Volatile var ethState: String = "Ethernet待機中…"
    @Volatile var ethAddrs: String = "-"
    @Volatile var lidarInfo: String = "未検出"
    @Volatile var lidarSrc: String = "-"
    @Volatile var dataType: Int = -1
    @Volatile var lastImu: FloatArray? = null
    val pointPackets = AtomicLong()
    val pointCount = AtomicLong()
    val imuPackets = AtomicLong()
    val lostPackets = AtomicLong()
    val pushPackets = AtomicLong()

    private var network: Network? = null
    private val sockets = mutableListOf<DatagramSocket>()
    private val threads = mutableListOf<Thread>()
    @Volatile private var running = false
    @Volatile var bindNote: String = ""

    /** Optional raw packet sink: record format = [type u8 (1=point,2=imu)][len u16 LE][bytes]. */
    @Volatile var recorder: java.io.OutputStream? = null
    val recordedBytes = AtomicLong()

    /** Optional consumer of raw packets (e.g. the SLAM core). type: 1=point, 2=imu */
    @Volatile var packetSink: ((Int, ByteArray, Int) -> Unit)? = null

    private fun record(type: Int, buf: ByteArray, len: Int) {
        packetSink?.invoke(type, buf, len)
        val out = recorder ?: return
        synchronized(out) {
            try {
                out.write(type)
                out.write(len and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write(buf, 0, len)
                recordedBytes.addAndGet(len + 3L)
            } catch (e: Exception) { Log.w(TAG, "record: ${e.message}") }
        }
    }
    private var lastUdpCnt = -1

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            Log.i(TAG, "Ethernet available: $n")
            synchronized(this@LidarReceiver) {
                if (network != null) return
                network = n
                ethState = "Ethernet接続"
                openSockets(n)
            }
        }

        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            ethAddrs = "${lp.interfaceName}: " + lp.linkAddresses.joinToString { it.toString() }
            Log.i(TAG, "Link: $ethAddrs")
        }

        override fun onLost(n: Network) {
            Log.i(TAG, "Ethernet lost: $n")
            synchronized(this@LidarReceiver) {
                if (n != network) return
                closeSockets()
                network = null
                ethState = "Ethernet切断"
                ethAddrs = "-"
            }
        }
    }

    fun start() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.requestNetwork(req, callback)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        synchronized(this) {
            closeSockets()
            network = null
        }
    }

    // ---------------------------------------------------------------

    private fun openSocket(n: Network, port: Int): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.receiveBufferSize = 4 * 1024 * 1024
        s.bind(InetSocketAddress(port))
        try {
            n.bindSocket(s)
        } catch (e: Exception) {
            // e.g. EPERM when an always-routing VPN (no bypass) covers this app.
            // Receiving still works unbound: packets to eth0's address are delivered locally.
            Log.w(TAG, "bindSocket($port) failed, using unbound socket: ${e.message}")
            bindNote = " (VPN有効のため非バインド受信)"
        }
        sockets += s
        return s
    }

    private fun openSockets(n: Network) {
        running = true
        try {
            val point = openSocket(n, Livox.HOST_POINT_PORT)
            val imu = openSocket(n, Livox.HOST_IMU_PORT)
            val push = openSocket(n, Livox.HOST_PUSH_PORT)
            val detect = openSocket(n, Livox.HOST_DETECT_PORT).apply { broadcast = true }

            threads += loop("point", point) { buf, len, pkt -> onPoint(buf, len, pkt) }
            threads += loop("imu", imu) { buf, len, _ -> onImu(buf, len) }
            threads += loop("push", push) { _, _, _ -> pushPackets.incrementAndGet() }
            threads += loop("detect", detect) { buf, len, pkt -> onDetect(buf, len, pkt) }
            threads += Thread({ detectLoop(detect) }, "mid360-detect-tx").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "sockets open")
        } catch (e: Exception) {
            Log.e(TAG, "socket open failed", e)
            ethState = "ソケット作成失敗: ${e.message}"
        }
    }

    private fun closeSockets() {
        running = false
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        threads.forEach { it.interrupt() }
        threads.clear()
    }

    private fun loop(
        name: String, s: DatagramSocket,
        handler: (ByteArray, Int, DatagramPacket) -> Unit,
    ): Thread = Thread({
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (running && !s.isClosed) {
            try {
                pkt.length = buf.size
                s.receive(pkt)
                handler(buf, pkt.length, pkt)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "$name: ${e.message}")
            }
        }
    }, "mid360-$name").apply { isDaemon = true; start() }

    /** Periodically broadcast a discovery request so we can show the LiDAR's SN/IP. */
    private fun detectLoop(s: DatagramSocket) {
        var seq = 0
        val targets = listOf("255.255.255.255", "192.168.1.255")
        while (running && !s.isClosed) {
            val req = Livox.buildDetectRequest(seq++)
            for (t in targets) {
                runCatching {
                    s.send(DatagramPacket(req, req.size, InetAddress.getByName(t), Livox.LIDAR_DETECT_PORT))
                }.onFailure { Log.d(TAG, "detect send $t: ${it.message}") }
            }
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
        }
    }

    private fun onDetect(buf: ByteArray, len: Int, pkt: DatagramPacket) {
        val ack = Livox.parseDetectAck(buf, len) ?: return
        val info = "${Livox.devTypeName(ack.devType)} SN=${ack.sn} IP=${ack.ip}"
        if (info != lidarInfo) Log.i(TAG, "detected: $info (from ${pkt.address})")
        lidarInfo = info
    }

    private fun onPoint(buf: ByteArray, len: Int, pkt: DatagramPacket) {
        if (len < Livox.HEADER_SIZE) return
        record(1, buf, len)
        val b = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val dotNum = b.getShort(5).toInt() and 0xFFFF
        val udpCnt = b.getShort(7).toInt() and 0xFFFF
        val type = buf[10].toInt() and 0xFF

        if (lastUdpCnt >= 0) {
            val gap = (udpCnt - lastUdpCnt - 1) and 0xFFFF
            if (gap in 1..1000) lostPackets.addAndGet(gap.toLong())
        }
        lastUdpCnt = udpCnt
        pointPackets.incrementAndGet()
        dataType = type
        lidarSrc = pkt.address?.hostAddress ?: "-"

        var off = Livox.HEADER_SIZE
        var n = 0
        when (type) {
            Livox.DATA_TYPE_CART_HIGH -> {           // int32 mm x3, refl, tag = 14 bytes
                repeat(dotNum) {
                    if (off + 14 > len) return@repeat
                    val x = b.getInt(off)
                    val y = b.getInt(off + 4)
                    val z = b.getInt(off + 8)
                    if (x != 0 || y != 0 || z != 0) {
                        points.add(x / 1000f, y / 1000f, z / 1000f, buf[off + 12].toInt() and 0xFF)
                        n++
                    }
                    off += 14
                }
            }
            Livox.DATA_TYPE_CART_LOW -> {            // int16 cm x3, refl, tag = 8 bytes
                repeat(dotNum) {
                    if (off + 8 > len) return@repeat
                    val x = b.getShort(off).toInt()
                    val y = b.getShort(off + 2).toInt()
                    val z = b.getShort(off + 4).toInt()
                    if (x != 0 || y != 0 || z != 0) {
                        points.add(x / 100f, y / 100f, z / 100f, buf[off + 6].toInt() and 0xFF)
                        n++
                    }
                    off += 8
                }
            }
        }
        pointCount.addAndGet(n.toLong())
    }

    private fun onImu(buf: ByteArray, len: Int) {
        if (len < Livox.HEADER_SIZE + 24) return
        record(2, buf, len)
        val b = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val o = Livox.HEADER_SIZE
        // gyro x,y,z [rad/s], acc x,y,z [g]
        lastImu = FloatArray(6) { b.getFloat(o + it * 4) }
        imuPackets.incrementAndGet()
    }
}
