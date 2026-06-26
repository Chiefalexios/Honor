package com.honorguard.ui.screens

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.honorguard.data.model.*
import com.honorguard.ui.GuardViewModel
import com.honorguard.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: GuardViewModel by viewModels()

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request default dialer role
        requestDefaultDialer()

        setContent {
            HonorGuardTheme {
                MainScreen(viewModel)
            }
        }
    }

    private fun requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                defaultDialerLauncher.launch(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                )
            }
        } else {
            val telecom = getSystemService(TelecomManager::class.java)
            if (telecom.defaultDialerPackage != packageName) {
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                }
                defaultDialerLauncher.launch(intent)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
// MAIN SCREEN
// ══════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: GuardViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Recents", "Dialpad", "Contacts")

    Scaffold(
        containerColor = GuardColors.NavyDeep,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Shield icon
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(GuardColors.BlueAccent.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Security, null,
                                tint = GuardColors.BlueAccent, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("HonorGuard", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = GuardColors.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GuardColors.NavyDeep
                ),
                actions = {
                    IconButton(onClick = { /* settings */ }) {
                        Icon(Icons.Default.Settings, "Settings", tint = GuardColors.Steel)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = GuardColors.NavyCard) {
                tabs.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        icon = {
                            Icon(when (i) {
                                0 -> Icons.Default.History
                                1 -> Icons.Default.DialerSip
                                else -> Icons.Default.Contacts
                            }, label)
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor   = GuardColors.BlueAccent,
                            selectedTextColor   = GuardColors.BlueAccent,
                            unselectedIconColor = GuardColors.Steel,
                            unselectedTextColor = GuardColors.Steel,
                            indicatorColor      = GuardColors.BlueAccent.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> RecentsScreen(viewModel)
                1 -> DialpadScreen(viewModel)
                2 -> ContactsScreen()
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
// RECENTS SCREEN
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun RecentsScreen(viewModel: GuardViewModel) {
    val calls by viewModel.recentCalls.collectAsState()

    if (calls.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PhoneDisabled, null,
                    modifier = Modifier.size(64.dp), tint = GuardColors.NavyBorder)
                Spacer(Modifier.height(16.dp))
                Text("No call history yet", color = GuardColors.Steel)
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(calls, key = { it.id }) { record ->
            CallLogItem(record)
        }
    }
}

@Composable
fun CallLogItem(record: CallRecord) {
    val spamColor = GuardColors.forSpam(record.spamScore)
    val timeStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        .format(Date(record.startTimeMs))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar / spam indicator
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(GuardColors.NavyCard, CircleShape)
                .border(1.5.dp, spamColor.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val initial = (record.displayName ?: record.number).firstOrNull()?.uppercaseChar() ?: '?'
            if (record.spamScore == SpamScore.SPAM || record.spamScore == SpamScore.FRAUD) {
                Icon(Icons.Default.Warning, null, tint = spamColor, modifier = Modifier.size(22.dp))
            } else {
                Text(initial.toString(), color = GuardColors.White,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.displayName ?: record.number,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (record.spamScore != SpamScore.SAFE && record.spamScore != SpamScore.UNKNOWN) {
                    Spacer(Modifier.width(6.dp))
                    SpamBadge(record.spamScore)
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (record.type) {
                        CallType.INCOMING -> Icons.Default.CallReceived
                        CallType.OUTGOING -> Icons.Default.CallMade
                        CallType.MISSED   -> Icons.Default.CallMissed
                    },
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = when (record.type) {
                        CallType.MISSED -> GuardColors.Red
                        else -> GuardColors.Steel
                    }
                )
                Spacer(Modifier.width(4.dp))
                Text(timeStr, style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.Steel, fontSize = 12.sp)
                if (record.recordingPath != null) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.FiberManualRecord, "Recorded",
                        modifier = Modifier.size(8.dp), tint = GuardColors.Amber)
                    Spacer(Modifier.width(2.dp))
                    Text("REC", fontSize = 10.sp, color = GuardColors.Amber,
                        fontWeight = FontWeight.Bold)
                }
            }
        }

        // Call back button
        IconButton(onClick = { }) {
            Icon(Icons.Default.Phone, "Call", tint = GuardColors.BlueAccent)
        }
    }

    HorizontalDivider(color = GuardColors.NavyBorder.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 74.dp))
}

@Composable
fun SpamBadge(score: SpamScore) {
    val color = GuardColors.forSpam(score)
    val label = when (score) {
        SpamScore.SUSPECTED -> "SUSPECTED"
        SpamScore.SPAM      -> "SPAM"
        SpamScore.FRAUD     -> "FRAUD"
        else -> ""
    }
    if (label.isEmpty()) return
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = 0.5.sp
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════
// DIALPAD SCREEN
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun DialpadScreen(viewModel: GuardViewModel) {
    val number by viewModel.dialpadNumber.collectAsState()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        // Number display
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp),
            Alignment.Center
        ) {
            if (number.isEmpty()) {
                Text("Enter number", color = GuardColors.NavyBorder,
                    style = MaterialTheme.typography.displayLarge)
            } else {
                Text(
                    formatDialNumber(number),
                    color = GuardColors.White,
                    style = MaterialTheme.typography.displayLarge,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = GuardColors.NavyBorder)
        Spacer(Modifier.height(32.dp))

        // Dialpad grid
        val keys = listOf(
            "1" to "", "2" to "ABC", "3" to "DEF",
            "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
            "*" to "", "0" to "+", "#" to ""
        )

        keys.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { (digit, letters) ->
                    DialKey(digit = digit, letters = letters) {
                        viewModel.appendDigit(digit)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(24.dp))

        // Action row: delete | call | voicemail
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Delete
            IconButton(
                onClick = { viewModel.deleteDigit() },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.BackspaceOutlined, "Delete",
                    tint = GuardColors.Steel, modifier = Modifier.size(24.dp))
            }

            // Call button
            FloatingActionButton(
                onClick = {
                    if (number.isNotEmpty()) {
                        val intent = Intent(Intent.ACTION_CALL,
                            android.net.Uri.parse("tel:$number"))
                        context.startActivity(intent)
                        viewModel.clearDialpad()
                    }
                },
                containerColor = GuardColors.Green,
                contentColor = Color.White,
                modifier = Modifier.size(68.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Default.Phone, "Call",
                    modifier = Modifier.size(30.dp))
            }

            // Spacer (balance)
            Box(Modifier.size(56.dp))
        }
    }
}

@Composable
fun DialKey(digit: String, letters: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable { onClick() }
            .background(GuardColors.NavyCard, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit, color = GuardColors.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Light)
            if (letters.isNotEmpty()) {
                Text(letters, color = GuardColors.Steel, fontSize = 9.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

fun formatDialNumber(raw: String): String {
    if (raw.length <= 5) return raw
    // Indian mobile format: +91 98765 43210
    val digits = raw.filter { it.isDigit() }
    return when {
        digits.length == 10 -> "${digits.substring(0,5)} ${digits.substring(5)}"
        else -> raw
    }
}

// ══════════════════════════════════════════════════════════════════════════
// CONTACTS SCREEN (placeholder — uses system contacts)
// ══════════════════════════════════════════════════════════════════════════
@Composable
fun ContactsScreen() {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Contacts, null,
                modifier = Modifier.size(64.dp), tint = GuardColors.NavyBorder)
            Spacer(Modifier.height(16.dp))
            Text("Contacts", color = GuardColors.Steel,
                style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text("Opens system contacts app", color = GuardColors.NavyBorder,
                fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("content://contacts")))
                },
                colors = ButtonDefaults.buttonColors(containerColor = GuardColors.BlueAccent)
            ) {
                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open Contacts")
            }
        }
    }
}
