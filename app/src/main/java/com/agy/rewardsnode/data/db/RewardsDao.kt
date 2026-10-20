package com.agy.rewardsnode.data.db

import androidx.room.*
import com.agy.rewardsnode.data.model.AccountProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface RewardsDao {
    @Query("SELECT * FROM account_profiles WHERE isEnabled = 1 ORDER BY email ASC")
    fun getAllActiveProfilesFlow(): Flow<List<AccountProfile>>

    @Query("SELECT * FROM account_profiles WHERE isEnabled = 1 ORDER BY email ASC")
    suspend fun getAllActiveProfiles(): List<AccountProfile>

    @Query("SELECT * FROM account_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: String): AccountProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateProfile(profile: AccountProfile)

    @Update
    suspend fun updateProfile(profile: AccountProfile)

    @Delete
    suspend fun deleteProfile(profile: AccountProfile)

    @Query("UPDATE account_profiles SET completedSearchesToday = 0 WHERE lastActiveDate != :today")
    suspend fun resetDailyCountsIfNewDay(today: String)
}
