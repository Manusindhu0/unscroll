package com.nextmove.focus

import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private enum class TopLevelTab { FOCUS, LEAGUE, PROFILE }
    private enum class FeedbackType(val label: String, val headline: String, val hint: String) {
        ISSUE("Issue", "Something got in the way?", "Tell us what you expected, and what happened instead."),
        IDEA("Idea", "What should Unscroll do next?", "The smallest detail can become the next great feature."),
        APPRECIATION("Love", "Want to make our day?", "Tell the team what helped you show up for your life.")
    }

    private val store by lazy { FocusSessionStore(this) }
    private val googleAuth by lazy { GoogleAuthController() }
    private val league by lazy { FirebaseLeagueRepository(this) }
    private val interTypeface by lazy { ResourcesCompat.getFont(this, R.font.inter_regular) ?: Typeface.DEFAULT }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private var selectedDuration = 45
    private var selectedBreak = 15
    private var selectedTopTab = TopLevelTab.FOCUS
    private var beamAnimator: ValueAnimator? = null
    private var remoteProfile: RemoteProfile? = null
    private var profileLoading = false
    private var profileLoadAttempted = false
    private var profileBootstrapAttempted = false
    private var leagueBoard: LeagueBoard? = null
    private var leagueBoardWeekKey: String? = null
    private var leagueLoading = false
    private var leagueError: String? = null
    private var leagueWeekOffset = 0
    private var leagueFriends: List<LeagueFriend>? = null
    private var friendsLoading = false
    private var friendsError: String? = null
    private var leagueShowingFriends = false
    private var pendingInviteCode: String? = null
    private var focusStartInFlight = false
    private var completingRemoteSessionId: String? = null
    private var endingRemoteSessionId: String? = null
    private var selectedFeedbackType = FeedbackType.ISSUE
    private var focusPartners: List<FocusPartner>? = null
    private var partnersLoading = false
    private var focusPartnerError: String? = null
    private var focusPartnerListener: ListenerRegistration? = null
    private var activePartnerContainer: LinearLayout? = null
    private var activeSessionTickerGeneration = 0
    private var friendProfile: FriendFocusProfile? = null
    private var friendProfileUid: String? = null
    private var friendProfileWeekKey: String? = null
    private var friendProfileLoading = false
    private var friendProfileError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BreakReminderReceiver.ensureChannel(this)
        // One-time migration from the old Accessibility blocker. Do not reset
        // the user's selection on every launch.
        store.ensureBankingSafeDefault()
        receiveLeagueInvite(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (receiveLeagueInvite(intent) && ::content.isInitialized) render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        beamAnimator?.cancel()
        stopFocusPartnerObserver()
        super.onDestroy()
    }

    private fun appTypeface(style: Int = Typeface.NORMAL): Typeface = Typeface.create(interTypeface, style)

    private fun render() {
        handler.removeCallbacksAndMessages(null)
        activeSessionTickerGeneration += 1
        beamAnimator?.cancel()
        stopFocusPartnerObserver()
        syncPendingLeagueUpdates()
        requestRemoteProfileIfNeeded()
        // A timer is never enough to earn progress. Once Focus Lock has been
        // verified, leaving Android Screen Pinning invalidates the whole round.
        // This happens before activeSession() so returning after the deadline
        // cannot turn an already-escaped round into a completion.
        if (store.hasActiveSessionRecord() && store.focusLockWasVerified() && !FocusLockMonitor.isActive(this)) {
            store.interruptForFocusLockExit()
            syncPendingLeagueUpdates()
        }
        val session = store.activeSession()
        if (session == null) releaseFocusLock()
        when {
            session != null -> {
                preparePage()
                showActiveSession(session)
                // An unfinished round always returns to Focus Lock.
                handler.post { requestFocusLock() }
            }
            store.consumeCompletion() -> {
                preparePage()
                showCompletion()
            }
            store.consumeFocusLockInterruption() -> {
                selectedTopTab = TopLevelTab.FOCUS
                preparePage(TopLevelTab.FOCUS)
                showFocusLockInterrupted()
            }
            store.rescueOffer() != null -> {
                preparePage(TopLevelTab.FOCUS)
                showRescueRound(store.rescueOffer()!!)
            }
            store.username() == null -> {
                preparePage()
                showUsernameSetup()
            }
            pendingInviteCode != null -> showJoinLeagueInvite(pendingInviteCode)
            store.nextTask() != null -> {
                preparePage(TopLevelTab.FOCUS)
                showQueuedNextMove()
            }
            else -> when (selectedTopTab) {
                TopLevelTab.FOCUS -> {
                    preparePage(TopLevelTab.FOCUS)
                    showHome()
                }
                TopLevelTab.LEAGUE -> if (leagueShowingFriends) showFriends() else showLeague()
                TopLevelTab.PROFILE -> showProfile()
            }
        }
    }

    /** Builds a real top-level screen with a persistent navigation dock when appropriate. */
    private fun preparePage(selectedTab: TopLevelTab? = null) {
        activePartnerContainer = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.night))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.night))
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        selectedTab?.let {
            // The dock carries both an icon and a label. Give it enough vertical
            // room so labels don't get cut on compact Android screens.
            root.addView(navigationDock(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)))
        }
        setContentView(root)
    }

    private fun navigationDock(selected: TopLevelTab): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(10))
            setBackgroundColor(color(R.color.surface))
        }
        listOf(
            Triple(TopLevelTab.FOCUS, "Focus", R.drawable.ic_nav_focus),
            Triple(TopLevelTab.LEAGUE, "League", R.drawable.ic_nav_league),
            Triple(TopLevelTab.PROFILE, "Profile", R.drawable.ic_nav_profile)
        ).forEach { (tab, label, iconRes) ->
            val active = tab == selected
            val slot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            slot.addView(View(this).apply {
                setBackgroundColor(color(if (active) R.color.focus_green else R.color.surface))
            }, LinearLayout.LayoutParams(dp(28), dp(2)))
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                typeface = appTypeface(if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(color(if (active) R.color.focus_green else R.color.muted))
                gravity = Gravity.CENTER
                compoundDrawablePadding = dp(1)
                val icon = ContextCompat.getDrawable(this@MainActivity, iconRes)?.mutate()
                icon?.let { DrawableCompat.setTint(it, color(if (active) R.color.focus_green else R.color.muted)) }
                setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
                background = rounded(if (active) R.color.surface_high else R.color.surface, 14)
                setOnClickListener {
                    when (tab) {
                        TopLevelTab.FOCUS -> {
                            selectedTopTab = TopLevelTab.FOCUS
                            render()
                        }
                        TopLevelTab.LEAGUE -> showLeague()
                        TopLevelTab.PROFILE -> showProfile()
                    }
                }
            }
            slot.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
            dock.addView(slot, LinearLayout.LayoutParams(0, dp(68), 1f))
        }
        return dock
    }

    private fun showUsernameSetup() {
        brand()
        addSpace(66)
        text("Make focus\nfeel personal.", 34, R.color.ink, Typeface.BOLD)
        addSpace(12)
        text("Pick a username for your profile, score, friends and future rankings.", 17, R.color.muted)
        addSpace(38)
        label("YOUR USERNAME")
        val username = input("e.g. jaani")
        addSpace(26)
        primaryButton("Create my profile") {
            val value = username.text.toString().trim().removePrefix("@")
            if (value.length < 3) username.error = "Use at least 3 characters" else {
                store.setUsername(value)
                render()
            }
        }
    }

    private fun showHome() {
        homeHeader()
        addSpace(22)
        label("WHAT WILL YOU WORK ON?")
        addSpace(7)
        val task = input("e.g. Guitar practice or DSA")
        addSpace(20)
        label("FOCUS LENGTH")
        addSpace(8)
        focusLengthCards()
        addSpace(20)
        primaryButton("Lock in · $selectedDuration min") {
            val value = task.text.toString().trim()
            if (value.isBlank()) task.error = "Write one thing you want to focus on" else beginFocus(value, selectedDuration)
        }
        addSpace(11)
        text("Stop scrolling. Start living.", 15, R.color.focus_green, Typeface.BOLD)
        addSpace(5)
        text("Start once. Focus Lock holds this screen for the round.", 13, R.color.muted)
        addSpace(30)
        focusLockCard()
        addSpace(18)
        text("Every completed round and your score live in Profile.", 14, R.color.muted)
    }

    private fun homeHeader() {
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = TextView(this).apply {
            text = "↑"
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.night))
            background = rounded(R.color.focus_green, 99)
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(38), dp(38)))
        header.addView(TextView(this).apply {
            text = "UNSCROLL\n@${store.username()}"
            textSize = 13f
            letterSpacing = .07f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.focus_green))
            setPadding(dp(11), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "TODAY  ${store.todayScore()}m"
            textSize = 12f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.muted))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        })
        header.addView(Button(this).apply {
            text = "?  Help"
            isAllCaps = false
            textSize = 12f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
            background = rounded(R.color.surface_card, 14, R.color.divider)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { showHelpCenter() }
        }, LinearLayout.LayoutParams(dp(72), dp(38)))
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun showHelpCenter() {
        selectedTopTab = TopLevelTab.FOCUS
        preparePage(TopLevelTab.FOCUS)
        textButton("← Back to focus") { render() }
        addSpace(16)
        brand()
        addSpace(16)
        text("Hey, how can\nwe help?", 31, R.color.ink, Typeface.BOLD)
        addSpace(8)
        text("Send a note directly to the Unscroll team. We read every one.", 16, R.color.muted)
        addSpace(24)

        val welcomeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(R.color.surface_card, 20)
        }
        welcomeCard.addView(TextView(this).apply {
            text = "CHOOSE A NOTE"
            textSize = 11f
            letterSpacing = .1f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.focus_green))
        })
        welcomeCard.addView(TextView(this).apply {
            text = selectedFeedbackType.headline
            textSize = 19f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
            setPadding(0, dp(6), 0, dp(3))
        })
        welcomeCard.addView(TextView(this).apply {
            text = selectedFeedbackType.hint
            textSize = 14f
            setTextColor(color(R.color.muted))
        })
        content.addView(welcomeCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSpace(12)
        feedbackTypePicker()
        addSpace(24)
        label("YOUR MESSAGE")
        addSpace(8)
        val message = EditText(this).apply {
            hint = when (selectedFeedbackType) {
                FeedbackType.ISSUE -> "For example: The blocker opens, but…"
                FeedbackType.IDEA -> "For example: It would be great if…"
                FeedbackType.APPRECIATION -> "For example: I finally finished…"
            }
            setHintTextColor(color(R.color.muted))
            setTextColor(color(R.color.ink))
            textSize = 16f
            typeface = appTypeface()
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minLines = 5
            maxLines = 8
            background = rounded(R.color.surface_card, 18, R.color.divider)
            setOnFocusChangeListener { _, focused ->
                background = rounded(R.color.surface_card, 18, if (focused) R.color.focus_green else R.color.divider)
            }
        }
        content.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(148)))
        addSpace(20)
        val submit = Button(this).apply {
            text = "Send to Unscroll"
            isAllCaps = false
            textSize = 17f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.night))
            background = rounded(R.color.focus_green, 18)
        }
        submit.setOnClickListener {
            val note = message.text.toString().trim()
            if (note.isBlank()) {
                message.error = "Write a quick note first"
            } else {
                submit.isEnabled = false
                submit.alpha = .65f
                submit.text = "Sending…"
                FeedbackGateway.send(
                    type = selectedFeedbackType.label,
                    message = note,
                    username = store.username().orEmpty(),
                    appVersion = appVersionName(),
                    androidVersion = Build.VERSION.RELEASE
                ) { result ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        submit.isEnabled = true
                        submit.alpha = 1f
                        submit.text = "Send to Unscroll"
                        result.fold(
                            onSuccess = {
                                showMessage("Sent — thank you", "Your note reached the Unscroll team. We really do read these.")
                                message.setText("")
                            },
                            onFailure = { error ->
                                showMessage("Couldn’t send yet", friendlyFeedbackError(error))
                            }
                        )
                    }
                }
            }
        }
        content.addView(submit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        addSpace(10)
        text("Your note goes straight to the Unscroll team—no mail app needed.", 13, R.color.muted)
    }

    private fun feedbackTypePicker() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        FeedbackType.values().forEachIndexed { index, type ->
            val selected = type == selectedFeedbackType
            val card = TextView(this).apply {
                text = type.label
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = appTypeface(if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(color(if (selected) R.color.night else R.color.ink))
                background = rounded(if (selected) R.color.focus_green else R.color.surface_card, 16)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedFeedbackType = type
                    showHelpCenter()
                }
            }
            row.addView(card, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                if (index < FeedbackType.values().lastIndex) marginEnd = dp(8)
            })
        }
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
    }

    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    private fun friendlyFeedbackError(error: Throwable): String = when (error.message) {
        "try_again_later" -> "You just sent a note. Try again in a minute if you have something else to add."
        "unauthorized" -> "The feedback inbox needs a quick server check. Please try again shortly."
        else -> "Check your connection and try again. Your note is still here."
    }

    private fun focusLengthCards() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple(25, "QUICK", "one clean push"),
            Triple(45, "DEEP", "default mode"),
            Triple(60, "FLOW", "go long")
        ).forEachIndexed { index, (minutes, title, caption) ->
            val selected = minutes == selectedDuration
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(if (selected) R.color.focus_green else R.color.surface_high, 18)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedDuration = minutes
                    render()
                }
            }
            card.addView(TextView(this).apply {
                text = "$minutes"
                textSize = 22f
                typeface = appTypeface(Typeface.BOLD)
                setTextColor(color(if (selected) R.color.night else R.color.ink))
            })
            card.addView(TextView(this).apply {
                text = "$title · MIN"
                textSize = 10f
                letterSpacing = .08f
                typeface = appTypeface(Typeface.BOLD)
                setTextColor(color(if (selected) R.color.night else R.color.focus_green))
            })
            card.addView(TextView(this).apply {
                text = caption
                textSize = 11f
                setTextColor(color(if (selected) R.color.night else R.color.muted))
                setPadding(0, dp(5), 0, 0)
            })
            row.addView(card, LinearLayout.LayoutParams(0, dp(86), 1f).apply {
                if (index < 2) marginEnd = dp(8)
            })
        }
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86)))
    }

    private fun scoreCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(R.color.surface_card, 22)
        }
        val top = TextView(this).apply {
            text = "TODAY’S SCORE"
            textSize = 12f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.muted))
        }
        card.addView(top)
        val points = TextView(this).apply {
            text = "${store.todayScore()} points"
            textSize = 30f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.focus_green))
        }
        card.addView(points)
        val complete = store.completedCount()
        card.addView(TextView(this).apply {
            text = "$complete completed session${if (complete == 1) "" else "s"} · 1 point per focused minute"
            textSize = 14f
            setTextColor(color(R.color.muted))
        })
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun showActiveSession(session: FocusSession) {
        val tickerGeneration = activeSessionTickerGeneration
        brand()
        addSpace(24)
        val phase = text("STOP SCROLLING. START LIVING.", 13, R.color.focus_red, Typeface.BOLD).apply {
            letterSpacing = .12f
        }
        addSpace(8)
        text("Stay with\nit.", 38, R.color.ink, Typeface.BOLD)
        addSpace(8)
        val focusMessage = text("This round belongs to the thing you chose.", 16, R.color.muted)

        val signal = FocusSignalView(this)
        content.addView(signal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(198)))

        val moveCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(17))
            background = rounded(R.color.surface_card, 22)
        }
        moveCard.addView(TextView(this).apply {
            text = "PRACTISING · ${session.craft.uppercase(Locale.getDefault())}"
            textSize = 11f
            letterSpacing = .1f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.muted))
        })
        moveCard.addView(Space(this), LinearLayout.LayoutParams(1, dp(7)))
        moveCard.addView(TextView(this).apply {
            text = session.task
            textSize = 21f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        moveCard.addView(Space(this), LinearLayout.LayoutParams(1, dp(5)))
        moveCard.addView(TextView(this).apply {
            text = "Chosen before distraction showed up."
            textSize = 14f
            setTextColor(color(R.color.muted))
        })
        content.addView(moveCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addActivePartnerBar()
        observeFocusPartnersForActiveSession()
        addSpace(24)
        text(
            "Focus Lock is active",
            15,
            R.color.focus_green,
            Typeface.BOLD
        )
        addSpace(4)
        text(
            "Unscroll is pinned for this round. No app list, no app switching.",
            15,
            R.color.muted
        )
        addSpace(12)
        text(
            "This round ends automatically when the light turns green.",
            13,
            R.color.muted
        )
        fun updateLight() {
            if (tickerGeneration != activeSessionTickerGeneration) return
            if (store.hasActiveSessionRecord() && store.focusLockWasVerified() && !FocusLockMonitor.isActive(this)) {
                store.interruptForFocusLockExit()
                render()
                return
            }
            val refreshed = store.activeSession()
            if (refreshed == null) {
                releaseFocusLock()
                signal.playCompletion()
                phase.animate().alpha(0f).setDuration(150).withEndAction {
                    phase.text = "ROUND COMPLETE"
                    phase.setTextColor(color(R.color.focus_green))
                    phase.animate().alpha(1f).setDuration(220).start()
                }.start()
                focusMessage.text = "Nice work — your attention stayed yours."
                handler.postDelayed({ render() }, 1_500)
                return
            }
            val isFinalStretch = refreshed.progress() >= .5f
            val phaseColor = if (isFinalStretch) R.color.focus_yellow else R.color.focus_red
            signal.bind(refreshed)
            val nextPhase = if (isFinalStretch) "FINAL STRETCH" else "STOP SCROLLING. START LIVING."
            if (phase.text != nextPhase) {
                phase.animate().alpha(0f).setDuration(150).withEndAction {
                    phase.text = nextPhase
                    phase.setTextColor(color(phaseColor))
                    phase.animate().alpha(1f).setDuration(220).start()
                }.start()
            } else {
                phase.setTextColor(color(phaseColor))
            }
            updateActivePartnerBar()
            handler.postDelayed({
                if (tickerGeneration == activeSessionTickerGeneration) updateLight()
            }, 2_000)
        }
        updateLight()
    }

    private fun showFocusLockInterrupted() {
        brand()
        addSpace(52)
        text("Round not counted.", 31, R.color.ink, Typeface.BOLD)
        addSpace(12)
        text(
            "Focus Lock was exited before the light turned green. No XP, score, or League time was added.",
            17,
            R.color.muted
        )
        addSpace(32)
        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(17))
            background = rounded(R.color.surface_card, 22)
        }
        note.addView(TextView(this).apply {
            text = "KEEP IT CLEAN"
            textSize = 11f
            letterSpacing = .1f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.focus_green))
        })
        note.addView(TextView(this).apply {
            text = "Start a fresh round when you are ready to stay pinned until it finishes."
            textSize = 16f
            setTextColor(color(R.color.ink))
            setPadding(0, dp(7), 0, 0)
        })
        content.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSpace(26)
        primaryButton("Start a fresh round") { render() }
    }

    private fun showCompletion() {
        brand()
        addSpace(40)
        val celebration = FocusCelebrationView(this)
        content.addView(celebration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132)))
        addSpace(4)
        val greenLight = View(this).apply { background = rounded(R.color.focus_green, 99) }
        content.addView(greenLight, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        addSpace(20)
        text("You kept the promise.", 30, R.color.ink, Typeface.BOLD)
        addSpace(8)
        val finished = store.history().firstOrNull()
        val gained = finished?.durationMillis?.div(60_000L)?.toInt() ?: 0
        val craft = finished?.craft ?: store.currentCraft()
        val score = text("+$gained $craft XP", 19, R.color.focus_green, Typeface.BOLD)
        ValueAnimator.ofInt(0, gained).apply {
            duration = 850
            interpolator = DecelerateInterpolator(1.7f)
            var didConfirm = false
            addUpdateListener {
                score.text = "+${it.animatedValue} $craft XP"
                if (!didConfirm && it.animatedFraction >= .99f) {
                    didConfirm = true
                    score.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
            }
            start()
        }
        addSpace(12)
        val totalCraftMinutes = store.craftMinutes(craft)
        text("$craft · Level ${practiceLevel(totalCraftMinutes)} · ${formatFocusTime(totalCraftMinutes)} practised", 14, R.color.focus_green, Typeface.BOLD)
        addSpace(8)
        text("Decide the next move before your break fills itself.", 17, R.color.muted)
        addSpace(34)
        label("WHAT’S NEXT?")
        val nextTask = input("Write the next thing you’ll do")
        addSpace(24)
        label("NUDGE ME IN")
        choiceRow(listOf(15, 30, 60), selectedBreak) {
            selectedBreak = it
            content.removeAllViews()
            showCompletion()
        }
        addSpace(30)
        primaryButton("Save my next move") {
            val value = nextTask.text.toString().trim()
            if (value.isBlank()) {
                nextTask.error = "Give your future self a clear next step"
                return@primaryButton
            }
            val at = System.currentTimeMillis() + selectedBreak * 60_000L
            store.saveNextTask(value, at)
            ReminderScheduler.schedule(this, at)
            requestNotificationPermissionIfNeeded()
            render()
        }
        addSpace(10)
        textButton("I’m done for today") { render() }
    }

    private fun showRescueRound(offer: RescueOffer) {
        brand()
        addSpace(44)
        val pulse = FocusCelebrationView(this)
        pulse.alpha = .72f
        content.addView(pulse, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)))
        addSpace(12)
        text("Round slipped.\nSave today with five.", 30, R.color.ink, Typeface.BOLD)
        addSpace(10)
        text("No shame, no reset. Just a tiny restart for your ${offer.craft.lowercase(Locale.getDefault())} practice.", 16, R.color.muted)
        addSpace(24)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(R.color.focus_green_muted, 20, R.color.focus_green)
        }
        card.addView(TextView(this).apply {
            text = "5-MINUTE RESCUE ROUND"
            textSize = 11f
            letterSpacing = .1f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.focus_green_bright))
        })
        card.addView(TextView(this).apply {
            text = offer.task
            textSize = 20f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
            setPadding(0, dp(6), 0, dp(2))
        })
        card.addView(TextView(this).apply {
            text = "One small rep still counts."
            textSize = 14f
            setTextColor(color(R.color.muted))
        })
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSpace(24)
        primaryButton("Start 5-minute restart") {
            startRescueRound(offer)
        }
        addSpace(10)
        textButton("Not today") {
            store.clearRescueOffer()
            render()
        }
    }

    private fun showQueuedNextMove() {
        val next = store.nextTask() ?: run { render(); return }
        brand()
        addSpace(58)
        text("Your next move", 13, R.color.focus_green, Typeface.BOLD)
        addSpace(10)
        text(next, 31, R.color.ink, Typeface.BOLD)
        addSpace(10)
        text("You’re free right now. When you’re ready, one tap starts the next round.", 17, R.color.muted)
        addSpace(34)
        label("NEXT FOCUS LENGTH")
        choiceRow(listOf(25, 45, 60), selectedDuration) { selectedDuration = it; render() }
        addSpace(28)
        primaryButton("Start $selectedDuration min focus") { beginFocus(next, selectedDuration) }
        addSpace(10)
        textButton("Change task") { showChangeNextTask(next) }
        textButton("Skip for today") {
            ReminderScheduler.cancel(this)
            store.clearNextTask()
            render()
        }
    }

    private fun showChangeNextTask(existing: String) {
        preparePage(TopLevelTab.FOCUS)
        brand()
        addSpace(58)
        text("What feels most important now?", 28, R.color.ink, Typeface.BOLD)
        addSpace(26)
        val replacement = input("Next task").apply { setText(existing); setSelection(text.length) }
        addSpace(26)
        primaryButton("Update next move") {
            val value = replacement.text.toString().trim()
            if (value.isBlank()) replacement.error = "Name the next move" else {
                store.saveNextTask(value, store.nextReminderAt().takeIf { it > System.currentTimeMillis() })
                render()
            }
        }
        addSpace(10)
        textButton("Cancel") { render() }
    }

    private fun showProfile() {
        selectedTopTab = TopLevelTab.PROFILE
        preparePage(TopLevelTab.PROFILE)
        requestRemoteProfileIfNeeded()
        val liveProfile = remoteProfile
        val visibleHistory = liveProfile?.recentHistory ?: store.history()
        brand()
        addSpace(24)
        profileHero()
        addSpace(12)
        googleAccountCard()
        addSpace(12)
        sharingPreferenceCard()
        addSpace(18)
        skillXpCard()
        addSpace(26)
        val totalMinutes = liveProfile?.totalFocusMinutes ?: store.totalFocusMinutes()
        metricRow(
            "${store.todayScore()}m" to "today",
            "${store.currentStreakDays(visibleHistory)}" to "day streak",
            formatFocusTime(totalMinutes) to "all time"
        )
        addSpace(28)
        label("THIS WEEK")
        addSpace(10)
        val week = store.weeklyFocus(visibleHistory)
        val weekMinutes = week.sumOf { it.minutes }
        val weekCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = rounded(R.color.surface_card, 22)
        }
        weekCard.addView(TextView(this).apply {
            text = if (weekMinutes == 0) "Your rhythm starts with one round." else "$weekMinutes focused minutes this week"
            textSize = 16f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        val weekView = FocusWeekView(this).apply { setDays(week) }
        weekCard.addView(weekView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(142)))
        content.addView(weekCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSpace(16)
        weeklyReceiptPreview(visibleHistory)
        addSpace(26)
        label("YOUR RECORD")
        addSpace(10)
        val started = liveProfile?.startedRounds ?: store.startedCount()
        val completed = liveProfile?.completedRounds ?: store.completedCount()
        val rate = if (started == 0) 0 else (completed * 100 / started)
        metricRow(
            "$completed" to "rounds kept",
            "$rate%" to "completion",
            "${liveProfile?.bestRoundMinutes ?: 0}m" to "best round"
        )
        addSpace(26)
        label(if (liveProfile != null) "VERIFIED FOCUS HISTORY" else "RECENT FOCUS HISTORY")
        addSpace(10)
        val recentHistory = visibleHistory.take(3)
        if (recentHistory.isEmpty()) {
            text("Your first focus round will appear here.", 16, R.color.muted)
        } else {
            recentHistory.forEachIndexed { index, item ->
                historyRow(item)
                if (index < recentHistory.lastIndex) addSpace(8)
            }
        }
        addSpace(12)
        textButton("View full focus history") { showHistory() }
        addSpace(16)
        textButton("Change username") { showChangeUsername() }
    }

    private fun skillXpCard() {
        val craft = store.mainCraft()
        val minutes = store.craftMinutes(craft)
        val level = practiceLevel(minutes)
        val inLevel = minutes % XP_PER_LEVEL_MINUTES
        val remaining = XP_PER_LEVEL_MINUTES - inLevel
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(R.color.surface_card, 22)
        }
        card.addView(TextView(this).apply {
            text = "MAIN CRAFT"
            textSize = 11f
            letterSpacing = .1f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.focus_green))
        })
        card.addView(TextView(this).apply {
            text = "$craft · Level $level"
            textSize = 24f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
            setPadding(0, dp(7), 0, dp(2))
        })
        card.addView(TextView(this).apply {
            text = "${formatFocusTime(minutes)} of deliberate practice"
            textSize = 14f
            setTextColor(color(R.color.muted))
        })
        val xpView = PracticeXpView(this).apply {
            setProgress(inLevel.toFloat() / XP_PER_LEVEL_MINUTES)
        }
        card.addView(xpView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)).apply {
            topMargin = dp(12)
        })
        card.addView(TextView(this).apply {
            text = if (minutes == 0) "Your first completed round earns real practice XP."
            else "$remaining XP to Level ${level + 1} · 1 focused minute = 1 XP"
            textSize = 12f
            setTextColor(color(R.color.focus_green))
        })
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun weeklyReceiptPreview(entries: List<FocusHistoryEntry>) {
        val summary = weeklyPracticeSummary(entries)
        val sunday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(R.color.league_gold_muted, 22, R.color.league_gold)
            isClickable = true
            isFocusable = true
            setOnClickListener { showWeeklyReceipt(entries) }
        }
        card.addView(TextView(this).apply {
            text = if (sunday) "SUNDAY PRACTICE RECEIPT" else "WEEKLY PRACTICE RECEIPT"
            textSize = 11f
            letterSpacing = .1f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.league_gold))
        })
        card.addView(TextView(this).apply {
            text = "${formatFocusTime(summary.minutes)} focused · ${summary.reps} reps"
            textSize = 20f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
            setPadding(0, dp(7), 0, dp(3))
        })
        card.addView(TextView(this).apply {
            text = "Main craft: ${summary.mainCraft} · tap to view and share"
            textSize = 14f
            setTextColor(color(R.color.muted))
        })
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private data class WeeklyPracticeSummary(
        val minutes: Int,
        val reps: Int,
        val streak: Int,
        val mainCraft: String
    )

    private fun weeklyPracticeSummary(entries: List<FocusHistoryEntry>): WeeklyPracticeSummary {
        val start = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7)
        }.timeInMillis
        val completed = entries.filter { it.completed && it.endedAt >= start }
        val mainCraft = completed.groupBy { it.craft }
            .maxByOrNull { (_, values) -> values.sumOf { it.durationMillis } }
            ?.key ?: store.currentCraft()
        return WeeklyPracticeSummary(
            minutes = completed.sumOf { (it.durationMillis / 60_000L).toInt() },
            reps = completed.size,
            streak = store.currentStreakDays(entries),
            mainCraft = mainCraft
        )
    }

    private fun showWeeklyReceipt(entries: List<FocusHistoryEntry> = store.history()) {
        selectedTopTab = TopLevelTab.PROFILE
        preparePage(TopLevelTab.PROFILE)
        val summary = weeklyPracticeSummary(entries)
        textButton("← Back to profile") { showProfile() }
        addSpace(18)
        brand()
        addSpace(14)
        text("Your week,\nmade visible.", 31, R.color.ink, Typeface.BOLD)
        addSpace(8)
        text("This receipt is proof of the time you gave to your real life.", 16, R.color.muted)
        addSpace(26)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = rounded(R.color.league_gold_muted, 24, R.color.league_gold)
        }
        card.addView(TextView(this).apply {
            text = "UNSCROLL · WEEKLY PRACTICE RECEIPT"
            textSize = 11f
            letterSpacing = .1f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.league_gold))
        })
        card.addView(TextView(this).apply {
            text = formatFocusTime(summary.minutes)
            textSize = 38f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
            setPadding(0, dp(18), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "focused this week"
            textSize = 15f
            setTextColor(color(R.color.muted))
        })
        card.addView(View(this).apply { setBackgroundColor(color(R.color.league_gold)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(18); bottomMargin = dp(14)
        })
        listOf(
            "${summary.reps}" to "practice reps",
            "${summary.streak}" to "day streak",
            summary.mainCraft to "main craft"
        ).forEach { (value, label) ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = value
                textSize = 18f
                typeface = appTypeface(Typeface.BOLD)
                setTextColor(color(R.color.ink))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(color(R.color.muted))
            })
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSpace(24)
        primaryButton("Share / save my receipt") { shareWeeklyReceipt(summary) }
        addSpace(10)
        text("Share it with a friend, or save it to Notes as a quiet reminder of your progress.", 13, R.color.muted)
    }

    private fun shareWeeklyReceipt(summary: WeeklyPracticeSummary) {
        val message = """
            UNSCROLL · WEEKLY PRACTICE RECEIPT

            ${formatFocusTime(summary.minutes)} focused
            ${summary.reps} practice reps
            ${summary.streak}-day streak
            Main craft: ${summary.mainCraft}

            Stop scrolling. Start living.
        """.trimIndent()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }, "Share practice receipt"))
    }

    private fun requestRemoteProfileIfNeeded() {
        if (!league.isSignedIn() || profileLoading || profileLoadAttempted) return
        profileLoading = true
        profileLoadAttempted = true
        league.loadProfile { result ->
            profileLoading = false
            result.fold(
                onSuccess = {
                    remoteProfile = it
                    if ((selectedTopTab == TopLevelTab.PROFILE || selectedTopTab == TopLevelTab.LEAGUE) && store.activeSession() == null) render()
                },
                onFailure = {
                    if (!profileBootstrapAttempted && store.username() != null) {
                        profileBootstrapAttempted = true
                        league.bootstrapProfile(store.username().orEmpty()) { created ->
                            if (created.isSuccess) {
                                profileLoadAttempted = false
                                requestRemoteProfileIfNeeded()
                            }
                        }
                    }
                }
            )
        }
    }

    private fun showChangeUsername() {
        preparePage(TopLevelTab.PROFILE)
        brand()
        addSpace(48)
        text("Change username", 29, R.color.ink, Typeface.BOLD)
        addSpace(22)
        val name = input("Username").apply { setText(store.username()); setSelection(text.length) }
        addSpace(22)
        primaryButton("Save username") {
            val value = name.text.toString().trim().removePrefix("@")
            if (value.length < 3) {
                name.error = "Use at least 3 characters"
            } else if (league.isSignedIn()) {
                league.bootstrapProfile(value) { result ->
                    result.fold(
                        onSuccess = {
                            store.setUsername(value)
                            invalidateRemoteLeagueData()
                            showProfile()
                        },
                        onFailure = { name.error = friendlyError(it) }
                    )
                }
            } else {
                store.setUsername(value)
                render()
            }
        }
        addSpace(10)
        textButton("Cancel") { showProfile() }
    }

    private fun showHistory() {
        preparePage(TopLevelTab.PROFILE)
        brand()
        addSpace(38)
        text("Focus history", 30, R.color.ink, Typeface.BOLD)
        addSpace(8)
        text("Every promise you started is here—completed or not.", 16, R.color.muted)
        addSpace(28)
        val entries = remoteProfile?.recentHistory ?: store.history()
        if (entries.isEmpty()) {
            text("Your first session will show up here.", 17, R.color.muted)
        } else {
            entries.forEachIndexed { index, item ->
                historyRow(item)
                if (index < entries.lastIndex) addSpace(10)
            }
        }
        addSpace(24)
        textButton("Back to profile") { showProfile() }
    }

    private fun showLeague() {
        selectedTopTab = TopLevelTab.LEAGUE
        leagueShowingFriends = false
        preparePage(TopLevelTab.LEAGUE)
        requestRemoteProfileIfNeeded()
        loadLeagueBoardIfNeeded()
        brand()
        addSpace(24)
        leagueHeader()
        addSpace(7)
        text("A private battle for the work that actually got done.", 15, R.color.muted)
        addSpace(24)

        if (!league.isSignedIn()) {
            val signInCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
                background = rounded(R.color.surface_card, 22)
            }
            signInCard.addView(TextView(this).apply {
                text = "Connect Google to enter League"
                textSize = 19f
                typeface = appTypeface(Typeface.BOLD)
                setTextColor(color(R.color.ink))
            })
            signInCard.addView(TextView(this).apply {
                text = "Your friends, verified score and history stay with your account—not just this phone."
                textSize = 15f
                setTextColor(color(R.color.muted))
                setPadding(0, dp(7), 0, dp(16))
            })
            val goToProfile = Button(this).apply {
                text = "Connect Google"
                isAllCaps = false
                setTextColor(color(R.color.night))
                background = rounded(R.color.focus_green, 16)
                setOnClickListener { showProfile() }
            }
            signInCard.addView(goToProfile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            content.addView(signInCard)
            return
        }

        val week = selectedLeagueWeek()
        addWeekNavigator(week)
        addSpace(24)
        label("YOUR FRIENDS · WEEKLY FOCUS")
        addSpace(4)
        text("Minutes completed from ${week.rangeLabel}", 14, R.color.muted)
        addSpace(10)
        val boardCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(R.color.surface_card, 22)
        }
        when {
            leagueLoading -> boardCard.addView(TextView(this).apply {
                text = "Loading verified scores…"
                textSize = 16f
                setTextColor(color(R.color.muted))
            })
            leagueError != null -> {
                boardCard.addView(TextView(this).apply {
                    text = "League is not connected yet."
                    textSize = 18f
                    typeface = appTypeface(Typeface.BOLD)
                    setTextColor(color(R.color.ink))
                })
                boardCard.addView(TextView(this).apply {
                    text = leagueError
                    textSize = 14f
                    setTextColor(color(R.color.muted))
                    setPadding(0, dp(7), 0, dp(14))
                })
                val retry = Button(this).apply {
                    text = "Try again"
                    isAllCaps = false
                    setOnClickListener { loadLeagueBoardIfNeeded(force = true) }
                }
                boardCard.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            }
            leagueBoard?.players?.isNotEmpty() == true -> {
                leagueBoard?.players?.forEachIndexed { index, player ->
                    leaguePlayerRow(boardCard, index + 1, player)
                    if (index < leagueBoard!!.players.lastIndex) {
                        boardCard.addView(Space(this), LinearLayout.LayoutParams(1, dp(10)))
                    }
                }
            }
            else -> boardCard.addView(TextView(this).apply {
                text = "Invite a friend to make this board competitive."
                textSize = 16f
                setTextColor(color(R.color.muted))
            })
        }
        content.addView(boardCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSpace(22)
        secondaryButton("Invite someone to Unscroll") { createLeagueInvite() }
        addSpace(10)
        textButton("Join with an invite code") { showJoinLeagueInvite() }
        addSpace(10)
        textButton("Refresh board") {
            loadLeagueBoardIfNeeded(force = true)
        }
    }

    private fun leagueHeader() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Focus League"
            textSize = 30f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "Friends"
            isAllCaps = false
            textSize = 14f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
            background = rounded(R.color.surface_high, 16)
            setOnClickListener { showFriends() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private data class LeagueWeek(
        val key: String,
        val title: String,
        val rangeLabel: String
    )

    /** Zero is the live week. Older weeks stay browseable; a future week never opens. */
    private fun selectedLeagueWeek(): LeagueWeek {
        val start = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val daysSinceMonday = (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
            add(Calendar.WEEK_OF_YEAR, leagueWeekOffset)
        }
        val end = start.clone() as Calendar
        end.add(Calendar.DAY_OF_YEAR, 6)
        val formatter = SimpleDateFormat("d MMM", Locale.getDefault())
        return LeagueWeek(
            key = FirebaseLeagueRepository.weekKeyForOffset(leagueWeekOffset),
            title = if (leagueWeekOffset == 0) "This week" else "Week of ${formatter.format(start.time)}",
            rangeLabel = "${formatter.format(start.time)} – ${formatter.format(end.time)}"
        )
    }

    private fun addWeekNavigator(week: LeagueWeek) {
        val navigator = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(R.color.surface_card, 20)
        }
        fun arrow(symbol: String, enabled: Boolean, action: () -> Unit): Button = Button(this).apply {
            text = symbol
            isAllCaps = false
            textSize = 28f
            setTextColor(color(if (enabled) R.color.ink else R.color.muted))
            alpha = if (enabled) 1f else .35f
            isEnabled = enabled
            background = rounded(R.color.surface_high, 14)
            setOnClickListener { action() }
        }
        navigator.addView(arrow("‹", true) {
            leagueWeekOffset -= 1
            leagueBoard = null
            leagueBoardWeekKey = null
            leagueError = null
            showLeague()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val weekCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        weekCopy.addView(TextView(this).apply {
            text = week.title
            gravity = Gravity.CENTER
            textSize = 17f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        weekCopy.addView(TextView(this).apply {
            text = week.rangeLabel
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(2), 0, 0)
        })
        navigator.addView(weekCopy, LinearLayout.LayoutParams(0, dp(52), 1f))
        navigator.addView(arrow("›", leagueWeekOffset < 0) {
            leagueWeekOffset += 1
            leagueBoard = null
            leagueBoardWeekKey = null
            leagueError = null
            showLeague()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(navigator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
    }

    private fun loadLeagueBoardIfNeeded(force: Boolean = false) {
        val week = selectedLeagueWeek()
        if (!league.isSignedIn() || leagueLoading || (!force && leagueBoardWeekKey == week.key && leagueBoard != null)) return
        val requestedWeekKey = week.key
        leagueLoading = true
        leagueError = null
        league.loadBoard(requestedWeekKey) { result ->
            leagueLoading = false
            result.fold(
                onSuccess = { board ->
                    if (selectedLeagueWeek().key == requestedWeekKey) {
                        leagueBoard = board
                        leagueBoardWeekKey = requestedWeekKey
                    }
                },
                onFailure = { error ->
                    if (selectedLeagueWeek().key == requestedWeekKey) leagueError = friendlyError(error)
                }
            )
            if (selectedTopTab == TopLevelTab.LEAGUE && store.activeSession() == null) render()
        }
    }

    /** Starts one real-time presence subscription for this active focus round. */
    private fun observeFocusPartnersForActiveSession() {
        focusPartnerListener?.remove()
        focusPartnerListener = null
        focusPartners = null
        focusPartnerError = null
        if (!league.isSignedIn()) {
            focusPartnerError = "Connect Google in Profile to focus together."
            updateActivePartnerBar()
            return
        }

        partnersLoading = true
        focusPartnerListener = league.observeFocusPartners { result ->
            partnersLoading = false
            result.fold(
                onSuccess = { partners ->
                    focusPartners = partners
                    focusPartnerError = null
                },
                onFailure = { error ->
                    focusPartners = emptyList()
                    focusPartnerError = friendlyError(error)
                }
            )
            if (store.activeSession() != null) updateActivePartnerBar()
        }
    }

    private fun stopFocusPartnerObserver() {
        focusPartnerListener?.remove()
        focusPartnerListener = null
        focusPartners = null
        partnersLoading = false
        focusPartnerError = null
        activePartnerContainer = null
    }

    /**
     * Friends never get a chat surface during focus. This is a quiet live cue:
     * who is showing up, what craft they chose, and how long their round has left.
     */
    private fun addActivePartnerBar() {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        activePartnerContainer = holder
        content.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        updateActivePartnerBar()
    }

    private fun updateActivePartnerBar() {
        val holder = activePartnerContainer ?: return
        holder.removeAllViews()
        val partners = focusPartners.orEmpty()
            .filter { it.endsAtMillis > System.currentTimeMillis() }
            .take(MAX_VISIBLE_FOCUS_PARTNERS)
        val error = focusPartnerError
        if (partners.isEmpty() && error == null) {
            holder.visibility = View.GONE
            return
        }

        holder.visibility = View.VISIBLE
        holder.setPadding(dp(16), dp(14), dp(16), dp(14))
        holder.background = rounded(
            if (partners.isEmpty()) R.color.surface_card else R.color.focus_green_muted,
            18,
            if (partners.isEmpty()) R.color.surface_high else R.color.focus_green
        )
        holder.addView(TextView(this).apply {
            text = if (partners.isEmpty()) "LIVE FOCUS STATUS" else "FOCUSING WITH YOU"
            textSize = 10f
            letterSpacing = .1f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(if (partners.isEmpty()) R.color.muted else R.color.focus_green))
        })

        if (partners.isEmpty()) {
            holder.addView(TextView(this).apply {
                text = "Live friend status is offline for this round. Your focus timer still works normally."
                textSize = 13f
                setTextColor(color(R.color.muted))
                setPadding(0, dp(7), 0, 0)
            })
            return
        }

        partners.forEachIndexed { index, partner ->
            addActivePartnerRow(holder, partner, index > 0)
        }
        val remaining = focusPartners.orEmpty().count { it.endsAtMillis > System.currentTimeMillis() } - partners.size
        if (remaining > 0) {
            holder.addView(TextView(this).apply {
                text = "+$remaining more friend${if (remaining == 1) "" else "s"} locked in"
                textSize = 12f
                typeface = appTypeface(Typeface.BOLD)
                setTextColor(color(R.color.focus_green))
                setPadding(0, dp(10), 0, 0)
            })
        }
    }

    private fun addActivePartnerRow(holder: LinearLayout, partner: FocusPartner, hasRowBefore: Boolean) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(if (hasRowBefore) 14 else 8), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = partner.username.firstOrNull()?.uppercaseChar()?.toString() ?: "F"
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.night))
            background = rounded(R.color.focus_green, 99)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val roundMinutes = partner.durationMinutes.takeIf { it > 0 } ?: 25
        val minutesLeft = ((partner.endsAtMillis - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(1L)
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        copy.addView(TextView(this).apply {
            text = "@${partner.username} · ${partner.craft}"
            textSize = 15f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        copy.addView(TextView(this).apply {
            text = "${partner.task} · $roundMinutes min · $minutesLeft min left"
            textSize = 12f
            setTextColor(color(R.color.focus_green))
            maxLines = 1
        })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        holder.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val completedFraction = ((roundMinutes - minutesLeft).toFloat() / roundMinutes)
            .coerceIn(0.08f, 1f)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(R.color.surface_high, 99)
        }
        track.addView(View(this).apply {
            background = rounded(R.color.focus_green, 99)
        }, LinearLayout.LayoutParams(0, dp(4), completedFraction))
        track.addView(View(this), LinearLayout.LayoutParams(0, dp(4), 1f - completedFraction))
        holder.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
            topMargin = dp(8)
        })
    }

    private fun showFriends() {
        selectedTopTab = TopLevelTab.LEAGUE
        leagueShowingFriends = true
        preparePage(TopLevelTab.LEAGUE)
        brand()
        addSpace(22)
        textButton("← Back to League") { showLeague() }
        addSpace(14)
        text("Your focus circle", 30, R.color.ink, Typeface.BOLD)
        addSpace(8)
        text("The people who can see your weekly focus and make it feel real.", 16, R.color.muted)
        addSpace(26)

        if (!league.isSignedIn()) {
            text("Connect Google in Profile to manage friends.", 16, R.color.muted)
            return
        }

        loadFriendsIfNeeded()
        when {
            friendsLoading -> text("Loading your friends…", 16, R.color.muted)
            friendsError != null -> {
                text("Couldn’t load your friends.", 18, R.color.ink, Typeface.BOLD)
                addSpace(6)
                text(friendsError.orEmpty(), 14, R.color.muted)
                addSpace(14)
                secondaryButton("Try again") { loadFriendsIfNeeded(force = true) }
            }
            leagueFriends.isNullOrEmpty() -> {
                val emptyCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(20), dp(20), dp(20))
                    background = rounded(R.color.surface_card, 22)
                }
                emptyCard.addView(TextView(this).apply {
                    text = "Your circle starts with one person."
                    textSize = 18f
                    typeface = appTypeface(Typeface.BOLD)
                    setTextColor(color(R.color.ink))
                })
                emptyCard.addView(TextView(this).apply {
                    text = "Invite someone who will notice when you show up."
                    textSize = 14f
                    setTextColor(color(R.color.muted))
                    setPadding(0, dp(7), 0, 0)
                })
                content.addView(emptyCard)
            }
            else -> leagueFriends.orEmpty().forEachIndexed { index, friend ->
                friendRow(friend)
                if (index < leagueFriends.orEmpty().lastIndex) addSpace(10)
            }
        }
        addSpace(24)
        secondaryButton("Invite a friend") { createLeagueInvite() }
        addSpace(10)
        textButton("Join with an invite code") { showJoinLeagueInvite() }
    }

    private fun loadFriendsIfNeeded(force: Boolean = false) {
        if (!league.isSignedIn() || friendsLoading || (!force && leagueFriends != null)) return
        friendsLoading = true
        friendsError = null
        league.loadFriends { result ->
            friendsLoading = false
            result.fold(
                onSuccess = { friends -> leagueFriends = friends },
                onFailure = { error -> friendsError = friendlyError(error) }
            )
            if (selectedTopTab == TopLevelTab.LEAGUE && store.activeSession() == null) render()
        }
    }

    private fun friendRow(friend: LeagueFriend) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(12), dp(13))
            background = rounded(R.color.surface_card, 20)
            isClickable = true
            isFocusable = true
            contentDescription = "Open @${friend.username}'s focus profile"
            setOnClickListener { showFriendProfile(friend.uid) }
        }
        row.addView(TextView(this).apply {
            text = friend.username.firstOrNull()?.uppercaseChar()?.toString() ?: "F"
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.night))
            background = rounded(R.color.focus_green, 99)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        copy.addView(TextView(this).apply {
            text = "@${friend.username}"
            textSize = 16f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        copy.addView(TextView(this).apply {
            text = "In your weekly League"
            textSize = 12f
            setTextColor(color(R.color.muted))
        })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Remove"
            isAllCaps = false
            textSize = 12f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.focus_red))
            background = rounded(R.color.surface_high, 14)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Remove @${friend.username}?")
                    .setMessage("You will both disappear from each other’s Friends League. You can always add each other again with a new invite.")
                    .setNegativeButton("Keep") { _, _ -> }
                    .setPositiveButton("Remove") { _, _ ->
                        league.removeFriend(friend.uid) { result ->
                            result.fold(
                                onSuccess = {
                                    leagueFriends = null
                                    leagueBoard = null
                                    leagueBoardWeekKey = null
                                    showFriends()
                                },
                                onFailure = { showMessage("Couldn’t remove friend", friendlyError(it)) }
                            )
                        }
                    }
                    .show()
            }
        }, LinearLayout.LayoutParams(dp(82), dp(42)))
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun leaguePlayerRow(parent: LinearLayout, rank: Int, player: LeaguePlayer) {
        val isLeader = rank == 1
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            translationY = dp(8).toFloat()
            alpha = 0f
            background = when {
                isLeader -> rounded(R.color.league_gold_muted, 18, R.color.league_gold)
                player.isYou -> rounded(R.color.surface_high, 18, R.color.divider)
                else -> rounded(R.color.surface_card, 18, R.color.divider)
            }
            if (!player.isYou) {
                isClickable = true
                isFocusable = true
                contentDescription = "Open @${player.username}'s focus profile"
                setOnClickListener { showFriendProfile(player.uid) }
            }
        }
        row.addView(TextView(this).apply {
            text = if (isLeader) "1" else "#$rank"
            gravity = Gravity.CENTER
            textSize = if (isLeader) 17f else 14f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(if (isLeader) R.color.night else R.color.focus_green))
            background = if (isLeader) rounded(R.color.league_gold, 99) else null
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        row.addView(TextView(this).apply {
            text = "@${player.username}${if (player.isYou) "  (you)" else ""}"
            textSize = 16f
            typeface = if (player.isYou || isLeader) appTypeface(Typeface.BOLD) else appTypeface()
            setTextColor(color(R.color.ink))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        row.addView(TextView(this).apply {
            text = "${player.points}m"
            textSize = if (isLeader) 19f else 16f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(if (isLeader) R.color.league_gold else R.color.focus_green))
        })
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        row.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((rank * 55L).coerceAtMost(300L))
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * A friend's page is deliberately built from the friends-visible summary
     * collections only. It never queries their private focusSessions history.
     */
    private fun showFriendProfile(friendUid: String) {
        selectedTopTab = TopLevelTab.LEAGUE
        leagueShowingFriends = false
        preparePage(TopLevelTab.LEAGUE)
        val week = selectedLeagueWeek()
        requestFriendProfileIfNeeded(friendUid, week.key)

        brand()
        addSpace(20)
        textButton("← Back to League") { showLeague() }
        addSpace(14)

        when {
            friendProfileLoading -> {
                text("Loading focus profile…", 18, R.color.muted)
            }
            friendProfileError != null -> {
                text("Couldn’t open this profile.", 22, R.color.ink, Typeface.BOLD)
                addSpace(8)
                text(friendProfileError.orEmpty(), 15, R.color.muted)
                addSpace(20)
                secondaryButton("Try again") {
                    requestFriendProfileIfNeeded(friendUid, week.key, force = true)
                }
            }
            friendProfile?.uid == friendUid && friendProfileWeekKey == week.key -> {
                renderFriendProfile(friendProfile!!, week)
            }
            else -> {
                text("Loading focus profile…", 18, R.color.muted)
            }
        }
    }

    private fun requestFriendProfileIfNeeded(friendUid: String, weekKey: String, force: Boolean = false) {
        val isCached = friendProfile?.uid == friendUid && friendProfileWeekKey == weekKey
        if (!league.isSignedIn() || friendProfileLoading || (!force && isCached && friendProfileError == null)) return

        friendProfileUid = friendUid
        friendProfileWeekKey = weekKey
        friendProfile = null
        friendProfileLoading = true
        friendProfileError = null
        league.loadFriendProfile(friendUid, weekKey) { result ->
            // Ignore an older answer if the user switched person/week first.
            if (friendProfileUid != friendUid || friendProfileWeekKey != weekKey) return@loadFriendProfile
            friendProfileLoading = false
            result.fold(
                onSuccess = { profile -> friendProfile = profile },
                onFailure = { error -> friendProfileError = friendlyError(error) }
            )
            if (selectedTopTab == TopLevelTab.LEAGUE && store.activeSession() == null) {
                showFriendProfile(friendUid)
            }
        }
    }

    private fun renderFriendProfile(profile: FriendFocusProfile, week: LeagueWeek) {
        val hero = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(R.color.surface_card, 22)
        }
        hero.addView(TextView(this).apply {
            text = profile.username.firstOrNull()?.uppercaseChar()?.toString() ?: "F"
            gravity = Gravity.CENTER
            textSize = 28f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.night))
            background = rounded(R.color.focus_green, 99)
        }, LinearLayout.LayoutParams(dp(66), dp(66)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        copy.addView(TextView(this).apply {
            text = "@${profile.username}"
            textSize = 24f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        copy.addView(TextView(this).apply {
            text = "${profile.mainCraft} · Level ${practiceLevel(profile.totalFocusMinutes)}"
            textSize = 14f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.focus_green))
            setPadding(0, dp(3), 0, 0)
        })
        copy.addView(TextView(this).apply {
            text = "${formatFocusTime(profile.totalFocusMinutes)} of deliberate practice"
            textSize = 13f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(3), 0, 0)
        })
        hero.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        profile.activePractice?.let { active ->
            addSpace(14)
            activeFriendStrip(profile.username, active)
        }

        addSpace(24)
        metricRow(
            "Level ${practiceLevel(profile.totalFocusMinutes)}" to "practice",
            formatFocusTime(profile.totalFocusMinutes) to "all time",
            "${profile.completedRounds}" to "rounds kept"
        )

        addSpace(28)
        label("${week.title.uppercase(Locale.getDefault())} · FOCUS RHYTHM")
        addSpace(8)
        val weekCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = rounded(R.color.surface_card, 22)
        }
        weekCard.addView(TextView(this).apply {
            text = if (profile.week.totalMinutes == 0) {
                "No completed rounds in this week yet."
            } else {
                "${profile.week.totalMinutes} focused minutes · ${week.rangeLabel}"
            }
            textSize = 16f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        weekCard.addView(FocusWeekView(this).apply {
            setDays(profile.week.days)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(142)))
        content.addView(weekCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addSpace(28)
        label("RECENT COMPLETED ROUNDS")
        addSpace(8)
        if (profile.recentWins.isEmpty()) {
            text("Their completed focus rounds will appear here.", 15, R.color.muted)
        } else {
            profile.recentWins.forEachIndexed { index, win ->
                friendWinRow(win)
                if (index < profile.recentWins.lastIndex) addSpace(8)
            }
        }
        addSpace(18)
        text(
            "Only completed rounds are shared. Work names appear only when a friend chooses to share them.",
            13,
            R.color.muted
        )
    }

    private fun activeFriendStrip(username: String, active: FocusPartner) {
        val minutesLeft = ((active.endsAtMillis - System.currentTimeMillis() + 59_999L) / 60_000L)
            .coerceAtLeast(1L)
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(16))
            background = rounded(R.color.focus_green_muted, 20, R.color.focus_green)
        }
        strip.addView(TextView(this).apply {
            text = "LOCKED IN NOW"
            textSize = 11f
            letterSpacing = .1f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.focus_green))
        })
        strip.addView(TextView(this).apply {
            text = "@$username is practising ${active.craft}"
            textSize = 18f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
            setPadding(0, dp(6), 0, 0)
        })
        strip.addView(TextView(this).apply {
            text = "${active.task} · ${active.durationMinutes} min round · $minutesLeft min left"
            textSize = 13f
            setTextColor(color(R.color.focus_green))
            setPadding(0, dp(4), 0, 0)
            maxLines = 1
        })
        content.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun friendWinRow(win: SharedFocusWin) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(R.color.surface_card, 18)
        }
        row.addView(TextView(this).apply {
            text = win.task ?: "${win.craft} practice"
            textSize = 16f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
            maxLines = 1
        })
        row.addView(TextView(this).apply {
            text = "${win.craft} · ${win.durationMinutes} min · ${formatDate(win.completedAtMillis)}"
            textSize = 13f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun createLeagueInvite() {
        league.createFriendInvite { result ->
            result.fold(
                onSuccess = { shareLeagueInvite(it) },
                onFailure = { showMessage("Couldn’t create invite", friendlyError(it)) }
            )
        }
    }

    private fun shareLeagueInvite(code: String) {
        val inviteLink = Uri.Builder()
            .scheme(INVITE_SCHEME)
            .authority(INVITE_HOST)
            .appendPath(INVITE_JOIN_PATH)
            .appendQueryParameter("code", code)
            .build()
            .toString()
        val invite = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Join my Unscroll Focus League—let’s compete on real focus.\n\nOpen this link in Unscroll:\n$inviteLink\n\nIf the app doesn’t open, use this invite code in League → Join:\n$code\n\nThis invite expires in 7 days."
            )
        }
        startActivity(Intent.createChooser(invite, "Invite to Unscroll"))
    }

    private fun showJoinLeagueInvite(initialCode: String? = pendingInviteCode) {
        selectedTopTab = TopLevelTab.LEAGUE
        leagueShowingFriends = false
        preparePage(TopLevelTab.LEAGUE)
        brand()
        addSpace(42)
        text("Join a friend", 30, R.color.ink, Typeface.BOLD)
        addSpace(8)
        text(
            if (initialCode != null) "You’ve opened a League invite. Connect your account, then join your friend."
            else "Paste the one-time code your friend shared with you.",
            16,
            R.color.muted
        )
        addSpace(28)
        val code = input("Invite code").apply {
            initialCode?.let {
                setText(it)
                setSelection(text.length)
            }
        }
        addSpace(24)
        if (!league.isSignedIn()) {
            primaryButton("Connect Google to join") {
                googleAuth.signIn(this@MainActivity) { outcome ->
                    if (!outcome.signedIn) {
                        showMessage("Google sign-in", outcome.message ?: "Couldn’t connect your Google account.")
                        return@signIn
                    }
                    league.bootstrapProfile(store.username().orEmpty()) { profileResult ->
                        profileResult.fold(
                            onSuccess = {
                                invalidateRemoteLeagueData()
                                showJoinLeagueInvite(code.text.toString().trim())
                            },
                            onFailure = { code.error = friendlyError(it) }
                        )
                    }
                }
            }
            addSpace(10)
        }
        primaryButton(if (league.isSignedIn()) "Join Focus League" else "Join after connecting Google") {
            if (!league.isSignedIn()) {
                showMessage("Connect Google", "Connect Google first so we can add you to your friend’s League.")
                return@primaryButton
            }
            val value = code.text.toString().trim()
            if (value.isBlank()) {
                code.error = "Paste an invite code"
            } else {
                league.acceptFriendInvite(value) { result ->
                    result.fold(
                        onSuccess = {
                            pendingInviteCode = null
                            leagueBoard = null
                            leagueBoardWeekKey = null
                            leagueFriends = null
                            leagueError = null
                            showLeague()
                        },
                        onFailure = { code.error = friendlyError(it) }
                    )
                }
            }
        }
        addSpace(10)
        textButton("Back to League") {
            pendingInviteCode = null
            showLeague()
        }
    }

    private fun receiveLeagueInvite(intent: Intent): Boolean {
        val link = intent.data ?: return false
        if (intent.action != Intent.ACTION_VIEW ||
            link.scheme != INVITE_SCHEME ||
            link.host != INVITE_HOST ||
            link.path != "/$INVITE_JOIN_PATH"
        ) return false

        val code = link.getQueryParameter("code")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { INVITE_CODE_PATTERN.matches(it) }
            ?: return false
        pendingInviteCode = code
        selectedTopTab = TopLevelTab.LEAGUE
        return true
    }

    private fun historyRow(item: FocusHistoryEntry) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = rounded(R.color.surface_card, 18)
        }
        val icon = if (item.completed) "✓" else "×"
        val status = when {
            item.completed -> "Completed"
            item.interrupted -> "Focus Lock exited"
            else -> "Ended early"
        }
        row.addView(TextView(this).apply {
            text = "$icon  ${item.task}"
            textSize = 17f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(if (item.completed) R.color.ink else R.color.muted))
        })
        row.addView(TextView(this).apply {
            val minutes = item.durationMillis / 60_000L
            text = "$status · ${item.craft} · $minutes min${if (item.isRescue) " · rescue" else ""} · ${formatDate(item.startedAt)}"
            textSize = 14f
            setTextColor(color(if (item.completed) R.color.focus_green else R.color.muted))
        })
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun beginFocus(task: String, duration: Int, craft: String = craftForTask(task)) {
        if (focusStartInFlight) return
        val startedAt = System.currentTimeMillis()
        // Lock first, sync second. A slow connection must never make someone tap
        // Start repeatedly or leave a gap in which they can reopen distractions.
        store.setCurrentCraft(craft)
        startFocusLocally(task, duration, startedAtMillis = startedAt, craft = craft)
        if (league.isSignedIn()) {
            focusStartInFlight = true
            league.startFocus(task, duration, craft) { result ->
                focusStartInFlight = false
                result.fold(
                    onSuccess = { remote ->
                        if (store.activeSession() != null) {
                            store.attachRemoteSession(remote.sessionId)
                            focusPartnerError = null
                        } else {
                            // Focus Lock failed or was exited while the network
                            // request was in flight. Close the remote session so
                            // it cannot later become a League win.
                            league.endFocus(remote.sessionId) { }
                        }
                    },
                    // Focus still works offline; the only thing skipped is the
                    // server-verified League score for this one round.
                    onFailure = { _ ->
                        focusPartnerError = "Live focus could not connect for this round."
                        if (store.activeSession() != null) updateActivePartnerBar()
                    }
                )
            }
        }
    }

    private fun startFocusLocally(
        task: String,
        duration: Int,
        remoteSessionId: String? = null,
        startedAtMillis: Long = System.currentTimeMillis(),
        craft: String = store.currentCraft()
    ) {
        ReminderScheduler.cancel(this)
        store.clearNextTask()
        store.start(task, duration, remoteSessionId, startedAtMillis, craft = craft)
        FocusSilenceController(this).activate()
        render()
        handler.post { requestFocusLock() }
    }

    private fun startRescueRound(offer: RescueOffer) {
        ReminderScheduler.cancel(this)
        store.clearNextTask()
        store.startRescue(offer.task, offer.craft)
        FocusSilenceController(this).activate()
        render()
        handler.post { requestFocusLock() }
    }

    /** Uses Android's built-in screen pinning instead of an Accessibility overlay. */
    private fun requestFocusLock() {
        if (store.activeSession() == null) return
        try {
            startLockTask()
            // startLockTask can bring up Android's own pinning confirmation.
            // Verify after that hand-off instead of trusting the call itself.
            handler.postDelayed({
                if (store.activeSession() == null) return@postDelayed
                if (FocusLockMonitor.isActive(this)) {
                    store.markFocusLockVerified()
                } else {
                    store.interruptForFocusLockExit()
                    render()
                }
            }, 8_000)
        } catch (_: Exception) {
            store.interruptForFocusLockExit()
            render()
        }
    }

    private fun releaseFocusLock() {
        runCatching { stopLockTask() }
    }

    private fun craftForTask(task: String): String {
        val words = " ${task.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()} "
        fun matches(terms: List<String>) = terms.any { term -> words.contains(" ${term.lowercase(Locale.ROOT)} ") }
        return when {
            matches(listOf("code", "coding", "dsa", "leetcode", "program", "programming", "debug", "debugging", "android", "web dev")) -> "Coding"
            matches(listOf("guitar", "piano", "music", "sing", "singing", "drum")) -> "Music"
            matches(listOf("gym", "workout", "run", "running", "yoga", "exercise", "exercising", "training")) -> "Fitness"
            matches(listOf("study", "studying", "exam", "revision", "homework", "lecture", "assignment", "read", "reading")) -> "Study"
            matches(listOf("spanish", "french", "german", "english", "language", "vocab")) -> "Language"
            matches(listOf("draw", "drawing", "art", "design", "paint", "painting", "sketch", "sketching", "illustrate", "illustration")) -> "Art"
            matches(listOf("write", "writing", "essay", "journal", "blog")) -> "Writing"
            else -> task.trim().take(32).ifBlank { "Practice" }
        }
    }

    private fun syncPendingLeagueUpdates() {
        if (!league.isSignedIn()) return
        val completionId = store.pendingRemoteCompletionId()
        if (completionId != null && completingRemoteSessionId != completionId) {
            completingRemoteSessionId = completionId
            league.completeFocus(completionId) { result ->
                completingRemoteSessionId = null
                if (result.isSuccess) {
                    store.clearPendingRemoteCompletion()
                    focusPartners = null
                    invalidateRemoteLeagueData()
                }
            }
        }
        val earlyEndId = store.pendingRemoteEarlyEndId()
        if (earlyEndId != null && endingRemoteSessionId != earlyEndId) {
            endingRemoteSessionId = earlyEndId
            league.endFocus(earlyEndId) { result ->
                endingRemoteSessionId = null
                if (result.isSuccess) {
                    store.clearPendingRemoteEarlyEnd()
                    focusPartners = null
                    invalidateRemoteLeagueData()
                }
            }
        }
    }

    private fun invalidateRemoteLeagueData() {
        remoteProfile = null
        profileLoadAttempted = false
        profileBootstrapAttempted = false
        leagueBoard = null
        leagueBoardWeekKey = null
        leagueError = null
        friendProfile = null
        friendProfileUid = null
        friendProfileWeekKey = null
        friendProfileLoading = false
        friendProfileError = null
        focusPartners = null
        if (store.activeSession() == null && (selectedTopTab == TopLevelTab.PROFILE || selectedTopTab == TopLevelTab.LEAGUE)) {
            render()
        }
    }

    private fun friendlyError(error: Throwable): String =
        error.localizedMessage?.takeIf { it.isNotBlank() } ?: "Check your connection and try again."

    private fun focusLockCard() {
        val soundsQuiet = isDndAccessEnabled()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(18))
            background = rounded(R.color.surface_card, 22)
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "▣"
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.night))
            background = rounded(R.color.focus_green, 99)
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        title.addView(TextView(this).apply {
            text = "Focus Lock"
            textSize = 18f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        title.addView(TextView(this).apply {
            text = "Every round pins Unscroll. No apps to configure."
            textSize = 13f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "ON"
            gravity = Gravity.CENTER
            textSize = 10f
            letterSpacing = .08f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.night))
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(R.color.focus_green, 99)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
        card.addView(header)
        card.addView(Space(this), LinearLayout.LayoutParams(1, dp(16)))

        val checks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Locked" to true,
            "No apps" to true,
            "Quiet" to soundsQuiet
        ).forEachIndexed { index, (label, ready) ->
            checks.addView(TextView(this).apply {
                text = "${if (ready) "✓" else "○"}  $label"
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = appTypeface(Typeface.BOLD)
                setTextColor(color(if (ready) R.color.focus_green else R.color.muted))
                background = rounded(R.color.surface_high, 13)
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (index < 2) marginEnd = dp(7)
            })
        }
        card.addView(checks, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        card.addView(TextView(this).apply {
            text = "Uses Android Screen Pinning, not Accessibility—so UPI and banking apps are not given a screen-control service to flag."
            textSize = 13f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(12), 0, 0)
        })
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun isDndAccessEnabled(): Boolean {
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return notifications.isNotificationPolicyAccessGranted
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31)
    }

    private fun brand() = text("UNSCROLL", 14, R.color.focus_green, Typeface.BOLD)

    private fun profileHero() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(R.color.surface_card, 24)
        }
        val initial = store.username()?.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
        val avatar = TextView(this).apply {
            text = initial
            gravity = Gravity.CENTER
            textSize = 27f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.night))
            background = rounded(R.color.focus_green_bright, 99)
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(62), dp(62)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        copy.addView(TextView(this).apply {
            text = "@${store.username()}"
            textSize = 21f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        copy.addView(TextView(this).apply {
            text = "Reclaim your attention. Reclaim your life."
            textSize = 15f
            setTextColor(color(R.color.muted))
        })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun googleAccountCard() {
        val signedIn = googleAuth.isSignedIn(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = rounded(R.color.surface_high, 18)
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply {
            text = if (signedIn) "Google account connected" else "Keep your League identity"
            textSize = 15f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.ink))
        })
        copy.addView(TextView(this).apply {
            text = if (signedIn) googleAuth.accountLabel(this@MainActivity) ?: "Connected with Google" else
                "Sign in to keep friends and scores when you change phones."
            textSize = 13f
            setTextColor(color(R.color.muted))
        })
        card.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val action = Button(this).apply {
            text = if (signedIn) "Sign out" else "Google"
            isAllCaps = false
            textSize = 13f
            setTextColor(color(if (signedIn) R.color.muted else R.color.night))
            background = rounded(if (signedIn) R.color.surface else R.color.focus_green, 14)
            setOnClickListener {
                if (signedIn) {
                    googleAuth.signOut(this@MainActivity) { render() }
                } else {
                    googleAuth.signIn(this@MainActivity) { outcome ->
                        if (outcome.signedIn) {
                            league.bootstrapProfile(store.username().orEmpty()) { profileResult ->
                                profileResult.fold(
                                    onSuccess = {
                                        invalidateRemoteLeagueData()
                                        render()
                                    },
                                    onFailure = {
                                        render()
                                        showMessage("Google connected", "Your Google account is connected, but League setup needs one retry: ${friendlyError(it)}")
                                    }
                                )
                            }
                        } else {
                            showMessage("Google sign-in", outcome.message ?: "Couldn’t connect your Google account.")
                        }
                    }
                }
            }
        }
        card.addView(action, LinearLayout.LayoutParams(dp(88), dp(42)))
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** The social layer is useful only if it stays a choice, not surveillance. */
    private fun sharingPreferenceCard() {
        val enabled = remoteProfile?.shareWorkNames == true
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
            background = rounded(R.color.surface_card, 18)
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply {
            text = "SHARE WORK NAMES"
            textSize = 11f
            letterSpacing = .08f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(R.color.focus_green))
        })
        copy.addView(TextView(this).apply {
            text = if (enabled) "On · friends can see completed task names."
            else "Off · friends see only craft, time and duration."
            textSize = 14f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Button(this).apply {
            text = if (enabled) "On" else "Off"
            isAllCaps = false
            textSize = 13f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(if (enabled) R.color.night else R.color.ink))
            background = rounded(if (enabled) R.color.focus_green else R.color.surface_high, 14)
            contentDescription = "${if (enabled) "Turn off" else "Turn on"} sharing work names with friends"
            setOnClickListener {
                if (!league.isSignedIn()) {
                    showMessage("Connect Google", "Connect your Google account before changing League privacy.")
                    return@setOnClickListener
                }
                isEnabled = false
                league.setShareWorkNames(!enabled) { result ->
                    result.fold(
                        onSuccess = {
                            remoteProfile = remoteProfile?.copy(shareWorkNames = !enabled)
                            showProfile()
                        },
                        onFailure = { error ->
                            isEnabled = true
                            showMessage("Couldn’t update privacy", friendlyError(error))
                        }
                    )
                }
            }
        }
        card.addView(toggle, LinearLayout.LayoutParams(dp(68), dp(42)))
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Okay", null)
            .show()
    }

    private fun metricRow(vararg metrics: Pair<String, String>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.forEachIndexed { index, (value, caption) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(14), dp(12), dp(13))
                background = rounded(R.color.surface_card, 18)
            }
            card.addView(TextView(this).apply {
                text = value
                textSize = 20f
                typeface = appTypeface(Typeface.BOLD)
                setTextColor(color(R.color.focus_green))
            })
            card.addView(TextView(this).apply {
                text = caption
                textSize = 12f
                setTextColor(color(R.color.muted))
            })
            row.addView(card, LinearLayout.LayoutParams(0, dp(76), 1f).apply {
                if (index < metrics.lastIndex) marginEnd = dp(8)
            })
        }
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun formatFocusTime(minutes: Int): String = when {
        minutes <= 0 -> "0m"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }

    /** One focused minute is one XP; each three-hour block earns the next level. */
    private fun practiceLevel(minutes: Int): Int = 1 + (minutes.coerceAtLeast(0) / XP_PER_LEVEL_MINUTES)

    private fun choiceRow(options: List<Int>, selected: Int, onSelected: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { index, minutes ->
            val button = Button(this).apply {
                text = "$minutes min"
                isAllCaps = false
                textSize = 15f
                typeface = appTypeface(if (minutes == selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(color(if (minutes == selected) R.color.night else R.color.ink))
                background = rounded(if (minutes == selected) R.color.focus_green else R.color.surface_high, 18)
                setOnClickListener {
                    animate().scaleX(.95f).scaleY(.95f).setDuration(80).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(DecelerateInterpolator()).start()
                    }.start()
                    onSelected(minutes)
                }
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (index < options.lastIndex) marginEnd = dp(8)
            })
        }
        content.addView(row)
    }

    private fun input(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(color(R.color.muted))
        setTextColor(color(R.color.ink))
        textSize = 18f
        maxLines = 2
        setPadding(dp(16), 0, dp(16), 0)
        background = rounded(R.color.surface_card, 18, R.color.divider)
        setOnFocusChangeListener { _, focused ->
            background = rounded(R.color.surface_card, 18, if (focused) R.color.focus_green else R.color.divider)
        }
        content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = addButton(label, R.color.focus_green, R.color.night, onClick)
    private fun secondaryButton(label: String, onClick: () -> Unit) = addButton(label, R.color.surface_high, R.color.ink, onClick)

    private fun addButton(label: String, background: Int, textColor: Int, onClick: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            typeface = appTypeface(Typeface.BOLD)
            setTextColor(color(textColor))
            this.background = rounded(background, 18)
            setOnClickListener { onClick() }
        }
        content.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
    }

    private fun textButton(label: String, onClick: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(color(R.color.muted))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }
        content.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
    }

    private fun text(value: String, size: Int, color: Int, style: Int = Typeface.NORMAL): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(this@MainActivity.color(color))
        typeface = appTypeface(style)
        content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun label(value: String) = text(value, 12, R.color.muted, Typeface.BOLD)
    private fun addSpace(height: Int) {
        val snapped = ((height / 4f).roundToInt() * 4).coerceAtLeast(4)
        content.addView(Space(this), LinearLayout.LayoutParams(1, dp(snapped)))
    }
    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(
        fill: Int,
        radius: Int,
        stroke: Int? = if (fill == R.color.surface || fill == R.color.surface_card) R.color.divider else null,
        strokeWidth: Int = 1
    ) = GradientDrawable().apply {
        setColor(color(fill))
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(strokeWidth), color(it)) }
    }
    private fun formatDate(time: Long): String = SimpleDateFormat("d MMM · h:mm a", Locale.getDefault()).format(Date(time))

    companion object {
        const val EXTRA_OPEN_NEXT_MOVE = "open_next_move"
        private const val XP_PER_LEVEL_MINUTES = 180
        private const val INVITE_SCHEME = "unscroll"
        private const val INVITE_HOST = "league"
        private const val INVITE_JOIN_PATH = "join"
        private const val MAX_VISIBLE_FOCUS_PARTNERS = 2
        private val INVITE_CODE_PATTERN = Regex("^[a-f0-9]{32}$")
    }
}
