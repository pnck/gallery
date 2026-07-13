package io.github.pnck.gallery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PhotoEntity::class, SyncKeyEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(SyncStateConverter::class)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    abstract fun syncKeyDao(): SyncKeyDao

    companion object {
        const val NAME = "gallery.db"

        /** v2: `excluded` flag so photos can be dropped from the backup queue (kept visible). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: `sizeBytes` + `bucketId`/`bucketName` for size sorting, the space-management
         *  view and the per-folder scan allowlist (backfilled lazily by the next scan). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE photos ADD COLUMN bucketId TEXT")
                db.execSQL("ALTER TABLE photos ADD COLUMN bucketName TEXT")
            }
        }

        /** Single construction point so Room stays an implementation detail of :core-data. */
        fun create(context: Context): GalleryDatabase =
            Room.databaseBuilder(context, GalleryDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
