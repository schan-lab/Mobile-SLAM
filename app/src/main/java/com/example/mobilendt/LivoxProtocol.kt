package com.example.mobilendt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Livox SDK2 (Mid-360) wire protocol, minimal subset.
 *
 * Point / IMU packet header (36 bytes, little endian):
 *   0  u8  version
 *   1  u16 length
 *   3  u16 time_interval (0.1us)
 *   5  u16 dot_num
 *   7  u16 udp_cnt
 *   9  u8  frame_cnt
 *  10  u8  data_type   (0=IMU, 1=cartesian int32 mm, 2=cartesian int16 cm)
 *  11  u8  time_type
 *  12  u8[12] reserved
 *  24  u32 crc32
 *  28  u64 timestamp (ns)
 *  36  data...
 *
 * Control frame header (24 bytes):
 *   0 sof(0xAA) 1 version 2 length(u16) 4 seq(u32) 8 cmd_id(u16)
 *  10 cmd_type 11 sender_type 12 resv[6] 18 crc16(header[0..18)) 20 crc32(data)
 */
object Livox {
    const val LIDAR_DETECT_PORT = 56000
    const val HOST_DETECT_PORT = 56001
    const val HOST_PUSH_PORT = 56201
    const val HOST_POINT_PORT = 56301
    const val HOST_IMU_PORT = 56401

    /** Factory default destination IP of Mid-360. */
    const val DEFAULT_HOST_IP = "192.168.1.50"

    const val HEADER_SIZE = 36
    const val DATA_TYPE_IMU = 0
    const val DATA_TYPE_CART_HIGH = 1
    const val DATA_TYPE_CART_LOW = 2

    private const val CTRL_HEADER = 24

    fun crc16Ccitt(buf: ByteArray, off: Int, len: Int): Int {
        var crc = 0xFFFF
        for (i in off until off + len) {
            crc = crc xor ((buf[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    /** Broadcast discovery request (cmd_id 0x0000). */
    fun buildDetectRequest(seq: Int): ByteArray {
        val b = ByteBuffer.allocate(CTRL_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0xAA.toByte())      // sof
        b.put(0)                   // version
        b.putShort(CTRL_HEADER.toShort())
        b.putInt(seq)
        b.putShort(0)              // cmd_id: discovery
        b.put(0)                   // cmd_type: REQ
        b.put(0)                   // sender_type: host
        b.put(ByteArray(6))
        val arr = b.array()
        val crc16 = crc16Ccitt(arr, 0, 18)
        b.putShort(18, crc16.toShort())
        b.putInt(20, 0)            // crc32 of empty data
        return arr
    }

    data class DetectAck(val devType: Int, val sn: String, val ip: String, val cmdPort: Int)

    fun parseDetectAck(buf: ByteArray, len: Int): DetectAck? {
        if (len < CTRL_HEADER + 24 || (buf[0].toInt() and 0xFF) != 0xAA) return null
        val b = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val cmdId = b.getShort(8).toInt() and 0xFFFF
        val cmdType = buf[10].toInt()
        if (cmdId != 0 || cmdType != 1) return null
        val d = CTRL_HEADER
        val devType = buf[d + 1].toInt() and 0xFF
        val sn = String(buf, d + 2, 16, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        val ip = (0 until 4).joinToString(".") { (buf[d + 18 + it].toInt() and 0xFF).toString() }
        val port = b.getShort(d + 22).toInt() and 0xFFFF
        return DetectAck(devType, sn, ip, port)
    }

    fun devTypeName(t: Int): String = when (t) {
        9, 35 -> "Mid-360"
        10 -> "HAP"
        else -> "type=$t"
    }
}
