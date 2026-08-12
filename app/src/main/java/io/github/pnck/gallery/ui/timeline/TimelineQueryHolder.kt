package io.github.pnck.gallery.ui.timeline

import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.domain.MediaTypeFilter
import io.github.pnck.gallery.domain.SyncFilter
import io.github.pnck.gallery.domain.TimelineQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Process-wide holder of the current timeline [TimelineQuery] (sort + sync-state
 * filter + media-type filter + folder allowlist), so the grid and the full-screen
 * detail pager swipe through the SAME ordered/filtered set — anything else is
 * deeply counter-intuitive.
 *
 * Sort and folders are persisted in [AppSettingsStore]; the sync-state and
 * media-type filters are transient session choices kept here so they survive
 * navigation between the two screens without being written to disk.
 */
@Singleton
class TimelineQueryHolder @Inject constructor(
    settings: AppSettingsStore,
) {
    private val _filter = MutableStateFlow(SyncFilter.ALL)
    val filter: StateFlow<SyncFilter> = _filter.asStateFlow()

    fun setFilter(filter: SyncFilter) {
        _filter.value = filter
    }

    private val _mediaType = MutableStateFlow(MediaTypeFilter.ALL)
    val mediaType: StateFlow<MediaTypeFilter> = _mediaType.asStateFlow()

    fun setMediaType(mediaType: MediaTypeFilter) {
        _mediaType.value = mediaType
    }

    /** The live query both the timeline grid and the detail pager derive their list from. */
    val query: Flow<TimelineQuery> = combine(
        settings.timelineSort,
        _filter,
        settings.scanBuckets,
        _mediaType,
    ) { sort, filter, buckets, mediaType ->
        TimelineQuery(sort = sort, filter = filter, bucketIds = buckets, mediaType = mediaType)
    }
}
