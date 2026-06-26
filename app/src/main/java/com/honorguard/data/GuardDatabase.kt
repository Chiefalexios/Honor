package com.honorguard.data

import android.content.Context
import androidx.room.*
import com.honorguard.data.model.CallRecord
import com.honorguard.data.model.CallType
import com.honorguard.data.model.SpamScore
import kotlinx.coroutines.flow.Flow

// ── Type Converters ────────────────────────────────────────────────────────
class Converters {
    @TypeConverter fun fromCallType(v: CallType): String = v.name
    @TypeConverter fun toCallType(v: String): CallType = CallType.valueOf(v)
    @TypeConverter fun fromSpamScore(v: SpamScore): String = v.name
    @TypeConverter fun toSpamScore(v: String): SpamScore = SpamScore.valueOf(v)
}

// ── DAO ───────────────────────────────────────────────────────────────────
@Dao
interface CallRecordDao {
    @Query("SELECT * FROM call_log ORDER BY startTimeMs DESC")
    fun getAllCalls(): Flow<List<CallRecord>>

    @Query("SELECT * FROM call_log ORDER BY startTimeMs DESC LIMIT :limit")
    fun getRecentCalls(limit: Int = 50): Flow<List<CallRecord>>

    @Query("SELECT * FROM call_log WHERE number = :number ORDER BY startTimeMs DESC")
    fun getCallsByNumber(number: String): Flow<List<CallRecord>>

    @Query("SELECT * FROM call_log WHERE spamScore IN ('SPAM','FRAUD') ORDER BY startTimeMs DESC")
    fun getSpamCalls(): Flow<List<CallRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: CallRecord): Long

    @Query("UPDATE call_log SET recordingPath = :path WHERE id = :id")
    suspend fun updateRecordingPath(id: Long, path: String)

    @Query("DELETE FROM call_log WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM call_log WHERE startTimeMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}

// ── Database ──────────────────────────────────────────────────────────────
@Database(entities = [CallRecord::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class GuardDatabase : RoomDatabase() {
    abstract fun callRecordDao(): CallRecordDao

    companion object {
        @Volatile private var INSTANCE: GuardDatabase? = null

        fun getInstance(context: Context): GuardDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GuardDatabase::class.java,
                    "honorguard.db"
                ).build().also { INSTANCE = it }
            }
    }
}
