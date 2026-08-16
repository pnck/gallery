package io.github.pnck.gallery.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.pnck.gallery.domain.TimelineSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsStore by preferencesDataStore(name = "app_settings")

/**
 * User-visible, non-secret app settings (secrets stay in EncryptedSharedPreferences,
 * PRD §5.2). Currently just the name of the cloud backup folder.
 */
class AppSettingsStore(private val context: Context) {

    private val folderKey = stringPreferencesKey("remote_folder_name")
    private val backupPausedKey = booleanPreferencesKey("backup_paused")
    private val scanBucketsKey = stringSetPreferencesKey("scan_bucket_ids")
    private val sortKey = stringPreferencesKey("timeline_sort")
    private val sizeBackfilledKey = booleanPreferencesKey("size_backfilled_v3")
    private val initialScanDoneKey = booleanPreferencesKey("initial_scan_done")
    private val logLevelKey = stringPreferencesKey("transport_log_level")
    private val blindScanAtKey = longPreferencesKey("blind_scan_at")
    private val probeScheduledAtKey = longPreferencesKey("autostart_probe_scheduled_at")
    private val probeCompletedAtKey = longPreferencesKey("autostart_probe_completed_at")
    private val accountEmailKey = stringPreferencesKey("account_email")
    private val storageTreeKey = stringPreferencesKey("saf_tree_uri")

    /** Name of the app's cloud folder that uploads are pinned to. */
    val remoteFolderName: Flow<String> = context.appSettingsStore.data.map { prefs ->
        prefs[folderKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_FOLDER_NAME
    }

    /** When true, the bulk background backup is paused (explicit uploads still run). */
    val backupPaused: Flow<Boolean> = context.appSettingsStore.data.map { it[backupPausedKey] ?: false }

    /**
     * The scan allowlist: MediaStore BUCKET_IDs the app imports/backs up. Empty means
     * "all folders" (the default). Doubles as the timeline's directory filter.
     */
    val scanBuckets: Flow<Set<String>> = context.appSettingsStore.data.map { it[scanBucketsKey] ?: emptySet() }

    /** False until the one-time v3 size/bucket backfill scan has been kicked off. */
    val sizeBackfilled: Flow<Boolean> = context.appSettingsStore.data.map { it[sizeBackfilledKey] ?: false }

    /**
     * False until the first successful local MediaStore scan. Cloud truth (reconcile /
     * downstream sync) must not enter the DB before this — a cloud-first fill makes
     * the timeline show only cloud-only photos and the local library look lost.
     */
    val initialScanDone: Flow<Boolean> = context.appSettingsStore.data.map { it[initialScanDoneKey] ?: false }

    suspend fun setInitialScanDone() {
        context.appSettingsStore.edit { it[initialScanDoneKey] = true }
    }

    /** Persisted timeline ordering (defaults to newest-first). */
    val timelineSort: Flow<TimelineSort> = context.appSettingsStore.data.map { prefs ->
        prefs[sortKey]?.let { name -> runCatching { TimelineSort.valueOf(name) }.getOrNull() }
            ?: TimelineSort.DATE_DESC
    }

    suspend fun setRemoteFolderName(name: String) {
        val cleaned = name.trim().ifBlank { DEFAULT_FOLDER_NAME }
        context.appSettingsStore.edit { it[folderKey] = cleaned }
    }

    suspend fun setBackupPaused(paused: Boolean) {
        context.appSettingsStore.edit { it[backupPausedKey] = paused }
    }

    suspend fun setScanBuckets(bucketIds: Set<String>) {
        context.appSettingsStore.edit { it[scanBucketsKey] = bucketIds }
    }

    suspend fun setTimelineSort(sort: TimelineSort) {
        context.appSettingsStore.edit { it[sortKey] = sort.name }
    }

    suspend fun setSizeBackfilled() {
        context.appSettingsStore.edit { it[sizeBackfilledKey] = true }
    }

    /**
     * The signed-in account's email — the identity anchor for account isolation:
     * [io.github.pnck.gallery.data.sync.AccountSwitchGuard] wipes account-scoped
     * state whenever a probe observes a different one. Survives sign-out on
     * purpose, so signing BACK in as somebody else still triggers the wipe.
     */
    val accountEmail: Flow<String?> = context.appSettingsStore.data.map { it[accountEmailKey] }

    suspend fun setAccountEmail(email: String) {
        context.appSettingsStore.edit { it[accountEmailKey] = email }
    }

    /**
     * The SAF document-tree grant (API-30 silent-delete path): a one-time picker
     * grant over shared storage, persisted via takePersistableUriPermission.
     */
    val storageTreeUri: Flow<String?> = context.appSettingsStore.data.map { it[storageTreeKey] }

    suspend fun setStorageTreeUri(uri: String) {
        context.appSettingsStore.edit { it[storageTreeKey] = uri }
    }

    /** Transport core (`gallery-wg`) verbosity: off/error/warn/info/debug/trace.
     *  Default warn — quiet by design (throughput is observed via diagnostics). */
    val transportLogLevel: Flow<String> = context.appSettingsStore.data.map { it[logLevelKey] ?: "warn" }

    /**
     * Wall-clock of the last BACKGROUND blind scan (0 = none observed): the
     * periodic worker found MediaStore empty while local-backed rows exist —
     * the definitive signature of foreground-restricted media access, which
     * permission APIs cannot report (MIUI keeps the permission GRANTED).
     * Cleared by the next successful background reconcile.
     */
    val blindScanAt: Flow<Long> = context.appSettingsStore.data.map { it[blindScanAtKey] ?: 0L }

    suspend fun setBlindScanAt(nowMs: Long) {
        context.appSettingsStore.edit { it[blindScanAtKey] = nowMs }
    }

    suspend fun clearBlindScan() {
        context.appSettingsStore.edit { it.remove(blindScanAtKey) }
    }

    /**
     * The autostart EXPERIMENT (see AutostartProbeWorker): each background
     * transition arms a fresh probe (scheduledAt=now); a BACKGROUND delivery
     * stamps completedAt (proof); a FOREGROUND delivery invalidates the round
     * (concludes without verdict — no proof either way, no lock-in).
     */
    val autostartProbeScheduledAt: Flow<Long> = context.appSettingsStore.data.map { it[probeScheduledAtKey] ?: 0L }
    val autostartProbeCompletedAt: Flow<Long> = context.appSettingsStore.data.map { it[probeCompletedAtKey] ?: 0L }

    /** Arm a fresh experiment: every background transition is a new question. */
    suspend fun noteAutostartProbeScheduled() {
        context.appSettingsStore.edit { it[probeScheduledAtKey] = System.currentTimeMillis() }
    }

    /** Delivery proven (a background run landed). */
    suspend fun noteAutostartProbeCompleted() {
        context.appSettingsStore.edit { it[probeCompletedAtKey] = System.currentTimeMillis() }
    }

    /** The probe ran while the app was VISIBLE — the round proves nothing, so
     *  conclude it (completed := scheduled) instead of leaving a stale
     *  outstanding probe that would accuse the system 15 min later. */
    suspend fun noteAutostartProbeInvalidated() {
        context.appSettingsStore.edit { prefs ->
            prefs[probeCompletedAtKey] = prefs[probeScheduledAtKey] ?: 0L
        }
    }

    suspend fun setTransportLogLevel(level: String) {
        context.appSettingsStore.edit { it[logLevelKey] = level }
    }

    companion object {
        const val DEFAULT_FOLDER_NAME = "MyGalleryBackup"
    }
}
