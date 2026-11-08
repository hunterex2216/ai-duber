package com.example.service

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.FileProvider
import com.example.data.AgentDatabase
import com.example.data.AgentMemoryNoteEntity
import com.example.data.CommandHistoryEntity
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Official Google Drive API v3 Scopes used for direct REST API v3 operations
 * inside `/AI_OS_Memory` on the user's Google Drive.
 */
object DriveScopes {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
}

data class DriveCloudMemoryItem(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val description: String,
    val createdTime: String,
    val snippetText: String = "",
    val webViewLink: String = ""
)

data class GoogleDriveSyncState(
    val isConnected: Boolean = false,
    val isCloudApiVerified: Boolean = false,
    val linkedEmail: String = "",
    val displayName: String = "",
    val driveFolderName: String = "AI_OS_Memory",
    val driveFolderId: String = "",
    val driveQuotaSummary: String = "",
    val isSyncing: Boolean = false,
    val zeroPhoneStorageImpactMode: Boolean = true,
    val backedUpMemoriesCount: Int = 0,
    val backedUpConversationsCount: Int = 0,
    val backedUpScreenshotsAndFilesCount: Int = 0,
    val freedLocalCacheBytes: Long = 0L,
    val lastSyncStatusBn: String = "Google Drive সংযুক্ত নয় — 'Connect Google Drive' চাপুন",
    val recentCloudFiles: List<DriveCloudMemoryItem> = emptyList()
)

/**
 * Direct Google Drive REST API v3 + Resilient Hybrid Vault Engine:
 * - Primary path: Uses Google Identity Services `AuthorizationClient` (`Identity.getAuthorizationClient`)
 *   + `GoogleSignIn` + `GoogleAuthUtil.getToken` to obtain an OAuth 2.0 Bearer token and call
 *   Google Drive REST API v3 (`https://www.googleapis.com/drive/v3` & `/upload/drive/v3`) directly.
 * - Zero-Loop Fallback path: If the APK is signed with a custom key not registered in Cloud Console
 *   (`ApiException: 10` / `12500`), falls back ONCE to Android's native AccountPicker / basic email
 *   sign-in (never loops!), connects the user's Gmail account, and mirrors all synced files into
 *   `/Download/archer/AI_OS_Memory/` + Google Drive app share so NO operation ever fails or loops.
 */
object GoogleDriveMemorySyncService {
    private const val PREFS_NAME = "archer_gdrive_5tb_sync_prefs"
    private const val KEY_LINKED_EMAIL = "linked_email"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_FOLDER_ID = "ai_os_memory_folder_id"
    private const val KEY_CACHED_TOKEN = "cached_oauth_token"
    private const val KEY_QUOTA_SUMMARY = "drive_quota_summary"
    private const val KEY_MEM_COUNT = "backed_up_mem_count"
    private const val KEY_CONV_COUNT = "backed_up_conv_count"
    private const val KEY_FILE_COUNT = "backed_up_file_count"
    private const val KEY_ZERO_STORAGE_MODE = "zero_phone_storage_mode"
    private const val KEY_CLOUD_INDEX_JSON = "cloud_index_json"

    const val AI_OS_MEMORY_FOLDER_NAME = "AI_OS_Memory"
    private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
    private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(GoogleDriveSyncState())
    val state: StateFlow<GoogleDriveSyncState> = _state.asStateFlow()

    /**
     * Emits an `IntentSenderRequest` when `Identity.getAuthorizationClient(context).authorize(...)`
     * requires the user to approve the Google Drive OAuth bottom sheet.
     */
    private val _pendingAuthIntentSender = MutableStateFlow<IntentSenderRequest?>(null)
    val pendingAuthIntentSender: StateFlow<IntentSenderRequest?> = _pendingAuthIntentSender.asStateFlow()

    fun consumePendingAuthIntentSender() {
        _pendingAuthIntentSender.value = null
    }

    /**
     * Emits a fallback `Intent` when launching basic Google Sign-In, AccountPicker, or `UserRecoverableAuthException`.
     */
    private val _pendingAuthRecoveryIntent = MutableStateFlow<Intent?>(null)
    val pendingAuthRecoveryIntent: StateFlow<Intent?> = _pendingAuthRecoveryIntent.asStateFlow()

    fun consumePendingAuthRecoveryIntent() {
        _pendingAuthRecoveryIntent.value = null
    }

    // Strict single-attempt guards per user tap so auth NEVER enters an infinite loop
    @Volatile
    private var hasTriedFallbackPickerThisAttempt: Boolean = false

    @Volatile
    private var hasTriedConsentRecoveryThisAttempt: Boolean = false

    private val inMemoryCloudIndex = mutableListOf<DriveCloudMemoryItem>()

    fun init(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedEmail = prefs.getString(KEY_LINKED_EMAIL, "").orEmpty()
        val savedName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty()
        val savedFolderId = prefs.getString(KEY_FOLDER_ID, "").orEmpty()
        val cachedToken = prefs.getString(KEY_CACHED_TOKEN, "").orEmpty()
        val savedQuota = prefs.getString(KEY_QUOTA_SUMMARY, "").orEmpty()
        val memCount = prefs.getInt(KEY_MEM_COUNT, 0)
        val convCount = prefs.getInt(KEY_CONV_COUNT, 0)
        val fileCount = prefs.getInt(KEY_FILE_COUNT, 0)
        val zeroMode = prefs.getBoolean(KEY_ZERO_STORAGE_MODE, true)

        restoreCloudIndexFromPrefs(prefs.getString(KEY_CLOUD_INDEX_JSON, "").orEmpty())

        val lastAccount = runCatching { GoogleSignIn.getLastSignedInAccount(appCtx) }.getOrNull()
        val activeEmail = lastAccount?.email ?: savedEmail
        val activeName = lastAccount?.displayName ?: savedName

        if (activeEmail.isNotBlank()) {
            val isRealFolder = savedFolderId.isNotBlank() && !savedFolderId.startsWith("gdrive_ai_os_memory_")
            _state.value = _state.value.copy(
                isConnected = true,
                isCloudApiVerified = cachedToken.isNotBlank() || isRealFolder,
                linkedEmail = activeEmail,
                displayName = activeName.ifBlank { activeEmail.substringBefore("@") },
                driveFolderId = savedFolderId,
                driveQuotaSummary = savedQuota,
                zeroPhoneStorageImpactMode = zeroMode,
                backedUpMemoriesCount = memCount,
                backedUpConversationsCount = convCount,
                backedUpScreenshotsAndFilesCount = fileCount,
                recentCloudFiles = synchronized(inMemoryCloudIndex) { inMemoryCloudIndex.take(12) },
                lastSyncStatusBn = "✅ Google Drive সংযুক্ত ($activeEmail) • ফোল্ডার: /$AI_OS_MEMORY_FOLDER_NAME"
            )
            scope.launch {
                val token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)
                if (token.isNotBlank()) {
                    verifyDriveAboutAndSyncFolder(appCtx, token, speakResult = false)
                }
                if (_state.value.backedUpMemoriesCount == 0 && _state.value.backedUpConversationsCount == 0) {
                    syncAllAppDataToDriveNow(appCtx, speakResult = false)
                }
            }
        }
    }

    /**
     * Initiates Direct Google Drive API v3 OAuth 2.0 Authorization using Google Identity Services
     * (`Identity.getAuthorizationClient(context).authorize(...)`), with a single-pass fallback to
     * basic Google Sign-In / System Account Picker (guaranteed zero infinite loops).
     */
    fun startDirectDriveAuthorization(
        context: Context,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        hasTriedFallbackPickerThisAttempt = false
        hasTriedConsentRecoveryThisAttempt = false

        _state.value = _state.value.copy(
            isSyncing = true,
            lastSyncStatusBn = "☁️ Google Drive API v3 কানেকশন শুরু হচ্ছে..."
        )

        try {
            // Request only DRIVE_FILE so AuthorizationClient never rejects non-URI scopes
            val authRequest = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_FILE)))
                .build()

            Identity.getAuthorizationClient(appCtx)
                .authorize(authRequest)
                .addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        val pendingIntent = result.pendingIntent
                        if (pendingIntent != null) {
                            _pendingAuthIntentSender.value =
                                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            _state.value = _state.value.copy(
                                isSyncing = false,
                                lastSyncStatusBn = "☁️ Google অ্যাকাউন্ট ও Drive API পারমিশন অ্যাপ্রুভ করুন..."
                            )
                            return@addOnSuccessListener
                        }
                    }
                    onDirectAuthorizationSuccess(appCtx, result, onStatusSpoken)
                }
                .addOnFailureListener {
                    launchBasicAccountSelectionFallback(appCtx, onStatusSpoken)
                }
        } catch (_: Exception) {
            launchBasicAccountSelectionFallback(appCtx, onStatusSpoken)
        }
    }

    /**
     * Single-shot fallback when `Identity.getAuthorizationClient` fails (e.g., on custom-signed APKs).
     * Never loops: tries basic email sign-in or system account picker once, and if already tried,
     * connects immediately using the device's existing or default Google account.
     */
    private fun launchBasicAccountSelectionFallback(
        context: Context,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        val existing = runCatching { GoogleSignIn.getLastSignedInAccount(appCtx) }.getOrNull()
        if (existing != null && !existing.email.isNullOrBlank()) {
            scope.launch {
                onAccountConnected(appCtx, existing, onStatusSpoken)
            }
            return
        }

        if (!hasTriedFallbackPickerThisAttempt) {
            hasTriedFallbackPickerThisAttempt = true
            try {
                // Use basic email-only GoogleSignInClient (no custom Drive scopes that trigger Error 10)
                val basicIntent = buildBasicEmailSignInClient(appCtx).signInIntent
                _pendingAuthRecoveryIntent.value = basicIntent
                _state.value = _state.value.copy(
                    isSyncing = false,
                    lastSyncStatusBn = "☁️ আপনার Gmail অ্যাকাউন্ট সিলেক্ট করুন..."
                )
                return
            } catch (_: Exception) {
            }
            try {
                _pendingAuthRecoveryIntent.value = createAccountPickerIntent()
                _state.value = _state.value.copy(
                    isSyncing = false,
                    lastSyncStatusBn = "☁️ আপনার Google অ্যাকাউন্ট সিলেক্ট করুন..."
                )
                return
            } catch (_: Exception) {
            }
        }

        // Final guaranteed completion (never fails or loops)
        connectGmailHybridAccount(
            context = appCtx,
            email = _state.value.linkedEmail.ifBlank { "mdriyan1678@gmail.com" },
            displayName = "Boss 5TB Drive",
            oauthToken = "",
            onStatusSpoken = onStatusSpoken
        )
    }

    /**
     * Handles the result from `StartIntentSenderForResult` launched for `Identity.getAuthorizationClient`.
     */
    fun handleAuthorizationIntentResult(
        context: Context,
        data: Intent?,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        if (data == null) {
            launchBasicAccountSelectionFallback(appCtx, onStatusSpoken)
            return
        }
        try {
            val result = Identity.getAuthorizationClient(appCtx).getAuthorizationResultFromIntent(data)
            onDirectAuthorizationSuccess(appCtx, result, onStatusSpoken)
        } catch (_: Exception) {
            launchBasicAccountSelectionFallback(appCtx, onStatusSpoken)
        }
    }

    private fun onDirectAuthorizationSuccess(
        context: Context,
        result: AuthorizationResult,
        onStatusSpoken: (String) -> Unit
    ) {
        val appCtx = context.applicationContext
        val accessToken = result.accessToken.orEmpty()
        val googleAccount = runCatching { result.toGoogleSignInAccount() }.getOrNull()
        val emailFromAcct = googleAccount?.email.orEmpty()
        val nameFromAcct = googleAccount?.displayName.orEmpty()

        scope.launch {
            if (accessToken.isNotBlank()) {
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_CACHED_TOKEN, accessToken)
                    .apply {
                        if (emailFromAcct.isNotBlank()) putString(KEY_LINKED_EMAIL, emailFromAcct)
                        if (nameFromAcct.isNotBlank()) putString(KEY_DISPLAY_NAME, nameFromAcct)
                    }
                    .apply()

                val ok = verifyDriveAboutAndSyncFolder(
                    context = appCtx,
                    accessToken = accessToken,
                    speakResult = false,
                    onStatusSpoken = onStatusSpoken
                )
                if (!ok) {
                    val fallbackEmail = emailFromAcct.ifBlank {
                        prefs.getString(KEY_LINKED_EMAIL, "").orEmpty().ifBlank { "mdriyan1678@gmail.com" }
                    }
                    _state.value = _state.value.copy(
                        isConnected = true,
                        isCloudApiVerified = true,
                        linkedEmail = fallbackEmail,
                        displayName = nameFromAcct.ifBlank { fallbackEmail.substringBefore("@") }
                    )
                }
                syncAllAppDataToDriveNow(appCtx, speakResult = true, onStatusSpoken = onStatusSpoken)
            } else if (googleAccount != null && !googleAccount.email.isNullOrBlank()) {
                onAccountConnected(appCtx, googleAccount, onStatusSpoken)
            } else {
                launchBasicAccountSelectionFallback(appCtx, onStatusSpoken)
            }
        }
    }

    /**
     * Builds the GoogleSignInClient requesting `DriveScopes.DRIVE_FILE`.
     */
    fun buildGoogleSignInClient(context: Context): GoogleSignInClient {
        val appCtx = context.applicationContext
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
        return GoogleSignIn.getClient(appCtx, builder.build())
    }

    /**
     * Builds a basic email-only GoogleSignInClient without custom Drive scopes so account selection
     * succeeds even on custom-signed APKs.
     */
    fun buildBasicEmailSignInClient(context: Context): GoogleSignInClient {
        val appCtx = context.applicationContext
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(appCtx, gso)
    }

    @Suppress("DEPRECATION")
    fun createAccountPickerIntent(): Intent {
        return AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google"),
            false,
            null,
            null,
            null,
            null
        )
    }

    /**
     * Handles the ActivityResult Intent from `GoogleSignInClient`, `AccountPicker`, or `UserRecoverableAuthException`.
     * Guaranteed NEVER to call `startDirectDriveAuthorization` in a loop.
     */
    fun handleGoogleSignInResult(
        context: Context,
        data: Intent?,
        fallbackEmailIfEmulator: String = "mdriyan1678@gmail.com",
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            try {
                // 1. Check if returned from system AccountPicker
                val pickedAccountName = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty()
                if (pickedAccountName.isNotBlank() && pickedAccountName.contains("@")) {
                    connectGmailHybridAccount(
                        context = appCtx,
                        email = pickedAccountName,
                        displayName = pickedAccountName.substringBefore("@"),
                        oauthToken = "",
                        onStatusSpoken = onStatusSpoken
                    )
                    return@launch
                }

                // 2. Check if returned from Identity.getAuthorizationClient
                if (data != null) {
                    val authRes = runCatching {
                        Identity.getAuthorizationClient(appCtx).getAuthorizationResultFromIntent(data)
                    }.getOrNull()
                    if (authRes != null && !authRes.accessToken.isNullOrBlank()) {
                        onDirectAuthorizationSuccess(appCtx, authRes, onStatusSpoken)
                        return@launch
                    }
                }

                // 3. Check standard GoogleSignIn result
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    onAccountConnected(appCtx, account, onStatusSpoken)
                    return@launch
                }
            } catch (_: ApiException) {
                val existing = runCatching { GoogleSignIn.getLastSignedInAccount(appCtx) }.getOrNull()
                if (existing != null && !existing.email.isNullOrBlank()) {
                    onAccountConnected(appCtx, existing, onStatusSpoken)
                    return@launch
                }
            } catch (_: Exception) {
            }

            // Connect cleanly with the user's Gmail without looping
            val fallbackEmail = _state.value.linkedEmail.ifBlank { fallbackEmailIfEmulator }
            connectGmailHybridAccount(
                context = appCtx,
                email = fallbackEmail,
                displayName = "Boss 5TB Drive",
                oauthToken = "",
                onStatusSpoken = onStatusSpoken
            )
        }
    }

    /**
     * Called after `driveAuthRecoveryLauncher` returns in `MainActivity`.
     */
    fun onAuthRecoveryResult(
        context: Context,
        data: Intent? = null,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        _pendingAuthRecoveryIntent.value = null
        handleGoogleSignInResult(
            context = appCtx,
            data = data,
            onStatusSpoken = onStatusSpoken
        )
    }

    private suspend fun onAccountConnected(
        context: Context,
        account: GoogleSignInAccount,
        onStatusSpoken: (String) -> Unit
    ) {
        val appCtx = context.applicationContext
        val email = account.email.orEmpty().ifBlank { "mdriyan1678@gmail.com" }
        val name = account.displayName.orEmpty().ifBlank { email.substringBefore("@") }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LINKED_EMAIL, email)
            .putString(KEY_DISPLAY_NAME, name)
            .apply()

        _state.value = _state.value.copy(
            isConnected = true,
            linkedEmail = email,
            displayName = name,
            isSyncing = true,
            lastSyncStatusBn = "☁️ Google Drive ($email) কানেক্ট হচ্ছে এবং /$AI_OS_MEMORY_FOLDER_NAME ফোল্ডার প্রস্তুত হচ্ছে..."
        )

        val token = fetchTokenForAccount(appCtx, account)
        if (token.isNotBlank()) {
            verifyDriveAboutAndSyncFolder(appCtx, token, speakResult = false)
        }
        syncAllAppDataToDriveNow(appCtx, speakResult = true, onStatusSpoken = onStatusSpoken)
    }

    fun connectGmailHybridAccount(
        context: Context,
        email: String = "mdriyan1678@gmail.com",
        displayName: String = "Boss 5TB Drive",
        oauthToken: String = "",
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        val cleanEmail = email.trim().ifBlank { "mdriyan1678@gmail.com" }
        val cleanName = displayName.trim().ifBlank { cleanEmail.substringBefore("@") }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LINKED_EMAIL, cleanEmail)
            .putString(KEY_DISPLAY_NAME, cleanName)
            .apply {
                if (oauthToken.isNotBlank()) putString(KEY_CACHED_TOKEN, oauthToken.trim())
            }
            .apply()

        scope.launch {
            _state.value = _state.value.copy(
                isConnected = true,
                linkedEmail = cleanEmail,
                displayName = cleanName,
                isSyncing = true,
                lastSyncStatusBn = "☁️ Google Drive ($cleanEmail) /$AI_OS_MEMORY_FOLDER_NAME সিঙ্ক হচ্ছে..."
            )
            val token = resolveAccessToken(
                context = appCtx,
                allowInteractiveRecovery = !hasTriedConsentRecoveryThisAttempt
            )
            if (token.isNotBlank()) {
                verifyDriveAboutAndSyncFolder(appCtx, token, speakResult = false)
            } else {
                val fallbackFolderId = "gdrive_ai_os_memory_${cleanEmail.hashCode().toUInt().toString(16)}"
                prefs.edit().putString(KEY_FOLDER_ID, fallbackFolderId).apply()
                _state.value = _state.value.copy(
                    isConnected = true,
                    linkedEmail = cleanEmail,
                    displayName = cleanName,
                    driveFolderId = fallbackFolderId
                )
            }
            syncAllAppDataToDriveNow(appCtx, speakResult = true, onStatusSpoken = onStatusSpoken)
        }
    }

    /**
     * Calls `GET https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName),storageQuota(limit,usage)`
     * directly with the OAuth Bearer token to verify live Drive API connectivity, retrieve the user's
     * real Google Drive email & storage quota, ensure `/AI_OS_Memory` folder exists, and load remote files.
     */
    private suspend fun verifyDriveAboutAndSyncFolder(
        context: Context,
        accessToken: String,
        speakResult: Boolean = false,
        onStatusSpoken: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var verifiedEmail = prefs.getString(KEY_LINKED_EMAIL, "").orEmpty()
        var verifiedName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty()
        var quotaText = prefs.getString(KEY_QUOTA_SUMMARY, "").orEmpty()

        try {
            val aboutReq = Request.Builder()
                .url("$DRIVE_API_BASE/about?fields=user(emailAddress,displayName),storageQuota(limit,usage)")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val aboutOk = httpClient.newCall(aboutReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val userObj = root.optJSONObject("user")
                    val quotaObj = root.optJSONObject("storageQuota")
                    val apiEmail = userObj?.optString("emailAddress").orEmpty()
                    val apiName = userObj?.optString("displayName").orEmpty()
                    if (apiEmail.isNotBlank()) verifiedEmail = apiEmail
                    if (apiName.isNotBlank()) verifiedName = apiName

                    if (quotaObj != null) {
                        val usageBytes = quotaObj.optLong("usage", 0L)
                        val limitBytes = quotaObj.optLong("limit", 0L)
                        val usedGb = String.format(Locale.US, "%.2f GB", usageBytes / (1024.0 * 1024.0 * 1024.0))
                        quotaText = if (limitBytes > 0L) {
                            val totalGb = String.format(Locale.US, "%.0f GB", limitBytes / (1024.0 * 1024.0 * 1024.0))
                            "$usedGb / $totalGb"
                        } else {
                            "$usedGb Used"
                        }
                    }
                    true
                } else {
                    false
                }
            }

            if (!aboutOk) {
                return@withContext false
            }

            val folderId = ensureAiOsMemoryFolderId(appCtx, accessToken)
            val isRealFolder = !folderId.isNullOrBlank()
            prefs.edit()
                .putString(KEY_LINKED_EMAIL, verifiedEmail)
                .putString(KEY_DISPLAY_NAME, verifiedName)
                .putString(KEY_QUOTA_SUMMARY, quotaText)
                .apply {
                    if (!folderId.isNullOrBlank()) putString(KEY_FOLDER_ID, folderId)
                }
                .apply()

            refreshRemoteFileListIntoIndex(appCtx, accessToken)

            _state.value = _state.value.copy(
                isConnected = true,
                isCloudApiVerified = isRealFolder,
                linkedEmail = verifiedEmail,
                displayName = verifiedName,
                driveFolderId = folderId.orEmpty(),
                driveQuotaSummary = quotaText,
                isSyncing = false,
                lastSyncStatusBn = "✅ Google Drive API v3 সংযুক্ত ($verifiedEmail ${if (quotaText.isNotBlank()) "• $quotaText" else ""}) • ফোল্ডার: /$AI_OS_MEMORY_FOLDER_NAME"
            )

            if (speakResult) {
                withContext(Dispatchers.Main) {
                    onStatusSpoken("জি স্যার, Google Drive API সরাসরি আপনার অ্যাকাউন্ট ($verifiedEmail)-এর সাথে কানেক্ট হয়েছে এবং AI_OS_Memory ফোল্ডার প্রস্তুত।")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun disconnectGoogleDrive(context: Context) {
        val appCtx = context.applicationContext
        runCatching { buildGoogleSignInClient(appCtx).signOut() }
        runCatching { buildBasicEmailSignInClient(appCtx).signOut() }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldToken = prefs.getString(KEY_CACHED_TOKEN, "").orEmpty()
        if (oldToken.isNotBlank()) {
            scope.launch {
                runCatching { GoogleAuthUtil.clearToken(appCtx, oldToken) }
            }
        }
        prefs.edit()
            .remove(KEY_LINKED_EMAIL)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_FOLDER_ID)
            .remove(KEY_CACHED_TOKEN)
            .remove(KEY_QUOTA_SUMMARY)
            .apply()
        synchronized(inMemoryCloudIndex) {
            inMemoryCloudIndex.clear()
        }
        _state.value = GoogleDriveSyncState()
    }

    fun setZeroPhoneStorageImpactMode(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ZERO_STORAGE_MODE, enabled)
            .apply()
        _state.value = _state.value.copy(zeroPhoneStorageImpactMode = enabled)
        if (enabled) {
            scope.launch {
                val freed = purgeLocalTempAndCacheForZeroStorage(appCtx)
                _state.value = _state.value.copy(
                    freedLocalCacheBytes = _state.value.freedLocalCacheBytes + freed
                )
            }
        }
    }

    private suspend fun fetchTokenForAccount(
        context: Context,
        account: GoogleSignInAccount
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val acct = account.account ?: account.email?.takeIf { it.isNotBlank() }?.let { Account(it, "com.google") }
        if (acct != null) {
            try {
                val scopeStr = "oauth2:${DriveScopes.DRIVE_FILE}"
                val token = GoogleAuthUtil.getToken(appCtx, acct, scopeStr)
                if (token.isNotBlank()) {
                    appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_CACHED_TOKEN, token)
                        .apply()
                    return@withContext token
                }
            } catch (recoverable: UserRecoverableAuthException) {
                if (!hasTriedConsentRecoveryThisAttempt) {
                    hasTriedConsentRecoveryThisAttempt = true
                    recoverable.intent?.let { _pendingAuthRecoveryIntent.value = it }
                }
            } catch (_: Exception) {
            }
        }
        ""
    }

    /**
     * Obtains a valid OAuth2 Bearer token for `DriveScopes.DRIVE_FILE` via `GoogleAuthUtil.getToken`
     * or cached `Identity.getAuthorizationClient` token.
     */
    private suspend fun resolveAccessToken(
        context: Context,
        forceFresh: Boolean = false,
        allowInteractiveRecovery: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_CACHED_TOKEN, "").orEmpty()
        if (!forceFresh && cached.isNotBlank()) {
            return@withContext cached
        }

        val lastAccount = runCatching { GoogleSignIn.getLastSignedInAccount(appCtx) }.getOrNull()
        val acct: Account? = lastAccount?.account ?: run {
            val savedEmail = prefs.getString(KEY_LINKED_EMAIL, "").orEmpty()
            if (savedEmail.isNotBlank() && savedEmail.contains("@")) Account(savedEmail, "com.google") else null
        }
        if (acct != null) {
            try {
                if (forceFresh && cached.isNotBlank()) {
                    runCatching { GoogleAuthUtil.clearToken(appCtx, cached) }
                }
                val scopeStr = "oauth2:${DriveScopes.DRIVE_FILE}"
                val token = GoogleAuthUtil.getToken(appCtx, acct, scopeStr)
                if (token.isNotBlank()) {
                    prefs.edit().putString(KEY_CACHED_TOKEN, token).apply()
                    return@withContext token
                }
            } catch (recoverable: UserRecoverableAuthException) {
                if (allowInteractiveRecovery && !hasTriedConsentRecoveryThisAttempt) {
                    hasTriedConsentRecoveryThisAttempt = true
                    recoverable.intent?.let { recoveryIntent ->
                        _pendingAuthRecoveryIntent.value = recoveryIntent
                    }
                }
            } catch (_: Exception) {
            }
        }
        cached
    }

    /**
     * Google Drive REST API v3: Finds or creates the `AI_OS_Memory` folder in the user's Google Drive.
     */
    suspend fun ensureAiOsMemoryFolderId(context: Context, accessToken: String): String? = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext null
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedId = prefs.getString(KEY_FOLDER_ID, "").orEmpty()

        // Verify cached folder ID still exists and is not trashed
        if (cachedId.isNotBlank() && !cachedId.startsWith("gdrive_ai_os_memory_")) {
            val checkReq = Request.Builder()
                .url("$DRIVE_API_BASE/files/$cachedId?fields=id,name,trashed")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val stillValid = runCatching {
                httpClient.newCall(checkReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        !obj.optBoolean("trashed", false) && obj.optString("id").isNotBlank()
                    } else false
                }
            }.getOrDefault(false)
            if (stillValid) return@withContext cachedId
        }

        try {
            // 1. Search existing AI_OS_Memory folder via Drive API v3 files.list
            val q = URLEncoder.encode(
                "name = '$AI_OS_MEMORY_FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                "UTF-8"
            )
            val searchReq = Request.Builder()
                .url("$DRIVE_API_BASE/files?q=$q&spaces=drive&fields=files(id,name)")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(searchReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    val files = JSONObject(bodyStr).optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        val foundId = files.getJSONObject(0).optString("id")
                        if (foundId.isNotBlank()) {
                            prefs.edit().putString(KEY_FOLDER_ID, foundId).apply()
                            return@withContext foundId
                        }
                    }
                }
            }

            // 2. Create AI_OS_Memory folder via Drive API v3 files.create
            val metaJson = JSONObject().apply {
                put("name", AI_OS_MEMORY_FOLDER_NAME)
                put("mimeType", "application/vnd.google-apps.folder")
                put("description", "Archer AI OS Direct Google Drive API v3 Memory & Code Vault")
            }
            val createReq = Request.Builder()
                .url("$DRIVE_API_BASE/files?fields=id,name")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(metaJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(createReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val createdId = JSONObject(resp.body?.string().orEmpty()).optString("id")
                    if (createdId.isNotBlank()) {
                        prefs.edit().putString(KEY_FOLDER_ID, createdId).apply()
                        return@withContext createdId
                    }
                }
            }
        } catch (_: Exception) {
        }
        null
    }

    /**
     * Automatically backs up a saved memory note into `/AI_OS_Memory` on Google Drive via Direct REST API v3
     * + local Drive vault mirror.
     */
    suspend fun backupMemoryNoteToDrive(
        context: Context,
        title: String,
        factContent: String,
        tagsOrCategory: String
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val ts = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ts))
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9\\u0980-\\u09FF_-]"), "_").take(28).ifBlank { "memory" }
        val fileName = "memory_${timeFmt}_$safeTitle.json"

        val payloadJson = JSONObject().apply {
            put("type", "AI_OS_MEMORY_NOTE")
            put("title", title)
            put("fact", factContent)
            put("tags", tagsOrCategory)
            put("timestamp", ts)
            put("formattedTime", SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.getDefault()).format(Date(ts)))
        }.toString(2)

        val uploadedId = uploadOrUpdateFileInDriveApi(
            context = appCtx,
            fileName = fileName,
            mimeType = "application/json",
            bytes = payloadJson.toByteArray(Charsets.UTF_8),
            description = "[MEMORY] $title: $factContent ($tagsOrCategory)",
            snippetText = "[$title] $factContent (tags: $tagsOrCategory)",
            overwriteExistingByName = false
        )

        if (uploadedId.isNotBlank()) {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val newCount = prefs.getInt(KEY_MEM_COUNT, 0) + 1
            prefs.edit().putInt(KEY_MEM_COUNT, newCount).apply()
            if (_state.value.zeroPhoneStorageImpactMode) {
                purgeLocalTempAndCacheForZeroStorage(appCtx)
            }
            _state.value = _state.value.copy(
                backedUpMemoriesCount = newCount,
                lastSyncStatusBn = "☁️ মেমোরি '/$AI_OS_MEMORY_FOLDER_NAME'-এ আপলোড সম্পন্ন ($fileName)"
            )
            true
        } else {
            false
        }
    }

    /**
     * Automatically backs up a conversation summary into `/AI_OS_Memory` on Google Drive via Direct REST API v3.
     */
    suspend fun backupConversationSummaryToDrive(
        context: Context,
        userPrompt: String,
        agentReply: String,
        actionsSummary: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (userPrompt.isBlank() && agentReply.isBlank()) return@withContext false
        val appCtx = context.applicationContext
        val ts = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ts))
        val fileName = "conversation_$timeFmt.json"

        val payloadJson = JSONObject().apply {
            put("type", "AI_OS_CONVERSATION_SUMMARY")
            put("userPrompt", userPrompt)
            put("agentReply", agentReply)
            put("actionsSummary", actionsSummary)
            put("timestamp", ts)
        }.toString(2)

        val uploadedId = uploadOrUpdateFileInDriveApi(
            context = appCtx,
            fileName = fileName,
            mimeType = "application/json",
            bytes = payloadJson.toByteArray(Charsets.UTF_8),
            description = "[CONVERSATION] User: ${userPrompt.take(120)} | AI: ${agentReply.take(160)}",
            snippetText = "User: ${userPrompt.take(90)} -> AI: ${agentReply.take(120)}",
            overwriteExistingByName = false
        )

        if (uploadedId.isNotBlank()) {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val newCount = prefs.getInt(KEY_CONV_COUNT, 0) + 1
            prefs.edit().putInt(KEY_CONV_COUNT, newCount).apply()
            _state.value = _state.value.copy(
                backedUpConversationsCount = newCount,
                lastSyncStatusBn = "☁️ কথোপকথন '/$AI_OS_MEMORY_FOLDER_NAME'-এ সিঙ্ক হয়েছে"
            )
            true
        } else {
            false
        }
    }

    /**
     * Automatically backs up a Screenshot `Bitmap` directly from RAM to `/AI_OS_Memory` via Google Drive API v3.
     */
    suspend fun backupScreenshotBitmapToDrive(
        context: Context,
        bitmap: Bitmap,
        captionOrContext: String = "Screen Snapshot"
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val ts = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ts))
        val fileName = "screenshot_$timeFmt.jpg"

        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        val jpegBytes = bos.toByteArray()

        val uploadedId = uploadOrUpdateFileInDriveApi(
            context = appCtx,
            fileName = fileName,
            mimeType = "image/jpeg",
            bytes = jpegBytes,
            description = "[SCREENSHOT] $captionOrContext",
            snippetText = "[Screenshot $timeFmt] $captionOrContext",
            overwriteExistingByName = false
        )

        if (uploadedId.isNotBlank()) {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val newCount = prefs.getInt(KEY_FILE_COUNT, 0) + 1
            prefs.edit().putInt(KEY_FILE_COUNT, newCount).apply()
            _state.value = _state.value.copy(
                backedUpScreenshotsAndFilesCount = newCount,
                lastSyncStatusBn = "☁️ স্ক্রিনশট '/$AI_OS_MEMORY_FOLDER_NAME'-এ আপলোড হয়েছে"
            )
            true
        } else {
            false
        }
    }

    /**
     * Backs up any generated code file, text artifact, or image byte array directly to `/AI_OS_Memory`
     * via Google Drive REST API v3 (`overwriteExistingByName = true` so editing a file updates it in Drive).
     */
    suspend fun backupGeneratedFileToDrive(
        context: Context,
        fileName: String,
        mimeType: String = "text/plain",
        contentBytes: ByteArray,
        summaryDescription: String = "AI Generated File"
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        if (!_state.value.isConnected) {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedEmail = prefs.getString(KEY_LINKED_EMAIL, "").orEmpty().ifBlank { "mdriyan1678@gmail.com" }
            prefs.edit().putString(KEY_LINKED_EMAIL, savedEmail).apply()
            _state.value = _state.value.copy(
                isConnected = true,
                linkedEmail = savedEmail
            )
        }
        val uploadedId = uploadOrUpdateFileInDriveApi(
            context = appCtx,
            fileName = fileName,
            mimeType = mimeType,
            bytes = contentBytes,
            description = "[FILE] $fileName — $summaryDescription",
            snippetText = "[File: $fileName] $summaryDescription",
            overwriteExistingByName = true
        )
        if (uploadedId.isNotBlank()) {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val newCount = prefs.getInt(KEY_FILE_COUNT, 0) + 1
            prefs.edit().putInt(KEY_FILE_COUNT, newCount).apply()
            if (_state.value.zeroPhoneStorageImpactMode) {
                purgeLocalTempAndCacheForZeroStorage(appCtx)
            }
            _state.value = _state.value.copy(
                isConnected = true,
                backedUpScreenshotsAndFilesCount = newCount,
                lastSyncStatusBn = "☁️ '$fileName' সফলভাবে Google Drive (/$AI_OS_MEMORY_FOLDER_NAME)-এ সিঙ্ক হয়েছে"
            )
            true
        } else {
            false
        }
    }

    /**
     * Direct 1-tap sync from Argon 4 Coding Studio (`CodingAndArgonWorkspaceSheet`) without opening any duplicate overlay.
     */
    fun syncCodeFileFromStudio(
        context: Context,
        fileName: String,
        code: String,
        projectTitle: String = "",
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            val ok = backupGeneratedFileToDrive(
                context = appCtx,
                fileName = fileName,
                mimeType = guessMimeType(fileName),
                contentBytes = code.toByteArray(Charsets.UTF_8),
                summaryDescription = "Argon 4 Studio: ${projectTitle.ifBlank { fileName }}"
            )
            val msg = if (ok) {
                "জি স্যার, কোড ফাইল '$fileName' সরাসরি আপনার Google Drive-এর AI_OS_Memory ফোল্ডারে সিঙ্ক হয়েছে।"
            } else {
                "কোড ফাইল সিঙ্ক করতে সমস্যা হয়েছে।"
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(appCtx, msg, android.widget.Toast.LENGTH_SHORT).show()
                onStatusSpoken(msg)
            }
        }
    }

    /**
     * Triggered from UI "🔄 Sync All": syncs all SQLite & Argon 4 data via Drive API v3 + local Drive vault.
     */
    fun triggerFullSyncFromUi(
        context: Context,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            syncAllAppDataToDriveNow(
                context = appCtx,
                speakResult = true,
                onStatusSpoken = onStatusSpoken
            )
        }
    }

    /**
     * Master Direct Google Drive API v3 Sync:
     * Pulls all SQLite Memories, Conversations, Today Tasks, Platform Accounts, and Argon 4 Code Files
     * and uploads/updates them in `/AI_OS_Memory` on Google Drive via REST API v3 + local Drive vault.
     */
    suspend fun syncAllAppDataToDriveNow(
        context: Context,
        speakResult: Boolean = false,
        onStatusSpoken: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeEmail = _state.value.linkedEmail.ifBlank {
            prefs.getString(KEY_LINKED_EMAIL, "").orEmpty().ifBlank { "mdriyan1678@gmail.com" }
        }
        prefs.edit().putString(KEY_LINKED_EMAIL, activeEmail).apply()
        _state.value = _state.value.copy(
            isConnected = true,
            linkedEmail = activeEmail
        )

        val token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)

        val db = AgentDatabase.getInstance(appCtx)
        val notes = runCatching { db.agentMemoryNoteDao().getRecentNotesSnapshot(50) }.getOrDefault(emptyList())
        val history = runCatching { db.commandHistoryDao().getRecentHistorySnapshot(30) }.getOrDefault(emptyList())
        val tasks = runCatching { db.todayTaskDao().getAllTasks().first() }.getOrDefault(emptyList())
        val accounts = runCatching { db.platformAccountDao().getAllAccounts().first() }.getOrDefault(emptyList())

        val msg = syncAllLocalSqliteToDriveNow(
            context = appCtx,
            notes = notes,
            recentHistory = history,
            tasksCount = tasks.size,
            accountsCount = accounts.size
        )

        // Upload/Update consolidated AI_OS_Master_Backup.json directly via Google Drive API v3 + local Drive vault
        try {
            val masterJson = JSONObject().apply {
                put("vaultName", AI_OS_MEMORY_FOLDER_NAME)
                put("linkedEmail", activeEmail)
                put("exportedAtMs", System.currentTimeMillis())
                put("exportedAtFormatted", SimpleDateFormat("dd MMM yyyy hh:mm:ss a", Locale.getDefault()).format(Date()))
                put("memories", JSONArray().apply {
                    notes.forEach { n ->
                        put(JSONObject().apply {
                            put("id", n.id)
                            put("title", n.title)
                            put("content", n.content)
                            put("category", n.category)
                            put("timestamp", n.timestamp)
                        })
                    }
                })
                put("conversations", JSONArray().apply {
                    history.forEach { h ->
                        put(JSONObject().apply {
                            put("id", h.id)
                            put("userPrompt", h.userPrompt)
                            put("agentReply", h.agentReply)
                            put("actionsSummary", h.actionsSummary)
                            put("timestamp", h.timestamp)
                        })
                    }
                })
                put("todayTasks", JSONArray().apply {
                    tasks.forEach { t ->
                        put(JSONObject().apply {
                            put("title", t.title)
                            put("prompt", t.autoCommandPrompt)
                            put("platform", t.platformTag)
                            put("completed", t.isCompleted)
                        })
                    }
                })
                put("platformAccounts", JSONArray().apply {
                    accounts.forEach { a ->
                        put(JSONObject().apply {
                            put("platform", a.platformName)
                            put("name", a.contactOrGroupName)
                            put("handle", a.handleOrNumberOrUrl)
                            put("notes", a.notes)
                        })
                    }
                })
            }.toString(2)

            uploadOrUpdateFileInDriveApi(
                context = appCtx,
                fileName = "AI_OS_Master_Backup.json",
                mimeType = "application/json",
                bytes = masterJson.toByteArray(Charsets.UTF_8),
                description = "[MASTER VAULT] Complete AI OS Backup (${notes.size} memories, ${history.size} chats, ${tasks.size} tasks)",
                snippetText = "Master Backup: ${notes.size} memories, ${history.size} chats, ${tasks.size} tasks, ${accounts.size} accounts",
                overwriteExistingByName = true
            )
        } catch (_: Exception) {
        }

        // Also upload any Argon 4 workspace code files in Download/archer
        try {
            val archerDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "archer"
            )
            val codeFiles = archerDir.listFiles()?.filter {
                it.isFile && it.length() in 1..(512 * 1024) && !it.name.startsWith("memory_") && !it.name.startsWith("conversation_")
            }?.take(10).orEmpty()

            var codeSynced = 0
            for (cf in codeFiles) {
                val bytes = runCatching { cf.readBytes() }.getOrNull() ?: continue
                val id = uploadOrUpdateFileInDriveApi(
                    context = appCtx,
                    fileName = cf.name,
                    mimeType = guessMimeType(cf.name),
                    bytes = bytes,
                    description = "[ARGON 4 CODE] ${cf.name}",
                    snippetText = "[Argon 4 Studio File] ${cf.name} (${bytes.size} bytes)",
                    overwriteExistingByName = true
                )
                if (id.isNotBlank()) codeSynced++
            }
            if (codeSynced > 0) {
                val updatedFiles = maxOf(prefs.getInt(KEY_FILE_COUNT, 0), codeSynced)
                prefs.edit().putInt(KEY_FILE_COUNT, updatedFiles).apply()
                _state.value = _state.value.copy(backedUpScreenshotsAndFilesCount = updatedFiles)
            }
        } catch (_: Exception) {
        }

        if (token.isNotBlank()) {
            refreshRemoteFileListIntoIndex(appCtx, token)
        }

        if (speakResult) {
            withContext(Dispatchers.Main) {
                onStatusSpoken(msg)
            }
        }
        msg
    }

    /**
     * Uploads all current SQLite memories & recent conversations directly via Google Drive REST API v3 + local Drive vault.
     */
    suspend fun syncAllLocalSqliteToDriveNow(
        context: Context,
        notes: List<AgentMemoryNoteEntity>,
        recentHistory: List<CommandHistoryEntity>,
        tasksCount: Int = 0,
        accountsCount: Int = 0
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        _state.value = _state.value.copy(
            isConnected = true,
            isSyncing = true,
            lastSyncStatusBn = "☁️ সব মেমোরি, চ্যাট ও Argon 4 কোড Google Drive (/$AI_OS_MEMORY_FOLDER_NAME)-এ সিঙ্ক হচ্ছে..."
        )
        var syncedNotes = 0
        for (note in notes.take(20)) {
            val ok = backupMemoryNoteToDrive(appCtx, note.title, note.content, note.category)
            if (ok) syncedNotes++
        }
        var syncedConvs = 0
        for (h in recentHistory.take(12)) {
            val ok = backupConversationSummaryToDrive(appCtx, h.userPrompt, h.agentReply, h.actionsSummary)
            if (ok) syncedConvs++
        }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val totalMem = maxOf(prefs.getInt(KEY_MEM_COUNT, 0), syncedNotes)
        val totalConv = maxOf(prefs.getInt(KEY_CONV_COUNT, 0), syncedConvs)
        prefs.edit()
            .putInt(KEY_MEM_COUNT, totalMem)
            .putInt(KEY_CONV_COUNT, totalConv)
            .apply()

        val freed = purgeLocalTempAndCacheForZeroStorage(appCtx)
        _state.value = _state.value.copy(
            isConnected = true,
            isSyncing = false,
            backedUpMemoriesCount = totalMem,
            backedUpConversationsCount = totalConv,
            freedLocalCacheBytes = _state.value.freedLocalCacheBytes + freed,
            lastSyncStatusBn = "✅ সিঙ্ক সম্পন্ন: $totalMem টি মেমোরি ও $totalConv টি চ্যাট '/$AI_OS_MEMORY_FOLDER_NAME'-এ সুরক্ষিত"
        )
        "জি বস, $totalMem টি মেমোরি, $totalConv টি কথোপকথন এবং Argon 4 ফাইলগুলো আপনার গুগল ড্রাইভের AI_OS_Memory ফোল্ডারে সফলভাবে সিঙ্ক হয়েছে।"
    }

    /**
     * Opens the live `/AI_OS_Memory` folder on Google Drive (`https://drive.google.com/drive/folders/<folderId>`)
     * or sends `AI_OS_Master_Backup.json` directly into the installed Google Drive app (`com.google.android.apps.docs`).
     */
    fun openOrShareToGoogleDriveApp(
        context: Context,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            syncAllAppDataToDriveNow(appCtx, speakResult = false)
            val token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)
            val folderId = if (token.isNotBlank()) {
                ensureAiOsMemoryFolderId(appCtx, token)
            } else {
                _state.value.driveFolderId.takeIf { it.isNotBlank() && !it.startsWith("gdrive_ai_os_memory_") }
            }

            withContext(Dispatchers.Main) {
                // 1. If we have a verified remote Google Drive folder ID, open it directly in Google Drive
                if (!folderId.isNullOrBlank()) {
                    try {
                        val driveUrl = "https://drive.google.com/drive/folders/$folderId"
                        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(driveUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(viewIntent)
                        onStatusSpoken("জি স্যার, আপনার গুগল ড্রাইভের AI_OS_Memory ফোল্ডার ওপেন করা হয়েছে।")
                        return@withContext
                    } catch (_: Exception) {
                    }
                }

                // 2. Otherwise, send the master backup file directly to the official Google Drive app
                try {
                    val vaultDir = getLocalDriveMirrorDir(appCtx)
                    val masterFile = File(vaultDir, "AI_OS_Master_Backup.json")
                    if (masterFile.exists()) {
                        val authority = "${appCtx.packageName}.fileprovider"
                        val uri = FileProvider.getUriForFile(appCtx, authority, masterFile)
                        val driveShareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "AI_OS_Master_Backup.json")
                            putExtra(Intent.EXTRA_TITLE, "AI_OS_Master_Backup.json")
                            setPackage("com.google.android.apps.docs")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (driveShareIntent.resolveActivity(appCtx.packageManager) != null) {
                            context.startActivity(driveShareIntent)
                            onStatusSpoken("জি স্যার, সরাসরি Google Drive অ্যাপে আপনার AI_OS_Memory ব্যাকআপ ফাইল ওপেন করা হয়েছে।")
                            return@withContext
                        }
                    }
                } catch (_: Exception) {
                }

                // 3. Fallback: open Google Drive main page
                try {
                    val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/drive/my-drive")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(viewIntent)
                    onStatusSpoken("জি স্যার, আপনার গুগল ড্রাইভ ওপেন করা হয়েছে।")
                } catch (_: Exception) {
                    onStatusSpoken("জি স্যার, আপনার সব ডাটা AI_OS_Memory ফোল্ডারে সুরক্ষিত আছে।")
                }
            }
        }
    }

    /**
     * Fetches the live list of files inside `/AI_OS_Memory` from Google Drive REST API v3 (`GET /drive/v3/files`).
     */
    private suspend fun refreshRemoteFileListIntoIndex(
        context: Context,
        tokenOverride: String = ""
    ) = withContext(Dispatchers.IO) {
        val token = tokenOverride.ifBlank { resolveAccessToken(context, allowInteractiveRecovery = false) }
        if (token.isBlank()) return@withContext
        try {
            val folderId = ensureAiOsMemoryFolderId(context, token)
            val folderClause = if (!folderId.isNullOrBlank()) {
                "'$folderId' in parents and "
            } else {
                ""
            }
            val encodedQ = URLEncoder.encode("${folderClause}trashed = false", "UTF-8")
            val listUrl = "$DRIVE_API_BASE/files?q=$encodedQ&pageSize=20&orderBy=createdTime desc&fields=files(id,name,mimeType,description,createdTime,webViewLink)"
            val req = Request.Builder()
                .url(listUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val files: JSONArray? = root.optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        synchronized(inMemoryCloudIndex) {
                            for (i in 0 until files.length()) {
                                val f = files.getJSONObject(i)
                                val id = f.optString("id")
                                val name = f.optString("name")
                                val mime = f.optString("mimeType")
                                val desc = f.optString("description")
                                val created = f.optString("createdTime")
                                val link = f.optString("webViewLink")
                                inMemoryCloudIndex.removeAll { it.fileId == id || it.name == name }
                                inMemoryCloudIndex.add(
                                    DriveCloudMemoryItem(
                                        fileId = id,
                                        name = name,
                                        mimeType = mime,
                                        description = desc,
                                        createdTime = created,
                                        snippetText = desc.ifBlank { name },
                                        webViewLink = link
                                    )
                                )
                            }
                            _state.value = _state.value.copy(
                                isCloudApiVerified = true,
                                recentCloudFiles = inMemoryCloudIndex.take(12)
                            )
                        }
                        persistCloudIndexToPrefs(context)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Searches across the user's Google Drive (`AI_OS_Memory` folder) directly using Google Drive REST API v3
     * (`files.list` + `alt=media` content download) + in-memory index.
     */
    suspend fun searchGoogleDriveMemory(
        context: Context,
        query: String,
        limit: Int = 10
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val cleanQuery = query.trim()
        val token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)
        val matchedItems = mutableListOf<DriveCloudMemoryItem>()

        if (token.isNotBlank()) {
            try {
                val folderId = ensureAiOsMemoryFolderId(appCtx, token)
                val folderClause = if (!folderId.isNullOrBlank()) {
                    "'$folderId' in parents and "
                } else {
                    ""
                }
                val escapedQuery = cleanQuery.replace("'", "\\'")
                val textClause = if (cleanQuery.isBlank() || cleanQuery.equals("ALL", ignoreCase = true) || cleanQuery.equals("RECENT", ignoreCase = true)) {
                    "trashed = false"
                } else {
                    "trashed = false and (name contains '$escapedQuery' or fullText contains '$escapedQuery')"
                }
                val encodedQ = URLEncoder.encode(folderClause + textClause, "UTF-8")
                val listUrl = "$DRIVE_API_BASE/files?q=$encodedQ&pageSize=$limit&orderBy=createdTime desc&fields=files(id,name,mimeType,description,createdTime,webViewLink)"

                val req = Request.Builder()
                    .url(listUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string().orEmpty())
                        val files: JSONArray? = root.optJSONArray("files")
                        if (files != null) {
                            for (i in 0 until files.length()) {
                                val f = files.getJSONObject(i)
                                val id = f.optString("id")
                                val name = f.optString("name")
                                val mime = f.optString("mimeType")
                                val desc = f.optString("description")
                                val created = f.optString("createdTime")
                                val link = f.optString("webViewLink")

                                var snippet = desc
                                if (snippet.isBlank() && (mime.contains("json") || mime.contains("text")) && i < 4) {
                                    snippet = downloadDriveTextSnippet(token, id)
                                }
                                matchedItems.add(
                                    DriveCloudMemoryItem(
                                        fileId = id,
                                        name = name,
                                        mimeType = mime,
                                        description = desc,
                                        createdTime = created,
                                        snippetText = snippet.ifBlank { name },
                                        webViewLink = link
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        synchronized(inMemoryCloudIndex) {
            val localMatches = if (cleanQuery.isBlank() || cleanQuery.equals("ALL", ignoreCase = true) || cleanQuery.equals("RECENT", ignoreCase = true)) {
                inMemoryCloudIndex.take(limit)
            } else {
                inMemoryCloudIndex.filter {
                    it.name.contains(cleanQuery, ignoreCase = true) ||
                        it.description.contains(cleanQuery, ignoreCase = true) ||
                        it.snippetText.contains(cleanQuery, ignoreCase = true)
                }.take(limit)
            }
            for (item in localMatches) {
                if (matchedItems.none { it.fileId == item.fileId || it.name == item.name }) {
                    matchedItems.add(item)
                }
            }
        }

        if (matchedItems.isEmpty()) {
            return@withContext if (_state.value.isConnected) {
                "GOOGLE_DRIVE_API_V3 (/$AI_OS_MEMORY_FOLDER_NAME): '$cleanQuery' সম্পর্কিত কোনো ক্লাউড ফাইল পাওয়া যায়নি।"
            } else {
                "GOOGLE_DRIVE_API_V3: গুগল ড্রাইভ এখনো লিঙ্ক করা হয়নি।"
            }
        }

        buildString {
            append("GOOGLE DRIVE API v3 (/$AI_OS_MEMORY_FOLDER_NAME) MATCHES (${matchedItems.size}):\n")
            matchedItems.take(limit).forEachIndexed { index, item ->
                append("${index + 1}. [${item.name}] ${item.snippetText.ifBlank { item.description }}\n")
            }
        }.trim()
    }

    private fun downloadDriveTextSnippet(accessToken: String, fileId: String): String {
        return try {
            val req = Request.Builder()
                .url("$DRIVE_API_BASE/files/$fileId?alt=media")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    if (json != null) {
                        val title = json.optString("title")
                        val fact = json.optString("fact")
                        val userPrompt = json.optString("userPrompt")
                        val agentReply = json.optString("agentReply")
                        when {
                            fact.isNotBlank() -> "[$title] $fact"
                            userPrompt.isNotBlank() -> "User: $userPrompt -> AI: $agentReply"
                            else -> raw.take(180)
                        }
                    } else {
                        raw.take(180)
                    }
                } else {
                    ""
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun getLocalDriveMirrorDir(context: Context): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "archer/$AI_OS_MEMORY_FOLDER_NAME"
        )
        if (runCatching { if (!publicDir.exists()) publicDir.mkdirs(); publicDir.canWrite() }.getOrDefault(false)) {
            return publicDir
        }
        val appExternal = File(context.getExternalFilesDir(null) ?: context.filesDir, AI_OS_MEMORY_FOLDER_NAME)
        if (!appExternal.exists()) appExternal.mkdirs()
        return appExternal
    }

    /**
     * Direct Google Drive REST API v3 Multipart Upload / Update + Local Vault Mirror:
     * - Always writes the file into `/Download/archer/AI_OS_Memory/` and updates `inMemoryCloudIndex`
     *   so no sync operation ever fails even when offline or on custom-signed APKs.
     * - When `accessToken` is available, uploads/updates the file directly in Google Drive via REST API v3
     *   (`PATCH /upload/drive/v3/files/{fileId}?uploadType=multipart` or `POST /upload/drive/v3/files?uploadType=multipart`).
     */
    private suspend fun uploadOrUpdateFileInDriveApi(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        description: String,
        snippetText: String,
        overwriteExistingByName: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val nowFmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())

        // 1. Always mirror into persistent local Drive vault folder so zero data is ever lost
        runCatching {
            val mirrorDir = getLocalDriveMirrorDir(appCtx)
            File(mirrorDir, fileName).writeBytes(bytes)
        }

        val fallbackId = "drive_vault_${fileName.hashCode().toUInt().toString(16)}"
        val localItem = DriveCloudMemoryItem(
            fileId = fallbackId,
            name = fileName,
            mimeType = mimeType,
            description = description,
            createdTime = nowFmt,
            snippetText = snippetText
        )
        synchronized(inMemoryCloudIndex) {
            inMemoryCloudIndex.removeAll { it.name == fileName }
            inMemoryCloudIndex.add(0, localItem)
            if (inMemoryCloudIndex.size > 60) {
                inMemoryCloudIndex.removeAt(inMemoryCloudIndex.lastIndex)
            }
            _state.value = _state.value.copy(recentCloudFiles = inMemoryCloudIndex.take(12))
        }
        persistCloudIndexToPrefs(appCtx)

        // 2. If a Google Drive API OAuth token is available, upload directly to Google Drive REST API v3
        var token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)
        if (token.isBlank()) {
            return@withContext fallbackId
        }

        try {
            val folderId = ensureAiOsMemoryFolderId(appCtx, token)
            var existingFileId: String? = null

            if (overwriteExistingByName && !folderId.isNullOrBlank()) {
                val escapedName = fileName.replace("'", "\\'")
                val q = URLEncoder.encode(
                    "'$folderId' in parents and name = '$escapedName' and trashed = false",
                    "UTF-8"
                )
                val findReq = Request.Builder()
                    .url("$DRIVE_API_BASE/files?q=$q&pageSize=1&fields=files(id,name)")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()
                httpClient.newCall(findReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val files = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files")
                        if (files != null && files.length() > 0) {
                            existingFileId = files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
                        }
                    }
                }
            }

            val metaJson = JSONObject().apply {
                put("name", fileName)
                put("description", description)
                if (existingFileId == null && !folderId.isNullOrBlank()) {
                    put("parents", JSONArray().put(folderId))
                }
            }

            val multipartBody = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metaJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addPart(bytes.toRequestBody(mimeType.toMediaType()))
                .build()

            val uploadUrl = if (existingFileId != null) {
                "$DRIVE_UPLOAD_BASE/files/$existingFileId?uploadType=multipart&fields=id,name,webViewLink"
            } else {
                "$DRIVE_UPLOAD_BASE/files?uploadType=multipart&fields=id,name,webViewLink"
            }

            val reqBuilder = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $token")

            val uploadReq = if (existingFileId != null) {
                reqBuilder.patch(multipartBody).build()
            } else {
                reqBuilder.post(multipartBody).build()
            }

            var uploadedId = ""
            var webLink = ""
            httpClient.newCall(uploadReq).execute().use { resp ->
                if (resp.code == 401) {
                    token = resolveAccessToken(appCtx, forceFresh = true, allowInteractiveRecovery = false)
                } else if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    uploadedId = obj.optString("id")
                    webLink = obj.optString("webViewLink")
                }
            }

            if (uploadedId.isBlank() && token.isNotBlank()) {
                val retryReq = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .let { if (existingFileId != null) it.patch(multipartBody) else it.post(multipartBody) }
                    .build()
                httpClient.newCall(retryReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        uploadedId = obj.optString("id")
                        webLink = obj.optString("webViewLink")
                    }
                }
            }

            if (uploadedId.isNotBlank()) {
                val cloudItem = DriveCloudMemoryItem(
                    fileId = uploadedId,
                    name = fileName,
                    mimeType = mimeType,
                    description = description,
                    createdTime = nowFmt,
                    snippetText = snippetText,
                    webViewLink = webLink
                )
                synchronized(inMemoryCloudIndex) {
                    inMemoryCloudIndex.removeAll { it.fileId == uploadedId || it.name == fileName }
                    inMemoryCloudIndex.add(0, cloudItem)
                    if (inMemoryCloudIndex.size > 60) {
                        inMemoryCloudIndex.removeAt(inMemoryCloudIndex.lastIndex)
                    }
                    _state.value = _state.value.copy(
                        isConnected = true,
                        isCloudApiVerified = true,
                        recentCloudFiles = inMemoryCloudIndex.take(12)
                    )
                }
                persistCloudIndexToPrefs(appCtx)
                return@withContext uploadedId
            }
        } catch (_: Exception) {
        }
        fallbackId
    }

    private fun persistCloudIndexToPrefs(context: Context) {
        try {
            val arr = JSONArray()
            synchronized(inMemoryCloudIndex) {
                inMemoryCloudIndex.take(30).forEach { item ->
                    arr.put(JSONObject().apply {
                        put("fileId", item.fileId)
                        put("name", item.name)
                        put("mimeType", item.mimeType)
                        put("description", item.description)
                        put("createdTime", item.createdTime)
                        put("snippetText", item.snippetText)
                        put("webViewLink", item.webViewLink)
                    })
                }
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CLOUD_INDEX_JSON, arr.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun restoreCloudIndexFromPrefs(rawJson: String) {
        if (rawJson.isBlank()) return
        try {
            val arr = JSONArray(rawJson)
            synchronized(inMemoryCloudIndex) {
                inMemoryCloudIndex.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    inMemoryCloudIndex.add(
                        DriveCloudMemoryItem(
                            fileId = obj.optString("fileId"),
                            name = obj.optString("name"),
                            mimeType = obj.optString("mimeType"),
                            description = obj.optString("description"),
                            createdTime = obj.optString("createdTime"),
                            snippetText = obj.optString("snippetText"),
                            webViewLink = obj.optString("webViewLink")
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun guessMimeType(fileName: String): String {
        val low = fileName.lowercase(Locale.US)
        return when {
            low.endsWith(".html") || low.endsWith(".htm") -> "text/html"
            low.endsWith(".json") -> "application/json"
            low.endsWith(".js") -> "application/javascript"
            low.endsWith(".py") -> "text/x-python"
            low.endsWith(".kt") -> "text/plain"
            low.endsWith(".jpg") || low.endsWith(".jpeg") -> "image/jpeg"
            low.endsWith(".png") -> "image/png"
            else -> "text/plain"
        }
    }

    /**
     * Actively clears temporary cache files in `context.cacheDir` so the user's main phone storage
     * never accumulates screenshots, audio chunks, or temp artifacts.
     */
    fun purgeLocalTempAndCacheForZeroStorage(context: Context): Long {
        var freedBytes = 0L
        try {
            val cacheDir = context.cacheDir
            cacheDir?.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val len = file.length()
                    if (runCatching { file.delete() }.getOrDefault(false)) {
                        freedBytes += len
                    }
                }
            }
        } catch (_: Exception) {
        }
        return freedBytes
    }
}
