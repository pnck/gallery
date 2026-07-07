package io.github.pnck.gallery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
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

        /** Single construction point so Room stays an implementation detail of :core-data. */
        fun create(context: Context): GalleryDatabase =
            Room.databaseBuilder(context, GalleryDatabase::class.java, NAME).build()
    }
}
