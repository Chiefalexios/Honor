package com.honorguard.ui

import android.app.Application
import android.telecom.Call
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.honorguard.data.GuardDatabase
import com.honorguard.data.model.*
import com.honorguard.data.repository.SpamRepository
import com.honorguard.service.CallRecordingService
import com.honorguard.service.GuardInCallService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class GuardViewModel(app: Application) : AndroidViewModel(app) {

    private val db = GuardDatabase.getInstance(app)
    private val spamRepo = SpamRepository(app)

    // ── Call Log ──────────────────────────────────────────────────────────
    val recentCalls = db.callRecordDao().getRecentCalls(100)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val spamCalls = db.callRecordDao().getSpamCalls()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Active Call UI State ──────────────────────────────────────────────
    private val _activeCallState = MutableStateFlow<ActiveCallState?>(null)
    val activeCallState: StateFlow<ActiveCallState?> = _activeCallState

    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration

    private var durationJob: Job? = null
    private var currentCallLogId: Long? = null

    // ── Recording State ───────────────────────────────────────────────────
    val isRecording = CallRecordingService.isRecording
    val recordingPath = CallRecordingService.currentPath

    // ── Dialpad ───────────────────────────────────────────────────────────
    private val _dialpadNumber = MutableStateFlow("")
    val dialpadNumber: StateFlow<String> = _dialpadNumber

    // ── Init: observe Telecom call state ─────────────────────────────────
    init {
        viewModelScope.launch {
            combine(
                GuardInCallService.activeCall,
                GuardInCallService.callState
            ) { call, state -> Pair(call, state) }.collect { (call, state) ->
                handleCallStateChange(call, state)
            }
        }
    }

    // ── Call State Machine ────────────────────────────────────────────────
    private fun handleCallStateChange(call: Call?, state: Int) {
        if (call == null) {
            // Call ended — save to log
            finaliseCallLog()
            _activeCallState.value = null
            durationJob?.cancel()
            _callDuration.value = 0
            return
        }

        val number = call.details?.handle?.schemeSpecificPart ?: "Unknown"

        when (state) {
            Call.STATE_RINGING -> {
                viewModelScope.launch {
                    val spamResult = spamRepo.checkNumber(number)
                    val displayName = spamRepo.resolveDisplayName(number)

                    _activeCallState.value = ActiveCallState(
                        number = number,
                        displayName = displayName,
                        photoUri = null,
                        callType = CallType.INCOMING,
                        spamScore = spamResult.score,
                        spamReason = spamResult.reason,
                        callState = TelecomCallState.RINGING
                    )
                }
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                viewModelScope.launch {
                    val displayName = spamRepo.resolveDisplayName(number)
                    _activeCallState.value = ActiveCallState(
                        number = number,
                        displayName = displayName,
                        photoUri = null,
                        callType = CallType.OUTGOING,
                        spamScore = SpamScore.UNKNOWN,
                        spamReason = null,
                        callState = TelecomCallState.DIALING
                    )
                }
            }
            Call.STATE_ACTIVE -> {
                _activeCallState.update { it?.copy(callState = TelecomCallState.ACTIVE) }
                startDurationTimer()
                // Insert initial call log entry
                viewModelScope.launch { insertCallLog(number) }
            }
            Call.STATE_HOLDING -> {
                _activeCallState.update { it?.copy(
                    callState = TelecomCallState.HOLDING,
                    isOnHold = true
                )}
                durationJob?.cancel()
            }
            Call.STATE_DISCONNECTED -> {
                _activeCallState.update { it?.copy(callState = TelecomCallState.DISCONNECTED) }
            }
        }
    }

    // ── Duration Timer ────────────────────────────────────────────────────
    private fun startDurationTimer() {
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _callDuration.value++
                _activeCallState.update { it?.copy(durationSeconds = _callDuration.value) }
            }
        }
    }

    // ── Call Log Persistence ──────────────────────────────────────────────
    private suspend fun insertCallLog(number: String) {
        val state = _activeCallState.value ?: return
        val id = db.callRecordDao().insert(
            CallRecord(
                number = number,
                displayName = state.displayName,
                type = state.callType,
                startTimeMs = System.currentTimeMillis(),
                durationSeconds = 0,
                recordingPath = null,
                spamScore = state.spamScore,
                spamReason = state.spamReason
            )
        )
        currentCallLogId = id
    }

    private fun finaliseCallLog() {
        val id = currentCallLogId ?: return
        val duration = _callDuration.value
        val path = recordingPath.value
        viewModelScope.launch {
            if (path != null) {
                db.callRecordDao().updateRecordingPath(id, path)
            }
        }
        currentCallLogId = null
    }

    // ── Call Actions (delegated to InCallService) ─────────────────────────
    fun answerCall() = GuardInCallService.instance?.answerCall()
    fun rejectCall() = GuardInCallService.instance?.rejectCall()
    fun endCall()    = GuardInCallService.instance?.endCall()

    fun toggleMute() {
        GuardInCallService.instance?.toggleMute()
        _activeCallState.update { it?.copy(isMuted = !(it.isMuted)) }
    }

    fun toggleSpeaker() {
        val next = !(_activeCallState.value?.isSpeakerOn ?: false)
        GuardInCallService.instance?.setSpeaker(next)
        _activeCallState.update { it?.copy(isSpeakerOn = next) }
    }

    fun toggleHold() {
        val onHold = _activeCallState.value?.isOnHold ?: false
        if (onHold) GuardInCallService.instance?.unholdCall()
        else        GuardInCallService.instance?.holdCall()
        _activeCallState.update { it?.copy(isOnHold = !onHold) }
    }

    fun toggleRecording() {
        if (isRecording.value) {
            GuardInCallService.instance?.stopRecording()
        } else {
            GuardInCallService.instance?.startRecordingManual()
        }
        _activeCallState.update { it?.copy(isRecording = !isRecording.value) }
    }

    fun sendDtmf(digit: Char) = GuardInCallService.instance?.sendDtmf(digit)

    // ── Dialpad ───────────────────────────────────────────────────────────
    fun appendDigit(digit: String) {
        _dialpadNumber.value += digit
    }

    fun deleteDigit() {
        val current = _dialpadNumber.value
        if (current.isNotEmpty()) _dialpadNumber.value = current.dropLast(1)
    }

    fun clearDialpad() { _dialpadNumber.value = "" }
}
