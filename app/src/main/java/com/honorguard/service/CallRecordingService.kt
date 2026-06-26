package com.honorguard.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.honorguard.HonorGuardApp
import com.honorguard.R
import com.honorguard.ui.screens.InCallActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CallRecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var audioManager: AudioManager? = null
    private var outputPath: String? = null

    companion object {
        const val ACTION_START = "com.honorguard.RECORD_START"
        const val ACTION_STOP  = "com.honorguard.RECORD_STOP"

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _currentPath = MutableStateFlow<String?>(null)
        val currentPath: StateFlow<String?> = _currentPath

        private val _recordingMode = MutableStateFlow("—")
        val recordingMode: StateFlow<String> = _recordingMode

        private const val TAG = "HonorGuard.Recording"
        private const val NOTIFICATION_ID = 3
        private const val SAMPLE_RATE = 16000
        private const val BIT_RATE = 64_000
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val number = intent.getStringExtra("number") ?: "unknown"
                startRecording(number)
            }
            ACTION_STOP -> stopRecordingAndSave()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(number: String) {
        if (_isRecording.value) return

        val file = createOutputFile(number)
        outputPath = file.absolutePath

        // ── Speaker routing trick ──────────────────────────────────
        // Force speakerphone ON so caller's voice plays through the
        // speaker and MIC picks up both sides of the conversation.
        // This is the only reliable method on MagicOS without root.
        audioManager = getSystemService(AudioManager::class.java)
        audioManager?.apply {
            mode = AudioManager.MODE_IN_COMMUNICATION
            isSpeakerphoneOn = true
        }

        // Wait for speaker routing to settle
        Thread.sleep(400)

        // Try VOICE_RECOGNITION first — less restricted than MIC on MagicOS
        val success = tryRecord(file, MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION")
        if (!success) {
            // Fallback to plain MIC
            val micSuccess = tryRecord(file, MediaRecorder.AudioSource.MIC, "MIC")
            if (!micSuccess) {
                // Last resort — VOICE_COMMUNICATION
                tryRecord(file, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION")
            }
        }
    }

    private fun tryRecord(file: File, audioSource: Int, modeName: String): Boolean {
        return try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder!!.apply {
                setAudioSource(audioSource)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(BIT_RATE)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            _isRecording.value = true
            _currentPath.value = file.absolutePath
            _recordingMode.value = modeName
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "Recording started: $modeName — speaker ON, both voices captured via MIC")
            true

        } catch (e: Exception) {
            Log.w(TAG, "$modeName failed: ${e.message}")
            mediaRecorder?.release()
            mediaRecorder = null
            false
        }
    }

    fun stopRecordingAndSave() {
        if (!_isRecording.value) return
        _isRecording.value = false

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) { }
        mediaRecorder?.release()
        mediaRecorder = null

        // Restore audio to earpiece after recording stops
        audioManager?.apply {
            isSpeakerphoneOn = false
            mode = AudioManager.MODE_NORMAL
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Recording saved: $outputPath")
    }

    private fun createOutputFile(number: String): File {
        val dir = File(getExternalFilesDir(null), "Recordings").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val safe = number.replace("+", "").replace(" ", "").take(15)
        return File(dir, "CALL_${ts}_$safe.m4a")
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, InCallActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, HonorGuardApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle("Recording call")
            .setContentText("Mode: ${_recordingMode.value} • Speaker ON")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopRecordingAndSave()
        super.onDestroy()
    }
}
