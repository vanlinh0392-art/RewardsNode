package com.agy.rewardsnode.data.db

import com.agy.rewardsnode.data.model.AccountProfile
import kotlinx.coroutines.flow.Flow

interface RewardsDao {
    fun getAllActiveProfilesFlow(): Flow<List<AccountProfile>>
    suspend fun getAllActiveProfiles(): List<AccountProfile>
    suspend fun getAllProfiles(): List<AccountProfile>
    suspend fun getProfileById(id: String): AccountProfile?
    suspend fun insertOrUpdateProfile(profile: AccountProfile)
    suspend fun insertOrUpdateProfilesBatch(profiles: List<AccountProfile>)
    suspend fun updateProfile(profile: AccountProfile)
    suspend fun deleteProfile(profile: AccountProfile)
    suspend fun resetDailyCountsIfNewDay(today: String)
    suspend fun updateTargetSearches(id: String, targetSearches: Int)
    suspend fun updateAllTargetSearches(targetSearches: Int)
}
