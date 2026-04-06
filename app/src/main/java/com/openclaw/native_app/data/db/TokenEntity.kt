package com.openclaw.native_app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity — one row per API call made by the gateway.
 */
@Entity(tableName = "token_usage")
data class TokenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId:    String,
    val timestamp:    Long,
    val provider:     String,   // "anthropic", "openai", "gemini", "ollama"
    val model:        String,
    val inputTokens:  Int,
    val outputTokens: Int,
    val costUsd:      Double,   // computed at insert time
    val prompt:       String,   // first 300 chars of prompt
    val response:     String    // first 300 chars of response
)
