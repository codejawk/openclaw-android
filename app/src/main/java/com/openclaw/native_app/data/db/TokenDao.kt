package com.openclaw.native_app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TokenDao {

    @Insert
    suspend fun insert(entity: TokenEntity): Long

    @Query("SELECT * FROM token_usage ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<TokenEntity>>

    @Query("SELECT * FROM token_usage WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeSession(sessionId: String): Flow<List<TokenEntity>>

    @Query("SELECT SUM(inputTokens) FROM token_usage")
    fun totalInputTokens(): Flow<Int?>

    @Query("SELECT SUM(outputTokens) FROM token_usage")
    fun totalOutputTokens(): Flow<Int?>

    @Query("SELECT SUM(costUsd) FROM token_usage")
    fun totalCostUsd(): Flow<Double?>

    @Query("SELECT SUM(inputTokens) FROM token_usage WHERE sessionId = :sessionId")
    fun sessionInputTokens(sessionId: String): Flow<Int?>

    @Query("SELECT SUM(outputTokens) FROM token_usage WHERE sessionId = :sessionId")
    fun sessionOutputTokens(sessionId: String): Flow<Int?>

    @Query("SELECT SUM(costUsd) FROM token_usage WHERE sessionId = :sessionId")
    fun sessionCostUsd(sessionId: String): Flow<Double?>

    @Query("SELECT * FROM token_usage ORDER BY timestamp ASC")
    suspend fun allForExport(): List<TokenEntity>

    @Query("DELETE FROM token_usage")
    suspend fun deleteAll()
}
