package com.yinian.android.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.yinian.android.data.dao.FocusDao
import com.yinian.android.data.dao.IdeaDao
import com.yinian.android.data.dao.RelationDao
import com.yinian.android.data.dao.TaskDao
import com.yinian.android.data.dao.TimeBlockDao
import com.yinian.android.data.entity.FocusSessionEntity
import com.yinian.android.data.entity.IdeaEntity
import com.yinian.android.data.entity.RelationEntity
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.data.entity.TimeBlockEntity

@Database(
    entities = [
        IdeaEntity::class,
        TaskEntity::class,
        RelationEntity::class,
        TimeBlockEntity::class,
        FocusSessionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ideaDao(): IdeaDao
    abstract fun taskDao(): TaskDao
    abstract fun relationDao(): RelationDao
    abstract fun timeBlockDao(): TimeBlockDao
    abstract fun focusDao(): FocusDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "yinian.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
