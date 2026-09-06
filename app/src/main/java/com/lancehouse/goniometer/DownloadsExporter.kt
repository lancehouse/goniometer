package com.lancehouse.goniometer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * USB fallback for getting a session off the phone when GSConnect/KDE
 * Connect can't reach the laptop — confirmed in practice that some
 * enterprise/clinic wifi enables AP client isolation, which silently blocks
 * the direct device-to-device traffic KDE Connect needs even when both
 * devices are on the same subnet. Writes into the public Downloads folder
 * (Download/goniometer/) so the file shows up in a normal USB
 * file-transfer session or PC file browser — no adb, no dev tools needed.
 */
object DownloadsExporter {
    private const val SUBDIR = "goniometer"

    /** Returns the written Uri, or null if the write failed. */
    fun exportToDownloads(context: Context, sessionFile: File): Uri? {
        val displayName = "${sessionFile.nameWithoutExtension}.gonio.json"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(context, sessionFile, displayName)
            } else {
                exportViaLegacyStorage(sessionFile, displayName)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun exportViaMediaStore(context: Context, sessionFile: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val wrote = resolver.openOutputStream(uri)?.use { out ->
            sessionFile.inputStream().use { it.copyTo(out) }
        }
        return if (wrote != null) uri else null
    }

    @Suppress("DEPRECATION")
    private fun exportViaLegacyStorage(sessionFile: File, displayName: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
        dir.mkdirs()
        val dest = File(dir, displayName)
        sessionFile.copyTo(dest, overwrite = true)
        return Uri.fromFile(dest)
    }
}
