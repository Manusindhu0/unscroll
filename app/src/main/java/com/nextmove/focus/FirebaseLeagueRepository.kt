package com.nextmove.focus

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class LeaguePlayer(
    val uid: String,
    val username: String,
    val points: Int,
    val isYou: Boolean
)

data class LeagueBoard(
    val players: List<LeaguePlayer>
)

data class LeagueFriend(
    val uid: String,
    val username: String
)

/** A live, deliberately minimal presence signal for accepted friends only. */
data class FocusPartner(
    val uid: String,
    val username: String,
    val craft: String,
    val task: String,
    val durationMinutes: Int,
    val endsAtMillis: Long
)

data class RemoteFocusStart(
    val sessionId: String,
    val endsAtMillis: Long
)

data class RemoteProfile(
    val username: String,
    val leaguePoints: Int,
    val totalFocusMinutes: Int,
    val startedRounds: Int,
    val completedRounds: Int,
    val bestRoundMinutes: Int,
    val mainCraft: String,
    val shareWorkNames: Boolean,
    val recentHistory: List<FocusHistoryEntry>
)

/** A safe seven-day summary. It contains no raw sessions or account data. */
data class FriendWeek(
    val weekKey: String,
    val totalMinutes: Int,
    val days: List<WeeklyFocusDay>
)

/** A completed focus round deliberately copied into the friends-only surface. */
data class SharedFocusWin(
    val craft: String,
    val task: String?,
    val durationMinutes: Int,
    val completedAtMillis: Long
)

data class FriendFocusProfile(
    val uid: String,
    val username: String,
    val mainCraft: String,
    val totalFocusMinutes: Int,
    val completedRounds: Int,
    val activePractice: FocusPartner?,
    val week: FriendWeek,
    val recentWins: List<SharedFocusWin>
)

/**
 * Spark-plan social layer. Firestore rules validate the atomic session + score
 * writes; no cloud function or billing account is required for this beta.
 */
class FirebaseLeagueRepository(context: Context) {
    private val appContext = context.applicationContext

    fun isSignedIn(): Boolean = firebaseApp()
        ?.let { FirebaseAuth.getInstance(it).currentUser?.isAnonymous == false }
        ?: false

    fun bootstrapProfile(username: String, onDone: (Result<Unit>) -> Unit) {
        val cleanName = username.trim().removePrefix("@")
        val handle = cleanName.lowercase(Locale.ROOT)
        if (!USERNAME_REGEX.matches(cleanName)) {
            onDone(Result.failure(IllegalArgumentException("Use 3–20 letters, numbers, or underscores.")))
            return
        }
        withSignedInDb(onDone) { db, uid ->
            val user = db.collection(USERS).document(uid)
            val handleRef = db.collection(HANDLES).document(handle)
            db.runTransaction { tx ->
                val currentUser = tx.get(user)
                val claimedHandle = tx.get(handleRef)
                if (claimedHandle.exists() && claimedHandle.getString("uid") != uid) {
                    throw IllegalStateException("That username is already taken.")
                }
                val previousHandle = currentUser.getString("handle")
                if (!previousHandle.isNullOrBlank() && previousHandle != handle) {
                    tx.delete(db.collection(HANDLES).document(previousHandle))
                }
                tx.set(handleRef, mapOf(
                    "uid" to uid,
                    "username" to cleanName,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                val base = mutableMapOf<String, Any>(
                    "uid" to uid,
                    "username" to cleanName,
                    "handle" to handle,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    // These also migrate profiles created before friend profiles
                    // existed, without silently enabling work-name sharing.
                    "mainCraft" to (currentUser.getString("mainCraft") ?: "Practice"),
                    "shareWorkNames" to (currentUser.getBoolean("shareWorkNames") ?: false)
                )
                if (!currentUser.exists()) {
                    base += mapOf(
                        "createdAt" to FieldValue.serverTimestamp(),
                        "leaguePoints" to 0,
                        "totalFocusMinutes" to 0,
                        "startedRounds" to 0,
                        "completedRounds" to 0,
                        "bestRoundMinutes" to 0,
                        "mainCraft" to "Practice",
                        "shareWorkNames" to false
                    )
                }
                tx.set(user, base, SetOptions.merge())
                Unit
            }.addOnSuccessListener { onDone(Result.success(Unit)) }
                .addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    fun startFocus(task: String, durationMinutes: Int, craft: String, onDone: (Result<RemoteFocusStart>) -> Unit) {
        val cleanTask = task.trim().take(140)
        val cleanCraft = craft.trim().take(32).ifBlank { "Practice" }
        if (cleanTask.isBlank() || durationMinutes !in ALLOWED_DURATIONS) {
            onDone(Result.failure(IllegalArgumentException("Use a task and a supported focus length.")))
            return
        }
        withSignedInDb(onDone) { db, uid ->
            val user = db.collection(USERS).document(uid)
            val session = db.collection(SESSIONS).document()
            // Rules require this end time to be at least the server request time
            // plus the selected duration. A manually advanced phone clock only
            // makes a session longer; it cannot make it award early.
            val endsAt = Timestamp(Date(System.currentTimeMillis() + durationMinutes * MINUTE_MILLIS + SERVER_TIME_BUFFER_MILLIS))
            db.runTransaction { tx ->
                val profile = tx.get(user)
                if (!profile.exists()) throw IllegalStateException("Create your League profile first.")
                if (!profile.getString("activeSessionId").isNullOrBlank()) {
                    throw IllegalStateException("Finish your current verified focus round first.")
                }
                val shareWorkNames = profile.getBoolean("shareWorkNames") == true
                tx.set(session, mapOf(
                    "uid" to uid,
                    "task" to cleanTask,
                    "craft" to cleanCraft,
                    "durationMinutes" to durationMinutes,
                    "startedAt" to FieldValue.serverTimestamp(),
                    "endsAt" to endsAt,
                    "status" to STATUS_ACTIVE
                ))
                tx.update(user, mapOf(
                    "activeSessionId" to session.id,
                    "activeSessionEndsAt" to endsAt,
                    "activePractice" to mapOf(
                        "task" to if (shareWorkNames) cleanTask else "",
                        "craft" to cleanCraft,
                        "durationMinutes" to durationMinutes,
                        "endsAt" to endsAt,
                        "startedAt" to FieldValue.serverTimestamp()
                    ),
                    "mainCraft" to cleanCraft,
                    "shareWorkNames" to shareWorkNames,
                    "startedRounds" to (profile.getLong("startedRounds") ?: 0L) + 1L,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                RemoteFocusStart(session.id, endsAt.toDate().time)
            }.addOnSuccessListener { onDone(Result.success(it)) }
                .addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    fun completeFocus(sessionId: String, onDone: (Result<Int>) -> Unit) {
        withSignedInDb(onDone) { db, uid ->
            val user = db.collection(USERS).document(uid)
            val session = db.collection(SESSIONS).document(sessionId)
            val weekKey = currentWeekKey()
            val weeklyScore = user.collection(WEEKLY_SCORES).document(weekKey)
            val sharedWeek = user.collection(SHARED_WEEKS).document(weekKey)
            val sharedWin = user.collection(SHARED_WINS).document(sessionId)
            db.runTransaction { tx ->
                val focus = tx.get(session)
                val profile = tx.get(user)
                val previousWeek = tx.get(weeklyScore)
                val previousSharedWeek = tx.get(sharedWeek)
                if (!focus.exists() || focus.getString("uid") != uid) throw IllegalStateException("Focus round not found.")
                if (focus.getString("status") == STATUS_COMPLETED) return@runTransaction 0
                if (focus.getString("status") != STATUS_ACTIVE) throw IllegalStateException("This round ended early.")
                val duration = (focus.getLong("durationMinutes") ?: 0L).toInt()
                if (duration !in ALLOWED_DURATIONS) throw IllegalStateException("Focus round is invalid.")
                val currentPoints = (profile.getLong("leaguePoints") ?: 0L).toInt()
                tx.update(session, mapOf(
                    "status" to STATUS_COMPLETED,
                    "completedAt" to FieldValue.serverTimestamp()
                ))
                tx.update(user, mapOf(
                    "activeSessionId" to FieldValue.delete(),
                    "activeSessionEndsAt" to FieldValue.delete(),
                    "activePractice" to FieldValue.delete(),
                    "leaguePoints" to currentPoints + duration,
                    "totalFocusMinutes" to (profile.getLong("totalFocusMinutes") ?: 0L) + duration,
                    "completedRounds" to (profile.getLong("completedRounds") ?: 0L) + 1L,
                    "bestRoundMinutes" to maxOf((profile.getLong("bestRoundMinutes") ?: 0L).toInt(), duration),
                    "mainCraft" to (profile.getString("mainCraft") ?: focus.getString("craft") ?: "Practice"),
                    "shareWorkNames" to (profile.getBoolean("shareWorkNames") ?: false),
                    "lastCompletedWeekKey" to weekKey,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                tx.set(weeklyScore, mapOf(
                    "uid" to uid,
                    "username" to (profile.getString("username") ?: "focus"),
                    "weekKey" to weekKey,
                    "minutes" to (previousWeek.getLong("minutes") ?: 0L) + duration,
                    "sessionId" to sessionId,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                val todayField = currentSharedDayField()
                val sharedWeekFields = SHARED_DAY_FIELDS.associateWith { field ->
                    (previousSharedWeek.getLong(field) ?: 0L).toInt() + if (field == todayField) duration else 0
                }
                tx.set(sharedWeek, mapOf(
                    "uid" to uid,
                    "username" to (profile.getString("username") ?: "focus"),
                    "weekKey" to weekKey,
                    "minutes" to (previousSharedWeek.getLong("minutes") ?: 0L).toInt() + duration,
                    "sessionId" to sessionId,
                    "updatedAt" to FieldValue.serverTimestamp()
                ) + sharedWeekFields)
                tx.set(sharedWin, mapOf(
                    "uid" to uid,
                    "sessionId" to sessionId,
                    "craft" to (focus.getString("craft") ?: "Practice"),
                    "task" to if (profile.getBoolean("shareWorkNames") == true) {
                        focus.getString("task") ?: ""
                    } else "",
                    "durationMinutes" to duration,
                    "completedAt" to FieldValue.serverTimestamp()
                ))
                duration
            }.addOnSuccessListener { onDone(Result.success(it)) }
                .addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    fun endFocus(sessionId: String, onDone: (Result<Unit>) -> Unit) {
        withSignedInDb(onDone) { db, uid ->
            val user = db.collection(USERS).document(uid)
            val session = db.collection(SESSIONS).document(sessionId)
            db.runTransaction { tx ->
                val focus = tx.get(session)
                val profile = tx.get(user)
                if (!focus.exists() || focus.getString("uid") != uid) throw IllegalStateException("Focus round not found.")
                if (focus.getString("status") == STATUS_ACTIVE) {
                    tx.update(session, mapOf(
                        "status" to STATUS_ENDED_EARLY,
                        "endedAt" to FieldValue.serverTimestamp()
                    ))
                    if (profile.getString("activeSessionId") == sessionId) {
                        tx.update(user, mapOf(
                            "activeSessionId" to FieldValue.delete(),
                            "activeSessionEndsAt" to FieldValue.delete(),
                            "activePractice" to FieldValue.delete(),
                            "mainCraft" to (profile.getString("mainCraft") ?: focus.getString("craft") ?: "Practice"),
                            "shareWorkNames" to (profile.getBoolean("shareWorkNames") ?: false),
                            "updatedAt" to FieldValue.serverTimestamp()
                        ))
                    }
                }
                Unit
            }.addOnSuccessListener { onDone(Result.success(Unit)) }
                .addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    fun loadProfile(onDone: (Result<RemoteProfile>) -> Unit) {
        withSignedInDb(onDone) { db, uid ->
            val user = db.collection(USERS).document(uid)
            val sessions = db.collection(SESSIONS)
                .whereEqualTo("uid", uid)
                .orderBy("startedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(HISTORY_LIMIT)
            user.get().addOnSuccessListener { profile ->
                if (!profile.exists()) {
                    onDone(Result.failure(IllegalStateException("Create your League profile first.")))
                    return@addOnSuccessListener
                }
                sessions.get().addOnSuccessListener { history ->
                    onDone(Result.success(
                        RemoteProfile(
                            username = profile.getString("username") ?: "focus",
                            leaguePoints = (profile.getLong("leaguePoints") ?: 0L).toInt(),
                            totalFocusMinutes = (profile.getLong("totalFocusMinutes") ?: 0L).toInt(),
                            startedRounds = (profile.getLong("startedRounds") ?: 0L).toInt(),
                            completedRounds = (profile.getLong("completedRounds") ?: 0L).toInt(),
                            bestRoundMinutes = (profile.getLong("bestRoundMinutes") ?: 0L).toInt(),
                            mainCraft = profile.getString("mainCraft") ?: "Practice",
                            shareWorkNames = profile.getBoolean("shareWorkNames") == true,
                            recentHistory = history.documents.mapNotNull(::historyEntry)
                        )
                    ))
                }.addOnFailureListener { onDone(Result.failure(it)) }
            }.addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    /** League is intentionally private: a member sees only themself and accepted friends. */
    fun loadBoard(weekKey: String, onDone: (Result<LeagueBoard>) -> Unit) {
        withSignedInDb(onDone) { db, uid ->
            db.collection(USERS).document(uid).collection(FRIENDS).get()
                .addOnSuccessListener { friends ->
                    val profileRefs = listOf(db.collection(USERS).document(uid)) + friends.documents.map {
                        db.collection(USERS).document(it.id)
                    }
                    val scoreRefs = profileRefs.map { it.collection(WEEKLY_SCORES).document(weekKey) }
                    val reads = (profileRefs + scoreRefs).map { it.get() }
                    Tasks.whenAllSuccess<com.google.firebase.firestore.DocumentSnapshot>(reads)
                        .addOnSuccessListener { people ->
                            val players = profileRefs.indices.mapNotNull { index ->
                                player(people[index], uid, (people[index + profileRefs.size].getLong("minutes") ?: 0L).toInt())
                            }
                                .sortedWith(compareByDescending<LeaguePlayer> { it.points }.thenBy { it.username.lowercase(Locale.ROOT) })
                            onDone(Result.success(LeagueBoard(players)))
                        }
                        .addOnFailureListener { onDone(Result.failure(it)) }
                }
                .addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    fun loadFriends(onDone: (Result<List<LeagueFriend>>) -> Unit) {
        withSignedInDb(onDone) { db, uid ->
            db.collection(USERS).document(uid).collection(FRIENDS).get()
                .addOnSuccessListener { snapshot ->
                    val friends = snapshot.documents.mapNotNull { document ->
                        val username = document.getString("username") ?: return@mapNotNull null
                        LeagueFriend(document.id, username)
                    }.sortedBy { it.username.lowercase(Locale.ROOT) }
                    onDone(Result.success(friends))
                }
                .addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    /**
     * Changes the default-safe work-name setting. Turning it off also clears
     * names from the currently shared win documents; completed time and craft
     * remain visible to friends.
     */
    fun setShareWorkNames(enabled: Boolean, onDone: (Result<Unit>) -> Unit) {
        withSignedInDb(onDone) { db, uid ->
            val user = db.collection(USERS).document(uid)
            db.runTransaction { tx ->
                val profile = tx.get(user)
                if (!profile.exists()) throw IllegalStateException("Create your League profile first.")
                val updates = mutableMapOf<String, Any>(
                    "shareWorkNames" to enabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!enabled) {
                    @Suppress("UNCHECKED_CAST")
                    val active = profile.get("activePractice") as? Map<String, Any>
                    if (active != null) updates["activePractice"] = active + ("task" to "")
                }
                tx.update(user, updates)
                Unit
            }.addOnSuccessListener {
                if (enabled) {
                    onDone(Result.success(Unit))
                    return@addOnSuccessListener
                }
                db.collection(USERS).document(uid).collection(SHARED_WINS)
                    .orderBy("completedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(WORK_NAME_CLEAR_LIMIT)
                    .get()
                    .addOnSuccessListener { wins ->
                        if (wins.isEmpty) {
                            onDone(Result.success(Unit))
                            return@addOnSuccessListener
                        }
                        val batch = db.batch()
                        wins.documents.forEach { win -> batch.update(win.reference, "task", "") }
                        batch.commit()
                            .addOnSuccessListener { onDone(Result.success(Unit)) }
                            .addOnFailureListener { onDone(Result.failure(it)) }
                    }
                    .addOnFailureListener { onDone(Result.failure(it)) }
            }.addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    /** Reads a friend's deliberately shared profile surface; raw sessions stay owner-only. */
    fun loadFriendProfile(friendUid: String, weekKey: String, onDone: (Result<FriendFocusProfile>) -> Unit) {
        withSignedInDb(onDone) { db, uid ->
            val friendLink = db.collection(USERS).document(uid).collection(FRIENDS).document(friendUid)
            val friend = db.collection(USERS).document(friendUid)
            val sharedWeek = friend.collection(SHARED_WEEKS).document(weekKey)
            val recentWins = friend.collection(SHARED_WINS)
                .orderBy("completedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(RECENT_SHARED_WINS_LIMIT)
            Tasks.whenAllSuccess<Any>(listOf(
                friendLink.get(),
                friend.get(),
                sharedWeek.get(),
                recentWins.get()
            )).addOnSuccessListener { values ->
                val link = values[0] as DocumentSnapshot
                val profile = values[1] as DocumentSnapshot
                val week = values[2] as DocumentSnapshot
                val wins = values[3] as com.google.firebase.firestore.QuerySnapshot
                if (!link.exists()) {
                    onDone(Result.failure(IllegalStateException("This profile is only available to Focus League friends.")))
                    return@addOnSuccessListener
                }
                if (!profile.exists()) {
                    onDone(Result.failure(IllegalStateException("This Focus profile is unavailable.")))
                    return@addOnSuccessListener
                }
                val username = profile.getString("username") ?: "focus"
                val activePractice = focusPartnerFrom(profile)
                onDone(Result.success(
                    FriendFocusProfile(
                        uid = friendUid,
                        username = username,
                        mainCraft = profile.getString("mainCraft") ?: activePractice?.craft ?: "Practice",
                        totalFocusMinutes = (profile.getLong("totalFocusMinutes") ?: 0L).toInt(),
                        completedRounds = (profile.getLong("completedRounds") ?: 0L).toInt(),
                        activePractice = activePractice,
                        week = friendWeekFrom(week, weekKey),
                        recentWins = wins.documents.mapNotNull(::sharedWinFrom)
                    )
                ))
            }.addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    /**
     * Listens to the accepted friends' profile documents for the duration of an
     * active round. This is intentionally presence-only: no messages, typing,
     * location, or app-usage data leaves either phone.
     */
    fun observeFocusPartners(onUpdate: (Result<List<FocusPartner>>) -> Unit): ListenerRegistration? {
        val app = firebaseApp()
        val user = app?.let { FirebaseAuth.getInstance(it).currentUser }
        if (app == null || user == null || user.isAnonymous) {
            onUpdate(Result.failure(IllegalStateException("Connect Google in Profile to focus together.")))
            return null
        }

        val db = FirebaseFirestore.getInstance(app)
        val composite = CompositeListenerRegistration()
        db.collection(USERS).document(user.uid).collection(FRIENDS).get()
            .addOnSuccessListener { friends ->
                if (composite.isRemoved()) return@addOnSuccessListener
                val friendIds = friends.documents.map { it.id }.distinct()
                if (friendIds.isEmpty()) {
                    onUpdate(Result.success(emptyList()))
                    return@addOnSuccessListener
                }

                val lock = Any()
                val profiles = linkedMapOf<String, DocumentSnapshot>()
                friendIds.forEach { friendUid ->
                    val registration = db.collection(USERS).document(friendUid)
                        .addSnapshotListener { profile, error ->
                            if (composite.isRemoved()) return@addSnapshotListener
                            if (error != null) {
                                onUpdate(Result.failure(error))
                                return@addSnapshotListener
                            }
                            synchronized(lock) {
                                if (profile == null || !profile.exists()) profiles.remove(friendUid)
                                else profiles[friendUid] = profile
                                onUpdate(Result.success(focusPartnersFrom(profiles.values)))
                            }
                        }
                    composite.add(registration)
                }
            }
            .addOnFailureListener { error ->
                if (!composite.isRemoved()) onUpdate(Result.failure(error))
            }
        return composite
    }

    /** Removes the link for both people, so neither person stays on the other's board. */
    fun removeFriend(friendUid: String, onDone: (Result<Unit>) -> Unit) {
        withSignedInDb(onDone) { db, uid ->
            val mine = db.collection(USERS).document(uid).collection(FRIENDS).document(friendUid)
            val theirs = db.collection(USERS).document(friendUid).collection(FRIENDS).document(uid)
            db.runTransaction { tx ->
                if (!tx.get(mine).exists()) throw IllegalStateException("This friend is already removed.")
                tx.delete(mine)
                tx.delete(theirs)
                Unit
            }.addOnSuccessListener { onDone(Result.success(Unit)) }
                .addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    fun createFriendInvite(onDone: (Result<String>) -> Unit) {
        withSignedInDb(onDone) { db, uid ->
            val code = UUID.randomUUID().toString().replace("-", "")
            val expiresAt = Timestamp(Date(System.currentTimeMillis() + INVITE_LIFETIME_MILLIS))
            val invite = db.collection(INVITES).document(code)
            val user = db.collection(USERS).document(uid)
            val rateLimit = user.collection(RATE_LIMITS).document(INVITE_RATE_LIMIT)
            db.runTransaction { tx ->
                val profile = tx.get(user)
                val username = profile.getString("username")
                    ?: throw IllegalStateException("Create your League profile first.")
                tx.set(rateLimit, mapOf("lastCreatedAt" to FieldValue.serverTimestamp()))
                tx.set(invite, mapOf(
                    "ownerUid" to uid,
                    "ownerUsername" to username,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to expiresAt,
                    "acceptedBy" to null
                ))
                code
            }.addOnSuccessListener { onDone(Result.success(it)) }
                .addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    fun acceptFriendInvite(inviteCode: String, onDone: (Result<Unit>) -> Unit) {
        val code = inviteCode.trim().lowercase(Locale.ROOT)
        withSignedInDb(onDone) { db, uid ->
            val invite = db.collection(INVITES).document(code)
            db.runTransaction { tx ->
                val invitation = tx.get(invite)
                if (!invitation.exists()) throw IllegalStateException("That invite does not exist.")
                val ownerUid = invitation.getString("ownerUid") ?: throw IllegalStateException("That invite is invalid.")
                if (ownerUid == uid) throw IllegalStateException("You cannot accept your own invite.")
                if (!invitation.getString("acceptedBy").isNullOrBlank()) throw IllegalStateException("That invite was already used.")
                val expires = invitation.getTimestamp("expiresAt") ?: throw IllegalStateException("That invite is invalid.")
                if (System.currentTimeMillis() > expires.toDate().time) throw IllegalStateException("That invite expired.")
                val guest = tx.get(db.collection(USERS).document(uid))
                val ownerUsername = invitation.getString("ownerUsername")
                    ?: throw IllegalStateException("This is an older invite. Ask your friend for a fresh one.")
                if (!guest.exists()) throw IllegalStateException("Create your League profile first.")
                tx.update(invite, mapOf(
                    "acceptedBy" to uid,
                    "acceptedAt" to FieldValue.serverTimestamp()
                ))
                val ownerFriend = db.collection(USERS).document(ownerUid).collection(FRIENDS).document(uid)
                val guestFriend = db.collection(USERS).document(uid).collection(FRIENDS).document(ownerUid)
                tx.set(ownerFriend, mapOf(
                    "uid" to uid,
                    "username" to (guest.getString("username") ?: "focus"),
                    "inviteCode" to code,
                    "addedAt" to FieldValue.serverTimestamp()
                ))
                tx.set(guestFriend, mapOf(
                    "uid" to ownerUid,
                    "username" to ownerUsername,
                    "inviteCode" to code,
                    "addedAt" to FieldValue.serverTimestamp()
                ))
                Unit
            }.addOnSuccessListener { onDone(Result.success(Unit)) }
                .addOnFailureListener { onDone(Result.failure(it)) }
        }
    }

    private fun <T> withSignedInDb(
        onDone: (Result<T>) -> Unit,
        action: (FirebaseFirestore, String) -> Unit
    ) {
        val app = firebaseApp()
        val uid = app?.let { FirebaseAuth.getInstance(it).currentUser?.uid }
        if (app == null || uid == null) {
            onDone(Result.failure(IllegalStateException("Connect a Google account to use Focus League.")))
            return
        }
        action(FirebaseFirestore.getInstance(app), uid)
    }

    private fun historyEntry(doc: com.google.firebase.firestore.DocumentSnapshot): FocusHistoryEntry? {
        val task = doc.getString("task") ?: return null
        val startedAt = doc.getTimestamp("startedAt")?.toDate()?.time ?: return null
        val duration = (doc.getLong("durationMinutes") ?: return null).toInt()
        val completed = doc.getString("status") == STATUS_COMPLETED
        val endedAt = (if (completed) doc.getTimestamp("completedAt") else doc.getTimestamp("endedAt"))
            ?.toDate()?.time ?: doc.getTimestamp("endsAt")?.toDate()?.time ?: startedAt
        return FocusHistoryEntry(
            task = task,
            startedAt = startedAt,
            durationMillis = duration * MINUTE_MILLIS,
            completed = completed,
            endedAt = endedAt,
            craft = doc.getString("craft") ?: "Practice"
        )
    }

    private fun player(
        doc: com.google.firebase.firestore.DocumentSnapshot,
        currentUid: String,
        weeklyMinutes: Int
    ): LeaguePlayer? {
        val username = doc.getString("username") ?: return null
        return LeaguePlayer(
            uid = doc.id,
            username = username,
            points = weeklyMinutes,
            isYou = doc.id == currentUid
        )
    }

    private fun focusPartnersFrom(profiles: Collection<DocumentSnapshot>): List<FocusPartner> {
        return profiles.mapNotNull(::focusPartnerFrom).sortedBy { it.endsAtMillis }
    }

    private fun focusPartnerFrom(profile: DocumentSnapshot): FocusPartner? {
        val endsAt = profile.getTimestamp("activePractice.endsAt")?.toDate()?.time ?: return null
        if (endsAt <= System.currentTimeMillis()) return null
        val username = profile.getString("username") ?: return null
        return FocusPartner(
            uid = profile.id,
            username = username,
            craft = profile.getString("activePractice.craft") ?: "Practice",
            // An empty task is intentional: this person chose not to share work names.
            task = profile.getString("activePractice.task")?.takeIf { it.isNotBlank() } ?: "a focus round",
            durationMinutes = (profile.getLong("activePractice.durationMinutes") ?: 0L).toInt(),
            endsAtMillis = endsAt
        )
    }

    private fun friendWeekFrom(document: DocumentSnapshot, weekKey: String): FriendWeek {
        val isCurrentWeek = weekKey == currentWeekKey()
        val todayIndex = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        return FriendWeek(
            weekKey = weekKey,
            totalMinutes = (document.getLong("minutes") ?: 0L).toInt(),
            days = SHARED_DAY_FIELDS.mapIndexed { index, field ->
                WeeklyFocusDay(
                    label = SHARED_DAY_LABELS[index],
                    minutes = (document.getLong(field) ?: 0L).toInt(),
                    isToday = isCurrentWeek && index == todayIndex
                )
            }
        )
    }

    private fun sharedWinFrom(document: DocumentSnapshot): SharedFocusWin? {
        val completedAt = document.getTimestamp("completedAt")?.toDate()?.time ?: return null
        val duration = (document.getLong("durationMinutes") ?: return null).toInt()
        if (duration !in ALLOWED_DURATIONS) return null
        return SharedFocusWin(
            craft = document.getString("craft") ?: "Practice",
            task = document.getString("task")?.takeIf { it.isNotBlank() },
            durationMinutes = duration,
            completedAtMillis = completedAt
        )
    }

    private fun firebaseApp(): FirebaseApp? = runCatching { FirebaseApp.getInstance() }.getOrNull()

    /** One lifecycle handle for the independent document listeners above. */
    private class CompositeListenerRegistration : ListenerRegistration {
        private val registrations = mutableListOf<ListenerRegistration>()
        private var removed = false

        fun add(registration: ListenerRegistration) {
            val shouldRemove = synchronized(this) {
                if (removed) true else {
                    registrations += registration
                    false
                }
            }
            if (shouldRemove) registration.remove()
        }

        fun isRemoved(): Boolean = synchronized(this) { removed }

        override fun remove() {
            val toRemove = synchronized(this) {
                if (removed) return
                removed = true
                registrations.toList().also { registrations.clear() }
            }
            toRemove.forEach { it.remove() }
        }
    }

    companion object {
        private const val USERS = "users"
        private const val HANDLES = "handles"
        private const val SESSIONS = "focusSessions"
        private const val FRIENDS = "friends"
        private const val WEEKLY_SCORES = "weeklyScores"
        private const val SHARED_WEEKS = "sharedWeeks"
        private const val SHARED_WINS = "sharedWins"
        private const val INVITES = "friendInvites"
        private const val RATE_LIMITS = "rateLimits"
        private const val INVITE_RATE_LIMIT = "invite"
        private const val STATUS_ACTIVE = "active"
        private const val STATUS_COMPLETED = "completed"
        private const val STATUS_ENDED_EARLY = "endedEarly"
        private const val HISTORY_LIMIT = 100L
        private const val RECENT_SHARED_WINS_LIMIT = 6L
        private const val WORK_NAME_CLEAR_LIMIT = 100L
        private const val MINUTE_MILLIS = 60_000L
        private const val SERVER_TIME_BUFFER_MILLIS = 30_000L
        private const val INVITE_LIFETIME_MILLIS = 7 * 24 * 60 * MINUTE_MILLIS
        private val ALLOWED_DURATIONS = setOf(25, 45, 60)
        private val USERNAME_REGEX = Regex("^[A-Za-z0-9_]{3,20}$")
        private val SHARED_DAY_FIELDS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        private val SHARED_DAY_LABELS = listOf("M", "T", "W", "T", "F", "S", "S")

        private fun currentSharedDayField(): String {
            val index = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            return SHARED_DAY_FIELDS[index]
        }

        /** Monday-keyed local week, e.g. 2026-09-14. It keeps a friends group on a fresh weekly board. */
        fun currentWeekKey(): String = weekKeyForOffset(0)

        fun weekKeyForOffset(weekOffset: Int): String {
            val start = Calendar.getInstance().apply {
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                val daysSinceMonday = (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
                add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
                add(Calendar.WEEK_OF_YEAR, weekOffset)
            }
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(start.time)
        }
    }
}
