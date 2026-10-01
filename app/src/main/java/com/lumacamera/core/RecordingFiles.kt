package com.lumacamera.core

import java.io.File

/** Shared by the private-video library and its read-only content provider. */
object RecordingFiles {
    fun isRecordingName(name: String): Boolean = name.startsWith("LUMA_") &&
        name.endsWith(".mp4", ignoreCase = true) && name.length > 9 &&
        name.none { it == '/' || it == '\\' || it == '\u0000' } && name != "." && name != ".."

    fun resolve(directory: File, name: String): File {
        require(isRecordingName(name)) { "Nome de gravação inválido." }
        val parent = directory.canonicalFile
        val file = File(parent, name).canonicalFile
        require(file.parentFile == parent) { "A gravação está fora da pasta permitida." }
        return file
    }
}
