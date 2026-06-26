package com.honorguard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// ── Call Log Entry ─────────────────────────────────────────────────────────
@Entity(tableName = "call_log")
data class CallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val displayName: String?,
    val type: CallType,          // INCOMING / OUTGOING / MISSED
    val startTimeMs: Long,
    val durationSeconds: Long,
    val recordingPath: String?,  // null if not recorded
    val spamScore: SpamScore,
    val spamReason: String?
)

enum class CallType { INCOMING, OUTGOING, MISSED }

enum class SpamScore {
    SAFE,       // verified contact or known safe
    UNKNOWN,    // no data
    SUSPECTED,  // community reports
    SPAM,       // confirmed spam/scam
    FRAUD       // confirmed fraud
}

// ── Active Call State (in-memory, held in ViewModel) ───────────────────────
data class ActiveCallState(
    val number: String,
    val displayName: String?,
    val photoUri: String?,
    val callType: CallType,
    val spamScore: SpamScore,
    val spamReason: String?,
    val callState: TelecomCallState,
    val durationSeconds: Long = 0L,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isOnHold: Boolean = false,
    val isRecording: Boolean = false,
    val recordingPath: String? = null
)

enum class TelecomCallState {
    RINGING,
    DIALING,
    ACTIVE,
    HOLDING,
    DISCONNECTED
}

// ── Spam Check Result ──────────────────────────────────────────────────────
data class SpamCheckResult(
    val number: String,
    val score: SpamScore,
    val reportCount: Int,
    val reason: String?,
    val source: String   // "local" | "community" | "api"
)
