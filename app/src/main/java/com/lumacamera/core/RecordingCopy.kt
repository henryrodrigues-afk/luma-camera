package com.lumacamera.core

import java.io.InputStream
import java.io.OutputStream

/** Copy exactly the immutable snapshot; partial output remains pending and is deleted by its owner. */
object RecordingCopy {
    fun copy(input: InputStream, output: OutputStream, expectedBytes: Long,
        onProgress: (Long) -> Unit = {}): Long {
        require(expectedBytes >= 0L)
        val buffer = ByteArray(256 * 1024)
        var copied = 0L
        while (true) {
            var count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                buffer[0] = single.toByte()
                count = 1
            }
            check(count.toLong() <= expectedBytes - copied) {
                "O original mudou durante a cópia. Original preservado."
            }
            output.write(buffer, 0, count)
            copied += count
            // Do not release reservation for a write that failed, including a partially written one.
            onProgress(copied)
        }
        check(copied == expectedBytes) { "A cópia não corresponde ao original completo. Original preservado." }
        return copied
    }
}
