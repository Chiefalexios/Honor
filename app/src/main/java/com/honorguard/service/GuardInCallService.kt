package com.honorguard.service

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.honorguard.ui.screens.InCallActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GuardInCallService is the heart of the dialer.
 *
 * Android's Telecom framework calls this service for every call lifecycle
 * event. We bridge those events to:
 *   - InCallActivity (the UI)
 *   - CallRecordingService (auto-recording)
 *   - SpamRepository (number checking)
 *
 * To become the default dialer, the user grants permission via
 * RoleManager.ROLE_DIALER — we request this on first launch.
 */
class GuardInCallService : InCallService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Single source of truth for the active call — observed by InCallActivity
    companion object {
        private val _activeCall = MutableStateFlow<Call?>(null)
        val activeCall: StateFlow<Call?> = _activeCall

        private val _callState = MutableStateFlow(Call.STATE_NEW)
        val callState: StateFlow<Int> = _callState

        // Singleton reference so InCallActivity can drive actions
        var instance: GuardInCallService? = null
            private set
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    // ── Call events ───────────────────────────────────────────────────────
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        _activeCall.value = call

        call.registerCallback(callCallback)
        updateState(call.state)

        // Launch InCallActivity
        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
            putExtra("number", call.details?.handle?.schemeSpecificPart ?: "")
            putExtra("call_direction",
                if (call.details.callDirection == Call.Details.DIRECTION_INCOMING)
                    "INCOMING" else "OUTGOING"
            )
        }
        startActivity(intent)

        // Auto-start recording when call becomes active (handled in callback)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        _activeCall.value = null
        stopRecording()
    }

    // ── Call state callback ───────────────────────────────────────────────
    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            updateState(state)
            when (state) {
                Call.STATE_ACTIVE -> {
                    // Call connected — start auto-recording
                    startRecordingIfEnabled()
                }
                Call.STATE_DISCONNECTED -> {
                    stopRecording()
                }
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            // Handle caller ID updates, etc.
        }
    }

    private fun updateState(state: Int) {
        _callState.value = state
    }

    // ── Call actions (called from InCallActivity) ─────────────────────────

    fun answerCall() {
        _activeCall.value?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun rejectCall() {
        _activeCall.value?.reject(false, null)
    }

    fun endCall() {
        _activeCall.value?.disconnect()
    }

    fun holdCall() {
        _activeCall.value?.hold()
    }

    fun unholdCall() {
        _activeCall.value?.unhold()
    }

    fun toggleMute() {
        val current = isMuted
        setMuted(!current)
    }

    fun setSpeaker(on: Boolean) {
        val route = if (on) android.telecom.CallAudioState.ROUTE_SPEAKER
                    else android.telecom.CallAudioState.ROUTE_EARPIECE
        setAudioRoute(route)
    }

    fun sendDtmf(digit: Char) {
        _activeCall.value?.playDtmfTone(digit)
        // Stop after short delay
        scope.launch {
            delay(150)
            _activeCall.value?.stopDtmfTone()
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────
    private var recordingServiceBound = false

    private fun startRecordingIfEnabled() {
        // Check user preference (DataStore read — simplified here)
        val intent = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_START
            putExtra("number",
                _activeCall.value?.details?.handle?.schemeSpecificPart ?: "unknown")
        }
        startForegroundService(intent)
        recordingServiceBound = true
    }

    fun startRecordingManual() {
        startRecordingIfEnabled()
    }

    fun stopRecording() {
        if (recordingServiceBound) {
            val intent = Intent(this, CallRecordingService::class.java).apply {
                action = CallRecordingService.ACTION_STOP
            }
            startService(intent)
            recordingServiceBound = false
        }
    }

    // Expose recording mode for UI debugging (e.g., "DualAudioRecord" vs "MIC only")
    val recordingMode = CallRecordingService.recordingMode
}
