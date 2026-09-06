package com.lancehouse.goniometer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a saved session file to KDE Connect (the Android app GSConnect
 * interoperates with) so it lands in ~/GSConnectInbox on the paired laptop.
 *
 * Sets Intent.setPackage() to KDE Connect's package directly rather than
 * leaving the share intent open — that skips Android's "which app?" chooser
 * entirely and jumps straight into KDE Connect's own device-picker, which is
 * as close to a one-tap send as the public Android APIs allow (KDE Connect
 * doesn't publish a "send to device X, no UI at all" API for third parties).
 * Falls back to the normal share sheet if KDE Connect isn't installed, so
 * Send still works (just with an extra step) rather than silently failing.
 */
object SessionSharing {
    private const val KDE_CONNECT_PACKAGE = "org.kde.kdeconnect_tp"

    fun send(context: Context, sessionFile: File) {
        val exportFile = SessionStorage.exportFileFor(context, sessionFile)
        val uri = FileProvider.getUriForFile(
            context,
            "com.lancehouse.goniometer.fileprovider",
            exportFile,
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
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

    /**
     * Hands the loose chart PNGs for a session to KDE Connect as a multi-file
     * share (ACTION_SEND_MULTIPLE) — same one-tap-as-possible `setPackage`
     * trick and same chooser fallback as [send]. The charts are also embedded
     * in the `.gonio.json` that [send] transfers; this path is for when you
     * just want the images to drop straight into a report on the laptop.
     */
    fun sendCharts(context: Context, chartFiles: List<File>) {
        if (chartFiles.isEmpty()) return
        val uris = ArrayList<android.net.Uri>(chartFiles.map {
            FileProvider.getUriForFile(context, "com.lancehouse.goniometer.fileprovider", it)
        })

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/png"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(KDE_CONNECT_PACKAGE)
        }

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            intent.setPackage(null)
            context.startActivity(Intent.createChooser(intent, "Send charts"))
        }
    }
}
