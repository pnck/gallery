package io.github.pnck.gallery.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.domain.PhotoRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** MVI intents of the timeline (PRD §9.2). */
sealed class TimelineIntent {
    data object ForceSync : TimelineIntent()
    data class OnPhotoClick(val photoId: String) : TimelineIntent()
    data object RequestFreeSpace : TimelineIntent()
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Uploading(val done: Int, val total: Int) : SyncStatus
}

/** PagingData is collected separately in the UI, never inside State (PRD §9.2). */
data class TimelineState(
    val syncStatus: SyncStatus = SyncStatus.Idle,
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    repo: PhotoRepository,
) : ViewModel() {

    val photosFlow = repo.getPagedTimelinePhotos().cachedIn(viewModelScope)

    private val _state = MutableStateFlow(TimelineState())
    val state = _state.asStateFlow()

    fun processIntent(intent: TimelineIntent) {
        when (intent) {
            is TimelineIntent.ForceSync -> {
                // T-301/T-303: enqueue unique WorkManager requests.
            }
            is TimelineIntent.RequestFreeSpace -> {
                // T-302: compute deletable URIs and hand them to the UI for
                // MediaStore.createDeleteRequest (workers cannot show dialogs).
            }
            is TimelineIntent.OnPhotoClick -> Unit // navigation handled by the screen
        }
    }
}
