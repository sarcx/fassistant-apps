package dev.todor.fassistantapps.install

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Lets Android's installer read a downloaded APK, and nothing else.
 *
 * An install intent has to carry a content:// address: this app targets 25, where a file:// one
 * throws, and FileProvider lives in AndroidX, which this app does not use. Only files directly in
 * the download directory are served, and only to whoever the intent granted read access.
 */
class ApkProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(apk(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri) = MIME_TYPE

    // Some installers ask for the name and size before reading.
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val file = apk(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .apply { addRow(arrayOf<Any>(file.name, file.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    private fun apk(uri: Uri): File {
        val directory = Installer.downloads(context!!)
        val file = File(directory, uri.lastPathSegment ?: throw FileNotFoundException(uri.toString()))
        if (file.parentFile != directory || !file.isFile) throw FileNotFoundException(uri.toString())
        return file
    }

    companion object {
        const val MIME_TYPE = "application/vnd.android.package-archive"
    }
}
