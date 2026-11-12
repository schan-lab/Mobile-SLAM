package com.example.mobilendt

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal PCD reader (ascii / binary / binary_compressed), extracting x, y, z only. */
class PcdData(val xyz: FloatArray, val n: Int, val totalPoints: Int, val fields: String, val format: String) {
    val min = FloatArray(3) { Float.MAX_VALUE }
    val max = FloatArray(3) { -Float.MAX_VALUE }
    var zLo = 0f; var zHi = 1f   // 2nd / 98th percentile of z (colour range)

    init {
        for (i in 0 until n) for (k in 0..2) {
            val v = xyz[3 * i + k]
            if (v < min[k]) min[k] = v
            if (v > max[k]) max[k] = v
        }
        if (n > 0) {
            val step = maxOf(1, n / 20000)
            val zs = FloatArray((n + step - 1) / step) { xyz[3 * (it * step) + 2] }
            zs.sort()
            zLo = zs[(zs.size * 0.02).toInt()]
            zHi = zs[minOf(zs.size - 1, (zs.size * 0.98).toInt())]
            if (zHi - zLo < 0.1f) zHi = zLo + 0.1f
        }
    }
}

object PcdFile {
    /** Points beyond this are uniformly subsampled to keep memory / GPU load reasonable. */
    const val MAX_POINTS = 6_000_000

    fun read(input: InputStream): PcdData {
        val ins = DataInputStream(BufferedInputStream(input, 1 shl 16))
        var fields = listOf<String>()
        var sizes = listOf<Int>(); var types = listOf<String>(); var counts = listOf<Int>()
        var points = -1; var width = 0; var height = 1
        var data = ""
        while (true) {
            val line = readLine(ins) ?: error("ヘッダーが途中で終わっています")
            val t = line.trim().split(Regex("\\s+"))
            if (t.isEmpty() || t[0].startsWith("#")) continue
            when (t[0].uppercase()) {
                "FIELDS" -> fields = t.drop(1)
                "SIZE" -> sizes = t.drop(1).map { it.toInt() }
                "TYPE" -> types = t.drop(1)
                "COUNT" -> counts = t.drop(1).map { it.toInt() }
                "WIDTH" -> width = t[1].toInt()
                "HEIGHT" -> height = t[1].toInt()
                "POINTS" -> points = t[1].toInt()
                "DATA" -> { data = t[1].lowercase(); break }
            }
        }
        if (points < 0) points = width * height
        if (counts.isEmpty()) counts = List(fields.size) { 1 }
        val ix = fields.indexOf("x"); val iy = fields.indexOf("y"); val iz = fields.indexOf("z")
        require(ix >= 0 && iy >= 0 && iz >= 0) { "x/y/z フィールドがありません (${fields.joinToString(" ")})" }

        val stride = if (points > MAX_POINTS) (points + MAX_POINTS - 1) / MAX_POINTS else 1
        val outN = (points + stride - 1) / stride
        val xyz = FloatArray(outN * 3)
        var n = 0

        // byte offset of each field within a point record
        val offs = IntArray(fields.size)
        for (i in 1 until fields.size) offs[i] = offs[i - 1] + sizes[i - 1] * counts[i - 1]
        val step = offs.last() + sizes.last() * counts.last()
        fun value(b: ByteBuffer, pos: Int, f: Int): Float = when {
            types[f] == "F" && sizes[f] == 8 -> b.getDouble(pos).toFloat()
            types[f] == "F" -> b.getFloat(pos)
            sizes[f] == 1 -> (if (types[f] == "U") b.get(pos).toInt() and 0xFF else b.get(pos).toInt()).toFloat()
            sizes[f] == 2 -> (if (types[f] == "U") b.getShort(pos).toInt() and 0xFFFF else b.getShort(pos).toInt()).toFloat()
            sizes[f] == 4 -> b.getInt(pos).toFloat()
            else -> b.getLong(pos).toFloat()
        }

        when (data) {
            "ascii" -> {
                // column index of x/y/z in the expanded token list
                val col = IntArray(fields.size)
                for (i in 1 until fields.size) col[i] = col[i - 1] + counts[i - 1]
                var i = 0
                while (i < points) {
                    val line = readLine(ins) ?: break
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    if (i % stride == 0 && n < outN) {
                        val tok = t.split(Regex("\\s+"))
                        val x = tok[col[ix]].toFloat(); val y = tok[col[iy]].toFloat(); val z = tok[col[iz]].toFloat()
                        if (x.isFinite() && y.isFinite() && z.isFinite()) {
                            xyz[3 * n] = x; xyz[3 * n + 1] = y; xyz[3 * n + 2] = z; n++
                        }
                    }
                    i++
                }
            }
            "binary" -> {
                val chunkPts = 65536
                val buf = ByteArray(chunkPts * step)
                var read = 0
                while (read < points) {
                    val m = minOf(chunkPts, points - read)
                    ins.readFully(buf, 0, m * step)
                    val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                    for (j in 0 until m) {
                        val gi = read + j
                        if (gi % stride != 0 || n >= outN) continue
                        val base = j * step
                        val x = value(b, base + offs[ix], ix); val y = value(b, base + offs[iy], iy)
                        val z = value(b, base + offs[iz], iz)
                        if (x.isFinite() && y.isFinite() && z.isFinite()) {
                            xyz[3 * n] = x; xyz[3 * n + 1] = y; xyz[3 * n + 2] = z; n++
                        }
                    }
                    read += m
                }
            }
            "binary_compressed" -> {
                val hdr = ByteArray(8); ins.readFully(hdr)
                val hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val csize = hb.getInt(0); val usize = hb.getInt(4)
                val comp = ByteArray(csize); ins.readFully(comp)
                val raw = lzfDecompress(comp, usize)
                val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                // structure-of-arrays: field f starts at points * offs[f]
                for (gi in 0 until points) {
                    if (gi % stride != 0 || n >= outN) continue
                    val x = value(b, points * offs[ix] + gi * sizes[ix] * counts[ix], ix)
                    val y = value(b, points * offs[iy] + gi * sizes[iy] * counts[iy], iy)
                    val z = value(b, points * offs[iz] + gi * sizes[iz] * counts[iz], iz)
                    if (x.isFinite() && y.isFinite() && z.isFinite()) {
                        xyz[3 * n] = x; xyz[3 * n + 1] = y; xyz[3 * n + 2] = z; n++
                    }
                }
            }
            else -> error("未対応の DATA 形式: $data")
        }
        return PcdData(xyz, n, points, fields.joinToString(" "), data)
    }

    private fun readLine(ins: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
        }
    }

    private fun lzfDecompress(src: ByteArray, outLen: Int): ByteArray {
        val out = ByteArray(outLen)
        var ip = 0; var op = 0
        while (ip < src.size) {
            val ctrl = src[ip++].toInt() and 0xFF
            if (ctrl < 32) {
                val len = ctrl + 1
                System.arraycopy(src, ip, out, op, len); ip += len; op += len
            } else {
                var len = ctrl shr 5
                var ref = op - ((ctrl and 0x1F) shl 8) - 1
                if (len == 7) len += src[ip++].toInt() and 0xFF
                ref -= src[ip++].toInt() and 0xFF
                len += 2
                repeat(len) { out[op++] = out[ref++] }
            }
        }
        return out
    }
}
