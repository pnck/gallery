package io.github.pnck.gallery.ui.mydrive

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.pnck.gallery.provider.DriveEntry
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

/** The separate drive.readonly grant + browser-consent elevation state. */
sealed interface ElevatePhase {
    data object Idle : ElevatePhase

    /** Browser opened; waiting for the loopback redirect to complete the grant. */
    data object Working : ElevatePhase
    data class Failed(val message: String) : ElevatePhase
}

data class MyDriveState(
    val configured: Boolean = true,
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
 * "My Drive" browser (separate broad-read feature): browser-consent drive.readonly
 * elevation (via [DriveReadAccess], rclone-style loopback), folder navigation over ALL
 * file types, image preview, and multi-select download. Fully apart from the backup
 * flow, which stays on least-privilege drive.file.
 */
@HiltViewModel
class MyDriveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveRead: DriveReadAccess,
) : ViewModel() {

    private val _state = MutableStateFlow(
        MyDriveState(configured = driveRead.configured, granted = driveRead.isAuthorized()),
    )
    val state = _state.asStateFlow()

    private val events = Channel<MyDriveEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var elevateJob: Job? = null

    init {
        if (_state.value.granted) loadFolder(DriveEntry.ROOT_ID)
    }

    // ── drive.readonly elevation (browser consent, separate from backup sign-in) ──
    fun enableBrowsing() {
        if (elevateJob?.isActive == true) return
        elevateJob = viewModelScope.launch {
            _state.value = _state.value.copy(elevating = ElevatePhase.Working)
            val error = driveRead.authorize()
            if (error == null) {
                _state.value = _state.value.copy(granted = true, elevating = ElevatePhase.Idle)
                loadFolder(DriveEntry.ROOT_ID)
            } else {
                _state.value = _state.value.copy(elevating = ElevatePhase.Failed(error))
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
            runCatching {
                driveRead.browse(folderId, if (append) _state.value.nextPageToken else null)
            }.onSuccess { listing ->
                _state.value = _state.value.copy(
                    entries = if (append) _state.value.entries + listing.entries else listing.entries,
                    nextPageToken = listing.nextPageToken,
                    loading = false,
                )
            }.onFailure {
                _state.value = _state.value.copy(loading = false, error = it.message)
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

    /** Coil model for a thumbnail / full image, loaded with the drive.readonly token. */
    fun thumbModel(entry: DriveEntry): DriveReadUrl? = entry.thumbnailUrl?.let { DriveReadUrl(it) }

    fun imageModel(id: String): DriveReadUrl =
        DriveReadUrl("https://www.googleapis.com/drive/v3/files/$id?alt=media")

    // ── Multi-select download to a user-chosen folder (SAF, any location) ────
    fun downloadSelectedTo(treeUri: Uri) {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        val byId = _state.value.entries.associateBy { it.id }
        clearSelection()
        viewModelScope.launch {
            val ok: Int
            val failed: Int
            withContext(Dispatchers.IO) {
                val root = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri),
                )
                var okN = 0
                var failN = 0
                for (id in ids) {
                    val entry = byId[id] ?: continue
                    if (entry.isFolder) continue
                    if (saveInto(root, entry)) okN++ else failN++
                }
                ok = okN
                failed = failN
            }
            events.send(MyDriveEvent.Downloaded(ok, failed))
        }
    }

    private suspend fun saveInto(parentDoc: Uri, entry: DriveEntry): Boolean = runCatching {
        val resolver = context.contentResolver
        val doc = DocumentsContract.createDocument(resolver, parentDoc, entry.mimeType, entry.name)
            ?: return@runCatching false
        driveRead.download(entry.id).use { input ->
            resolver.openOutputStream(doc)?.use { out -> input.copyTo(out) } != null
        }
    }.getOrDefault(false)
}
