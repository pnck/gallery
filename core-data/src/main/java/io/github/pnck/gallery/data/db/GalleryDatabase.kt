package io.github.pnck.gallery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [PhotoEntity::class, SyncKeyEntity::class, UploadSessionEntity::class],
    version = 6,
    exportSchema = true,
)
@TypeConverters(SyncStateConverter::class)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    abstract fun syncKeyDao(): SyncKeyDao

    abstract fun uploadSessionDao(): UploadSessionDao

    companion object {
        const val NAME = "gallery.db"

        /**
         * Single construction point so Room stays an implementation detail of :core-data.
         *
         * NO migrations, by design: the DB is a pure CACHE of two truths (local
         * MediaStore scan + cloud listing) — reconcile-from-truth rebuilds the whole
         * classification from zero, so a version bump just recreates the schema.
         * Nothing user-meaningful is lost:
         *  - sync badges re-derive from content hashes on the next reconcile;
         *  - PENDING_DELETE tombstones are intentionally NOT preserved — cloud
         *    deletion only ever executes while both ends are connected and is
         *    re-confirmed, so a resurrected cloud-only row is the safe failure mode;
         *  - interrupted upload sessions simply restart.
         */
        fun create(context: Context): GalleryDatabase =
            Room.databaseBuilder(context, GalleryDatabase::class.java, NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
