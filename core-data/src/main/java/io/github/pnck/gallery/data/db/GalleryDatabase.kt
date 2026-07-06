package io.github.pnck.gallery.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [PhotoEntity::class, SyncKeyEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(SyncStateConverter::class)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    abstract fun syncKeyDao(): SyncKeyDao

    companion object {
        const val NAME = "gallery.db"
    }
}
