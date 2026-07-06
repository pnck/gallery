package io.github.pnck.gallery.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.PhotoEntity
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.SyncState
import io.github.pnck.gallery.domain.TimelinePhoto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed timeline (PRD §9.3). The RemoteMediator that tops the timeline up
 * from the cloud (cursors in sync_keys) is wired in with T-402/T-303.
 */
class PhotoRepositoryImpl(
    private val photoDao: PhotoDao,
) : PhotoRepository {

    override fun getPagedTimelinePhotos(): Flow<PagingData<TimelinePhoto>> =
        Pager(
            config = PagingConfig(pageSize = 90, enablePlaceholders = true),
            pagingSourceFactory = { photoDao.getPhotosPaged() },
        ).flow.map { paging -> paging.map { it.toTimelinePhoto() } }
}

/** Anti-corruption mapping (PRD §3.8): the UI never sees PhotoEntity. */
internal fun PhotoEntity.toTimelinePhoto(): TimelinePhoto =
    TimelinePhoto(
        id = id,
        renderUri = localUri ?: "${provider.orEmpty().lowercase()}://$cloudId",
        aspectRatio = if (height > 0) width.toFloat() / height else 1f,
        showCloudIcon = syncState == SyncState.CLOUD_ONLY,
    )
