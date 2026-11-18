package com.localmind.app.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ModelEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LocalMindDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun modelDao(): ModelDao

    companion object {
        const val DATABASE_NAME = "localmind.db"

        fun build(context: Context): LocalMindDatabase =
            Room.databaseBuilder(context.applicationContext, LocalMindDatabase::class.java, DATABASE_NAME)
                // Milestone 1 ships version 1; migrations will be added before any
                // schema change so user conversations survive upgrades.
                .build()
    }
}
