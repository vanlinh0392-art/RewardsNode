package com.agy.rewardsnode.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.agy.rewardsnode.data.SecureColumnCrypto
import com.agy.rewardsnode.data.model.AccountProfile
import com.agy.rewardsnode.data.model.VlogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Triển khai Database thuần túy dựa trên Android Native SQLiteOpenHelper (100% Android SDK).
 * Bật chế độ SQLite WAL (Write-Ahead Logging) cho I/O song song đa luồng.
 * Tích hợp MutableStateFlow để tự động phát tín hiệu cập nhật giao diện mà không cần Room.
 */
class AppDatabase private constructor(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val rewardsDaoImpl = NativeRewardsDao()
    private val vlogDaoImpl = NativeVlogDao()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS account_profiles (
                id TEXT PRIMARY KEY NOT NULL,
                email TEXT NOT NULL,
                profileName TEXT NOT NULL,
                level INTEGER NOT NULL DEFAULT 2,
                targetSearches INTEGER NOT NULL DEFAULT 35,
                completedSearchesToday INTEGER NOT NULL DEFAULT 0,
                totalPoints INTEGER NOT NULL DEFAULT 0,
                availablePoints INTEGER NOT NULL DEFAULT 0,
                lifetimePoints INTEGER NOT NULL DEFAULT 0,
                streakCount INTEGER NOT NULL DEFAULT 0,
                baselinePointsToday INTEGER NOT NULL DEFAULT 0,
                pointsEarnedToday INTEGER NOT NULL DEFAULT 0,
                pcProgress INTEGER NOT NULL DEFAULT 0,
                pcMax INTEGER NOT NULL DEFAULT 0,
                mobileProgress INTEGER NOT NULL DEFAULT 0,
                mobileMax INTEGER NOT NULL DEFAULT 0,
                edgeProgress INTEGER NOT NULL DEFAULT 0,
                edgeMax INTEGER NOT NULL DEFAULT 0,
                proxyUrl TEXT,
                customUserAgent TEXT,
                cookiesSnapshot TEXT,
                lastActiveDate TEXT NOT NULL,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                baselineDate TEXT NOT NULL DEFAULT '',
                dailySetProgress INTEGER NOT NULL DEFAULT 0,
                dailySetTotal INTEGER NOT NULL DEFAULT 3,
                dailySetDone INTEGER NOT NULL DEFAULT 0,
                claimablePoints INTEGER NOT NULL DEFAULT 0,
                readProgress INTEGER NOT NULL DEFAULT 0,
                readMax INTEGER NOT NULL DEFAULT 30,
                puzzleProgress INTEGER NOT NULL DEFAULT 0,
                cooldownUntilTimestamp INTEGER NOT NULL DEFAULT 0,
                searchWindowStartMs INTEGER NOT NULL DEFAULT 0,
                searchesInWindow INTEGER NOT NULL DEFAULT 0,
                dapiAccessToken TEXT,
                dapiRefreshToken TEXT,
                dapiTokenExpiryTimestampMs INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS vlog_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                timeFormatted TEXT NOT NULL,
                tag TEXT NOT NULL,
                level TEXT NOT NULL,
                message TEXT NOT NULL,
                details TEXT,
                accountEmail TEXT,
                snapshotId TEXT
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vlog_timestamp ON vlog_entries(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vlog_tag ON vlog_entries(tag)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vlog_level ON vlog_entries(level)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            safeAddColumn(db, "account_profiles", "availablePoints", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "lifetimePoints", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "streakCount", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "baselinePointsToday", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "pointsEarnedToday", "INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            safeAddColumn(db, "account_profiles", "pcProgress", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "pcMax", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "mobileProgress", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "mobileMax", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "edgeProgress", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "edgeMax", "INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 5) {
            safeAddColumn(db, "account_profiles", "baselineDate", "TEXT NOT NULL DEFAULT ''")
            safeAddColumn(db, "account_profiles", "dailySetProgress", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "dailySetDone", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "claimablePoints", "INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            safeAddColumn(db, "account_profiles", "dailySetTotal", "INTEGER NOT NULL DEFAULT 3")
        }
        if (oldVersion < 7) {
            safeAddColumn(db, "account_profiles", "dapiAccessToken", "TEXT")
            safeAddColumn(db, "account_profiles", "dapiRefreshToken", "TEXT")
            safeAddColumn(db, "account_profiles", "dapiTokenExpiryTimestampMs", "INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 8) {
            safeAddColumn(db, "account_profiles", "readProgress", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "readMax", "INTEGER NOT NULL DEFAULT 30")
        }
        if (oldVersion < 9) {
            safeAddColumn(db, "account_profiles", "puzzleProgress", "INTEGER NOT NULL DEFAULT 0")
            safeAddColumn(db, "account_profiles", "cooldownUntilTimestamp", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureSchemaUpToDate(db)
        try {
            // Mở khóa tự động tài khoản bị vô hiệu hóa nhầm do lỗi desync / frozen balance
            db.execSQL("UPDATE account_profiles SET isEnabled = 1 WHERE email = 'vanlinh0392@gmail.com' AND isEnabled = 0")
        } catch (_: Exception) {}
    }

    private fun ensureSchemaUpToDate(db: SQLiteDatabase) {
        safeAddColumn(db, "account_profiles", "availablePoints", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "lifetimePoints", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "streakCount", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "baselinePointsToday", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "pointsEarnedToday", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "pcProgress", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "pcMax", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "mobileProgress", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "mobileMax", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "edgeProgress", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "edgeMax", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "baselineDate", "TEXT NOT NULL DEFAULT ''")
        safeAddColumn(db, "account_profiles", "dailySetProgress", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "dailySetTotal", "INTEGER NOT NULL DEFAULT 3")
        safeAddColumn(db, "account_profiles", "dailySetDone", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "claimablePoints", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "readProgress", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "readMax", "INTEGER NOT NULL DEFAULT 30")
        safeAddColumn(db, "account_profiles", "puzzleProgress", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "cooldownUntilTimestamp", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "searchWindowStartMs", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "searchesInWindow", "INTEGER NOT NULL DEFAULT 0")
        safeAddColumn(db, "account_profiles", "dapiAccessToken", "TEXT")
        safeAddColumn(db, "account_profiles", "dapiRefreshToken", "TEXT")
        safeAddColumn(db, "account_profiles", "dapiTokenExpiryTimestampMs", "INTEGER NOT NULL DEFAULT 0")
    }

    private fun safeAddColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
        try {
            var exists = false
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx != -1 && column.equals(cursor.getString(nameIdx), ignoreCase = true)) {
                        exists = true
                        break
                    }
                }
            }
            if (!exists) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun rewardsDao(): RewardsDao = rewardsDaoImpl
    fun vlogDao(): VlogDao = vlogDaoImpl

    suspend fun updateTargetSearches(id: String, targetSearches: Int) = rewardsDaoImpl.updateTargetSearches(id, targetSearches)
    suspend fun updateAllTargetSearches(targetSearches: Int) = rewardsDaoImpl.updateAllTargetSearches(targetSearches)

    fun checkpointWal() {
        try {
            writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE);", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    // Checkpoint thành công
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =========================================================================
    // Native RewardsDao Implementation
    // =========================================================================
    private inner class NativeRewardsDao : RewardsDao {
        private val _profilesFlow = MutableStateFlow<List<AccountProfile>>(emptyList())

        init {
            refreshProfilesFlow()
        }

        private fun refreshProfilesFlow() {
            try {
                val list = queryAllActiveProfiles()
                _profilesFlow.value = list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun queryAllActiveProfiles(): List<AccountProfile> {
            val list = mutableListOf<AccountProfile>()
            val db = readableDatabase
            db.rawQuery("SELECT * FROM account_profiles ORDER BY isEnabled DESC, email ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(parseAccountProfile(cursor))
                }
            }
            return list
        }

        private fun getIntSafe(cursor: Cursor, columnName: String, defaultValue: Int = 0): Int {
            val idx = cursor.getColumnIndex(columnName)
            return if (idx >= 0 && !cursor.isNull(idx)) cursor.getInt(idx) else defaultValue
        }

        private fun getStringSafe(cursor: Cursor, columnName: String): String? {
            val idx = cursor.getColumnIndex(columnName)
            return if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
        }

        private fun getLongSafe(cursor: Cursor, columnName: String, defaultValue: Long = 0L): Long {
            val idx = cursor.getColumnIndex(columnName)
            return if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else defaultValue
        }

        private fun parseAccountProfile(cursor: Cursor): AccountProfile {
            val totalPts = getIntSafe(cursor, "totalPoints", 0)
            val availPts = getIntSafe(cursor, "availablePoints", totalPts)
            return AccountProfile(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                email = cursor.getString(cursor.getColumnIndexOrThrow("email")),
                profileName = cursor.getString(cursor.getColumnIndexOrThrow("profileName")),
                level = getIntSafe(cursor, "level", 2),
                targetSearches = getIntSafe(cursor, "targetSearches", 35),
                completedSearchesToday = getIntSafe(cursor, "completedSearchesToday", 0),
                totalPoints = totalPts,
                availablePoints = availPts,
                lifetimePoints = getIntSafe(cursor, "lifetimePoints", 0),
                streakCount = getIntSafe(cursor, "streakCount", 0),
                baselinePointsToday = getIntSafe(cursor, "baselinePointsToday", 0),
                pointsEarnedToday = getIntSafe(cursor, "pointsEarnedToday", 0),
                pcProgress = getIntSafe(cursor, "pcProgress", 0),
                pcMax = getIntSafe(cursor, "pcMax", 0),
                mobileProgress = getIntSafe(cursor, "mobileProgress", 0),
                mobileMax = getIntSafe(cursor, "mobileMax", 0),
                edgeProgress = getIntSafe(cursor, "edgeProgress", 0),
                edgeMax = getIntSafe(cursor, "edgeMax", 0),
                proxyUrl = SecureColumnCrypto.decrypt(getStringSafe(cursor, "proxyUrl")),
                customUserAgent = getStringSafe(cursor, "customUserAgent"),
                cookiesSnapshot = SecureColumnCrypto.decrypt(getStringSafe(cursor, "cookiesSnapshot")),
                lastActiveDate = getStringSafe(cursor, "lastActiveDate") ?: "",
                isEnabled = getIntSafe(cursor, "isEnabled", 1) == 1,
                baselineDate = getStringSafe(cursor, "baselineDate") ?: "",
                dailySetProgress = getIntSafe(cursor, "dailySetProgress", 0),
                dailySetTotal = getIntSafe(cursor, "dailySetTotal", 3),
                dailySetDone = getIntSafe(cursor, "dailySetDone", 0) == 1,
                claimablePoints = getIntSafe(cursor, "claimablePoints", 0),
                readProgress = getIntSafe(cursor, "readProgress", 0),
                readMax = getIntSafe(cursor, "readMax", 30),
                puzzleProgress = getIntSafe(cursor, "puzzleProgress", 0),
                cooldownUntilTimestamp = getLongSafe(cursor, "cooldownUntilTimestamp", 0L),
                searchWindowStartMs = getLongSafe(cursor, "searchWindowStartMs", 0L),
                searchesInWindow = getIntSafe(cursor, "searchesInWindow", 0),
                dapiAccessToken = SecureColumnCrypto.decrypt(getStringSafe(cursor, "dapiAccessToken")),
                dapiRefreshToken = SecureColumnCrypto.decrypt(getStringSafe(cursor, "dapiRefreshToken")),
                dapiTokenExpiryTimestampMs = getLongSafe(cursor, "dapiTokenExpiryTimestampMs", 0L)
            )
        }

        override fun getAllActiveProfilesFlow(): Flow<List<AccountProfile>> {
            refreshProfilesFlow()
            return _profilesFlow.asStateFlow()
        }

        override suspend fun getAllActiveProfiles(): List<AccountProfile> = withContext(Dispatchers.IO) {
            val list = mutableListOf<AccountProfile>()
            val db = readableDatabase
            db.rawQuery("SELECT * FROM account_profiles WHERE isEnabled = 1 ORDER BY email ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(parseAccountProfile(cursor))
                }
            }
            list
        }

        override suspend fun getAllProfiles(): List<AccountProfile> = withContext(Dispatchers.IO) {
            val list = mutableListOf<AccountProfile>()
            val db = readableDatabase
            db.rawQuery("SELECT * FROM account_profiles ORDER BY isEnabled DESC, email ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(parseAccountProfile(cursor))
                }
            }
            list
        }

        override suspend fun getProfileById(id: String): AccountProfile? = withContext(Dispatchers.IO) {
            val db = readableDatabase
            db.rawQuery("SELECT * FROM account_profiles WHERE id = ? LIMIT 1", arrayOf(id)).use { cursor ->
                if (cursor.moveToNext()) {
                    parseAccountProfile(cursor)
                } else null
            }
        }

        private fun buildProfileContentValues(profile: AccountProfile): ContentValues {
            return ContentValues().apply {
                put("id", profile.id)
                put("email", profile.email)
                put("profileName", profile.profileName)
                put("level", profile.level)
                put("targetSearches", profile.targetSearches)
                put("completedSearchesToday", profile.completedSearchesToday)
                put("totalPoints", if (profile.availablePoints > 0) profile.availablePoints else profile.totalPoints)
                put("availablePoints", profile.availablePoints)
                put("lifetimePoints", profile.lifetimePoints)
                put("streakCount", profile.streakCount)
                put("baselinePointsToday", profile.baselinePointsToday)
                put("pointsEarnedToday", profile.pointsEarnedToday)
                put("pcProgress", profile.pcProgress)
                put("pcMax", profile.pcMax)
                put("mobileProgress", profile.mobileProgress)
                put("mobileMax", profile.mobileMax)
                put("edgeProgress", profile.edgeProgress)
                put("edgeMax", profile.edgeMax)
                put("proxyUrl", SecureColumnCrypto.encrypt(profile.proxyUrl))
                put("customUserAgent", profile.customUserAgent)
                put("cookiesSnapshot", SecureColumnCrypto.encrypt(profile.cookiesSnapshot))
                put("lastActiveDate", profile.lastActiveDate)
                put("isEnabled", if (profile.isEnabled) 1 else 0)
                put("baselineDate", profile.baselineDate)
                put("dailySetProgress", profile.dailySetProgress)
                put("dailySetTotal", profile.dailySetTotal)
                put("dailySetDone", if (profile.dailySetDone) 1 else 0)
                put("claimablePoints", profile.claimablePoints)
                put("readProgress", profile.readProgress)
                put("readMax", profile.readMax)
                put("puzzleProgress", profile.puzzleProgress)
                put("cooldownUntilTimestamp", profile.cooldownUntilTimestamp)
                put("searchWindowStartMs", profile.searchWindowStartMs)
                put("searchesInWindow", profile.searchesInWindow)
                put("dapiAccessToken", SecureColumnCrypto.encrypt(profile.dapiAccessToken))
                put("dapiRefreshToken", SecureColumnCrypto.encrypt(profile.dapiRefreshToken))
                put("dapiTokenExpiryTimestampMs", profile.dapiTokenExpiryTimestampMs)
            }
        }

        override suspend fun insertOrUpdateProfile(profile: AccountProfile) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val values = buildProfileContentValues(profile)
            db.insertWithOnConflict("account_profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            refreshProfilesFlow()
        }

        override suspend fun insertOrUpdateProfilesBatch(profiles: List<AccountProfile>) = withContext(Dispatchers.IO) {
            if (profiles.isEmpty()) return@withContext
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (profile in profiles) {
                    val values = buildProfileContentValues(profile)
                    db.insertWithOnConflict("account_profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            refreshProfilesFlow()
        }

        override suspend fun updateProfile(profile: AccountProfile) = withContext(Dispatchers.IO) {
            insertOrUpdateProfile(profile)
        }

        override suspend fun deleteProfile(profile: AccountProfile) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.delete("account_profiles", "id = ?", arrayOf(profile.id))
            refreshProfilesFlow()
        }

        override suspend fun resetDailyCountsIfNewDay(today: String) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.execSQL(
                """
                UPDATE account_profiles 
                SET completedSearchesToday = 0,
                    pointsEarnedToday = 0,
                    pcProgress = 0,
                    mobileProgress = 0,
                    edgeProgress = 0,
                    readProgress = 0,
                    dailySetProgress = 0,
                    dailySetDone = 0,
                    searchWindowStartMs = 0,
                    searchesInWindow = 0,
                    cooldownUntilTimestamp = 0,
                    baselinePointsToday = CASE WHEN availablePoints > 0 THEN availablePoints ELSE totalPoints END 
                WHERE lastActiveDate != ?
                """.trimIndent(),
                arrayOf(today)
            )
            refreshProfilesFlow()
        }

        override suspend fun updateTargetSearches(id: String, targetSearches: Int) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("targetSearches", targetSearches)
            }
            db.update("account_profiles", values, "id = ?", arrayOf(id))
            refreshProfilesFlow()
            Unit
        }

        override suspend fun updateAllTargetSearches(targetSearches: Int) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("targetSearches", targetSearches)
            }
            db.update("account_profiles", values, null, null)
            refreshProfilesFlow()
            Unit
        }
    }

    // =========================================================================
    // Native VlogDao Implementation
    // =========================================================================
    private inner class NativeVlogDao : VlogDao {
        private val _vlogsFlow = MutableStateFlow<List<VlogEntry>>(emptyList())

        private fun parseVlogEntry(cursor: Cursor): VlogEntry {
            return VlogEntry(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                timeFormatted = cursor.getString(cursor.getColumnIndexOrThrow("timeFormatted")),
                tag = cursor.getString(cursor.getColumnIndexOrThrow("tag")),
                level = cursor.getString(cursor.getColumnIndexOrThrow("level")),
                message = cursor.getString(cursor.getColumnIndexOrThrow("message")),
                details = cursor.getString(cursor.getColumnIndexOrThrow("details")),
                accountEmail = cursor.getString(cursor.getColumnIndexOrThrow("accountEmail")),
                snapshotId = cursor.getString(cursor.getColumnIndexOrThrow("snapshotId"))
            )
        }

        private fun queryRecentVlogs(limit: Int): List<VlogEntry> {
            val list = mutableListOf<VlogEntry>()
            val db = readableDatabase
            db.rawQuery("SELECT * FROM vlog_entries ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(parseVlogEntry(cursor))
                }
            }
            return list
        }

        override fun getRecentVlogsFlow(limit: Int): Flow<List<VlogEntry>> {
            _vlogsFlow.value = queryRecentVlogs(limit)
            return _vlogsFlow.asStateFlow()
        }

        override suspend fun getRecentVlogs(limit: Int): List<VlogEntry> = withContext(Dispatchers.IO) {
            queryRecentVlogs(limit)
        }

        override suspend fun getPointHistoryVlogs(limit: Int): List<VlogEntry> = withContext(Dispatchers.IO) {
            val list = mutableListOf<VlogEntry>()
            val db = readableDatabase
            db.rawQuery(
                """
                SELECT * FROM vlog_entries 
                WHERE tag = 'POINT' 
                   OR message LIKE '%+%pts%' 
                   OR message LIKE '%+%đ%' 
                   OR message LIKE '%[POINT]%' 
                   OR message LIKE '%[DAPI]%' 
                   OR message LIKE '%[TASK]%' 
                   OR message LIKE '%[KEEP-EARNING]%' 
                   OR message LIKE '%[READY-TO-CLAIM]%'
                ORDER BY timestamp DESC 
                LIMIT ?
                """.trimIndent(),
                arrayOf(limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(parseVlogEntry(cursor))
                }
            }
            list
        }

        override fun getVlogsByTagFlow(tag: String, limit: Int): Flow<List<VlogEntry>> {
            val list = mutableListOf<VlogEntry>()
            val db = readableDatabase
            db.rawQuery("SELECT * FROM vlog_entries WHERE tag = ? ORDER BY timestamp DESC LIMIT ?", arrayOf(tag, limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(parseVlogEntry(cursor))
                }
            }
            return MutableStateFlow(list).asStateFlow()
        }

        override fun getErrorVlogsFlow(limit: Int): Flow<List<VlogEntry>> {
            val list = mutableListOf<VlogEntry>()
            val db = readableDatabase
            db.rawQuery("SELECT * FROM vlog_entries WHERE level = 'ERROR' ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(parseVlogEntry(cursor))
                }
            }
            return MutableStateFlow(list).asStateFlow()
        }

        override suspend fun insertVlogs(entries: List<VlogEntry>) = withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (entry in entries) {
                    val values = ContentValues().apply {
                        put("timestamp", entry.timestamp)
                        put("timeFormatted", entry.timeFormatted)
                        put("tag", entry.tag)
                        put("level", entry.level)
                        put("message", entry.message)
                        put("details", entry.details)
                        put("accountEmail", entry.accountEmail)
                        put("snapshotId", entry.snapshotId)
                    }
                    db.insertWithOnConflict("vlog_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        override suspend fun insertVlog(entry: VlogEntry): Unit = withContext(Dispatchers.IO) {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("timestamp", entry.timestamp)
                put("timeFormatted", entry.timeFormatted)
                put("tag", entry.tag)
                put("level", entry.level)
                put("message", entry.message)
                put("details", entry.details)
                put("accountEmail", entry.accountEmail)
                put("snapshotId", entry.snapshotId)
            }
            db.insertWithOnConflict("vlog_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            Unit
        }

        override suspend fun pruneOldVlogs(cutoffTimestamp: Long): Unit = withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.delete("vlog_entries", "timestamp < ?", arrayOf(cutoffTimestamp.toString()))
            Unit
        }

        override suspend fun clearAllVlogs(): Unit = withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.delete("vlog_entries", null, null)
            Unit
        }

        override suspend fun getVlogCount(): Int = withContext(Dispatchers.IO) {
            val db = readableDatabase
            db.rawQuery("SELECT COUNT(*) FROM vlog_entries", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
    }

    companion object {
        private const val DATABASE_NAME = "rewards_node_database.db"
        private const val DATABASE_VERSION = 8

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = AppDatabase(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
