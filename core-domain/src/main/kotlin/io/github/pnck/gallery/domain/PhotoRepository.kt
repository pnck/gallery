package io.github.pnck.gallery.domain

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

/**
 * Timeline read contract consumed by the UI layer (PRD §0, §9.2).
 *
 * Implementations live in :core-data (Room-backed PagingSource + RemoteMediator).
 */
interface PhotoRepository {
    /** Paged timeline ordered by dateTaken DESC. */
    fun getPagedTimelinePhotos(): Flow<PagingData<TimelinePhoto>>
}
