package com.example.mobilendt

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.concurrent.thread

class Mid360ConfigTest {

    // ------------------------------------------------------------------ pure helpers

    @Test fun normalizesFullWidthInput() {
        assertEquals("192.168.1.50", Mid360Config.normalize("１９２．１６８．１．５０"))
        assertEquals("192.168.1.50", Mid360Config.normalize("192。168｡1.50 "))
        assertTrue(Mid360Config.isValid("１９２．１６８．１．５０"))
    }

    @Test fun parsesAddresses() {
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 50), Mid360Config.ipBytes("192.168.1.50"))
        assertFalse(Mid360Config.isValid("192.168.1.256"))
        assertFalse(Mid360Config.isValid("192.168.1"))
        assertFalse(Mid360Config.isValid("a.b.c.d"))
        assertFalse(Mid360Config.isValid(""))
    }

    @Test fun comparesSubnets() {
        assertTrue(Mid360Config.sameSubnet("192.168.1.50", "192.168.1.123", "255.255.255.0"))
        assertFalse(Mid360Config.sameSubnet("192.168.1.50", "192.168.2.123", "255.255.255.0"))
        assertTrue(Mid360Config.sameSubnet("10.0.3.1", "10.0.200.9", "255.255.0.0"))
        assertFalse(Mid360Config.sameSubnet("10.0.3.1", "bad", "255.255.0.0"))
    }

    @Test fun convertsPrefixLength() {
        assertEquals("255.255.255.0", Mid360Config.prefixToMask(24))
        assertEquals("255.255.240.0", Mid360Config.prefixToMask(20))
        assertEquals("255.255.255.255", Mid360Config.prefixToMask(32))
        assertEquals("0.0.0.0", Mid360Config.prefixToMask(0))
    }

    @Test fun computesBroadcast() {
        assertEquals("192.168.1.255", Mid360Config.broadcast("192.168.1.50", 24))
        assertEquals("10.0.255.255", Mid360Config.broadcast("10.0.3.7", 16))
        assertEquals("172.16.15.255", Mid360Config.broadcast("172.16.9.1", 20))
        assertNull(Mid360Config.broadcast("192.168.1.50", 32))
    }

    @Test fun framesAndParsesAck() {
        val data = byteArrayOf(1, 2, 3)
        val f = Mid360Config.frame(0x0101, 77, data)
        val b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xAA, f[0].toInt() and 0xFF)
        assertEquals(27, b.getShort(2).toInt())
        assertEquals(77, b.getInt(4))
        assertEquals(0x0101, b.getShort(8).toInt())
        assertEquals(Livox.crc16Ccitt(f, 0, 18), b.getShort(18).toInt() and 0xFFFF)
        assertEquals(CRC32().apply { update(data) }.value, b.getInt(20).toLong() and 0xFFFFFFFFL)

        // a request is not an ACK; flip cmd_type to 1 (ACK) and fix the crc16
        assertNull(Mid360Config.parseAck(f, f.size, 0x0101, 77))
        val ack = ack(77, 0x0101, data)
        assertArrayEquals(data, Mid360Config.parseAck(ack, ack.size, 0x0101, 77))
        assertNull(Mid360Config.parseAck(ack, ack.size, 0x0101, 78))   // other seq
        assertNull(Mid360Config.parseAck(ack, ack.size, 0x0100, 77))   // other cmd
        ack[25] = 9
        assertNull(Mid360Config.parseAck(ack, ack.size, 0x0101, 77))   // crc32 mismatch
    }

    @Test fun detectRequestMatchesControlFrame() {
        assertArrayEquals(Mid360Config.frame(0, 5, ByteArray(0)), Livox.buildDetectRequest(5))
    }

    // ------------------------------------------------------------------ against a fake Mid-360 on localhost

    private lateinit var fake: DatagramSocket
    private lateinit var server: Thread
    private val cfg = mutableMapOf(
        4 to byteArrayOf(192.toByte(), 168.toByte(), 1, 123, -1, -1, -1, 0, 192.toByte(), 168.toByte(), 1, 1),
        6 to byteArrayOf(192.toByte(), 168.toByte(), 1, 50, 0xF5.toByte(), 0xDB.toByte(), 0xEC.toByte(), 0xDB.toByte()),
    )
    private val log = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun startFake() {
        fake = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", Mid360Config.LIDAR_CMD_PORT))
        }
        server = thread(isDaemon = true) {
            val buf = ByteArray(2000)
            val p = DatagramPacket(buf, buf.size)
            while (!fake.isClosed) {
                try {
                    p.length = buf.size
                    fake.receive(p)
                }
                catch (_: Exception) { break }
                val b = ByteBuffer.wrap(buf, 0, p.length).order(ByteOrder.LITTLE_ENDIAN)
                check(buf[0].toInt() and 0xFF == 0xAA && b.getShort(2).toInt() == p.length && buf[10].toInt() == 0)
                check(b.getShort(18).toInt() and 0xFFFF == Livox.crc16Ccitt(buf, 0, 18)) { "crc16" }
                val seq = b.getInt(4)
                val cmd = b.getShort(8).toInt() and 0xFFFF
                val d = buf.copyOfRange(24, p.length)
                val db = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
                val out: ByteArray = when (cmd) {
                    0x0101 -> {
                        val n = db.getShort(0).toInt()
                        val keys = (0 until n).map { db.getShort(4 + it * 2).toInt() }
                        log += "GET $keys"
                        val o = ByteBuffer.allocate(3 + keys.sumOf { 4 + cfg[it]!!.size }).order(ByteOrder.LITTLE_ENDIAN)
                        o.put(0).putShort(n.toShort())
                        keys.forEach { o.putShort(it.toShort()).putShort(cfg[it]!!.size.toShort()).put(cfg[it]!!) }
                        o.array()
                    }
                    0x0100 -> {
                        val n = db.getShort(0).toInt()
                        var off = 4
                        repeat(n) {
                            val k = db.getShort(off).toInt()
                            val l = db.getShort(off + 2).toInt()
                            cfg[k] = d.copyOfRange(off + 4, off + 4 + l)
                            log += "SET $k ${Mid360Config.ipString(cfg[k]!!, 0)}"
                            off += 4 + l
                        }
                        check(off == d.size)
                        byteArrayOf(0, 0, 0)
                    }
                    0x0200 -> {
                        log += "REBOOT ${db.getShort(0)}"
                        byteArrayOf(0)
                    }
                    else -> error("cmd $cmd")
                }
                val a = ack(seq, cmd, out)
                fake.send(DatagramPacket(a, a.size, p.socketAddress))
            }
        }
    }

    @After fun stopFake() {
        fake.close()
        server.join(2000)
    }

    @Test fun readsSettings() {
        val n = Mid360Config.read("127.0.0.1")
        assertEquals(Mid360Config.Network("192.168.1.123", "255.255.255.0", "192.168.1.1", "192.168.1.50"), n)
        assertEquals(listOf("GET [4, 6]"), log)
    }

    @Test fun writesDestinationOnly() {
        val rebooted = Mid360Config.write("127.0.0.1",
            Mid360Config.Network("192.168.1.123", "255.255.255.0", "192.168.1.1", "192.168.1.60"), changeLidarIP = false)
        assertFalse(rebooted)
        assertEquals(listOf("SET 5 192.168.1.60", "SET 6 192.168.1.60", "SET 7 192.168.1.60"), log)
        val point = ByteBuffer.wrap(cfg[6]!!).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Livox.HOST_POINT_PORT, point.getShort(4).toInt() and 0xFFFF)
        assertEquals(Mid360Config.LIDAR_POINT_PORT, point.getShort(6).toInt() and 0xFFFF)
    }

    @Test fun writesLidarIpAndReboots() {
        val rebooted = Mid360Config.write("127.0.0.1",
            Mid360Config.Network("10.0.0.2", "255.255.255.0", "10.0.0.1", "10.0.0.50"), changeLidarIP = true)
        assertTrue(rebooted)
        assertEquals("SET 4 10.0.0.2", log.first())
        assertEquals("REBOOT 100", log.last())
        assertEquals("255.255.255.0", Mid360Config.ipString(cfg[4]!!, 4))
    }

    @Test fun reportsNoResponse() {
        fake.close()
        val e = runCatching { Mid360Config.read("127.0.0.1") }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e is Mid360Config.Failure.NoResponse)
    }

    private fun ack(seq: Int, cmd: Int, data: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0xAA.toByte()).put(0).putShort((24 + data.size).toShort()).putInt(seq).putShort(cmd.toShort())
        b.put(1).put(1).put(ByteArray(6))
        val a = b.array()
        b.putShort(18, Livox.crc16Ccitt(a, 0, 18).toShort())
        b.putInt(20, if (data.isEmpty()) 0 else CRC32().apply { update(data) }.value.toInt())
        System.arraycopy(data, 0, a, 24, data.size)
        return a
    }

    @Suppress("unused") private val loopback: InetAddress = InetAddress.getLoopbackAddress()
}
