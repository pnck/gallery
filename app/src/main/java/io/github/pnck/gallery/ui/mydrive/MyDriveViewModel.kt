package io.github.pnck.gallery.ui.mydrive

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.provider.DeviceAuthChallenge
import io.github.pnck.gallery.provider.DriveEntry
import io.github.pnck.gallery.provider.ICloudStorageProvider
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A folder in the browse breadcrumb. */
data class Crumb(val id: String, val name: String)

/** The separate drive.readonly grant + device-flow elevation state. */
sealed interface ElevatePhase {
    data object Idle : ElevatePhase
    data object Requesting : ElevatePhase
    data class Approve(val challenge: DeviceAuthChallenge) : ElevatePhase
    data class Failed(val message: String) : ElevatePhase
}

data class MyDriveState(
    val granted: Boolean = false,
    val elevating: ElevatePhase = ElevatePhase.Idle,
    val stack: List<Crumb> = listOf(Crumb(DriveEntry.ROOT_ID, "")),
    val entries: List<DriveEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val nextPageToken: String? = null,
    val selection: Set<String> = emptySet(),
    /** The image entry being previewed full-screen, or null. */
    val preview: DriveEntry? = null,
)

/** One-shot feedback (snackbar). */
sealed interface MyDriveEvent {
    data class Downloaded(val ok: Int, val failed: Int) : MyDriveEvent
}

/**
 * "My Drive" browser (separate broad-read feature): drive.readonly elevation, folder
 * navigation over ALL file types, image preview, and multi-select download. Kept apart
 * from the backup flow, which stays on least-privilege drive.file.
 */
@HiltViewModel
class MyDriveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthManager,
    private val provider: ICloudStorageProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(MyDriveState(granted = auth.hasDriveRead()))
    val state = _state.asStateFlow()

    private val events = Channel<MyDriveEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var elevateJob: Job? = null

    init {
        if (_state.value.granted) loadFolder(DriveEntry.ROOT_ID)
    }

    // ── drive.readonly elevation (device flow, separate from backup sign-in) ──
    fun enableBrowsing() {
        if (elevateJob?.isActive == true) return
        elevateJob = viewModelScope.launch {
            _state.value = _state.value.copy(elevating = ElevatePhase.Requesting)
            when (val challenge = auth.requestDeviceAuthorization(readAccess = true)) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(elevating = ElevatePhase.Approve(challenge.data))
                    when (val res = auth.pollForToken(challenge.data)) {
                        is ApiResult.Success -> {
                            _state.value = _state.value.copy(granted = true, elevating = ElevatePhase.Idle)
                            loadFolder(DriveEntry.ROOT_ID)
                        }
                        is ApiResult.Error ->
                            _state.value = _state.value.copy(elevating = ElevatePhase.Failed(res.message))
                    }
                }
                is ApiResult.Error ->
                    _state.value = _state.value.copy(elevating = ElevatePhase.Failed(challenge.message))
            }
        }
    }

    fun cancelElevate() {
        elevateJob?.cancel()
        elevateJob = null
        _state.value = _state.value.copy(elevating = ElevatePhase.Idle)
    }

    // ── Browsing ────────────────────────────────────────────────────────────
    private fun loadFolder(folderId: String, append: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val res = provider.browse(folderId, if (append) _state.value.nextPageToken else null)) {
                is ApiResult.Success -> _state.value = _state.value.copy(
                    entries = if (append) _state.value.entries + res.data.entries else res.data.entries,
                    nextPageToken = res.data.nextPageToken,
                    loading = false,
                )
                is ApiResult.Error -> _state.value = _state.value.copy(loading = false, error = res.message)
            }
        }
    }

    fun open(entry: DriveEntry) {
        when {
            entry.isFolder -> {
                _state.value = _state.value.copy(
                    stack = _state.value.stack + Crumb(entry.id, entry.name),
                    entries = emptyList(),
                    nextPageToken = null,
                    selection = emptySet(),
                )
                loadFolder(entry.id)
            }
            entry.isImage -> _state.value = _state.value.copy(preview = entry)
            else -> toggleSelect(entry.id)
        }
    }

    fun navigateTo(index: Int) {
        val stack = _state.value.stack
        if (index >= stack.lastIndex) return
        val trimmed = stack.subList(0, index + 1).toList()
        _state.value = _state.value.copy(stack = trimmed, entries = emptyList(), nextPageToken = null, selection = emptySet())
        loadFolder(trimmed.last().id)
    }

    /** @return true if a level was popped (else the caller lets the drawer/back handle it). */
    fun goUp(): Boolean {
        val stack = _state.value.stack
        if (stack.size <= 1) return false
        navigateTo(stack.size - 2)
        return true
    }

    fun loadMore() {
        if (_state.value.loading || _state.value.nextPageToken == null) return
        loadFolder(_state.value.stack.last().id, append = true)
    }

    fun toggleSelect(id: String) {
        _state.value = _state.value.copy(
            selection = _state.value.selection.toMutableSet().apply { if (!add(id)) remove(id) },
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selection = emptySet())
    }

    fun closePreview() {
        _state.value = _state.value.copy(preview = null)
    }

    /** Full-resolution authenticated URL for the image preview (Bearer added by the shared client). */
    fun originalUrl(id: String): String = "https://www.googleapis.com/drive/v3/files/$id?alt=media"

    // ── Multi-select download (any file type → device Downloads) ─────────────
    fun downloadSelected() {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        val byId = _state.value.entries.associateBy { it.id }
        clearSelection()
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            for (id in ids) {
                val entry = byId[id] ?: continue
                if (entry.isFolder) continue
                if (saveToDownloads(entry)) ok++ else failed++
            }
            events.send(MyDriveEvent.Downloaded(ok, failed))
        }
    }

    private suspend fun saveToDownloads(entry: DriveEntry): Boolean = withContext(Dispatchers.IO) {
        val stream = when (val res = provider.downloadOriginal(entry.id)) {
            is ApiResult.Success -> res.data
            is ApiResult.Error -> return@withContext false
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, entry.name)
                    put(MediaStore.Downloads.MIME_TYPE, entry.mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values) ?: return@runCatching false
                context.contentResolver.openOutputStream(uri)?.use { out -> stream.use { it.copyTo(out) } }
                    ?: return@runCatching false
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                true
            } else {
                // Pre-Q: app-specific external dir (no runtime permission needed).
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@runCatching false
                val file = File(dir, entry.name)
                stream.use { input -> file.outputStream().use { input.copyTo(it) } }
                true
            }
        }.getOrDefault(false)
    }
}
