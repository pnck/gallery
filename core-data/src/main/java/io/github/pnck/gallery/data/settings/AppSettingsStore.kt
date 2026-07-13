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

    companion object {
        const val DEFAULT_FOLDER_NAME = "MyGalleryBackup"
    }
}
