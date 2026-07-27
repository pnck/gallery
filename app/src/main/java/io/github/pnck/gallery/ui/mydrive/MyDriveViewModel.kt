package io.github.pnck.gallery.ui.mydrive

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
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
    /** False until the authorization flag resolves (keystore read is off-main). */
    val gateResolved: Boolean = false,
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
    /** Details panel state: non-null while the panel is open. */
    val details: DetailsPanel? = null,
)

/** Details panel content: the row's entry plus the fetched metadata (null while loading). */
data class DetailsPanel(
    val entry: DriveEntry,
    val details: DriveFileDetails? = null,
    val error: String? = null,
)

/** One-shot feedback (snackbar). */
sealed interface MyDriveEvent {
    data class Downloaded(
        val ok: Int,
        val failed: Int,
        val skipped: Int = 0,
        /** True when files landed in the system Downloads folder (vs a SAF-picked one). */
        val toDownloads: Boolean = false,
    ) : MyDriveEvent
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
        MyDriveState(configured = driveRead.configured),
    )
    val state = _state.asStateFlow()

    private val events = Channel<MyDriveEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var elevateJob: Job? = null

    init {
        // The gate is a pure projection of the authorization flag: collect it from
        // the store-backed flow (seeded off-main — keystore reads must not touch
        // the main thread) and load the root folder whenever it flips to granted.
        viewModelScope.launch {
            driveRead.authorized.collect { granted ->
                val wasGranted = _state.value.granted
                _state.value = _state.value.copy(granted = granted, gateResolved = true)
                if (granted && !wasGranted) {
                    loadFolder(DriveEntry.ROOT_ID)
                } else if (!granted && wasGranted) {
                    // Revoked in Settings while we were alive: reset to the gate.
                    _state.value = _state.value.copy(
                        stack = listOf(Crumb(DriveEntry.ROOT_ID, "")),
                        entries = emptyList(),
                        nextPageToken = null,
                        selection = emptySet(),
                    )
                }
            }
        }
    }

    // ── drive.readonly elevation (browser consent, separate from backup sign-in) ──
    fun enableBrowsing() {
        if (elevateJob?.isActive == true) return
        elevateJob = viewModelScope.launch {
            _state.value = _state.value.copy(elevating = ElevatePhase.Working)
            val error = driveRead.authorize()
            if (error == null) {
                // The authorized-flow collector flips granted and loads the root.
                _state.value = _state.value.copy(elevating = ElevatePhase.Idle)
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

    // ── Details panel ───────────────────────────────────────────────────────
    fun showDetails(entry: DriveEntry) {
        _state.value = _state.value.copy(details = DetailsPanel(entry))
        if (entry.isFolder) return // the list call already knows everything about folders
        viewModelScope.launch {
            val panel = runCatching { driveRead.details(entry.id) }
                .fold(
                    onSuccess = { DetailsPanel(entry, details = it) },
                    onFailure = {
                        Log.w(TAG, "details for ${entry.id} failed: ${it.message}")
                        DetailsPanel(entry, error = it.message)
                    },
                )
            // Don't clobber the panel if the user already closed or re-targeted it.
            if (_state.value.details?.entry?.id == entry.id) {
                _state.value = _state.value.copy(details = panel)
            }
        }
    }

    fun dismissDetails() {
        _state.value = _state.value.copy(details = null)
    }

    /** Coil model for a thumbnail / full image, loaded with the drive.readonly token. */
    fun thumbModel(entry: DriveEntry): DriveReadUrl? = entry.thumbnailUrl?.let { DriveReadUrl(it) }

    fun imageModel(id: String): DriveReadUrl =
        DriveReadUrl("https://www.googleapis.com/drive/v3/files/$id?alt=media")

    // ── Download ────────────────────────────────────────────────────────────
    /**
     * Download the selection straight into the system Downloads folder — one tap,
     * no picker, no permission (MediaStore lets an app write its own Downloads
     * entries on API 29+). This is the default path; [downloadSelectedTo] (SAF
     * picker) remains only as the pre-29 fallback.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun downloadSelected() = downloadBatch(toDownloads = true) { entry -> saveToDownloads(entry) }

    /** Legacy path (pre-29): download the selection into a SAF-picked folder. */
    fun downloadSelectedTo(treeUri: Uri) {
        // Keep the tree grant beyond this flow; without it a later process death
        // turns a resumed download into a SecurityException.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "persistable permission denied for $treeUri (continuing with the transient grant)") }
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        downloadBatch(toDownloads = false) { entry -> saveInto(root, entry) }
    }

    /** Run [save] over the selection, tally ok/failed/skipped, and report. */
    private fun downloadBatch(toDownloads: Boolean, save: suspend (DriveEntry) -> String?) {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        val byId = _state.value.entries.associateBy { it.id }
        clearSelection()
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            var skipped = 0
            withContext(Dispatchers.IO) {
                for (id in ids) {
                    val entry = byId[id] ?: continue
                    if (entry.isFolder) continue
                    when (val error = save(entry)) {
                        null -> ok++
                        SKIP_UNSUPPORTED -> {
                            Log.w(TAG, "skip ${entry.name}: Google-native file needs export, not download")
                            skipped++
                        }
                        else -> {
                            Log.w(TAG, "download ${entry.name} FAILED: $error")
                            failed++
                        }
                    }
                }
            }
            events.send(MyDriveEvent.Downloaded(ok, failed, skipped, toDownloads = toDownloads))
        }
    }

    /**
     * Stream [entry] into a new MediaStore Downloads entry (marked pending while
     * writing so other apps don't see a partial file).
     * @return null on success, [SKIP_UNSUPPORTED] for Google-native files, else the
     *  failure reason. A failed/partial entry is deleted — never leave an empty or
     *  half-written file behind.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveToDownloads(entry: DriveEntry): String? {
        if (entry.mimeType.startsWith("application/vnd.google-apps")) return SKIP_UNSUPPORTED
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, entry.name)
            put(MediaStore.Downloads.MIME_TYPE, entry.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }
            .getOrElse { return "MediaStore insert threw: ${it.message}" }
            ?: return "MediaStore insert returned null"
        val failure = runCatching {
            driveRead.download(entry.id).use { input ->
                val out = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("openOutputStream returned null for $uri")
                out.use { input.copyTo(it) }
            }
        }.exceptionOrNull()
        if (failure != null) {
            runCatching { resolver.delete(uri, null, null) }
            return failure.message ?: failure.javaClass.simpleName
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return null
    }

    /**
     * Create the destination document and stream the file into it (SAF path).
     * @return null on success, [SKIP_UNSUPPORTED] for Google-native files, else the
     *  failure reason. A failed/partial destination is deleted — never leave an
     *  empty or half-written file behind.
     */
    private suspend fun saveInto(parentDoc: Uri, entry: DriveEntry): String? {
        // Google Docs/Sheets/Slides are virtual files: alt=media 400s on them, they
        // need the export endpoint. Skip loudly instead of writing an empty file.
        if (entry.mimeType.startsWith("application/vnd.google-apps")) return SKIP_UNSUPPORTED
        val resolver = context.contentResolver
        val doc = runCatching {
            DocumentsContract.createDocument(resolver, parentDoc, entry.mimeType, entry.name)
        }.getOrElse { return "createDocument threw: ${it.message}" }
            ?: return "createDocument returned null (provider rejected ${entry.mimeType})"
        val failure = runCatching {
            driveRead.download(entry.id).use { input ->
                val out = resolver.openOutputStream(doc)
                    ?: throw IllegalStateException("openOutputStream returned null for $doc")
                out.use { input.copyTo(it) }
            }
        }.exceptionOrNull()
        if (failure != null) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            return failure.message ?: failure.javaClass.simpleName
        }
        return null
    }

    private companion object {
        const val TAG = "gallery-mydrive"

        /** Sentinel [saveInto] result for files Drive can't serve as bytes. */
        const val SKIP_UNSUPPORTED = "google-native"
    }
}
