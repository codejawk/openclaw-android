package com.openclaw.native_app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities  = [TokenEntity::class],
    version   = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tokenDao(): TokenDao
}
