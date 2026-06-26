package com.honorguard.ui.screens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.honorguard.data.model.*
import com.honorguard.ui.GuardViewModel
import com.honorguard.ui.theme.*

class InCallActivity : ComponentActivity() {

    private val viewModel: GuardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during call
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        // Back press: minimize, don't kill call
        onBackPressedDispatcher.addCallback(this) {
            moveTaskToBack(true)
        }

        setContent {
            HonorGuardTheme {
                val callState by viewModel.activeCallState.collectAsState()
                val duration by viewModel.callDuration.collectAsState()
                val isRecording by viewModel.isRecording.collectAsState()

                if (callState != null) {
                    InCallScreen(
                        state = callState!!,
                        duration = duration,
                        isRecording = isRecording,
                        onAnswer  = { viewModel.answerCall() },
                        onReject  = { viewModel.rejectCall() },
                        onEnd     = { viewModel.endCall(); finish() },
                        onMute    = { viewModel.toggleMute() },
                        onSpeaker = { viewModel.toggleSpeaker() },
                        onHold    = { viewModel.toggleHold() },
                        onRecord  = { viewModel.toggleRecording() }
                    )
                } else {
                    // No active call — close
                    LaunchedEffect(Unit) { finish() }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
// IN-CALL SCREEN
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun InCallScreen(
    state: ActiveCallState,
    duration: Long,
    isRecording: Boolean,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onHold: () -> Unit,
    onRecord: () -> Unit
) {
    val isRinging = state.callState == TelecomCallState.RINGING
    val isActive  = state.callState == TelecomCallState.ACTIVE
    val spamColor = GuardColors.forSpam(state.spamScore)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to GuardColors.NavyDeep,
                    1f to Color(0xFF060D1A)
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(Modifier.height(64.dp))

            // ── Spam Warning Banner ────────────────────────────────────────
            AnimatedVisibility(
                visible = state.spamScore != SpamScore.SAFE &&
                          state.spamScore != SpamScore.UNKNOWN,
                enter = slideInVertically() + fadeIn()
            ) {
                SpamWarningBanner(state.spamScore, state.spamReason, spamColor)
            }

            Spacer(Modifier.height(32.dp))

            // ── Caller Avatar with pulsing shield ring ─────────────────────
            CallerAvatar(
                name = state.displayName ?: state.number,
                spamColor = spamColor,
                isRinging = isRinging,
                spamScore = state.spamScore
            )

            Spacer(Modifier.height(24.dp))

            // ── Caller Name ───────────────────────────────────────────────
            Text(
                text = state.displayName ?: "Unknown",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(4.dp))

            // ── Number ────────────────────────────────────────────────────
            Text(
                text = state.number,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                letterSpacing = 1.5.sp
            )

            Spacer(Modifier.height(12.dp))

            // ── Status / Duration ─────────────────────────────────────────
            val statusText = when (state.callState) {
                TelecomCallState.RINGING      -> "Incoming call"
                TelecomCallState.DIALING      -> "Calling…"
                TelecomCallState.ACTIVE       -> formatDuration(duration)
                TelecomCallState.HOLDING      -> "On hold"
                TelecomCallState.DISCONNECTED -> "Disconnected"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecording) {
                    val recMode by com.honorguard.service.CallRecordingService.recordingMode.collectAsState()
                    // Pulsing red dot
                    val recAlpha by rememberInfiniteTransition(label = "rec").animateFloat(
                        initialValue = 1f, targetValue = 0.3f,
                        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                        label = "recDot"
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .alpha(recAlpha)
                            .background(GuardColors.Amber, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("REC", color = GuardColors.Amber,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text("•  $statusText", color = GuardColors.Steel, fontSize = 14.sp)
                } else {
                    Text(statusText, color = GuardColors.Steel, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Control Buttons (active call) ─────────────────────────────
            AnimatedVisibility(visible = isActive || state.callState == TelecomCallState.HOLDING) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CallControlButton(
                            icon    = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label   = if (state.isMuted) "Unmute" else "Mute",
                            active  = state.isMuted,
                            onClick = onMute
                        )
                        CallControlButton(
                            icon    = Icons.Default.VolumeUp,
                            label   = if (state.isSpeakerOn) "Speaker On" else "Speaker",
                            active  = state.isSpeakerOn,
                            onClick = onSpeaker
                        )
                        CallControlButton(
                            icon    = Icons.Default.PauseCircle,
                            label   = if (state.isOnHold) "Resume" else "Hold",
                            active  = state.isOnHold,
                            onClick = onHold
                        )
                        CallControlButton(
                            icon    = if (isRecording) Icons.Default.StopCircle
                                      else Icons.Default.RadioButtonChecked,
                            label   = if (isRecording) "Stop Rec" else "Record",
                            active  = isRecording,
                            activeColor = GuardColors.Amber,
                            onClick = onRecord
                        )
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }

            // ── Answer / Reject (ringing) ─────────────────────────────────
            if (isRinging) {
                IncomingCallButtons(onAnswer = onAnswer, onReject = onReject)
            } else {
                // End call button
                Box(Modifier.fillMaxWidth(), Alignment.Center) {
                    FloatingActionButton(
                        onClick = onEnd,
                        containerColor = GuardColors.Red,
                        contentColor = Color.White,
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.CallEnd, "End call",
                            modifier = Modifier.size(32.dp))
                    }
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

// ── Spam Warning Banner ───────────────────────────────────────────────────
@Composable
fun SpamWarningBanner(score: SpamScore, reason: String?, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (score) {
                    SpamScore.FRAUD -> Icons.Default.GppBad
                    SpamScore.SPAM  -> Icons.Default.Warning
                    else            -> Icons.Default.Info
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    when (score) {
                        SpamScore.FRAUD     -> "⚠️  Likely Fraud"
                        SpamScore.SPAM      -> "Spam Call Detected"
                        SpamScore.SUSPECTED -> "Suspicious Number"
                        else -> ""
                    },
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                if (reason != null) {
                    Text(reason, color = color.copy(alpha = 0.75f), fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Caller Avatar ─────────────────────────────────────────────────────────
@Composable
fun CallerAvatar(name: String, spamColor: Color, isRinging: Boolean, spamScore: SpamScore) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "alpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "scale"
    )

    Box(
        modifier = Modifier.size(120.dp),
        contentAlignment = Alignment.Center
    ) {
        // Pulsing outer ring (the "shield" signature element)
        if (isRinging) {
            Box(
                Modifier
                    .fillMaxSize()
                    .scale(pulseScale)
                    .background(spamColor.copy(alpha = pulseAlpha), CircleShape)
            )
        }

        // Avatar circle
        Box(
            Modifier
                .size(96.dp)
                .background(GuardColors.NavyCard, CircleShape)
                .border(2.dp, spamColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (spamScore == SpamScore.SPAM || spamScore == SpamScore.FRAUD) {
                Icon(Icons.Default.GppBad, null,
                    tint = spamColor, modifier = Modifier.size(48.dp))
            } else {
                Text(
                    name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    color = GuardColors.White,
                    style = MaterialTheme.typography.displayLarge
                )
            }
        }
    }
}

// ── Control Button ─────────────────────────────────────────────────────────
@Composable
fun CallControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color = GuardColors.BlueAccent,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .background(
                    if (active) activeColor.copy(alpha = 0.18f) else GuardColors.NavyCard,
                    CircleShape
                )
        ) {
            Icon(icon, label,
                tint = if (active) activeColor else GuardColors.Steel,
                modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp,
            color = if (active) activeColor else GuardColors.Steel)
    }
}

// ── Answer / Reject Buttons ───────────────────────────────────────────────
@Composable
fun IncomingCallButtons(onAnswer: () -> Unit, onReject: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Reject
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FloatingActionButton(
                onClick = onReject,
                containerColor = GuardColors.Red,
                contentColor = Color.White,
                modifier = Modifier.size(72.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Default.CallEnd, "Reject", modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("Decline", color = GuardColors.Steel, fontSize = 13.sp)
        }

        // Answer
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FloatingActionButton(
                onClick = onAnswer,
                containerColor = GuardColors.Green,
                contentColor = Color.White,
                modifier = Modifier.size(72.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Default.Call, "Answer", modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("Answer", color = GuardColors.Steel, fontSize = 13.sp)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────
fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}
