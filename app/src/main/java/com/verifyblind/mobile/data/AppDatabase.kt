package com.verifyblind.mobile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HistoryEntity::class], version = 9, exportSchema = false)
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

        // v8 -> v9: eski sürümlerin bıraktığı tombstone (isDeleted = 1) satırlarını KALICI siler.
        // Sürekli senkron kaldırıldığında (2800d5f, 2026-07-23) silme sert DELETE'e geçti ama o
        // tarihten önce yazılmış tombstone'lar tabloda kaldı. O satırlar listede görünmez, buna
        // karşılık nonce'ları `getAllNonces()`'ta durur → yedekten geri yükleme onları sonsuza dek
        // "zaten var" sayıp atlar ("0 eklendi, 1 atlandı", kayıt bir daha gelmez). Kullanıcı bu
        // kayıtları zaten silmişti; satırı kaldırmak nonce'u serbest bırakır.
        //
        // `isDeleted` SÜTUNU şemada FİZİKSEL kalır (yıkıcı migration'dan kaçınmak için) — artık
        // hiç yazılmaz, sorgulardaki `isDeleted = 0` filtresi de bu yüzden davranışı değiştirmez.
        // iOS karşılığı: `AppDatabase.migrator` "v3_purge_tombstones".
        //
        // `internal` (private DEĞİL): `HistoryTombstonePurgeMigrationTest` doğrudan koşturuyor.
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM history_table WHERE isDeleted = 1")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "verifyblind_database"
                )
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9)
                .fallbackToDestructiveMigration() // For development simplicity
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
