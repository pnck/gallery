package io.github.pnck.gallery.ui.settings.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.domain.MediaBucket
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.work.SyncPipeline
import androidx.work.WorkManager
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Library scope (PRD §6.1): which device folders the gallery shows AND backs up.
 * Full-screen settings page per docs/GALLERY-UX-INTERACTION.md §5.4 — toggles
 * apply IMMEDIATELY (settings-page convention), the full-diff scan picks the
 * change up on the next sweep (no cursor reset needed).
 */
@HiltViewModel
class LibraryFoldersViewModel @Inject constructor(
    private val repo: PhotoRepository,
    private val settings: AppSettingsStore,
    private val workManager: WorkManager,
) : ViewModel() {

    /** Device folders with counts, freshest first load. */
    private val _buckets = MutableStateFlow<List<MediaBucket>?>(null)
    val buckets: StateFlow<List<MediaBucket>?> = _buckets.asStateFlow()

    /** The allowlist; empty = all folders. */
    val selected: StateFlow<Set<String>> = settings.scanBuckets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        viewModelScope.launch { _buckets.value = repo.availableBuckets() }
    }

    fun toggle(bucketId: String) {
        viewModelScope.launch {
            val next = selected.value.toMutableSet().apply { if (!add(bucketId)) remove(bucketId) }
            apply(next)
        }
    }

    fun selectAll() {
        viewModelScope.launch { apply(emptySet()) }
    }

    private suspend fun apply(ids: Set<String>) {
        settings.setScanBuckets(ids)
        SyncPipeline.enqueue(workManager)
    }
}
