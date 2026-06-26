package com.honorguard.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
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
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

/**
 * CallRecordingService — Snapdragon 7 Gen 3 (SM7550-AB) edition
 *
 * ═══════════════════════════════════════════════════════════════════════
 * WHY SNAPDRAGON NEEDS A DIFFERENT APPROACH
 * ═══════════════════════════════════════════════════════════════════════
 *
 * The original code assumed HiSilicon/Kirin audio routing (used on older
 * Honor devices). Honor 200 uses Qualcomm SM7550-AB with Aqstic WCD audio
 * codec and the Qualcomm audio HAL (hardware abstraction layer).
 *
 * Key differences:
 *
 *  HiSilicon path:
 *    VOICE_COMMUNICATION → captures mixed downlink+uplink via HiSilicon's
 *    proprietary audio DSP mixing — both call sides in one stream.
 *
 *  Qualcomm SM7550 path:
 *    VOICE_COMMUNICATION → on Qualcomm's Aqstic/WCD9380 codec, this source
 *    routes to the voice processing chain but Android 12+ SELinux policies
 *    block non-system apps from tapping the mixed stream. Only the uplink
 *    (your voice, microphone) comes through cleanly.
 *
 *    VOICE_CALL (AudioSource 4) → requires CAPTURE_AUDIO_OUTPUT (system
 *    permission, not grantable to non-root apps).
 *
 *    VOICE_DOWNLINK / VOICE_UPLINK — similarly restricted.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * WHAT WE DO INSTEAD: DUAL-CHANNEL STRATEGY
 * ═══════════════════════════════════════════════════════════════════════
 *
 * On Qualcomm SM7550 with stock MagicOS (no root), the most reliable
 * approach is:
 *
 * PRIMARY (attempted first):
 *   VOICE_COMMUNICATION source via MediaRecorder.
 *   On MagicOS 8.x (Android 14, which Honor 200 ships with), Honor's
 *   own telephony trust grants the default dialer elevated access.
 *   This captures uplink clearly; downlink capture depends on firmware.
 *
 * SECONDARY (if primary fails or is uplink-only):
 *   Dual AudioRecord approach:
 *     Track A: AudioSource.MIC           → your voice (uplink)
 *     Track B: AudioSource.VOICE_COMMUNICATION → attempted downlink
 *   Both are recorded to separate raw PCM files, then mixed into a
 *   single stereo M4A (left = your voice, right = caller's voice).
 *   This gives maximum evidence quality even if downlink is limited.
 *
 * TERTIARY (final fallback):
 *   Single MIC source — at minimum captures your side of the
 *   conversation, which is often sufficient for scammer evidence
 *   (your responses make the context clear).
 *
 * ═══════════════════════════════════════════════════════════════════════
 * AUDIO FORMAT
 * ═══════════════════════════════════════════════════════════════════════
 *   Sample rate : 16000 Hz (matches Qualcomm voice processing chain —
 *                 using 44100 Hz on SM7550 voice sources causes resampling
 *                 artifacts and can trigger HAL errors)
 *   Channels    : Stereo (dual-track mode) / Mono (single mode)
 *   Encoding    : AAC-LC at 64kbps (voice optimal; 128kbps wastes space)
 *   Container   : M4A (MPEG-4 Part 14) — universally playable
 *   Naming      : CALL_YYYYMMDD_HHMMSS_<number>.m4a
 */
class CallRecordingService : Service() {

    // Primary path
    private var mediaRecorder: MediaRecorder? = null

    // Dual AudioRecord path
    private var micRecord: AudioRecord? = null
    private var voiceRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var codec: MediaCodec? = null
    private var recordingThread: Thread? = null

    private var outputPath: String? = null
    private var activePath: RecordingPath = RecordingPath.NONE

    enum class RecordingPath { NONE, MEDIA_RECORDER, DUAL_TRACK, SINGLE_MIC }

    companion object {
        const val ACTION_START = "com.honorguard.RECORD_START"
        const val ACTION_STOP  = "com.honorguard.RECORD_STOP"

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _currentPath = MutableStateFlow<String?>(null)
        val currentPath: StateFlow<String?> = _currentPath

        private val _recordingMode = MutableStateFlow("—")
        val recordingMode: StateFlow<String> = _recordingMode   // shown in UI for debugging

        private const val TAG = "HonorGuard.Recording"
        private const val NOTIFICATION_ID = 3

        // Qualcomm SM7550 optimal voice sample rate
        // 44100 Hz causes ADSP resampling issues on Aqstic WCD codec
        private const val SAMPLE_RATE = 16000
        private const val BIT_RATE    = 64_000    // 64kbps — plenty for voice
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

    // ══════════════════════════════════════════════════════════════════
    // PATH 1: MediaRecorder with VOICE_COMMUNICATION
    // Best outcome on MagicOS 8.x (Android 14) with default dialer trust
    // ══════════════════════════════════════════════════════════════════
    private fun startRecording(number: String) {
        if (_isRecording.value) return

        val file = createOutputFile(number)
        outputPath = file.absolutePath

        val success = tryMediaRecorder(file)
        if (!success) {
            Log.w(TAG, "MediaRecorder path failed — trying dual AudioRecord")
            val dualSuccess = tryDualAudioRecord(file)
            if (!dualSuccess) {
                Log.w(TAG, "Dual AudioRecord failed — falling back to MIC only")
                trySingleMicRecord(file)
            }
        }
    }

    private fun tryMediaRecorder(file: File): Boolean {
        return try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(BIT_RATE)
                setAudioSamplingRate(SAMPLE_RATE)   // 16kHz — Qualcomm voice chain native rate
                setAudioChannels(1)                  // Mono for voice
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            activePath = RecordingPath.MEDIA_RECORDER
            _isRecording.value = true
            _currentPath.value = file.absolutePath
            _recordingMode.value = "MediaRecorder/VOICE_COMM"
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "Recording started via MediaRecorder (VOICE_COMMUNICATION)")
            true

        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder failed: ${e.message}")
            mediaRecorder?.release()
            mediaRecorder = null
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // PATH 2: Dual AudioRecord — stereo mix of both call legs
    // Left channel = MIC (your voice, uplink)
    // Right channel = VOICE_COMMUNICATION (caller, downlink — best effort)
    // Encoded to AAC stereo M4A via MediaCodec + MediaMuxer
    // ══════════════════════════════════════════════════════════════════
    private fun tryDualAudioRecord(file: File): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.w(TAG, "AudioRecord not supported on this device")
            return false
        }

        return try {
            val bufSize = maxOf(minBuf, 4096)

            micRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )

            // Attempt second track — may silently give only silence on some Snapdragon builds
            voiceRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )

            if (micRecord!!.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "MIC AudioRecord failed to init")
                releaseDualTrack()
                return false
            }

            // Setup MediaCodec AAC encoder
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2  // stereo
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE * 2)  // stereo
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize * 4)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec!!.start()

            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            micRecord!!.startRecording()
            voiceRecord?.startRecording()

            // Encoding happens on a background thread
            startDualTrackThread(bufSize)

            activePath = RecordingPath.DUAL_TRACK
            _isRecording.value = true
            _currentPath.value = file.absolutePath
            _recordingMode.value = "DualAudioRecord (L=mic, R=caller)"
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "Recording started via Dual AudioRecord")
            true

        } catch (e: Exception) {
            Log.w(TAG, "Dual AudioRecord setup failed: ${e.message}")
            releaseDualTrack()
            false
        }
    }

    private fun startDualTrackThread(bufSize: Int) {
        recordingThread = Thread {
            val micBuf   = ShortArray(bufSize / 2)
            val voiceBuf = ShortArray(bufSize / 2)
            val stereoBuf = ShortArray(bufSize)  // interleaved L/R

            var muxerTrackIndex = -1
            var muxerStarted = false
            var presentationUs = 0L
            val frameUs = (bufSize / 2 * 1_000_000L) / SAMPLE_RATE

            while (_isRecording.value) {
                // Read both tracks simultaneously (best effort — voice may return silence)
                val micRead   = micRecord?.read(micBuf, 0, micBuf.size) ?: 0
                val voiceRead = voiceRecord?.read(voiceBuf, 0, voiceBuf.size) ?: 0

                if (micRead <= 0) continue

                // Interleave: even samples = left (mic), odd = right (voice/silence)
                val frameSize = micRead
                for (i in 0 until frameSize) {
                    stereoBuf[i * 2]     = micBuf[i]
                    stereoBuf[i * 2 + 1] = if (voiceRead > 0 && i < voiceRead) voiceBuf[i] else 0
                }

                // Feed to MediaCodec encoder
                val inputIdx = codec?.dequeueInputBuffer(10_000) ?: -1
                if (inputIdx >= 0) {
                    val inputBuf: ByteBuffer? = codec?.getInputBuffer(inputIdx)
                    inputBuf?.clear()
                    for (i in 0 until frameSize * 2) {
                        inputBuf?.putShort(stereoBuf[i])
                    }
                    codec?.queueInputBuffer(inputIdx, 0,
                        frameSize * 2 * 2, presentationUs, 0)
                    presentationUs += frameUs
                }

                // Drain encoder output → muxer
                val bufferInfo = MediaCodec.BufferInfo()
                var outputIdx = codec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                while (outputIdx >= 0) {
                    if (outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            val trackIdx = muxer?.addTrack(codec!!.outputFormat) ?: -1
                            muxerTrackIndex = trackIdx
                            muxer?.start()
                            muxerStarted = true
                        }
                    } else if (outputIdx >= 0) {
                        val outputBuf = codec?.getOutputBuffer(outputIdx)
                        if (outputBuf != null && muxerStarted && muxerTrackIndex >= 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                muxer?.writeSampleData(muxerTrackIndex, outputBuf, bufferInfo)
                            }
                        }
                        codec?.releaseOutputBuffer(outputIdx, false)
                    }
                    outputIdx = codec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                }
            }

            // Flush encoder
            codec?.signalEndOfInputStream()
        }.also { it.name = "HG-RecordThread"; it.start() }
    }

    // ══════════════════════════════════════════════════════════════════
    // PATH 3: Single MIC — uplink only, always works
    // ══════════════════════════════════════════════════════════════════
    private fun trySingleMicRecord(file: File) {
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(BIT_RATE)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            activePath = RecordingPath.SINGLE_MIC
            _isRecording.value = true
            _currentPath.value = file.absolutePath
            _recordingMode.value = "MIC only (uplink)"
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "Recording started via MIC fallback (uplink only)")

        } catch (e: Exception) {
            Log.e(TAG, "All recording paths failed: ${e.message}")
            _isRecording.value = false
            _recordingMode.value = "Failed"
            stopSelf()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // STOP + CLEANUP
    // ══════════════════════════════════════════════════════════════════
    fun stopRecordingAndSave() {
        _isRecording.value = false   // signals recording thread to exit

        when (activePath) {
            RecordingPath.MEDIA_RECORDER, RecordingPath.SINGLE_MIC -> {
                try {
                    mediaRecorder?.stop()
                } catch (_: Exception) { }
                mediaRecorder?.release()
                mediaRecorder = null
            }
            RecordingPath.DUAL_TRACK -> {
                recordingThread?.join(2000)   // wait for thread to flush encoder
                releaseDualTrack()
            }
            RecordingPath.NONE -> { }
        }

        activePath = RecordingPath.NONE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Recording saved to: $outputPath")
    }

    private fun releaseDualTrack() {
        try { micRecord?.stop();   micRecord?.release()   } catch (_: Exception) { }
        try { voiceRecord?.stop(); voiceRecord?.release() } catch (_: Exception) { }
        try { codec?.stop();       codec?.release()       } catch (_: Exception) { }
        try { muxer?.stop();       muxer?.release()       } catch (_: Exception) { }
        micRecord = null; voiceRecord = null; codec = null; muxer = null
    }

    // ══════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════
    private fun createOutputFile(number: String): File {
        val dir = File(getExternalFilesDir(null), "Recordings").also { it.mkdirs() }
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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
            .setContentText("Mode: ${_recordingMode.value}")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopRecordingAndSave()
        super.onDestroy()
    }
}
