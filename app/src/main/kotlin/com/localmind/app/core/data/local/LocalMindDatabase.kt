package com.localmind.app.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ModelEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class LocalMindDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun modelDao(): ModelDao

    companion object {
        const val DATABASE_NAME = "localmind.db"

        /**
         * Adds GGUF validation metadata and per-model inference configuration.
         * Existing conversations and model rows are preserved; models imported
         * in Milestone 1 start as UNVERIFIED and are re-inspected when the
         * Models screen is opened.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN validation TEXT NOT NULL DEFAULT 'UNVERIFIED'")
                db.execSQL("ALTER TABLE models ADD COLUMN validationMessage TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN architecture TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN parameterCount INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN ggufQuantization TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN trainedContextLength INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN blockCount INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN embeddingLength INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN headCount INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN headCountKv INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN chatTemplate TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN contextLength INTEGER NOT NULL DEFAULT 4096")
                db.execSQL("ALTER TABLE models ADD COLUMN threadCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE models ADD COLUMN batchSize INTEGER NOT NULL DEFAULT 512")
                db.execSQL("ALTER TABLE models ADD COLUMN gpuLayers INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds runtime-compatibility state and renames the header status so the
         * UI never claims "Verified" for a header-only check.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE models ADD COLUMN runtimeValidated INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE models ADD COLUMN runtimeDescription TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN runtimeMessage TEXT")
                db.execSQL("UPDATE models SET validation = 'HEADER_VALID' WHERE validation = 'VALID'")
            }
        }

        fun build(context: Context): LocalMindDatabase =
            Room.databaseBuilder(context.applicationContext, LocalMindDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
