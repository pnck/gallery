package io.github.pnck.gallery.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    /** Transport core (`gallery-wg`) verbosity: off/error/warn/info/debug/trace.
     *  Default warn — quiet by design (throughput is observed via diagnostics). */
    val transportLogLevel: Flow<String> = context.appSettingsStore.data.map { it[logLevelKey] ?: "warn" }

    suspend fun setTransportLogLevel(level: String) {
        context.appSettingsStore.edit { it[logLevelKey] = level }
    }

    companion object {
        const val DEFAULT_FOLDER_NAME = "MyGalleryBackup"
    }
}
