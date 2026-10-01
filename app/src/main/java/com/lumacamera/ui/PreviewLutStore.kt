package com.lumacamera.ui

import android.content.Context
import com.lumacamera.effects.CubeLut
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class PreviewLutEntry(val file: String, val title: String)

/** Imports a bounded copy. URI access is needed once; later sessions use an app-private LUT. */
class PreviewLutStore(context: Context) {
    private val directory = File(context.filesDir, "preview-luts")
    private val names = context.getSharedPreferences("luma-luts", Context.MODE_PRIVATE)
    fun entries(): List<PreviewLutEntry> = directory.listFiles()?.filter { validName(it.name) }?.take(24)
        ?.map { PreviewLutEntry(it.name, names.getString(it.name, it.name) ?: it.name) }?.sortedBy { it.title } ?: emptyList()
    fun load(name: String?): CubeLut? {
        if (name == null || !validName(name)) return null
        val file = File(directory, name)
        check(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "LUT ausente ou muito grande" }
        return CubeLut.parse(file.readText(Charsets.UTF_8))
    }
    @Synchronized fun importLut(input: InputStream): PreviewLutEntry {
        val bytes = input.readBytesBounded(MAX_BYTES)
        val content = bytes.toString(Charsets.UTF_8)
        val lut = CubeLut.parse(content)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
        val name = "lut_$digest.cube"
        check(directory.exists() || directory.mkdirs()) { "Não foi possível criar a biblioteca de LUTs" }
        val destination = File(directory, name)
        if (!destination.exists()) {
            check(entries().size < 24) { "Biblioteca cheia: remova uma LUT para importar outra" }
            val temp = File.createTempFile("import-", ".tmp", directory)
            try { temp.writeBytes(bytes); check(temp.renameTo(destination)) { "Não foi possível salvar a LUT" } }
            finally { temp.delete() }
        }
        val title = lut.title.take(80).ifBlank { "LUT ${lut.size}³" }
        names.edit().putString(name, title).apply()
        return PreviewLutEntry(name, title)
    }
    @Synchronized fun remove(name: String) { if (validName(name)) { File(directory, name).delete(); names.edit().remove(name).apply() } }
    private fun validName(name: String) = name.matches(Regex("lut_[a-f0-9]{16}\\.cube"))
    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (true) {
            val read = read(block)
            if (read < 0) break
            check(output.size() + read <= limit) { "A LUT deve ter até 4 MiB" }
            output.write(block, 0, read)
        }
        return output.toByteArray()
    }
    companion object { private const val MAX_BYTES = 4 * 1024 * 1024 }
}
