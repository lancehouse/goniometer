package com.lancehouse.goniometer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists a whole cluster (a clinic-visit batch of kept measurements —
 * shoulder abduction, elbow flexion, etc.) as one JSON file per visit.
 * Filenames follow pab's own session-naming convention
 * (`<code>_<DD_MM_YYYY_HHMM>`, see ~/Projects/pab/SESSION_JSON_SCHEMA.md) so
 * they can be matched up by patient code and rough time once the pab-side
 * importer exists. Deliberately org.json rather than a serialization library
 * — no new dependency needed for a handful of flat fields.
 *
 * The session JSON holds only the numeric data (samples/marks/ranges) so it
 * stays small and cheap to list and rewrite. Chart PNGs are NOT stored here —
 * they are rendered on demand into [exportDir] only when the user exports.
 * Writes are atomic (temp file + rename) so an interrupted save can never
 * corrupt or lose an existing session.
 */
/** One saved-to-disk cluster file, as listed on the "Saved sessions" history screen. [readable] is false for a file that won't parse — it's still listed (so it can't silently vanish), just not openable. */
data class SavedSessionMeta(
    val file: File,
    val sessionName: String,
    val patientCode: String,
    val createdMs: Long,
    val measurementCount: Int,
    val readable: Boolean = true,
)

object SessionStorage {
    private const val PREFS_NAME = "goniometer_prefs"
    private const val KEY_LAST_CODE = "last_patient_code"
    private const val KEY_SENT_SESSIONS = "sent_sessions"
    private const val KEY_EXPORTED_SESSIONS = "exported_sessions"

    private fun timestampFormat() = SimpleDateFormat("dd_MM_yyyy_HHmm", Locale.US)

    fun lastPatientCode(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_CODE, "") ?: ""

    private fun setLastPatientCode(context: Context, code: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_CODE, code).apply()
    }

    private fun sessionsDir(context: Context): File =
        File(context.filesDir, "sessions").apply { mkdirs() }

    /** Separate from [sessionsDir] so export artifacts never clutter the primary listing. Covered by res/xml/file_paths.xml's `export/` root for FileProvider. */
    private fun exportDir(context: Context): File =
        File(context.filesDir, "export").apply { mkdirs() }

    /** The export bundle zip for a session (built on demand by [BundleExporter]). */
    fun bundleFileFor(context: Context, sessionName: String): File =
        File(exportDir(context), "$sessionName.gonio.zip")

    /** Writes [text] to [file] atomically: fully write a sibling `.tmp`, then rename over the target. A crash mid-write leaves the old file (or the recoverable `.tmp`) intact, never a truncated target. */
    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // Some filesystems won't rename onto an existing file.
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(text) // last resort — non-atomic
                tmp.delete()
            }
        }
    }

    fun isSent(context: Context, sessionName: String): Boolean =
        sentSessionNames(context).contains(sessionName)

    fun markSent(context: Context, sessionName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = sentSessionNames(context) + sessionName
        prefs.edit().putStringSet(KEY_SENT_SESSIONS, updated).apply()
    }

    private fun sentSessionNames(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_SENT_SESSIONS, emptySet()) ?: emptySet()

    fun isExported(context: Context, sessionName: String): Boolean =
        exportedSessionNames(context).contains(sessionName)

    fun markExported(context: Context, sessionName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = exportedSessionNames(context) + sessionName
        prefs.edit().putStringSet(KEY_EXPORTED_SESSIONS, updated).apply()
    }

    private fun exportedSessionNames(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXPORTED_SESSIONS, emptySet()) ?: emptySet()

    /** Deletes a saved session file (and any temp / export artifacts) and forgets its sent/exported state. */
    fun deleteSession(context: Context, meta: SavedSessionMeta) {
        meta.file.delete()
        File(meta.file.parentFile, "${meta.file.name}.tmp").delete()
        bundleFileFor(context, meta.sessionName).delete()
        File(exportDir(context), "${meta.sessionName}.gonio.zip.tmp").delete()
        File(exportDir(context), "${meta.sessionName}.gonio.json").delete() // legacy JSON-only export
        File(exportDir(context), meta.sessionName).deleteRecursively() // legacy loose chart PNGs

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_SENT_SESSIONS, sentSessionNames(context) - meta.sessionName)
            .putStringSet(KEY_EXPORTED_SESSIONS, exportedSessionNames(context) - meta.sessionName)
            .apply()
    }

    /**
     * Every cluster saved so far, most recent first. Recovers an orphaned
     * `.tmp` (interrupted save) when its target is missing or unreadable, and
     * lists an unparseable file as `readable = false` rather than dropping it —
     * a saved session must never silently disappear from this screen.
     */
    fun listSavedSessions(context: Context): List<SavedSessionMeta> {
        val dir = sessionsDir(context)

        dir.listFiles { f -> f.isFile && f.name.endsWith(".json.tmp") }?.forEach { tmp ->
            val target = File(tmp.parentFile, tmp.name.removeSuffix(".tmp"))
            val targetOk = target.isFile && parseOrNull(target) != null
            when {
                !targetOk && parseOrNull(tmp) != null -> tmp.renameTo(target)
                targetOk -> tmp.delete() // stale leftover
            }
        }

        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        return files.map { file ->
            val json = parseOrNull(file)
            if (json != null) {
                SavedSessionMeta(
                    file = file,
                    sessionName = json.optString("session_name", file.nameWithoutExtension),
                    patientCode = json.optString("patient_code", "UNK"),
                    createdMs = json.optLong("created_ms", file.lastModified()),
                    measurementCount = json.optJSONArray("measurements")?.length() ?: 0,
                )
            } else {
                SavedSessionMeta(
                    file = file,
                    sessionName = file.nameWithoutExtension,
                    patientCode = file.nameWithoutExtension.substringBefore('_', "UNK"),
                    createdMs = file.lastModified(),
                    measurementCount = 0,
                    readable = false,
                )
            }
        }.sortedByDescending { it.createdMs }
    }

    private fun parseOrNull(file: File): JSONObject? =
        try { JSONObject(file.readText()) } catch (e: Exception) { null }

    /** Reconstructs a saved cluster's measurements, or null if the file is unreadable. */
    fun loadCluster(meta: SavedSessionMeta): List<CapturedMeasurement>? {
        return try {
            val measurements = JSONObject(meta.file.readText()).getJSONArray("measurements")
            (0 until measurements.length()).map { i -> measurementFromJson(measurements.getJSONObject(i)) }
        } catch (e: Exception) {
            null
        }
    }

    private fun measurementFromJson(m: JSONObject): CapturedMeasurement {
        val samplesJson = m.getJSONArray("samples")
        val samples = (0 until samplesJson.length()).map { i ->
            val s = samplesJson.getJSONObject(i)
            Sample(
                tMs = s.getLong("t_ms"),
                roll = s.getDouble("roll").toFloat(),
                pitch = s.getDouble("pitch").toFloat(),
                yaw = s.getDouble("yaw").toFloat(),
                totalAngleDeg = s.getDouble("total_angle_deg").toFloat(),
            )
        }
        val marksJson = m.getJSONArray("marks")
        val marks = (0 until marksJson.length()).map { i ->
            val mk = marksJson.getJSONObject(i)
            Mark(tMs = mk.getLong("t_ms"), sampleIndex = mk.getInt("sample_index"))
        }
        val ranges = m.getJSONArray("ranges")
        val result = SessionResult(
            samples = samples,
            marks = marks,
            ranges = floatArrayOf(ranges.getDouble(0).toFloat(), ranges.getDouble(1).toFloat(), ranges.getDouble(2).toFloat()),
            primaryChannelIndex = m.getInt("primary_channel_index"),
        )
        val romType = try {
            RomType.valueOf(m.optString("rom_type", "AROM"))
        } catch (e: IllegalArgumentException) {
            RomType.AROM
        }
        return CapturedMeasurement(label = if (m.isNull("label")) "" else m.optString("label"), result = result, romType = romType)
    }

    /**
     * Rewrites [file]'s measurement labels in place (atomically), keeping
     * session_name/patient_code/created_ms untouched. Clears sent/exported
     * state for [sessionName] since any copy already handed off is now stale.
     */
    fun updateLabels(context: Context, file: File, sessionName: String, updated: List<CapturedMeasurement>) {
        val json = JSONObject(file.readText())
        json.put("measurements", JSONArray(updated.map { measurementJson(it) }))
        atomicWrite(file, json.toString(2))

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_SENT_SESSIONS, sentSessionNames(context) - sessionName)
            .putStringSet(KEY_EXPORTED_SESSIONS, exportedSessionNames(context) - sessionName)
            .apply()
    }

    /**
     * Writes every measurement in [cluster] under [patientCode] as one file
     * (atomically) and returns it. If a file for the same code+minute already
     * exists (a second batch saved within the same clinic-visit minute), a
     * numeric suffix is appended so nothing is silently overwritten.
     */
    fun saveCluster(context: Context, patientCode: String, cluster: List<CapturedMeasurement>): File {
        val code = patientCode.trim().uppercase().ifEmpty { "UNK" }
        val stamp = timestampFormat().format(Date())
        val dir = sessionsDir(context)

        var name = "${code}_${stamp}"
        var file = File(dir, "$name.json")
        var suffix = 2
        while (file.exists()) {
            name = "${code}_${stamp}_$suffix"
            file = File(dir, "$name.json")
            suffix++
        }

        val json = JSONObject().apply {
            put("patient_code", code)
            put("session_name", name)
            put("created_ms", System.currentTimeMillis())
            put("measurements", JSONArray(cluster.map { measurementJson(it) }))
        }

        atomicWrite(file, json.toString(2))
        setLastPatientCode(context, code)
        return file
    }

    private fun measurementJson(measurement: CapturedMeasurement): JSONObject {
        val result = measurement.result
        val trimmedLabel = measurement.label.trim()
        return JSONObject().apply {
            put("label", if (trimmedLabel.isEmpty()) JSONObject.NULL else trimmedLabel)
            put("rom_type", measurement.romType.name)
            put("primary_channel_index", result.primaryChannelIndex)
            put("ranges", JSONArray(result.ranges.map { it.toDouble() }))
            put("samples", JSONArray(result.samples.map { s ->
                JSONObject().apply {
                    put("t_ms", s.tMs)
                    put("roll", s.roll.toDouble())
                    put("pitch", s.pitch.toDouble())
                    put("yaw", s.yaw.toDouble())
                    put("total_angle_deg", s.totalAngleDeg.toDouble())
                }
            }))
            put("marks", JSONArray(result.marks.map { m ->
                JSONObject().apply {
                    put("t_ms", m.tMs)
                    put("sample_index", m.sampleIndex)
                }
            }))
        }
    }
}
