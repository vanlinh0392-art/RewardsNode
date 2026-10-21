package com.agy.rewardsnode.data.db

import com.agy.rewardsnode.data.model.VlogEntry
import kotlinx.coroutines.flow.Flow

interface VlogDao {
    fun getRecentVlogsFlow(limit: Int = 300): Flow<List<VlogEntry>>
    fun getVlogsByTagFlow(tag: String, limit: Int = 300): Flow<List<VlogEntry>>
    fun getErrorVlogsFlow(limit: Int = 100): Flow<List<VlogEntry>>
    suspend fun insertVlogs(entries: List<VlogEntry>)
    suspend fun insertVlog(entry: VlogEntry)
    suspend fun pruneOldVlogs(cutoffTimestamp: Long)
    suspend fun clearAllVlogs()
    suspend fun getVlogCount(): Int
}
