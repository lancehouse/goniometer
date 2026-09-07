package com.lancehouse.goniometer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands the export bundle (`‹sessionName›.gonio.zip`, see [BundleExporter]) to
 * KDE Connect so it lands in ~/GSConnectInbox on the paired laptop.
 *
 * Sets Intent.setPackage() to KDE Connect's package directly rather than
 * leaving the share intent open — that skips Android's "which app?" chooser
 * and jumps straight into KDE Connect's own device-picker, which is as close
 * to one-tap as the public APIs allow. Falls back to the normal share sheet if
 * KDE Connect isn't installed, so Send still works (with an extra step).
 */
object SessionSharing {
    private const val KDE_CONNECT_PACKAGE = "org.kde.kdeconnect_tp"

    fun send(context: Context, bundle: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "com.lancehouse.goniometer.fileprovider",
            bundle,
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(KDE_CONNECT_PACKAGE)
        }

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // KDE Connect isn't installed — fall back to the full share sheet
            // instead of failing silently.
            intent.setPackage(null)
            context.startActivity(Intent.createChooser(intent, "Send session"))
        }
    }
}
