package com.verifyblind.mobile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HistoryEntity::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao
    // abstract fun partnerDao(): PartnerDao -- Removed due to KSP issues

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v7 -> v8: işlem geçmişine şifreli `deviceName` kolonu. Veri kaybı olmadan yükseltir
        // (fallbackToDestructiveMigration güvenlik ağı olarak kalır).
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history_table ADD COLUMN deviceName TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "verifyblind_database"
                )
                .addMigrations(MIGRATION_7_8)
                .fallbackToDestructiveMigration() // For development simplicity
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
