package com.lancehouse.goniometer

import android.content.Context
import com.lancehouse.goniometer.ui.channelPeak
import com.lancehouse.goniometer.ui.channelValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the export artifact for a saved session: a single
 * `‹sessionName›.gonio.zip` containing
 *
 *   manifest.json   — the integration contract (see below); denormalised,
 *                     everything a consumer needs without touching raw samples
 *   session.json    — the on-disk session file verbatim, for reprocessing
 *   charts/NN_‹slug›_‹AROM|PROM›.png — one rendered chart per measurement
 *
 * The zip is one atomic unit so a consumer watching an inbox never sees a
 * partial set. Double extension `.gonio.zip` lets a laptop-side router pick it
 * out. Charts are rendered here, on the export action only — never on the save
 * path.
 *
 * manifest.json schema (bundle_schema = 2):
 *   {
 *     bundle_schema, session_name, patient_code, created_ms, created_iso,
 *     app_version,
 *     measurements: [{
 *       index (1-based), label (nullable), rom_type ("AROM"|"PROM"),
 *       primary_channel (0..2), primary_channel_label ("Range A/B/C" — neutral,
 *         no anatomical plane is claimed), primary_range_deg,
 *       deficit_to_full_deg (180 − primary), secondary_ranges_deg [n,n],
 *       min_deg/min_t_ms + max_deg/max_t_ms (primary-channel extremes vs
 *         baseline — motion can go either way from 0, so both are reported;
 *         primary_range_deg == max − min), total_angular_sweep_deg
 *         (gimbal-lock-immune cross-check), mark_count,
 *         marks_deg [primary-channel angle at each mark, capture order —
 *           length always == mark_count; an entry is null only if the mark
 *           points outside the sample stream (corrupt/hand-edited file)],
 *         sample_count, duration_ms, chart_png (zip-relative path)
 *     }]
 *   }
 * Consumers match a session on patient_code + created_ms; the joint/plane is
 * derived downstream from `label` + `rom_type`, not encoded here.
 *
 * Schema history:
 *   1 → 2 (2026-09-07): added measurements[].marks_deg for the gpab
 *     AROM/PROM value format `110 (-8->102) ; 43 ; 98`. A schema-1 bundle
 *     (marks_deg absent) makes the consumer fall back to range-only.
 */
object BundleExporter {
    const val BUNDLE_SCHEMA = 2

    /** Builds (or rebuilds) the bundle zip for [meta] and returns it, or null if the session can't be read. */
    fun buildBundle(context: Context, meta: SavedSessionMeta): File? {
        val cluster = SessionStorage.loadCluster(meta) ?: return null
        val target = SessionStorage.bundleFileFor(context, meta.sessionName)
        val tmp = File(target.parentFile, "${target.name}.tmp")

        val manifest = buildManifest(context, meta, cluster)

        ZipOutputStream(FileOutputStream(tmp)).use { zip ->
            zip.entry("manifest.json", manifest.toString(2).toByteArray())
            if (meta.file.isFile) zip.entry("session.json", meta.file.readBytes())
            cluster.forEachIndexed { i, m ->
                zip.entry("charts/${chartEntryName(i, m)}", ChartRenderer.renderPng(m, meta.sessionName))
            }
        }

        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true); tmp.delete()
            }
        }
        return target
    }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun buildManifest(context: Context, meta: SavedSessionMeta, cluster: List<CapturedMeasurement>): JSONObject {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(meta.createdMs))

        val version = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "${pi.versionName} ($code)"
        } catch (e: Exception) {
            "unknown"
        }

        return JSONObject().apply {
            put("bundle_schema", BUNDLE_SCHEMA)
            put("session_name", meta.sessionName)
            put("patient_code", meta.patientCode)
            put("created_ms", meta.createdMs)
            put("created_iso", iso)
            put("app_version", version)
            put("measurements", JSONArray(cluster.mapIndexed { i, m -> measurementJson(i, m) }))
        }
    }

    private fun measurementJson(index0: Int, m: CapturedMeasurement): JSONObject {
        val r = m.result
        val peak = channelPeak(r.samples, r.primaryChannelIndex)
        val sweep = if (r.samples.isEmpty()) 0f
            else r.samples.maxOf { it.totalAngleDeg } - r.samples.minOf { it.totalAngleDeg }
        val trimmed = m.label.trim()

        return JSONObject().apply {
            put("index", index0 + 1)
            put("label", if (trimmed.isEmpty()) JSONObject.NULL else trimmed)
            put("rom_type", m.romType.name)
            put("primary_channel", r.primaryChannelIndex)
            put("primary_channel_label", r.primaryLabel)
            put("primary_range_deg", round1(r.primaryRangeDeg))
            put("deficit_to_full_deg", round1(r.deficitToFullDeg))
            put("secondary_ranges_deg", JSONArray(r.secondaryChannels().map { round1(it.second) }))
            put("min_deg", round1(peak.minV))
            put("min_t_ms", peak.minTMs)
            put("max_deg", round1(peak.maxV))
            put("max_t_ms", peak.maxTMs)
            put("total_angular_sweep_deg", round1(sweep))
            put("mark_count", r.marks.size)
            put("marks_deg", JSONArray(r.marks.map { mk ->
                // Primary-channel angle at the mark, in capture order. Position
                // is preserved (never skipped) so marks_deg.size == mark_count;
                // SessionState.mark() can't produce an out-of-range index for a
                // freshly-captured session, but a loaded file might — emit null
                // rather than crash the export.
                if (mk.sampleIndex in r.samples.indices)
                    round1(channelValue(r.samples[mk.sampleIndex], r.primaryChannelIndex))
                else JSONObject.NULL
            }))
            put("sample_count", r.samples.size)
            put("duration_ms", r.samples.lastOrNull()?.tMs ?: 0L)
            put("chart_png", "charts/${chartEntryName(index0, m)}")
        }
    }

    /** Zip-relative PNG name: `NN_‹slug›_‹AROM|PROM›.png` (the zip name already carries the session). */
    fun chartEntryName(index0: Int, m: CapturedMeasurement): String =
        "${"%02d".format(index0 + 1)}_${slug(m.label)}_${m.romType.name}.png"

    private fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "unlabeled" }

    private fun round1(f: Float): Double = Math.round(f * 10.0) / 10.0
}
