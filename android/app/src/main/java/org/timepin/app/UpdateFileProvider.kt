package org.timepin.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** The installer receives a read grant for one immutable, verified APK. */
class UpdateFileProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"

    private fun apk(uri: Uri): File {
        val name = uri.pathSegments.singleOrNull()
        if (name == null || !name.matches(Regex("[0-9a-f]{64}\\.apk"))) throw FileNotFoundException()
        return File(requireNotNull(context).cacheDir, "updates/" + name)
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException()
        return ParcelFileDescriptor.open(apk(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): MatrixCursor {
        val file = apk(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when(it) {
                OpenableColumns.DISPLAY_NAME -> "TimePin.apk"
                OpenableColumns.SIZE -> file.length()
                else -> null
            } }.toTypedArray())
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
}
