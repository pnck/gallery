package io.github.pnck.gallery.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    /** Name of the app's cloud folder that uploads are pinned to. */
    val remoteFolderName: Flow<String> = context.appSettingsStore.data.map { prefs ->
        prefs[folderKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_FOLDER_NAME
    }

    /** When true, the bulk background backup is paused (explicit uploads still run). */
    val backupPaused: Flow<Boolean> = context.appSettingsStore.data.map { it[backupPausedKey] ?: false }

    suspend fun setRemoteFolderName(name: String) {
        val cleaned = name.trim().ifBlank { DEFAULT_FOLDER_NAME }
        context.appSettingsStore.edit { it[folderKey] = cleaned }
    }

    suspend fun setBackupPaused(paused: Boolean) {
        context.appSettingsStore.edit { it[backupPausedKey] = paused }
    }

    companion object {
        const val DEFAULT_FOLDER_NAME = "MyGalleryBackup"
    }
}
