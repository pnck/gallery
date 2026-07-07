package io.github.pnck.gallery.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.work.SyncPipeline
import io.github.pnck.gallery.work.UploadWorker
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** MVI intents of the timeline (PRD §9.2). */
sealed class TimelineIntent {
    data object ForceSync : TimelineIntent()
    data class OnPhotoClick(val photoId: String) : TimelineIntent()
    data object RequestFreeSpace : TimelineIntent()
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Scanning : SyncStatus
    data class Uploading(val done: Int, val total: Int) : SyncStatus
}

@HiltViewModel
class TimelineViewModel @Inject constructor(
    repo: PhotoRepository,
    private val workManager: WorkManager,
) : ViewModel() {

    /** PagingData is collected in the UI, never stored in a state object (PRD §9.2). */
    val photosFlow = repo.getPagedTimelinePhotos().cachedIn(viewModelScope)

    /** Sync indicator for the TopAppBar (PRD §9.1), derived from the unique chain. */
    val syncStatus = workManager
        .getWorkInfosForUniqueWorkFlow(SyncPipeline.UNIQUE_NAME)
        .map { infos -> infos.toSyncStatus() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus.Idle)

    fun processIntent(intent: TimelineIntent) {
        when (intent) {
            is TimelineIntent.ForceSync -> SyncPipeline.enqueue(workManager)
            is TimelineIntent.RequestFreeSpace -> {
                // T-302: compute deletable URIs, hand to UI for MediaStore.createDeleteRequest.
            }
            is TimelineIntent.OnPhotoClick -> Unit // navigation handled by the screen
        }
    }

    private fun List<WorkInfo>.toSyncStatus(): SyncStatus {
        val running = filter { it.state == WorkInfo.State.RUNNING }
        if (running.isEmpty()) return SyncStatus.Idle
        running.forEach { info ->
            val total = info.progress.getInt(UploadWorker.KEY_PROGRESS_TOTAL, 0)
            if (total > 0) {
                return SyncStatus.Uploading(
                    done = info.progress.getInt(UploadWorker.KEY_PROGRESS_DONE, 0),
                    total = total,
                )
            }
        }
        return SyncStatus.Scanning
    }
}
