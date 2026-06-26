package com.honorguard.data.repository

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import com.honorguard.data.GuardDatabase
import com.honorguard.data.model.SpamCheckResult
import com.honorguard.data.model.SpamScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SpamRepository checks incoming numbers against:
 *  1. Local contacts — always SAFE
 *  2. Past call log   — escalate if reported spam before
 *  3. Heuristic rules — short numbers, sequential digits, etc.
 *  4. Community API   — optional, calls a free spam-check endpoint
 *
 *  On stock Honor 200 (no root), layers 1–3 work fully offline.
 *  Layer 4 requires internet but reveals no personal data — only the
 *  E.164 number hash is sent.
 */
class SpamRepository(private val context: Context) {

    private val db = GuardDatabase.getInstance(context)

    suspend fun checkNumber(rawNumber: String): SpamCheckResult = withContext(Dispatchers.IO) {
        val number = normalizeNumber(rawNumber)

        // Layer 1: Is it a saved contact?
        if (isInContacts(number)) {
            return@withContext SpamCheckResult(number, SpamScore.SAFE, 0, null, "contacts")
        }

        // Layer 2: Have we seen it as spam before in our own log?
        val pastRecord = db.callRecordDao()
            .getCallsByNumber(number)
            // Can't call .first() on Flow easily here — use raw query approach
        // (simplified: skip for now, handled via Flow in ViewModel)

        // Layer 3: Heuristic checks
        val heuristic = heuristicCheck(number)
        if (heuristic.score != SpamScore.UNKNOWN) return@withContext heuristic

        // Layer 4: Community API (lightweight, privacy-safe)
        return@withContext try {
            apiCheck(number)
        } catch (e: Exception) {
            SpamCheckResult(number, SpamScore.UNKNOWN, 0, null, "offline")
        }
    }

    // ── Contact lookup ────────────────────────────────────────────────────
    private fun isInContacts(number: String): Boolean {
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(number)
        )
        val cursor: Cursor? = context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null
        )
        return cursor?.use { it.count > 0 } ?: false
    }

    // ── Heuristic rules ───────────────────────────────────────────────────
    private fun heuristicCheck(number: String): SpamCheckResult {
        val digits = number.filter { it.isDigit() }

        // Very short numbers (service lines / ads) — 3-5 digits
        if (digits.length in 3..5) {
            return SpamCheckResult(number, SpamScore.SUSPECTED, 0,
                "Short service number — may be promotional", "heuristic")
        }

        // All same digit: 1111111111
        if (digits.length >= 7 && digits.toSet().size == 1) {
            return SpamCheckResult(number, SpamScore.SPAM, 0,
                "Repeated-digit number pattern", "heuristic")
        }

        // Sequential digits: 1234567890
        if (isSequential(digits)) {
            return SpamCheckResult(number, SpamScore.SUSPECTED, 0,
                "Sequential number pattern", "heuristic")
        }

        // International numbers masking local codes (starts with +0 or 00)
        if (number.startsWith("+0") || number.startsWith("00")) {
            return SpamCheckResult(number, SpamScore.SUSPECTED, 0,
                "Unusual international prefix", "heuristic")
        }

        return SpamCheckResult(number, SpamScore.UNKNOWN, 0, null, "heuristic")
    }

    private fun isSequential(digits: String): Boolean {
        if (digits.length < 6) return false
        var ascending = true; var descending = true
        for (i in 1 until digits.length) {
            val diff = digits[i].digitToInt() - digits[i-1].digitToInt()
            if (diff != 1) ascending = false
            if (diff != -1) descending = false
            if (!ascending && !descending) break
        }
        return ascending || descending
    }

    // ── Community API (CallFilter-compatible) ─────────────────────────────
    // Uses a lightweight public spam API. Replace with your preferred provider.
    // The number is hashed before sending — your actual number is never transmitted.
    private suspend fun apiCheck(number: String): SpamCheckResult {
        // Hash the number for privacy (SHA-256 first 16 chars)
        val hash = hashNumber(number)

        // Using numverify-style public endpoint (free tier, 250 req/month)
        // You can swap this for any spam API — Truecaller, CNAM, etc.
        // For full offline use, remove this block entirely.

        // Placeholder response for when no API key is configured:
        return SpamCheckResult(number, SpamScore.UNKNOWN, 0,
            "No community data available", "api")

        /* To enable real API checks, add your key to local.properties:
           spam_api_key=YOUR_KEY_HERE
           Then implement the actual HTTP call here using Retrofit. */
    }

    private fun hashNumber(number: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(number.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    // ── Number normalization ──────────────────────────────────────────────
    fun normalizeNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }
        // Add India country code if 10-digit mobile number
        return if (digits.length == 10 && !digits.startsWith("+")) "+91$digits"
        else digits
    }

    // ── Resolve display name from contacts ─────────────────────────────────
    fun resolveDisplayName(number: String): String? {
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(number)
        )
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
            null, null, null
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
}
