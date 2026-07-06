package io.github.pnck.gallery.data.db

import androidx.room.TypeConverter
import io.github.pnck.gallery.domain.SyncState

class SyncStateConverter {
    @TypeConverter
    fun fromSyncState(state: SyncState): Int = state.code

    @TypeConverter
    fun toSyncState(code: Int): SyncState = SyncState.fromCode(code)
}
