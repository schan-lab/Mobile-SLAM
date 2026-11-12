package com.example.mobilendt

import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Shared GL program for drawing xyz points / lines, coloured by height or a flat colour. Use on the GL thread. */
class GlPoints {
    private var prog = 0
    private var uMvp = 0
    private var uSize = 0
    private var uMode = 0
    private var uColor = 0
    private var uZ = 0

    fun init() {
        prog = program(VS, FS)
        uMvp = glGetUniformLocation(prog, "uMvp")
        uSize = glGetUniformLocation(prog, "uSize")
        uMode = glGetUniformLocation(prog, "uMode")
        uColor = glGetUniformLocation(prog, "uColor")
        uZ = glGetUniformLocation(prog, "uZ")
    }

    fun genBuffers(n: Int): IntArray = IntArray(n).also { glGenBuffers(n, it, 0) }

    /** Starts a frame: binds program, sets matrix and height range for colouring. */
    fun begin(mvp: FloatArray, zMin: Float, zMax: Float) {
        glUseProgram(prog)
        glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        glUniform2f(uZ, zMin, zMax)
    }

    fun setZRange(zMin: Float, zMax: Float) = glUniform2f(uZ, zMin, zMax)

    fun upload(id: Int, buf: FloatBuffer, arr: FloatArray, n: Int, usage: Int = GL_DYNAMIC_DRAW) {
        if (n <= 0) return
        buf.clear()
        buf.put(arr, 0, n * 3)
        buf.flip()
        glBindBuffer(GL_ARRAY_BUFFER, id)
        glBufferData(GL_ARRAY_BUFFER, n * 12, buf, usage)
    }

    /** mode 0 = height colour, 1 = flat colour (r,g,b). */
    fun draw(id: Int, prim: Int, n: Int, mode: Int, size: Float, r: Float = 1f, g: Float = 1f, b: Float = 1f, first: Int = 0) {
        if (n <= 0) return
        glBindBuffer(GL_ARRAY_BUFFER, id)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        glUniform1f(uSize, size)
        glUniform1i(uMode, mode)
        glUniform3f(uColor, r, g, b)
        glDrawArrays(prim, first, n)
    }

    private fun shader(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(glGetShaderInfoLog(s))
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glBindAttribLocation(p, 0, "aPos")
        glLinkProgram(p)
        return p
    }

    companion object {
        fun floatBuffer(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private const val VS = """#version 300 es
            layout(location = 0) in vec3 aPos;
            uniform mat4 uMvp; uniform float uSize; uniform int uMode; uniform vec3 uColor; uniform vec2 uZ;
            out vec3 vColor;
            vec3 hue(float t) {  // blue -> cyan -> green -> yellow -> red
                t = clamp(t, 0.0, 1.0);
                return clamp(vec3(abs(t * 4.0 - 3.0) - 1.0, 2.0 - abs(t * 4.0 - 2.0), 2.0 - abs(t * 4.0 - 1.0)), 0.0, 1.0);
            }
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                gl_PointSize = uSize;
                vColor = uMode == 0 ? hue((aPos.z - uZ.x) / (uZ.y - uZ.x)) : uColor;
            }"""
        private const val FS = """#version 300 es
            precision mediump float;
            in vec3 vColor; out vec4 frag;
            void main() { frag = vec4(vColor, 1.0); }"""
    }
}
