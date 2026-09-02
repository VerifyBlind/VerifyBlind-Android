package com.verifyblind.mobile.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Room 8→9 geçişi: eski sürümlerin bıraktığı tombstone satırları kalıcı silinmeli.
 *
 * Neden önemli: `HistoryDao.getAllNonces()` filtresizdir (`SELECT nonce FROM history_table`).
 * `isDeleted = 1` ile tabloda kalan bir satır listede görünmez ama nonce'u dedup'a girer →
 * `BackupMapper.selectNewRecords` o kaydı "yerelde zaten var" sayar ve yedekten geri yükleme
 * kaydı sonsuza dek atlar ("0 eklendi, 1 atlandı", geçmiş boş kalır).
 *
 * Tombstone yazımı 2026-07-23'te (2800d5f) bırakıldı; bu geçiş o tarihten önce yazılmış
 * satırları temizler. iOS karşılığı: `HistoryDeleteTests.testMigrationPurgesLegacyTombstones`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryTombstonePurgeMigrationTest {

    /** v8 şemasıyla bellek-içi bir DB açar (Room devrede değil — geçişi doğrudan koşturuyoruz). */
    private fun openV8Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration
            .builder(RuntimeEnvironment.getApplication())
            .name(null)   // null = bellek-içi
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE history_table (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            title TEXT NOT NULL,
                            description TEXT NOT NULL,
                            actionType INTEGER NOT NULL DEFAULT 0,
                            status INTEGER NOT NULL,
                            timestamp INTEGER NOT NULL,
                            transactionId TEXT,
                            nonce TEXT NOT NULL,
                            personId TEXT NOT NULL DEFAULT '',
                            cardId TEXT NOT NULL DEFAULT '',
                            partnerId TEXT,
                            deviceName TEXT NOT NULL DEFAULT '',
                            isSent INTEGER NOT NULL DEFAULT 0,
                            isDeleted INTEGER NOT NULL DEFAULT 0,
                            revokeTime INTEGER
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun insertRow(db: SupportSQLiteDatabase, nonce: String, isDeleted: Int) {
        db.execSQL(
            """
            INSERT INTO history_table
                (title, description, actionType, status, timestamp, nonce, personId, cardId, deviceName, isSent, isDeleted)
            VALUES ('t', 'd', 1, 1, 1, ?, '', '', '', 0, ?)
            """.trimIndent(),
            arrayOf<Any>(nonce, isDeleted)
        )
    }

    private fun nonces(db: SupportSQLiteDatabase): List<String> {
        val out = mutableListOf<String>()
        db.query("SELECT nonce FROM history_table ORDER BY id").use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    @Test
    fun `migration purges tombstones and keeps live rows`() {
        val db = openV8Database()
        insertRow(db, "HAYALET", isDeleted = 1)
        insertRow(db, "CANLI", isDeleted = 0)

        AppDatabase.MIGRATION_8_9.migrate(db)

        assertEquals(listOf("CANLI"), nonces(db))
        db.close()
    }

    /** Tombstone yoksa geçiş hiçbir şeye dokunmamalı (temiz cihazlarda veri kaybı olmasın). */
    @Test
    fun `migration is a no-op without tombstones`() {
        val db = openV8Database()
        insertRow(db, "A", isDeleted = 0)
        insertRow(db, "B", isDeleted = 0)

        AppDatabase.MIGRATION_8_9.migrate(db)

        assertEquals(listOf("A", "B"), nonces(db))
        db.close()
    }
}
