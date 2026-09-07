package com.lancehouse.goniometer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Saves an export artifact into the phone's own public Downloads folder
 * (`Download/goniometer/`) so it shows up in any on-phone file manager and
 * over a normal USB (MTP) file-transfer session — no adb, no dev tools. This
 * is the offline path for when KDE Connect can't reach the laptop (e.g. clinic
 * wifi with AP client isolation, which silently blocks device-to-device
 * traffic even on the same subnet).
 */
object DownloadsExporter {
    private const val SUBDIR = "goniometer"

    /** Copies [file] into Download/goniometer/[displayName]. Returns the written Uri, or null on failure. */
    fun exportToDownloads(context: Context, file: File, displayName: String, mimeType: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(context, file, displayName, mimeType)
            } else {
                exportViaLegacyStorage(file, displayName)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun exportViaMediaStore(context: Context, file: File, displayName: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val wrote = resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return if (wrote != null) uri else null
    }

    @Suppress("DEPRECATION")
    private fun exportViaLegacyStorage(file: File, displayName: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
        dir.mkdirs()
        val dest = File(dir, displayName)
        file.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest)
    }
}
