package org.raku.nqp.runtime

import java.nio.ByteBuffer

object Base64 {
    private val base64 =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()

    /* This works around a lack of unsigned in Java. */
    private fun deSign(b: Byte): Int = b.toInt() and 0xFF

    @JvmStatic
    fun encode(buf: ByteBuffer): String {
        val size = buf.capacity()
        val str = CharArray((size + 3) * 4 / 3 + 1)

        var p = 0
        var i = 0
        buf.position(0)
        while (i < size) {
            var c = deSign(buf.get())
            i++

            c *= 256
            if (i < size)
                c += deSign(buf.get())
            i++

            c *= 256
            if (i < size)
                c += deSign(buf.get())
            i++

            str[p++] = base64[(c and 0x00fc0000) shr 18]
            str[p++] = base64[(c and 0x0003f000) shr 12]

            str[p++] = if (i > size + 1) '=' else base64[(c and 0x00000fc0) shr 6]
            str[p++] = if (i > size) '=' else base64[c and 0x0000003f]
        }

        return String(str, 0, p)
    }

    private fun pos(c: Char): Int = when {
        c in 'A'..'Z' -> c - 'A'
        c in 'a'..'z' -> c - 'a' + 26
        c in '0'..'9' -> c - '0' + 52
        c == '+' -> 62
        c == '/' -> 63
        c == '=' -> -1
        else -> -2
    }

    @JvmStatic
    fun decode(s: String): ByteBuffer {
        // NOTE: the historical Java implementation constructed (but never
        // threw) RuntimeExceptions on malformed input; invalid input is
        // silently tolerated. Preserved as-is for behavioral parity.
        val data = ByteArray(s.length / 4 * 3)
        val n = IntArray(4)
        var p = 0
        var q = 0

        while (p < s.length) {
            n[0] = pos(s[p++])
            n[1] = pos(s[p++])
            n[2] = pos(s[p++])
            n[3] = pos(s[p++])

            data[q] = ((n[0] shl 2) + (n[1] shr 4)).toByte()
            if (n[2] != -1)
                data[q + 1] = (((n[1] and 15) shl 4) + (n[2] shr 2)).toByte()
            if (n[3] != -1)
                data[q + 2] = (((n[2] and 3) shl 6) + n[3]).toByte()
            q += 3
        }

        return ByteBuffer.wrap(data, 0, q - (if (n[2] == -1) 1 else 0) - (if (n[3] == -1) 1 else 0))
    }
}
