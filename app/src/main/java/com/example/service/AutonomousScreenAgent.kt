package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AutonomousAgentDecision(
    val thought: String = "",
    val status: String = "CONTINUE", // CONTINUE, DONE, READY_FOR_CONFIRMATION, NEED_USER_CHOICE, FAILED
    val action: String = "NONE",     // TAP_NODE, TYPE_TEXT, TYPE_AND_SEND, SCROLL_DOWN, SCROLL_UP, SCROLL_LEFT, SCROLL_RIGHT, OPEN_APP, BACK, HOME, SEARCH_INTERNET, WAIT, NONE
    val nodeIndex: Int = -1,
    val sendNodeIndex: Int = -1,
    val x: Int = -1,
    val y: Int = -1,
    val text: String = "",
    val submitEnter: Boolean = false,
    val nextAction: String = "",     // Optional chained follow-up in same turn (e.g. TYPE_TEXT after TAP_NODE on Search)
    val nextNodeIndex: Int = -1,
    val nextText: String = "",
    val nextSubmitEnter: Boolean = false,
    val appName: String = "",
    val replyBn: String = ""
)

data class AutonomousAgentResult(
    val success: Boolean,
    val needsUserConfirmation: Boolean = false,
    val needsUserChoice: Boolean = false,
    val summaryBn: String,
    val stepsTaken: List<String> = emptyList(),
    val finalPackage: String = ""
)

/**
 * High-Speed, Self-Learning, Internet-Aware Autonomous Screen Agent
 * (Self-Learn App -> Observe -> Zero-LLM Fast-Path / Vision+Tree Brain -> Batched Act -> Verify).
 *
 * - Learns unfamiliar apps automatically on first sight and persists control maps in AppKnowledgeRegistry.
 * - Executes open-chat deictic sends ("একে", "ওকে") and unambiguous visible chats in ~1s without redundant LLM hops.
 * - Supports batched multi-step actions (TAP search + TYPE name, TYPE message + TAP Send) in a single turn.
 * - Can fetch live internet information mid-task when a goal needs current facts, prices, weather, or URLs.
 */
object AutonomousScreenAgent {

    data class ResolvedRecipientProfile(
        val rawInput: String,
        val isSelfId: Boolean,
        val isDeictic: Boolean,
        val primaryDisplayName: String,
        val resolvedPhoneNumber: String = "",
        val searchQueries: List<String> = emptyList(),
        val rowMatchKeywords: List<String> = emptyList()
    )

    private val FAST_VISION_MODELS = listOf(
        "models/gemini-2.5-flash",
        "models/gemini-2.5-pro",
        "models/gemini-3-flash-preview",
        "models/gemini-3.1-pro-preview"
    )

    private val DEICTIC_TERMS = setOf(
        "একে", "ওকে", "তাকে", "এনাকে", "উনাকে", "ইনাকে", "এইজনকে", "এই জনকে",
        "এই আইডি", "এই আইডিতে", "এই চ্যাট", "এই চ্যাটে", "এখানে", "এটাতে", "এটায়",
        "him", "her", "them", "this person", "this user", "this id", "this chat",
        "current chat", "current user", "here", "same person"
    )

    private val SELF_ID_TERMS = setOf(
        "আমার আইডি", "আমার আইডিতে", "আমার নিজের আইডি", "আমার নিজের আইডিতে",
        "নিজের আইডি", "নিজের আইডিতে", "নিজেকে", "আমাকে", "আমার নাম্বার", "আমার নাম্বারে",
        "আমার নম্বর", "আমার নম্বরে", "আমার হোয়াটসঅ্যাপ", "আমার হোয়াটসঅ্যাপ",
        "আমার অন্য আইডি", "আমার অন্য আইডিতে", "আমার সেকেন্ড আইডি", "আমার দ্বিতীয় আইডি",
        "my id", "my own id", "myself", "message yourself", "to me", "my number",
        "my whatsapp", "my account", "self", "note to self"
    )

    private val NON_CONTACT_ROW_LABELS = setOf(
        "all", "unread", "favourites", "favorites", "groups", "communities", "chats",
        "updates", "calls", "status", "channels", "archived", "locked chats",
        "meta ai", "ask meta ai or search", "search", "search…", "search...",
        "সার্চ", "খুঁজুন", "সব", "অপঠিত", "গ্রুপ", "কমিউনিটি", "চ্যাট", "কল", "আপডেট",
        "new chat", "new group", "new community", "broadcast lists", "starred messages"
    )

    fun isSelfRecipientReference(raw: String, fullGoal: String = ""): Boolean {
        val clean = raw.trim().lowercase()
            .replace(Regex("^(to|for|@)\\s+"), "")
            .replace(Regex("(কে|তে|র|য়|য়ে|য়ে)$"), "")
            .trim()
        val rawLow = raw.trim().lowercase()
        val goalLow = fullGoal.trim().lowercase()
        if (rawLow in SELF_ID_TERMS || clean in SELF_ID_TERMS) return true
        if (SELF_ID_TERMS.any { term -> rawLow.contains(term) || (rawLow.isBlank() && goalLow.contains(term)) }) {
            return true
        }
        return Regex("(?i)\\b(আমার\\s*(?:নিজের\\s*|অন্য\\s*)?(?:আইডি|নাম্বার|নম্বর|হোয়াটসঅ্যাপ|হোয়াটসঅ্যাপ)|নিজের\\s*আইডি|আমাকে\\s*মেসেজ|নিজেকে\\s*মেসেজ|message\\s+myself|my\\s+own\\s+id)\\b")
            .containsMatchIn("$rawLow $goalLow")
    }

    fun isDeicticReference(raw: String): Boolean {
        val clean = raw.trim().lowercase()
            .replace(Regex("^(to|for|@)\\s+"), "")
            .trim()
        if (clean.isBlank()) return false
        if (isSelfRecipientReference(clean)) return false
        if (clean in DEICTIC_TERMS) return true
        return DEICTIC_TERMS.any { term ->
            clean == "${term}কে" || clean == "${term}র" || clean == "${term}তে"
        }
    }

    /**
     * Builds multi-script (Bangla + English phonetic transliteration + Self-ID) search queries
     * and row-matching keywords so the agent can find contacts whether saved in English or Bangla,
     * and can locate the user's own account ("(You)", "Message yourself", Boss's name/phone).
     */
    fun buildSmartSearchAndMatchCandidates(rawName: String, isSelf: Boolean = false): List<String> {
        val out = LinkedHashSet<String>()
        if (isSelf || isSelfRecipientReference(rawName)) {
            out.add("(You)")
            out.add("You")
            out.add("Message yourself")
            out.add("নিজেকে")
        }
        val cleaned = rawName.trim()
            .replace(Regex("(?i)^(to|for|@)\\s+"), "")
            .replace(Regex("(?:কে|রে|ের|তে|য়ে|য়ে)\\s*$"), "")
            .replace(Regex("(?i)\\s*(?:এর\\s*আইডি|আইডি|ভাইয়া|ভাইয়া|ভাই|আপু|স্যার|বস)\\s*$"), "")
            .trim()
        if (cleaned.isNotBlank() && !isSelfRecipientReference(cleaned)) {
            out.add(cleaned)
            val enPhonetic = transliterateBengaliToEnglish(cleaned)
            if (enPhonetic.length >= 2 && !enPhonetic.equals(cleaned, ignoreCase = true)) {
                out.add(enPhonetic)
                val altPhonetic = enPhonetic
                    .replace("ee", "i")
                    .replace("oo", "u")
                    .replace("sh", "s")
                    .replace("v", "bh")
                if (altPhonetic.length >= 2) out.add(altPhonetic)
            }
            val firstWord = cleaned.split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
            if (firstWord.length >= 2) {
                out.add(firstWord)
                val firstEn = transliterateBengaliToEnglish(firstWord)
                if (firstEn.length >= 2) out.add(firstEn)
            }
        }
        return out.filter { it.isNotBlank() }
    }

    fun transliterateBengaliToEnglish(input: String): String {
        val hasBengali = input.any { it in '\u0980'..'\u09FF' }
        if (!hasBengali) return input.trim()

        val exactDictionary = mapOf(
            "রাহিম" to "Rahim", "রহিম" to "Rahim", "করিম" to "Karim", "রিয়ান" to "Riyan", "রিয়ান" to "Riyan",
            "হাসান" to "Hasan", "হোসাইন" to "Hossain", "হোসেন" to "Hosen", "সাকিব" to "Sakib", "শাকিব" to "Shakib",
            "তামিম" to "Tamim", "নাইম" to "Naim", "নাঈম" to "Nayeem", "ফাহিম" to "Fahim", "মাহিম" to "Mahim",
            "তানভীর" to "Tanvir", "সাব্বির" to "Sabbir", "আরিফ" to "Arif", "শরীফ" to "Sharif", "শরিফ" to "Sharif",
            "তারেক" to "Tarek", "তারিক" to "Tarik", "জাহিদ" to "Jahid", "নাহিদ" to "Nahid", "শহীদ" to "Shahid",
            "শাহিন" to "Shahin", "তুহিন" to "Tuhin", "সুমন" to "Sumon", "সুজন" to "Sujon", "রিপন" to "Ripon",
            "লিটন" to "Liton", "মিলন" to "Milon", "নয়ন" to "Noyon", "নয়ন" to "Noyon", "হৃদয়" to "Hridoy",
            "হৃদয়" to "Hridoy", "সাগর" to "Sagor", "আকাশ" to "Akash", "বাতাস" to "Batas", "শান্ত" to "Shanto",
            "প্রান্ত" to "Pranto", "জয়" to "Joy", "জয়" to "Joy", "বিজয়" to "Bijoy", "বিজয়" to "Bijoy",
            "রাকিব" to "Rakib", "রাজিব" to "Rajib", "সজীব" to "Sajib", "সজিব" to "Sajib", "আসিফ" to "Asif",
            "তৌহিদ" to "Towhid", "মেহেদী" to "Mehedi", "মেহেদি" to "Mehedi", "মাহমুদ" to "Mahmud", "আহমেদ" to "Ahmed",
            "মোহাম্মদ" to "Mohammad", "মুহাম্মদ" to "Muhammad", "মোঃ" to "Md", "মিমি" to "Mimi", "তানিয়া" to "Tania",
            "তানিয়া" to "Tania", "সাদিয়া" to "Sadia", "সাদিয়া" to "Sadia", "নুসরাত" to "Nusrat", "জান্নাত" to "Jannat",
            "সুমাইয়া" to "Sumaiya", "সুমাইয়া" to "Sumaiya", "ফারজানা" to "Farzana", "শারমিন" to "Sharmin",
            "আম্মু" to "Ammu", "আব্বু" to "Abbu", "মা" to "Maa", "বাবা" to "Baba", "ভাইয়া" to "Bhaiya", "ভাই" to "Bhai"
        )

        val words = input.trim().split(Regex("\\s+"))
        val mappedWords = words.map { w ->
            exactDictionary[w]?.let { return@map it }
            val sb = StringBuilder()
            var i = 0
            while (i < w.length) {
                val c = w[i]
                val next = w.getOrNull(i + 1)
                val piece = when (c) {
                    'অ' -> "a"; 'আ' -> "a"; 'ই' -> "i"; 'ঈ' -> "ee"; 'উ' -> "u"; 'ঊ' -> "u"
                    'ঋ' -> "ri"; 'এ' -> "e"; 'ঐ' -> "oi"; 'ও' -> "o"; 'ঔ' -> "ou"
                    'া' -> "a"; 'ি' -> "i"; 'ী' -> "i"; 'ু' -> "u"; 'ূ' -> "u"; 'ৃ' -> "ri"
                    'ে' -> "e"; 'ৈ' -> "oi"; 'ো' -> "o"; 'ৌ' -> "ou"; '্', '\u09BC' -> ""
                    'ক' -> "k"; 'খ' -> "kh"; 'গ' -> "g"; 'ঘ' -> "gh"; 'ঙ' -> "ng"
                    'চ' -> "ch"; 'ছ' -> "chh"; 'জ' -> "j"; 'ঝ' -> "jh"; 'ঞ' -> "n"
                    'ট' -> "t"; 'ঠ' -> "th"; 'ড' -> "d"; 'ঢ' -> "dh"; 'ণ' -> "n"
                    'ত' -> "t"; 'থ' -> "th"; 'দ' -> "d"; 'ধ' -> "dh"; 'ন' -> "n"
                    'প' -> "p"; 'ফ' -> "f"; 'ব' -> "b"; 'ভ' -> "bh"; 'ম' -> "m"
                    'য' -> if (next == '\u09BC') "y" else "j"
                    'র' -> "r"; 'ল' -> "l"; 'শ' -> "sh"; 'ষ' -> "sh"; 'স' -> "s"; 'হ' -> "h"
                    '\u09DC', '\u09DD' -> "r"; '\u09DF' -> "y"; 'ৎ' -> "t"; 'ং' -> "ng"; 'ঃ' -> "h"; 'ঁ' -> ""
                    else -> c.toString()
                }
                sb.append(piece)
                val isConsonant = c in 'ক'..'হ' || c == '\u09DC' || c == '\u09DD' || c == '\u09DF'
                val nextIsVowelSignOrHasanta = next != null && (next in 'া'..'ৌ' || next == '্' || next == '\u09BC')
                if (isConsonant && !nextIsVowelSignOrHasanta && next != null) {
                    sb.append('a')
                }
                i++
            }
            sb.toString().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        return mappedWords.joinToString(" ").trim()
    }

    private suspend fun resolveSmartRecipientProfile(
        context: Context,
        rawRecipient: String,
        goal: String,
        appHint: String
    ): ResolvedRecipientProfile = withContext(Dispatchers.IO) {
        val trimmed = rawRecipient.trim()
        val isSelf = isSelfRecipientReference(trimmed, goal)
        val isDeictic = !isSelf && (isDeicticReference(trimmed) || (trimmed.isBlank() && isDeicticReferenceInGoal(goal)))
        if (isDeictic) {
            return@withContext ResolvedRecipientProfile(
                rawInput = trimmed,
                isSelfId = false,
                isDeictic = true,
                primaryDisplayName = ""
            )
        }

        val searchQueries = LinkedHashSet<String>()
        val rowMatchKeywords = LinkedHashSet<String>()
        var phone = ""
        var displayName = trimmed

        val db = try { com.example.data.AgentDatabase.getInstance(context) } catch (_: Exception) { null }

        if (isSelf) {
            val driveState = GoogleDriveMemorySyncService.state.value
            val bossName = driveState.displayName.trim()
            val bossEmailPrefix = driveState.linkedEmail.substringBefore("@").trim()

            // Check saved platform accounts & memory notes for Boss's own number/ID
            val savedAccounts = try {
                db?.platformAccountDao()?.getAllAccounts()?.first().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            val savedNotes = try {
                db?.agentMemoryNoteDao()?.getRecentNotesSnapshot(30).orEmpty()
            } catch (_: Exception) {
                emptyList()
            }

            for (acc in savedAccounts) {
                val h = acc.handleOrNumberOrUrl.trim()
                val cName = acc.contactOrGroupName.trim()
                val digits = h.filter { it.isDigit() || it == '+' }
                if (digits.length >= 10 && phone.isBlank()) phone = digits
                if (cName.length >= 2) {
                    searchQueries.add(cName)
                    rowMatchKeywords.add(cName)
                }
                if (h.length >= 2) {
                    searchQueries.add(h)
                    rowMatchKeywords.add(h)
                }
            }
            for (note in savedNotes) {
                val combined = "${note.title} ${note.content}"
                if (combined.contains("আমার", ignoreCase = true) || combined.contains("নিজের", ignoreCase = true) ||
                    combined.contains("boss", ignoreCase = true) || combined.contains("my number", ignoreCase = true) ||
                    combined.contains("my id", ignoreCase = true)
                ) {
                    val m = Regex("(?:\\+?88)?01[3-9]\\d{8}").find(combined)
                    if (m != null && phone.isBlank()) phone = m.value
                }
            }

            if (bossName.length >= 2) {
                displayName = bossName
                searchQueries.add(bossName)
                rowMatchKeywords.add(bossName)
                val firstBoss = bossName.split(" ").firstOrNull()?.trim().orEmpty()
                if (firstBoss.length >= 2) {
                    searchQueries.add(firstBoss)
                    rowMatchKeywords.add(firstBoss)
                }
            } else {
                displayName = "আমার আইডি (You)"
            }
            if (bossEmailPrefix.length >= 3) {
                val cleanPrefix = bossEmailPrefix.replace(Regex("\\d+$"), "").trim()
                if (cleanPrefix.length >= 3) {
                    searchQueries.add(cleanPrefix)
                    rowMatchKeywords.add(cleanPrefix)
                }
            }

            // WhatsApp & chat apps universal Self-Chat markers
            searchQueries.add("(You)")
            searchQueries.add("Message yourself")
            searchQueries.add("You")
            rowMatchKeywords.add("(you)")
            rowMatchKeywords.add("message yourself")
            rowMatchKeywords.add("নিজেকে মেসেজ")
            rowMatchKeywords.add("নিজেকে")
            if (phone.isNotBlank()) {
                searchQueries.add(phone)
                rowMatchKeywords.add(phone.takeLast(8))
            }

            return@withContext ResolvedRecipientProfile(
                rawInput = trimmed.ifBlank { "আমার আইডি" },
                isSelfId = true,
                isDeictic = false,
                primaryDisplayName = displayName,
                resolvedPhoneNumber = phone,
                searchQueries = searchQueries.toList(),
                rowMatchKeywords = rowMatchKeywords.toList()
            )
        }

        // Named contact or phone number resolution
        val baseCandidates = buildSmartSearchAndMatchCandidates(trimmed, isSelf = false)
        baseCandidates.forEach {
            searchQueries.add(it)
            rowMatchKeywords.add(it)
        }

        val directDigits = trimmed.filter { it.isDigit() || it == '+' }
        if (directDigits.length >= 8) {
            phone = directDigits
        }

        // Look up Android ContactsContract using both Bangla & English phonetic candidates
        try {
            val cr = context.contentResolver
            cr.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val cName = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val cNum = if (numIdx >= 0) cursor.getString(numIdx)?.replace(Regex("[\\s\\-()]"), "").orEmpty() else ""
                    if (cName.isBlank()) continue
                    val matched = baseCandidates.any { cand ->
                        cand.length >= 2 && (cName.contains(cand, ignoreCase = true) || cand.contains(cName, ignoreCase = true))
                    }
                    if (matched) {
                        // Put exact saved contact name FIRST so WhatsApp search finds it on the very first try!
                        val ordered = LinkedHashSet<String>()
                        ordered.add(cName)
                        ordered.addAll(searchQueries)
                        searchQueries.clear()
                        searchQueries.addAll(ordered)
                        rowMatchKeywords.add(cName)
                        if (cNum.length >= 8 && phone.isBlank()) {
                            phone = cNum
                            rowMatchKeywords.add(cNum.takeLast(8))
                        }
                        displayName = cName
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        // Also check saved memory notes for custom nicknames / IDs (e.g. "আমার দ্বিতীয় আইডি রাহুল")
        try {
            val notes = db?.agentMemoryNoteDao()?.getRecentNotesSnapshot(30).orEmpty()
            for (note in notes) {
                val text = "${note.title} ${note.content}"
                if (baseCandidates.any { it.length >= 2 && text.contains(it, ignoreCase = true) }) {
                    val m = Regex("(?:\\+?88)?01[3-9]\\d{8}").find(text)
                    if (m != null && phone.isBlank()) {
                        phone = m.value
                        searchQueries.add(phone)
                        rowMatchKeywords.add(phone.takeLast(8))
                    }
                }
            }
        } catch (_: Exception) {}

        ResolvedRecipientProfile(
            rawInput = trimmed,
            isSelfId = false,
            isDeictic = false,
            primaryDisplayName = displayName.ifBlank { trimmed },
            resolvedPhoneNumber = phone,
            searchQueries = searchQueries.toList(),
            rowMatchKeywords = rowMatchKeywords.toList()
        )
    }

    fun appPackageMatchesHint(currentPkg: String, appHint: String): Boolean {
        val pkg = currentPkg.lowercase().trim()
        val hint = appHint.lowercase().trim()
        if (pkg.isBlank() || hint.isBlank() || hint == "current_screen" || hint == "current") return false
        return when {
            hint.contains("whatsapp") || hint.contains("হোয়াটসঅ্যাপ") || hint.contains("হোয়াটসঅ্যাপ") ->
                pkg.contains("whatsapp")
            hint.contains("messenger") || hint.contains("মেসেঞ্জার") ->
                pkg == "com.facebook.orca" || pkg.contains("orca")
            hint.contains("facebook") || hint.contains("ফেসবুক") ->
                pkg.contains("facebook")
            hint.contains("telegram") || hint.contains("টেলিগ্রাম") ->
                pkg.contains("telegram")
            hint.contains("instagram") || hint.contains("ইনস্টাগ্রাম") ->
                pkg.contains("instagram")
            hint.contains("sms") || hint.contains("message") || hint.contains("মেসেজ") ->
                pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms")
            hint.contains("youtube") || hint.contains("ইউটিউব") ->
                pkg.contains("youtube")
            hint.contains("play") || hint.contains("প্লে স্টোর") ->
                pkg == "com.android.vending"
            hint.contains("chrome") || hint.contains("ক্রোম") ->
                pkg.contains("chrome")
            else -> pkg.contains(hint)
        }
    }

    suspend fun runTask(
        context: Context,
        apiKey: String,
        goal: String,
        appHint: String = "",
        recipientHint: String = "",
        messageHint: String = "",
        stopBeforeFinalSend: Boolean = false,
        maxSteps: Int = 12,
        onProgress: (String) -> Unit = {}
    ): AutonomousAgentResult {
        AppKnowledgeRegistry.init(context)
        val isAccEnabled = AccessibilityAutomationBus.syncPermissionState(context)
        val svc = AccessibilityAutomationBus.serviceInstance
        if (!isAccEnabled || svc == null) {
            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
            return AutonomousAgentResult(
                success = false,
                summaryBn = "স্ক্রিন দেখে কাজ করার জন্য Accessibility সার্ভিসটা চালু করা দরকার — সেটিংস খুলে দিয়েছি, অন করে দিলেই কাজ শুরু করব।"
            )
        }

        if (apiKey.isBlank()) {
            return AutonomousAgentResult(
                success = false,
                summaryBn = "কাজটা করতে Gemini API Key প্রয়োজন—দয়া করে চাবি আইকনে কী-টি সেট করে দিন।"
            )
        }

        val cleanGoal = goal.trim()
        val rawRec = recipientHint.trim()
        val profile = resolveSmartRecipientProfile(context, rawRec, cleanGoal, appHint)
        val isDeictic = profile.isDeictic
        val effectiveRecipient = if (isDeictic) "" else profile.primaryDisplayName.ifBlank { rawRec }
        val cleanMsg = messageHint.trim()

        // Step 0: Inspect current screen FIRST before deciding whether to switch apps!
        val initialObs = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
        val currentPkg = initialObs?.packageName.orEmpty()
        val ownPkg = context.packageName
        val targetAppToOpen = appHint.ifBlank { inferAppFromGoal(cleanGoal) }
        val isWhatsAppTarget = targetAppToOpen.lowercase().let {
            it.contains("whatsapp") || it.contains("হোয়াটসঅ্যাপ") || it.contains("হোয়াটসঅ্যাপ")
        } || currentPkg.contains("whatsapp", ignoreCase = true)

        // ULTRA-FAST DIRECT WHATSAPP DEEP-LINK JUMP:
        // If we already resolved the phone number (from Contacts or Self-ID memory) and we are not already in that open chat,
        // jump straight into the exact WhatsApp chat thread in 0 steps!
        val alreadyInMatchingOpenChat = initialObs != null &&
            findComposerNode(initialObs) != null &&
            (isDeictic || headerMatchesProfile(initialObs, profile))

        var launchedDirectDeepLink = false
        if (isWhatsAppTarget && !alreadyInMatchingOpenChat && profile.resolvedPhoneNumber.length >= 8) {
            val formattedPhone = normalizePhoneForWhatsApp(profile.resolvedPhoneNumber)
            if (formattedPhone.length >= 10) {
                onProgress("${profile.primaryDisplayName}-এর হোয়াটসঅ্যাপ চ্যাটে সরাসরি ঢুকছি...")
                launchedDirectDeepLink = withContext(Dispatchers.Main) {
                    try {
                        val uriStr = buildString {
                            append("whatsapp://send?phone=$formattedPhone")
                            if (cleanMsg.isNotBlank()) append("&text=${Uri.encode(cleanMsg)}")
                        }
                        val deepIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
                            setPackage(if (currentPkg == "com.whatsapp.w4b") "com.whatsapp.w4b" else "com.whatsapp")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(deepIntent)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                if (launchedDirectDeepLink) {
                    delay(620L)
                }
            }
        }

        val shouldLaunchTargetApp = !launchedDirectDeepLink && when {
            appHint.isBlank() || appHint.equals("CURRENT_SCREEN", ignoreCase = true) -> {
                currentPkg == ownPkg && targetAppToOpen.isNotBlank()
            }
            appPackageMatchesHint(currentPkg, appHint) -> false // Already in target app! Keep current chat/scroll intact!
            else -> true
        }

        if (shouldLaunchTargetApp && targetAppToOpen.isNotBlank()) {
            onProgress("$targetAppToOpen খুলছি...")
            val launched = withContext(Dispatchers.Main) {
                launchAppForAgent(context, targetAppToOpen)
            }
            if (launched) {
                delay(550L)
            }
        }

        // Warm up model list if not cached yet (non-fatal)
        if (GeminiModelResolver.cached == null) {
            withContext(Dispatchers.IO) { GeminiModelResolver.resolve(apiKey) }
        }

        // Pre-fetch live internet info if goal explicitly asks to send/use web info (e.g. weather, score, price, URL)
        val preFetchedWebContext = maybeResolveWebContextForGoal(context, apiKey, cleanGoal, cleanMsg)

        val stepsTaken = mutableListOf<String>()
        var extraWebContext = preFetchedWebContext
        var lastSignature = ""
        var sameStateRepeatCount = 0
        var lastDecisionKey = ""
        val inspectedUnknownPkgs = HashSet<String>()
        val tappedRowCoordinates = HashSet<String>()
        val triedSearchQueries = LinkedHashSet<String>()
        var triedNewChatFab = false
        var wrongChatBackCount = 0

        for (stepIdx in 1..maxSteps) {
            val obs = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
            if (obs == null) {
                delay(220L)
                continue
            }

            // Self-learning phase: if this app is unfamiliar, study how it works once and persist blueprint
            if (obs.packageName.isNotBlank() &&
                obs.packageName != ownPkg &&
                !AppKnowledgeRegistry.hasDeepKnowledge(obs.packageName) &&
                inspectedUnknownPkgs.add(obs.packageName)
            ) {
                onProgress("নতুন অ্যাপটির ডিজাইন বুঝে নিচ্ছি...")
                val previewBmp = withContext(Dispatchers.Main) { svc.captureRealScreenBitmapForAgent() }
                val learned = withContext(Dispatchers.IO) {
                    discoverUnknownAppBlueprint(apiKey, obs, previewBmp)
                }
                if (!learned.isNullOrBlank()) {
                    AppKnowledgeRegistry.saveLearnedAppBlueprint(context, obs.packageName, "AI-Learned: $learned")
                }
            }

            // ZERO-LLM FAST PATH #1: Already inside the right chat with bottom composer visible!
            if (cleanMsg.isNotBlank() && preFetchedWebContext.isBlank()) {
                val fastOpenRes = tryInstantOpenChatSend(
                    context = context,
                    svc = svc,
                    obs = obs,
                    cleanMsg = cleanMsg,
                    profile = profile,
                    effectiveRecipient = effectiveRecipient,
                    isDeictic = isDeictic,
                    stopBeforeFinalSend = stopBeforeFinalSend,
                    stepsTaken = stepsTaken
                )
                if (fastOpenRes != null) return fastOpenRes
            }

            val composerNow = findComposerNode(obs)
            val topSearchInput = findTopSearchEditableNode(obs)

            // AUTONOMOUS WRONG-CHAT EXIT:
            // If we are inside an open chat thread (`composerNow != null`), and the user asked for a specific
            // person or their own ID (`!isDeictic`), and the open chat header does NOT match, immediately navigate BACK to chat list!
            if (composerNow != null &&
                !isDeictic &&
                (profile.isSelfId || effectiveRecipient.isNotBlank()) &&
                !headerMatchesProfile(obs, profile) &&
                wrongChatBackCount < 2
            ) {
                wrongChatBackCount++
                onProgress("অন্য চ্যাট খোলা আছে, ব্যাকে গিয়ে '${profile.primaryDisplayName}' খুঁজছি...")
                val backBtn = findTopBackButton(obs)
                val backed = withContext(Dispatchers.Main) {
                    if (backBtn != null) {
                        svc.performAgentTap(backBtn.cx, backBtn.cy, backBtn.bestLabel.ifBlank { "Back" })
                    } else {
                        svc.performAgentGlobalAction("BACK")
                    }
                }
                stepsTaken.add("FastPath: Exited non-matching open chat '${headerContactName(obs)}' -> $backed")
                delay(320L)
                continue
            }

            // ZERO-LLM FAST PATH #2: Target contact / Self-ID row is visible on screen in [BODY] (chat list or search results)!
            if (!isDeictic &&
                (profile.isSelfId || effectiveRecipient.isNotBlank()) &&
                composerNow == null
            ) {
                val matchingRow = findBestMatchingContactRowInBody(obs, profile, topSearchInput?.bounds?.bottom ?: 0)
                val rowKey = matchingRow?.let { "${it.cx / 24},${it.cy / 24}:${it.bestLabel}" }.orEmpty()
                if (matchingRow != null && tappedRowCoordinates.add(rowKey)) {
                    onProgress("'${matchingRow.bestLabel.ifBlank { profile.primaryDisplayName }}' পাওয়া গেছে, চ্যাটে ঢুকছি...")
                    val tapped = withContext(Dispatchers.Main) {
                        svc.performAgentTap(matchingRow.cx, matchingRow.cy, matchingRow.bestLabel)
                    }
                    if (tapped) {
                        stepsTaken.add("FastPath: Tapped contact row '${matchingRow.bestLabel}' @(${matchingRow.cx},${matchingRow.cy})")
                        delay(360L)
                        continue
                    }
                }
            }

            // ZERO-LLM FAST PATH #3: AUTONOMOUS TOP SEARCH BAR & NEW-CHAT DECISION ENGINE
            // When in a chat app (WhatsApp/Messenger/Telegram) without composer open and target isn't visible on screen yet,
            // do NOT waste 4 seconds on an LLM call just to decide to use the top Search bar!
            // Immediately go to the top Search bar (or New Chat FAB -> top row for Self-ID) and search!
            if (!isDeictic &&
                (profile.isSelfId || effectiveRecipient.isNotBlank()) &&
                composerNow == null &&
                isMessagingAppPackage(obs.packageName)
            ) {
                val nextQueryToTry = profile.searchQueries.firstOrNull { it !in triedSearchQueries }
                val searchTrigger = topSearchInput ?: findTopSearchTriggerNode(obs)

                if (nextQueryToTry != null && searchTrigger != null) {
                    triedSearchQueries.add(nextQueryToTry)
                    onProgress("উপরে সার্চ বারে গিয়ে '$nextQueryToTry' লিখে আইডিটা খুঁজছি...")
                    AppKnowledgeRegistry.recordLearnedRole(
                        context,
                        obs.packageName,
                        "SEARCH",
                        searchTrigger.viewId,
                        searchTrigger.bestLabel,
                        searchTrigger.cx,
                        searchTrigger.cy
                    )
                    val searchedOk = withContext(Dispatchers.Main) {
                        svc.performAgentTypeText(
                            cx = searchTrigger.cx,
                            cy = searchTrigger.cy,
                            text = nextQueryToTry,
                            labelHint = searchTrigger.bestLabel.ifBlank { "Search" },
                            submitIme = true
                        )
                    }
                    stepsTaken.add("FastPath: Top Search Bar query='$nextQueryToTry' @(${searchTrigger.cx},${searchTrigger.cy}) -> $searchedOk")
                    delay(480L)
                    continue
                }

                // If in WhatsApp/chat app and either search trigger wasn't found or Self-ID "(You)" is in the New Chat contact list:
                // Tapping "New chat" FAB in WhatsApp ALWAYS shows "(You) Message yourself" as the very first contact row!
                if (!triedNewChatFab) {
                    val newChatFab = findNewChatFabNode(obs)
                    if (newChatFab != null) {
                        triedNewChatFab = true
                        onProgress("New Chat কন্টাক্ট লিস্ট খুলে আইডিটা বের করছি...")
                        val tappedFab = withContext(Dispatchers.Main) {
                            svc.performAgentTap(newChatFab.cx, newChatFab.cy, newChatFab.bestLabel.ifBlank { "New chat" })
                        }
                        if (tappedFab) {
                            // Reset one primary query so inside the New Chat screen it can also search if needed
                            profile.searchQueries.firstOrNull()?.let { triedSearchQueries.remove(it) }
                            stepsTaken.add("FastPath: Opened New Chat FAB @(${newChatFab.cx},${newChatFab.cy})")
                            delay(460L)
                            continue
                        }
                    }
                }
            }

            val sig = obs.signature()
            val screenChanged = sig != lastSignature
            val bitmap = withContext(Dispatchers.Main) { svc.captureRealScreenBitmapForAgent() }

            val stuckWarning = if (!screenChanged && stepIdx > 1) {
                sameStateRepeatCount++
                "WARNING: Screen did NOT change after '$lastDecisionKey'! " +
                    "Switch strategy immediately: tap top Search bar + type alternate query (${profile.searchQueries.joinToString("/")}), tap exact contact row in [BODY], tap New Chat button, SCROLL_DOWN, or press BACK."
            } else {
                sameStateRepeatCount = 0
                ""
            }
            lastSignature = sig

            if (sameStateRepeatCount >= 4) {
                return AutonomousAgentResult(
                    success = false,
                    summaryBn = "স্ক্রিনে কয়েকবার চেষ্টা করেও '$cleanGoal' এর জন্য সঠিক বাটন বা আইডি খুঁজে পাইনি (${obs.packageName})। একটু স্ক্রিনটা ঠিক জায়গায় এনে আবার বলুন তো।",
                    stepsTaken = stepsTaken,
                    finalPackage = obs.packageName
                )
            }

            val prompt = buildBrainPrompt(
                goal = cleanGoal,
                appHint = appHint,
                recipientHint = effectiveRecipient,
                profile = profile,
                isDeictic = isDeictic,
                messageHint = cleanMsg,
                stopBeforeFinalSend = stopBeforeFinalSend,
                stepIdx = stepIdx,
                maxSteps = maxSteps,
                obs = obs,
                appKnowledge = AppKnowledgeRegistry.guideForPackage(obs.packageName),
                webContext = extraWebContext,
                history = stepsTaken,
                stuckWarning = stuckWarning,
                installedAppsHint = InstalledAppsIndex.summary(context, 45)
            )

            val rawJson = withContext(Dispatchers.IO) {
                callVisionAgentBrain(apiKey, prompt, bitmap)
            }
            if (rawJson.isNullOrBlank()) {
                return AutonomousAgentResult(
                    success = false,
                    summaryBn = "এখন AI ব্রেইন থেকে রেসপন্স আসেনি—নেট কানেকশন বা Gemini API Key টা একটু দেখে নিন।",
                    stepsTaken = stepsTaken,
                    finalPackage = obs.packageName
                )
            }

            val decision = parseDecisionJson(rawJson)
            val actionUpper = decision.action.uppercase().trim()
            val statusUpper = decision.status.uppercase().trim()

            onProgress(decision.replyBn.ifBlank { decision.thought }.ifBlank { "কাজ করছি..." })

            when (statusUpper) {
                "DONE" -> {
                    return AutonomousAgentResult(
                        success = true,
                        summaryBn = decision.replyBn.ifBlank { "কাজটি হয়ে গেছে!" },
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
                "NEED_USER_CHOICE" -> {
                    return AutonomousAgentResult(
                        success = false,
                        needsUserChoice = true,
                        summaryBn = decision.replyBn.ifBlank { "এখানে কয়েকজনকে দেখা যাচ্ছে—ঠিক কাকে পাঠাব একটু বলে দিন?" },
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
                "FAILED" -> {
                    return AutonomousAgentResult(
                        success = false,
                        summaryBn = decision.replyBn.ifBlank { "এই স্ক্রিনে খুঁজে দেখলাম, কিন্তু টার্গেট আইডি বা অপশনটা পেলাম না।" },
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
                "READY_FOR_CONFIRMATION" -> {
                    val targetNode = obs.nodes.getOrNull(decision.nodeIndex) ?: findSendNode(obs)
                    val tx = (targetNode?.cx ?: decision.x).takeIf { it > 0 } ?: (obs.screenWidth * 0.88f).toInt()
                    val ty = (targetNode?.cy ?: decision.y).takeIf { it > 0 } ?: (obs.screenHeight * 0.90f).toInt()
                    val lbl = targetNode?.bestLabel?.ifBlank { "Send" } ?: "Send"
                    val bounds = targetNode?.bounds ?: Rect(tx - 60, ty - 45, tx + 60, ty + 45)
                    ConfirmationGateBus.lockTargetAndHighlight(
                        ctx = context,
                        buttonLabel = lbl,
                        x = tx.toFloat(),
                        y = ty.toFloat(),
                        bounds = bounds,
                        appPackageOrName = obs.packageName,
                        typedTextPreview = cleanMsg
                    )
                    return AutonomousAgentResult(
                        success = true,
                        needsUserConfirmation = true,
                        summaryBn = decision.replyBn.ifBlank {
                            "সব রেডি! '$lbl' বাটনে লক করে দিয়েছি—এখন কি পাঠিয়ে দেব?"
                        },
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
            }

            // Mid-task live internet search tool if Brain requested web facts!
            if (actionUpper == "SEARCH_INTERNET") {
                val q = decision.text.ifBlank { cleanGoal }
                onProgress("ইন্টারনেট থেকে তথ্য খুঁজে নিচ্ছি...")
                val webRes = AgentAbilitySwitchesBus.searchWebOrMapsInBackground(
                    context = context,
                    apiKey = apiKey,
                    query = q,
                    useMapsGrounding = false
                )
                extraWebContext = webRes.take(1400)
                stepsTaken.add("Step $stepIdx [SEARCH_INTERNET '$q'] -> fetched ${extraWebContext.length} chars")
                continue
            }

            // Batched TYPE_AND_SEND: Type into composer + immediately tap Send in ONE Brain roundtrip!
            if (actionUpper == "TYPE_AND_SEND") {
                val composerNode = obs.nodes.getOrNull(decision.nodeIndex) ?: findComposerNode(obs)
                val msgToType = decision.text.ifBlank { cleanMsg }
                val cx = composerNode?.cx ?: decision.x.takeIf { it > 0 } ?: (obs.screenWidth / 2)
                val cy = composerNode?.cy ?: decision.y.takeIf { it > 0 } ?: (obs.screenHeight * 0.88f).toInt()
                if (composerNode != null) {
                    AppKnowledgeRegistry.recordLearnedRole(context, obs.packageName, "COMPOSER", composerNode.viewId, composerNode.bestLabel, cx, cy)
                }
                val typedOk = withContext(Dispatchers.Main) {
                    svc.performAgentTypeText(
                        cx = cx,
                        cy = cy,
                        text = msgToType,
                        labelHint = composerNode?.bestLabel.orEmpty(),
                        submitIme = false
                    )
                }
                stepsTaken.add("Step $stepIdx [TYPE '$msgToType'] -> $typedOk")
                delay(160L)

                val obsAfterType = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() } ?: obs
                val sendNode = obsAfterType.nodes.getOrNull(decision.sendNodeIndex)?.takeIf {
                    isSendLikeNode(it, obsAfterType.screenWidth, obsAfterType.screenHeight)
                } ?: findSendNode(obsAfterType)

                if (stopBeforeFinalSend) {
                    val sx = (sendNode?.cx ?: (obsAfterType.screenWidth * 0.90f).toInt())
                    val sy = (sendNode?.cy ?: cy)
                    val sLbl = sendNode?.bestLabel?.ifBlank { "Send" } ?: "Send"
                    ConfirmationGateBus.lockTargetAndHighlight(
                        ctx = context,
                        buttonLabel = sLbl,
                        x = sx.toFloat(),
                        y = sy.toFloat(),
                        bounds = sendNode?.bounds ?: Rect(sx - 55, sy - 45, sx + 55, sy + 45),
                        appPackageOrName = obsAfterType.packageName,
                        typedTextPreview = msgToType
                    )
                    return AutonomousAgentResult(
                        success = true,
                        needsUserConfirmation = true,
                        summaryBn = "মেসেজ লেখা হয়ে গেছে ও Send বাটন লক করে দিয়েছি—এখন কি পাঠাব?",
                        stepsTaken = stepsTaken,
                        finalPackage = obsAfterType.packageName
                    )
                }

                if (sendNode != null) {
                    AppKnowledgeRegistry.recordLearnedRole(context, obsAfterType.packageName, "SEND", sendNode.viewId, sendNode.bestLabel, sendNode.cx, sendNode.cy)
                    val sentOk = withContext(Dispatchers.Main) {
                        svc.performAgentTap(sendNode.cx, sendNode.cy, sendNode.bestLabel)
                    }
                    stepsTaken.add("Step $stepIdx [TAP SEND '${sendNode.bestLabel}'] -> $sentOk")
                    delay(240L)
                    val obsAfterSend = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
                    val composerAfter = obsAfterSend?.let { findComposerNode(it) }
                    val cleared = composerAfter == null ||
                        composerAfter.text.isBlank() ||
                        composerAfter.text.equals(composerAfter.hintText, ignoreCase = true) ||
                        !composerAfter.text.contains(msgToType.take(12), ignoreCase = true)
                    if (sentOk && cleared) {
                        return AutonomousAgentResult(
                            success = true,
                            summaryBn = decision.replyBn.ifBlank { "হয়ে গেছে! \"$msgToType\" মেসেজটা পাঠিয়ে দিয়েছি।" },
                            stepsTaken = stepsTaken,
                            finalPackage = obsAfterSend?.packageName ?: obsAfterType.packageName
                        )
                    }
                }
                continue
            }

            // Standard single or 2-step chained UI action
            val targetNode = obs.nodes.getOrNull(decision.nodeIndex)
            val cx = targetNode?.cx ?: decision.x
            val cy = targetNode?.cy ?: decision.y
            val nodeLabel = targetNode?.bestLabel.orEmpty()
            lastDecisionKey = "$actionUpper#${decision.nodeIndex}@$cx,$cy:${decision.text.take(20)}"

            if (targetNode != null) {
                val idLow = targetNode.viewId.lowercase()
                val lblLow = nodeLabel.lowercase()
                when {
                    idLow.contains("search") || lblLow.contains("search") || lblLow.contains("সার্চ") ->
                        AppKnowledgeRegistry.recordLearnedRole(context, obs.packageName, "SEARCH", targetNode.viewId, nodeLabel, cx, cy)
                    targetNode.editable ->
                        AppKnowledgeRegistry.recordLearnedRole(context, obs.packageName, "COMPOSER", targetNode.viewId, nodeLabel, cx, cy)
                    isSendLikeNode(targetNode, obs.screenWidth, obs.screenHeight) ->
                        AppKnowledgeRegistry.recordLearnedRole(context, obs.packageName, "SEND", targetNode.viewId, nodeLabel, cx, cy)
                }
            }

            val execOk = executeAtomicUiAction(context, svc, obs, actionUpper, targetNode, cx, cy, decision.text, nodeLabel, decision.submitEnter)
            stepsTaken.add(
                "Step $stepIdx [$actionUpper${if (nodeLabel.isNotBlank()) " '$nodeLabel'" else ""}${if (decision.text.isNotBlank()) " text='${decision.text}'" else ""}] -> ${if (execOk) "ok" else "failed"}"
            )

            // Execute optional chained next_action in the SAME Brain roundtrip (e.g. TAP Search + TYPE contact name!)
            val nextUpper = decision.nextAction.uppercase().trim()
            if (execOk && (nextUpper == "TYPE_TEXT" || nextUpper == "TYPE" || nextUpper == "TAP_NODE" || nextUpper == "TAP")) {
                delay(280L)
                val midObs = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
                if (midObs != null) {
                    if (nextUpper == "TYPE_TEXT" || nextUpper == "TYPE") {
                        val editTarget = midObs.nodes.getOrNull(decision.nextNodeIndex)?.takeIf { it.editable }
                            ?: midObs.nodes.firstOrNull { it.editable && it.focused }
                            ?: midObs.nodes.firstOrNull { it.editable }
                        if (editTarget != null) {
                            val typeOk2 = withContext(Dispatchers.Main) {
                                svc.performAgentTypeText(
                                    cx = editTarget.cx,
                                    cy = editTarget.cy,
                                    text = decision.nextText.ifBlank { effectiveRecipient },
                                    labelHint = editTarget.bestLabel,
                                    submitIme = decision.nextSubmitEnter
                                )
                            }
                            stepsTaken.add("Step ${stepIdx}b [CHAINED TYPE '${decision.nextText}'] -> $typeOk2")
                            delay(if (decision.nextSubmitEnter) 460L else 260L)
                            continue
                        }
                    } else if (nextUpper == "TAP_NODE" || nextUpper == "TAP") {
                        val tapTarget = midObs.nodes.getOrNull(decision.nextNodeIndex)
                        if (tapTarget != null) {
                            withContext(Dispatchers.Main) {
                                svc.performAgentTap(tapTarget.cx, tapTarget.cy, tapTarget.bestLabel)
                            }
                            stepsTaken.add("Step ${stepIdx}b [CHAINED TAP '${tapTarget.bestLabel}']")
                            delay(240L)
                            continue
                        }
                    }
                }
            }

            // Snappy settle wait before next observe
            val settleMs = when (actionUpper) {
                "OPEN_APP" -> 520L
                "SCROLL_DOWN", "SCROLL_UP", "SCROLL_LEFT", "SCROLL_RIGHT" -> 320L
                "TYPE_TEXT", "TYPE" -> if (decision.submitEnter) 440L else 200L
                "WAIT" -> 650L
                else -> 240L
            }
            delay(settleMs)
        }

        val finalPkg = withContext(Dispatchers.Main) { svc.captureRichScreenObservation()?.packageName.orEmpty() }
        return AutonomousAgentResult(
            success = false,
            summaryBn = "বেশ কয়েকবার চেষ্টা করলাম, কিন্তু কাজটা পুরো শেষ করতে পারিনি। স্ক্রিনে আরেকটু সামনে আনলে এখনই করে দিচ্ছি।",
            stepsTaken = stepsTaken,
            finalPackage = finalPkg
        )
    }

    private suspend fun executeAtomicUiAction(
        context: Context,
        svc: CubeAccessibilityService,
        obs: RichScreenObservation,
        actionUpper: String,
        targetNode: RichUiNode?,
        cx: Int,
        cy: Int,
        text: String,
        nodeLabel: String,
        submitEnter: Boolean
    ): Boolean = when (actionUpper) {
        "TAP_NODE", "TAP", "CLICK" -> {
            if (cx > 0 && cy > 0) {
                withContext(Dispatchers.Main) { svc.performAgentTap(cx, cy, nodeLabel) }
            } else false
        }
        "TYPE_TEXT", "TYPE" -> {
            val tx = if (cx > 0) cx else (obs.screenWidth / 2)
            val ty = if (cy > 0) cy else (obs.screenHeight * 0.85f).toInt()
            withContext(Dispatchers.Main) {
                svc.performAgentTypeText(
                    cx = tx,
                    cy = ty,
                    text = text,
                    labelHint = targetNode?.bestLabel ?: nodeLabel,
                    submitIme = submitEnter
                )
            }
        }
        "SCROLL_DOWN" -> withContext(Dispatchers.Main) { svc.performAgentScroll("DOWN") }
        "SCROLL_UP" -> withContext(Dispatchers.Main) { svc.performAgentScroll("UP") }
        "SCROLL_LEFT" -> withContext(Dispatchers.Main) { svc.performAgentScroll("LEFT") }
        "SCROLL_RIGHT" -> withContext(Dispatchers.Main) { svc.performAgentScroll("RIGHT") }
        "OPEN_APP" -> withContext(Dispatchers.Main) { launchAppForAgent(context, text) }
        "BACK" -> withContext(Dispatchers.Main) { svc.performAgentGlobalAction("BACK") }
        "HOME" -> withContext(Dispatchers.Main) { svc.performAgentGlobalAction("HOME") }
        "WAIT" -> true
        else -> false
    }

    /**
     * Zero-LLM Instant Open-Chat Send Fast-Path:
     * Executes in ~0.8-1.5s whenever the target chat composer is ALREADY open on screen!
     */
    private suspend fun tryInstantOpenChatSend(
        context: Context,
        svc: CubeAccessibilityService,
        obs: RichScreenObservation,
        cleanMsg: String,
        profile: ResolvedRecipientProfile,
        effectiveRecipient: String,
        isDeictic: Boolean,
        stopBeforeFinalSend: Boolean,
        stepsTaken: MutableList<String>
    ): AutonomousAgentResult? {
        val composer = findComposerNode(obs) ?: return null
        val isOpenChatMatch = isDeictic ||
            (effectiveRecipient.isBlank() && !profile.isSelfId) ||
            headerMatchesProfile(obs, profile)
        if (!isOpenChatMatch) return null

        AppKnowledgeRegistry.recordLearnedRole(context, obs.packageName, "COMPOSER", composer.viewId, composer.bestLabel, composer.cx, composer.cy)

        val currentText = composer.text.trim()
        val alreadyTyped = currentText.isNotBlank() &&
            !currentText.equals(composer.hintText.trim(), ignoreCase = true) &&
            currentText.contains(cleanMsg, ignoreCase = true)

        if (!alreadyTyped) {
            val typedOk = withContext(Dispatchers.Main) {
                svc.performAgentTypeText(
                    cx = composer.cx,
                    cy = composer.cy,
                    text = cleanMsg,
                    labelHint = composer.bestLabel,
                    submitIme = false
                )
            }
            if (!typedOk) return null
            stepsTaken.add("FastPath: Typed message directly into composer '${composer.bestLabel}'")
            delay(160L)
        }

        val obsAfterType = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() } ?: obs
        val updatedComposer = findComposerNode(obsAfterType)
        val verifiedTyped = updatedComposer?.text.orEmpty().contains(cleanMsg.take(12), ignoreCase = true)
        if (!verifiedTyped) return null // Let Gemini Vision inspect if custom UI rejected text

        val sendNode = findSendNode(obsAfterType) ?: return null
        AppKnowledgeRegistry.recordLearnedRole(context, obsAfterType.packageName, "SEND", sendNode.viewId, sendNode.bestLabel, sendNode.cx, sendNode.cy)

        if (stopBeforeFinalSend) {
            ConfirmationGateBus.lockTargetAndHighlight(
                ctx = context,
                buttonLabel = sendNode.bestLabel.ifBlank { "Send" },
                x = sendNode.cx.toFloat(),
                y = sendNode.cy.toFloat(),
                bounds = sendNode.bounds,
                appPackageOrName = obsAfterType.packageName,
                typedTextPreview = cleanMsg
            )
            return AutonomousAgentResult(
                success = true,
                needsUserConfirmation = true,
                summaryBn = "সব রেডি! মেসেজ লিখে Send বাটনে লক করে দিয়েছি—পাঠিয়ে দেব?",
                stepsTaken = stepsTaken,
                finalPackage = obsAfterType.packageName
            )
        }

        val tappedSend = withContext(Dispatchers.Main) {
            svc.performAgentTap(sendNode.cx, sendNode.cy, sendNode.bestLabel)
        }
        if (!tappedSend) return null
        stepsTaken.add("FastPath: Tapped Send '${sendNode.bestLabel}' @(${sendNode.cx},${sendNode.cy})")
        delay(220L)

        val obsAfterSend = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
        val composerAfter = obsAfterSend?.let { findComposerNode(it) }
        val cleared = composerAfter == null ||
            composerAfter.text.isBlank() ||
            composerAfter.text.equals(composerAfter.hintText, ignoreCase = true) ||
            !composerAfter.text.contains(cleanMsg.take(12), ignoreCase = true)
        if (!cleared) return null

        val targetWho = headerContactName(obs).ifBlank { effectiveRecipient }.ifBlank { "উনাকে" }
        return AutonomousAgentResult(
            success = true,
            summaryBn = "হয়ে গেছে! $targetWho কে \"$cleanMsg\" পাঠিয়ে দিয়েছি।",
            stepsTaken = stepsTaken,
            finalPackage = obsAfterSend?.packageName ?: obsAfterType.packageName
        )
    }

    private fun findComposerNode(obs: RichScreenObservation): RichUiNode? {
        val minComposerY = (obs.screenHeight * 0.40f).toInt()
        return obs.nodes
            .filter { n ->
                n.editable && n.cy >= minComposerY &&
                    !n.viewId.lowercase().contains("search") &&
                    !n.hintText.lowercase().contains("search") &&
                    !n.contentDesc.lowercase().contains("search")
            }
            .sortedByDescending { n ->
                var score = n.cy
                val meta = "${n.viewId} ${n.hintText} ${n.contentDesc}".lowercase()
                if (meta.contains("entry") || meta.contains("composer") || meta.contains("message") || meta.contains("chat_input")) {
                    score += 1000
                }
                score
            }
            .firstOrNull()
    }

    private fun isSendLikeNode(n: RichUiNode, screenWidth: Int, screenHeight: Int): Boolean {
        if (!n.clickable) return false
        val minSendY = (screenHeight * 0.40f).toInt()
        if (n.cy < minSendY) return false
        val idLow = n.viewId.lowercase()
        val lblLow = "${n.text} ${n.contentDesc}".lowercase().trim()
        if (idLow.contains("voice") || idLow.contains("record") || lblLow.contains("voice note") || lblLow.contains("record audio")) {
            return false
        }
        return idLow.contains("send") ||
            lblLow == "send" ||
            lblLow == "পাঠান" ||
            lblLow.startsWith("send ") ||
            (n.cx >= (screenWidth * 0.78f).toInt() && lblLow.contains("send"))
    }

    private fun findSendNode(obs: RichScreenObservation): RichUiNode? =
        obs.nodes.firstOrNull { isSendLikeNode(it, obs.screenWidth, obs.screenHeight) }

    private fun headerMatchesProfile(obs: RichScreenObservation, profile: ResolvedRecipientProfile): Boolean {
        val headerNodes = obs.nodes.filter { n ->
            n.zone == "HEADER" && !n.editable && !n.viewId.lowercase().contains("search")
        }
        if (headerNodes.isEmpty()) return false
        val candidates = (profile.rowMatchKeywords + profile.searchQueries + listOf(profile.primaryDisplayName))
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 }
            .distinct()
        if (candidates.isEmpty()) return false
        return headerNodes.any { n ->
            val combined = "${n.text} ${n.contentDesc}".lowercase()
            candidates.any { kw -> combined.contains(kw) }
        }
    }

    private fun headerContactName(obs: RichScreenObservation): String {
        val candidate = obs.nodes.firstOrNull { n ->
            n.zone == "HEADER" && !n.editable && !n.viewId.lowercase().contains("search") &&
                n.text.trim().length in 2..36 &&
                !n.text.equals("WhatsApp", ignoreCase = true) &&
                !n.text.equals("Messenger", ignoreCase = true) &&
                !n.text.equals("Telegram", ignoreCase = true) &&
                !n.text.equals("Instagram", ignoreCase = true)
        }
        return candidate?.text?.trim().orEmpty()
    }

    /**
     * Finds the best matching contact or Self-ID row in [BODY] (chat list, search results, or New Chat list).
     * Never clicks the top search bar, filter chips ("All", "Unread", "Groups"), or section headers.
     */
    private fun findBestMatchingContactRowInBody(
        obs: RichScreenObservation,
        profile: ResolvedRecipientProfile,
        searchBarBottomY: Int
    ): RichUiNode? {
        val keywords = (profile.rowMatchKeywords + profile.searchQueries + listOf(profile.primaryDisplayName))
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 }
            .distinct()
        if (keywords.isEmpty()) return null

        val minRowY = maxOf((obs.screenHeight * 0.14f).toInt(), searchBarBottomY + 12)
        val maxRowY = (obs.screenHeight * 0.88f).toInt()

        val candidates = obs.nodes.filter { n ->
            if (n.editable) return@filter false
            if (n.cy !in minRowY..maxRowY) return@filter false
            val idLow = n.viewId.lowercase()
            if (idLow.contains("search") || idLow.contains("filter") || idLow.contains("tab") || idLow.contains("fab")) {
                return@filter false
            }
            val label = n.bestLabel.trim().lowercase()
            if (label.isBlank() || label in NON_CONTACT_ROW_LABELS) return@filter false
            val fullText = "${n.text} ${n.contentDesc}".lowercase()
            keywords.any { kw -> fullText.contains(kw) }
        }

        if (candidates.isEmpty()) return null

        // Score candidates: exact contact-name ViewIds (`conversations_row_contact_name`, `contactpicker_row_name`),
        // `(You)` / `Message yourself` for Self-ID, and exact/prefix matches win immediately.
        return candidates.maxByOrNull { n ->
            var score = 0
            val idLow = n.viewId.lowercase()
            val txtLow = n.text.trim().lowercase()
            val descLow = n.contentDesc.trim().lowercase()
            val combined = "$txtLow $descLow"

            if (n.clickable) score += 200
            if (idLow.contains("conversations_row_contact_name") ||
                idLow.contains("contactpicker_row_name") ||
                idLow.contains("contact_name") ||
                idLow.contains("row_content") ||
                idLow.contains("chat_list")
            ) {
                score += 450
            }
            if (profile.isSelfId && (
                    combined.contains("(you)") ||
                        combined.contains("message yourself") ||
                        combined.contains("নিজেকে")
                    )
            ) {
                score += 700
            }
            for (kw in keywords) {
                if (txtLow == kw || descLow == kw) score += 500
                else if (txtLow.startsWith(kw) || descLow.startsWith(kw)) score += 300
                else if (combined.contains(kw)) score += 120
            }
            // Prefer upper result rows when scores are close
            score - (n.cy / 20)
        }
    }

    private fun findTopSearchEditableNode(obs: RichScreenObservation): RichUiNode? {
        val maxSearchY = (obs.screenHeight * 0.32f).toInt()
        return obs.nodes.firstOrNull { n ->
            n.editable && n.cy <= maxSearchY && (
                n.viewId.lowercase().contains("search") ||
                    n.hintText.lowercase().contains("search") ||
                    n.hintText.contains("খুঁজুন") ||
                    n.hintText.contains("সার্চ") ||
                    n.text.lowercase().contains("search") ||
                    n.zone == "HEADER"
                )
        }
    }

    private fun findTopSearchTriggerNode(obs: RichScreenObservation): RichUiNode? {
        val maxSearchY = (obs.screenHeight * 0.30f).toInt()
        return obs.nodes
            .filter { n ->
                n.cy <= maxSearchY && (
                    n.viewId.lowercase().let { id ->
                        id.contains("menuitem_search") ||
                            id.contains("search_bar") ||
                            id.contains("search_src_text") ||
                            id.contains("search_box") ||
                            id.contains("menu_search") ||
                            id.contains("action_search") ||
                            id.endsWith("/search")
                    } ||
                        n.bestLabel.lowercase().let { lbl ->
                            lbl == "search" ||
                                lbl == "সার্চ" ||
                                lbl == "খুঁজুন" ||
                                lbl.contains("ask meta ai or search") ||
                                lbl.contains("search…") ||
                                lbl.contains("search...")
                        }
                    )
            }
            .minByOrNull { it.cy }
    }

    private fun findNewChatFabNode(obs: RichScreenObservation): RichUiNode? {
        return obs.nodes.firstOrNull { n ->
            val idLow = n.viewId.lowercase()
            val lblLow = "${n.text} ${n.contentDesc}".lowercase()
            n.clickable && !n.editable && (
                idLow.endsWith(":id/fab") ||
                    idLow.contains("new_chat") ||
                    lblLow.contains("new chat") ||
                    lblLow.contains("নতুন চ্যাট") ||
                    lblLow.contains("select contact")
                )
        }
    }

    private fun findTopBackButton(obs: RichScreenObservation): RichUiNode? {
        val maxHeaderY = (obs.screenHeight * 0.20f).toInt()
        val maxLeftX = (obs.screenWidth * 0.28f).toInt()
        return obs.nodes.firstOrNull { n ->
            n.clickable && n.cy <= maxHeaderY && n.cx <= maxLeftX && (
                n.viewId.lowercase().contains("back") ||
                    n.viewId.lowercase().contains("up") ||
                    n.contentDesc.lowercase().contains("navigate up") ||
                    n.contentDesc.lowercase().contains("back") ||
                    n.contentDesc.contains("ফিরে")
                )
        }
    }

    private fun isMessagingAppPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("whatsapp") ||
            p.contains("orca") ||
            p.contains("telegram") ||
            p.contains("instagram") ||
            p.contains("messaging") ||
            p.contains("mms")
    }

    private fun normalizePhoneForWhatsApp(rawPhone: String): String {
        val digits = rawPhone.filter { it.isDigit() }
        return when {
            digits.startsWith("8801") && digits.length == 13 -> digits
            digits.startsWith("01") && digits.length == 11 -> "88$digits"
            digits.length >= 10 -> digits
            else -> ""
        }
    }

    private suspend fun maybeResolveWebContextForGoal(
        context: Context,
        apiKey: String,
        goal: String,
        messageHint: String
    ): String {
        if (!AgentAbilitySwitchesBus.state.value.backgroundWebSearch) return ""
        val combined = "$goal $messageHint".lowercase()
        val hasUrl = Regex("https?://\\S+").containsMatchIn(combined)
        val asksLiveInfo = hasUrl || listOf(
            "আজকের", "আবহাওয়া", "আবহাওয়া", "খবর", "দাম", "কত টাকা", "ম্যাচ", "স্কোর",
            "weather", "news", "price", "score", "rate", "search online", "ইন্টারনেট"
        ).any { combined.contains(it) }
        if (!asksLiveInfo) return ""
        return AgentAbilitySwitchesBus.searchWebOrMapsInBackground(
            context = context,
            apiKey = apiKey,
            query = goal,
            useMapsGrounding = false
        ).take(1200)
    }

    private fun discoverUnknownAppBlueprint(
        apiKey: String,
        obs: RichScreenObservation,
        bitmap: Bitmap?
    ): String? {
        val discoveryPrompt = buildString {
            appendLine("Analyze this unfamiliar Android app screen concisely in 2-3 sentences:")
            appendLine("1. What app is this and what is its main purpose?")
            appendLine("2. Where are the main Search, Menu/Tabs, Input/Composer, and Primary Action controls?")
            appendLine()
            appendLine(obs.toPromptText().take(2400))
        }
        val resolved = GeminiModelResolver.cached?.brain.orEmpty()
        val ordered = (resolved.filter { it.contains("flash", ignoreCase = true) } +
            listOf("models/gemini-2.5-flash") +
            resolved).distinct()
        val parts = JSONArray().put(JSONObject().put("text", discoveryPrompt))
        if (bitmap != null && !bitmap.isRecycled) {
            appendScaledBitmapPart(parts, bitmap, maxDim = 640, quality = 62)
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", 220))
        for (model in ordered.take(2)) {
            try {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/$model:generateContent?key=$apiKey")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 4500
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val resp = JSONObject(conn.inputStream.bufferedReader().readText())
                    val text = resp.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        ?.trim()
                    if (!text.isNullOrBlank()) return text
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun isDeicticReferenceInGoal(goal: String): Boolean {
        val lower = goal.lowercase()
        if (isSelfRecipientReference("", lower)) return false
        return DEICTIC_TERMS.any { lower.contains(it) }
    }

    private fun inferAppFromGoal(goal: String): String {
        val lower = goal.lowercase()
        return when {
            lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ") || lower.contains("হোয়াটসঅ্যাপ") -> "WhatsApp"
            lower.contains("messenger") || lower.contains("মেসেঞ্জার") -> "Messenger"
            lower.contains("telegram") || lower.contains("টেলিগ্রাম") -> "Telegram"
            lower.contains("instagram") || lower.contains("ইনস্টাগ্রাম") -> "Instagram"
            lower.contains("facebook") || lower.contains("ফেসবুক") -> "Facebook"
            lower.contains("youtube") || lower.contains("ইউটিউব") -> "YouTube"
            lower.contains("play store") || lower.contains("প্লে স্টোর") -> "Play Store"
            else -> ""
        }
    }

    private fun launchAppForAgent(context: Context, appName: String): Boolean {
        val lower = appName.trim().lowercase()
        if (lower.isBlank()) return false
        val pm = context.packageManager

        val priorityPkgs = when {
            lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ") || lower.contains("হোয়াটসঅ্যাপ") ->
                listOf("com.whatsapp", "com.whatsapp.w4b")
            lower.contains("messenger") || lower.contains("মেসেঞ্জার") ->
                listOf("com.facebook.orca")
            lower.contains("telegram") || lower.contains("টেলিগ্রাম") ->
                listOf("org.telegram.messenger", "org.telegram.messenger.web")
            lower.contains("instagram") || lower.contains("ইনস্টাগ্রাম") ->
                listOf("com.instagram.android")
            lower.contains("facebook") || lower.contains("ফেসবুক") ->
                listOf("com.facebook.katana")
            lower.contains("youtube") || lower.contains("ইউটিউব") ->
                listOf("com.google.android.youtube")
            lower.contains("play") || lower.contains("প্লে স্টোর") ->
                listOf("com.android.vending")
            lower.contains("sms") || lower.contains("messages") ->
                listOf("com.google.android.apps.messaging")
            else -> emptyList()
        }

        for (pkg in priorityPkgs) {
            try {
                val launch = pm.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    return true
                }
            } catch (_: Exception) {}
        }

        val installed = InstalledAppsIndex.search(context, appName)
        for (app in installed) {
            try {
                val launch = pm.getLaunchIntentForPackage(app.pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    return true
                }
            } catch (_: Exception) {}
        }

        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(appName)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (_: Exception) { false }
        }
        return false
    }

    private fun buildBrainPrompt(
        goal: String,
        appHint: String,
        recipientHint: String,
        profile: ResolvedRecipientProfile,
        isDeictic: Boolean,
        messageHint: String,
        stopBeforeFinalSend: Boolean,
        stepIdx: Int,
        maxSteps: Int,
        obs: RichScreenObservation,
        appKnowledge: String,
        webContext: String,
        history: List<String>,
        stuckWarning: String,
        installedAppsHint: String
    ): String = buildString {
        appendLine("USER GOAL: $goal")
        if (appHint.isNotBlank()) appendLine("TARGET_APP_HINT: $appHint")
        when {
            isDeictic -> {
                appendLine("RECIPIENT_CONTEXT: Deictic ('একে/ওকে/this person') -> Read the open screen header/profile! Do NOT search 'একে' or 'ওকে'.")
            }
            profile.isSelfId -> {
                appendLine("RECIPIENT_CONTEXT: SELF_ID (Boss wants to message their OWN account/ID!).")
                appendLine("HOW_TO_FIND_SELF_ID: Look for row containing '(You)', 'Message yourself', 'নিজেকে', or Boss name '${profile.primaryDisplayName}'. If not visible, tap Top Search bar and search '${profile.searchQueries.joinToString("', '")}', OR tap 'New chat' FAB (where '(You) Message yourself' is row #1).")
            }
            recipientHint.isNotBlank() -> {
                appendLine("TARGET_RECIPIENT_OR_ID: $recipientHint")
                if (profile.searchQueries.isNotEmpty()) {
                    appendLine("CANDIDATE_SEARCH_QUERIES (English + Bangla): ${profile.searchQueries.joinToString(", ")}")
                }
            }
        }
        if (messageHint.isNotBlank()) appendLine("MESSAGE_TO_SEND: $messageHint")
        appendLine("FINAL_SEND_MODE: ${if (stopBeforeFinalSend) "STOP_AND_LOCK_ON_SEND_BUTTON" else "AUTO_COMPLETE (use TYPE_AND_SEND when composer+send or open composer is ready)"}")
        appendLine("STEP: $stepIdx / $maxSteps")
        if (appKnowledge.isNotBlank()) {
            appendLine("APP_KNOWLEDGE_BLUEPRINT: $appKnowledge")
        }
        if (webContext.isNotBlank()) {
            appendLine("LIVE_INTERNET_FACTS: $webContext")
        }
        if (installedAppsHint.isNotBlank()) {
            appendLine("INSTALLED_APPS: $installedAppsHint")
        }
        appendLine()
        appendLine("=== CURRENT LIVE SCREEN UI TREE ===")
        appendLine(obs.toPromptText())
        if (history.isNotEmpty()) {
            appendLine("=== PREVIOUS STEPS TAKEN ===")
            history.takeLast(6).forEach { appendLine("- $it") }
        }
        if (stuckWarning.isNotBlank()) {
            appendLine()
            appendLine(stuckWarning)
        }
    }

    private fun appendScaledBitmapPart(parts: JSONArray, bitmap: Bitmap, maxDim: Int, quality: Int) {
        val bos = ByteArrayOutputStream()
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val scaled = if (w > maxDim || h > maxDim) {
            val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
            Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
        } else bitmap
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        parts.put(
            JSONObject().put(
                "inlineData",
                JSONObject()
                    .put("mimeType", "image/jpeg")
                    .put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
            )
        )
    }

    private fun callVisionAgentBrain(apiKey: String, prompt: String, bitmap: Bitmap?): String? {
        val resolved = GeminiModelResolver.cached?.brain.orEmpty()
        // Prioritize fast, confirmed Flash models first to eliminate 404 roundtrip delays
        val ordered = (
            listOf("models/gemini-2.5-flash") +
                resolved.filter { it.contains("flash", ignoreCase = true) } +
                FAST_VISION_MODELS +
                resolved
            ).distinct()

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (bitmap != null && !bitmap.isRecycled) {
            appendScaledBitmapPart(parts, bitmap, maxDim = 720, quality = 68)
        }

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                )
            )
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.12)
                    .put("maxOutputTokens", 700)
                    .put("responseMimeType", "application/json")
            )

        for (model in ordered.take(4)) {
            try {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/$model:generateContent?key=$apiKey")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 20000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val resp = JSONObject(conn.inputStream.bufferedReader().readText())
                    val respParts = resp.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?: continue
                    val sb = StringBuilder()
                    for (i in 0 until respParts.length()) {
                        sb.append(respParts.optJSONObject(i)?.optString("text").orEmpty())
                    }
                    val txt = sb.toString().trim()
                    if (txt.isNotBlank()) return txt
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun parseDecisionJson(raw: String): AutonomousAgentDecision {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonStr = Regex("\\{[\\s\\S]*}").find(cleaned)?.value ?: cleaned
        return try {
            val obj = JSONObject(jsonStr)
            AutonomousAgentDecision(
                thought = obj.optString("thought").trim(),
                status = obj.optString("status", "CONTINUE").trim(),
                action = obj.optString("action", "NONE").trim(),
                nodeIndex = obj.optInt("node_index", -1),
                sendNodeIndex = obj.optInt("send_node_index", -1),
                x = obj.optInt("x", -1),
                y = obj.optInt("y", -1),
                text = obj.optString("text"),
                submitEnter = obj.optBoolean("submit_enter", false),
                nextAction = obj.optString("next_action").trim(),
                nextNodeIndex = obj.optInt("next_node_index", -1),
                nextText = obj.optString("next_text"),
                nextSubmitEnter = obj.optBoolean("next_submit_enter", false),
                appName = obj.optString("app_name").trim(),
                replyBn = obj.optString("reply_bn").trim()
            )
        } catch (_: Exception) {
            AutonomousAgentDecision(
                thought = "JSON parse fallback",
                status = "FAILED",
                action = "NONE",
                replyBn = "স্ক্রিন বিশ্লেষণ করতে একটু সমস্যা হয়েছিল—আরেকবার বলুন তো।"
            )
        }
    }

    private const val SYSTEM_PROMPT = """
You are an ultra-fast, perceptive Autonomous Android UI Agent with a natural human personality.
You inspect BOTH the live screenshot AND structured Accessibility nodes (`[HEADER]`, `[BODY]`, `[BOTTOM]` with `#index`, `text`, `desc`, `hint`, `id`, `@(x,y)`) PLUS `APP_KNOWLEDGE_BLUEPRINT` (what you already learned about how this app works).

ACT FAST USING MINIMAL ROUNDTRIPS:
1. UNDERSTAND APP & SCREEN FIRST:
   - Read `APP_KNOWLEDGE_BLUEPRINT`, `FOREGROUND_APP`, and `[HEADER]` first.
   - Deictic pronoun ("একে", "ওকে", "তাকে", "এই আইডিতে"): if a chat composer in `[BOTTOM]` is open -> use `TYPE_AND_SEND` immediately! If on a user's PROFILE -> tap "Message" (`TYPE` not needed yet). If on a list with no open person -> `status="NEED_USER_CHOICE"`.
   - SELF-ID ("আমার আইডি", "আমার আইডিতে মেসেজ দেও", "নিজের আইডিতে"): NEVER search the literal Bangla phrase "আমার আইডি"! Instead look for the row containing `(You)`, `Message yourself`, `নিজেকে`, or the user's own name from `CANDIDATE_SEARCH_QUERIES`, OR tap the top Search bar and search `(You)` / Boss's English name, OR tap the `New chat` FAB (`com.whatsapp:id/fab`) where `(You) Message yourself` is ALWAYS the first contact row!
2. FAST BATCHED NAVIGATION TO FIND RECIPIENT/ID:
   - If the target ID/contact row is visible in `[BODY]` -> `TAP_NODE` on that row! (Never tap a Search bar or filter chip like "All"/"Unread" when trying to open a contact row).
   - If NOT visible in `[BODY]` -> immediately go to the TOP SEARCH BAR (`menuitem_search`, `search_bar`, `search_src_text`, "Search", "Ask Meta AI or Search"): use `action="TYPE_TEXT"` on the search bar/icon with `text="<best English or Bangla query from CANDIDATE_SEARCH_QUERIES>"` and `submit_enter=true` (our executor automatically taps to expand the search bar and types the query!).
   - If a Bangla query returned no results, try the English phonetic query from `CANDIDATE_SEARCH_QUERIES`.
   - When user implies scrolling or search doesn't fit -> `SCROLL_DOWN`. If wrong popup/chat opened -> `BACK`.
   - Need live internet info to type or answer? Use `action="SEARCH_INTERNET"` with `text="<search query or URL>"`.
3. COMPOSER vs SEARCH BAR (NEVER CONFUSE):
   - `[HEADER]` / `*search*` input = ONLY for contact names / search queries.
   - `[BOTTOM]` / `entry` / `composer` / `message` editable = ONLY for the clean message text!
4. SINGLE-TURN `TYPE_AND_SEND` (FASTEST SEND):
   - When the `[BOTTOM]` message composer is visible AND `FINAL_SEND_MODE == AUTO_COMPLETE`: use `action="TYPE_AND_SEND"`, `node_index=<composer_index>`, `send_node_index=<send_btn_if_visible_else_-1>`, `text="<clean message>"`, and provide a warm, natural human `reply_bn`!
   - When `FINAL_SEND_MODE == STOP_AND_LOCK_ON_SEND_BUTTON`: if composer does NOT have the message yet -> `TYPE_AND_SEND` (it automatically stops and locks the Neon Ring on Send!); if composer ALREADY has it -> `status="READY_FOR_CONFIRMATION"`, `node_index=<send_btn>`.
   - If message already appears in chat history / composer cleared after Send -> `status="DONE"`.

OUTPUT STRICTLY ONE JSON OBJECT:
{
  "thought": "Short natural note of what is on screen",
  "status": "CONTINUE" | "DONE" | "READY_FOR_CONFIRMATION" | "NEED_USER_CHOICE" | "FAILED",
  "action": "TAP_NODE" | "TYPE_TEXT" | "TYPE_AND_SEND" | "SCROLL_DOWN" | "SCROLL_UP" | "SCROLL_LEFT" | "SCROLL_RIGHT" | "OPEN_APP" | "BACK" | "HOME" | "SEARCH_INTERNET" | "WAIT" | "NONE",
  "node_index": 0,
  "send_node_index": -1,
  "x": -1,
  "y": -1,
  "text": "",
  "submit_enter": false,
  "next_action": "",
  "next_node_index": -1,
  "next_text": "",
  "next_submit_enter": false,
  "app_name": "",
  "reply_bn": "Warm, natural, human-like Bengali response (no robotic 'জি স্যার অটোনোমাস এজেন্ট...' phrasing)"
}
"""
}
