package com.lumacamera.camera

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.lumacamera.core.RecordingFiles
import java.io.File
import java.io.FileNotFoundException

/** Grants read access only to an explicitly shared private recording, never the whole files directory. */
class RecordingFileProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String { resolve(uri); return "video/mp4" }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Gravações compartilhadas permitem somente leitura.")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = resolve(uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Somente leitura")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException("Somente leitura")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException("Somente leitura")

    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("Provedor indisponível.")
        if (uri.scheme != "content" || uri.authority != authority(ctx) || uri.pathSegments.size != 2 ||
            uri.pathSegments.first() != "recordings") throw FileNotFoundException("Gravação inválida.")
        val file = try { RecordingFiles.resolve(File(ctx.filesDir, "recordings"), uri.pathSegments.last()) }
        catch (_: IllegalArgumentException) { throw FileNotFoundException("Gravação inválida.") }
        if (!file.isFile) throw FileNotFoundException("A gravação não existe mais.")
        return file
    }

    companion object {
        private fun authority(context: Context) = "${context.packageName}.recordings"
        fun uriFor(context: Context, file: File): Uri {
            val allowed = RecordingFiles.resolve(File(context.filesDir, "recordings"), file.name)
            require(allowed == file.canonicalFile) { "Arquivo fora da pasta de gravações." }
            return Uri.Builder().scheme("content").authority(authority(context))
                .appendPath("recordings").appendPath(file.name).build()
        }
    }
}
