package dev.buddy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        installSplashScreen()   // brand splash (Android 12+ API, back-ported), fading into the app
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent { BuddyTheme { Surface(Modifier.fillMaxSize(), color = colors.background) { BuddyApp(settings) } } }
    }
}

/** Opening another screen can fail (no handler, or a permission); that should be a message, never a crash. */
private fun Context.safeStart(intent: Intent) {
    try { startActivity(intent) } catch (e: RuntimeException) { Toast.makeText(this, "couldn't open that", Toast.LENGTH_SHORT).show() }
}

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Android 14+ can be told to offer whole-screen sharing only; older versions show the usual picker. */
private fun screenCaptureIntent(context: Context): Intent {
    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    return if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    else manager.createScreenCaptureIntent()
}

/** Everything the screens show and edit; each change is saved straight away. */
private class AppState(private val context: Context, val settings: Settings) {
    var provider by mutableStateOf(settings.provider)
    var voiceChoice by mutableStateOf(settings.voiceChoice)
    var speechLanguage by mutableStateOf(settings.speechLanguage)
    var speechRate by mutableFloatStateOf(settings.speechRate)
    var geminiKey by mutableStateOf(settings.geminiKey)
    var geminiModel by mutableStateOf(settings.geminiModel)
    var claudeKey by mutableStateOf(settings.claudeKey)
    var claudeModel by mutableStateOf(settings.claudeModel)
    var drawTags by mutableStateOf(settings.drawTags)
    var fetched by mutableStateOf<List<String>>(emptyList())
    var overlayOk by mutableStateOf(false)
    var micOk by mutableStateOf(false)
    var notifOk by mutableStateOf(false)
    var a11yOn by mutableStateOf(false)
    var claudeInstalled by mutableStateOf(false)
    var a11yConnected by mutableStateOf(false)
    var notifAccess by mutableStateOf(false)
    var calendarOk by mutableStateOf(false)
    var conversation by mutableStateOf(settings.conversationMode)
    var modeNote by mutableStateOf<String?>(null)
    var crashText by mutableStateOf("")

    /** Phone voice is the default (fastest); Gemini is an explicit choice. */
    val voice get() = voiceChoice ?: VoiceEngine.PHONE

    init { refresh() }

    /** Permissions can change while the user is in system settings, so this runs on every resume. */
    fun refresh() {
        overlayOk = AndroidSettings.canDrawOverlays(context)
        micOk = context.has(Manifest.permission.RECORD_AUDIO)
        notifOk = Build.VERSION.SDK_INT < 33 || context.has(Manifest.permission.POST_NOTIFICATIONS)
        a11yOn = BuddyAccessibilityService.isEnabled(context)
        a11yConnected = BuddyAccessibilityService.instance != null
        notifAccess = context.notificationAccessEnabled()
        crashText = CrashLog.read(context)
        calendarOk = context.hasCalendarPermission()
        claudeInstalled = context.isClaudeAppInstalled()
    }

    fun pick(p: Provider) { provider = p; settings.provider = p }
    fun pick(v: VoiceEngine) { voiceChoice = v; settings.voiceChoice = v }
    fun saveClaudeKey(raw: String) {
        claudeKey = cleanKey(raw); settings.claudeKey = raw
        // A real-looking key means the user wants the API: switch to it (it's the mode that can control the phone).
        if (looksLikeClaudeKey(claudeKey) && provider != Provider.CLAUDE) {
            pick(Provider.CLAUDE)
            modeNote = "Claude API mode on: Claude can now control your phone"
        }
    }
    fun saveGeminiKey(raw: String) { geminiKey = raw; settings.geminiKey = raw }
    fun saveClaudeModel(m: String) { claudeModel = m; settings.claudeModel = m }
    fun saveGeminiModel(m: String) { geminiModel = m; settings.geminiModel = m }
    fun setLanguage(tag: String) { speechLanguage = tag; settings.speechLanguage = tag }
    fun setDraw(on: Boolean) { drawTags = on; settings.drawTags = on }
    fun saveConversation(on: Boolean) { conversation = on; settings.conversationMode = on }
}

private class Actions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val grantOverlay: () -> Unit,
    val grantMic: () -> Unit,
    val grantNotifications: () -> Unit,
    val openAccessibility: () -> Unit,
    val openNotificationAccess: () -> Unit,
    val grantCalendar: () -> Unit,
)

@Composable
private fun BuddyApp(settings: Settings) {
    val context = LocalContext.current
    val state = remember { AppState(context, settings) }
    val running by BuddyService.running.collectAsState()
    var onboarding by remember { mutableStateOf(!settings.onboarded) }

    val appScope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state.refresh()
        appScope.launch { UpdateCheck.maybeCheck(context) }   // at most once an hour; sideload builds only
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state.micOk = it }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state.notifOk = it }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state.calendarOk = it }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == android.app.Activity.RESULT_OK && data != null) BuddyService.start(context, r.resultCode, data)
    }

    val actions = Actions(
        start = {
            val problem = when {
                !settings.isConfigured -> "Enter your Claude API key first"
                !settings.voiceReady -> "Enter a Gemini API key, or switch voice recognition to Phone"
                !state.overlayOk -> "Allow drawing over other apps first"
                !state.micOk -> "Allow the microphone first"
                else -> null
            }
            appScope.launch { UpdateCheck.maybeCheck(context) }
            when {
                problem != null -> Toast.makeText(context, problem, Toast.LENGTH_SHORT).show()
                Build.VERSION.SDK_INT >= 30 && BuddyAccessibilityService.instance != null -> BuddyService.start(context)
                else -> projectionLauncher.launch(screenCaptureIntent(context))
            }
        },
        stop = { BuddyService.stop(context) },
        grantOverlay = {
            context.safeStart(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        },
        grantMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        grantNotifications = { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        openAccessibility = { context.safeStart(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
        openNotificationAccess = { context.safeStart(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        grantCalendar = { calendarLauncher.launch(Manifest.permission.READ_CALENDAR) },
    )

    if (onboarding) Onboarding(state, actions) { settings.onboarded = true; onboarding = false }
    else Home(state, actions, running)
}

// ---------------------------------------------------------------------------------------------------------------------
// Shared pieces

@Composable
private fun ApiKeyField(state: AppState) {
    val bad = state.claudeKey.isNotEmpty() && !looksLikeClaudeKey(state.claudeKey)
    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        OutlinedTextField(
            value = state.claudeKey, onValueChange = state::saveClaudeKey,
            label = { Text("Anthropic API key") }, singleLine = true, isError = bad,
            supportingText = {
                Text(if (bad) "this doesn't look like a Claude API key (starts with sk-ant-)" else "${keyStatus(state.claudeKey)}. From console.anthropic.com, with prepaid credit.")
            },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun accessibilityRowSubtitle(on: Boolean) = if (on) "Claude can tap, type and scroll when you ask" else "Lets Claude control the phone (Claude API mode)"

@Composable
private fun RestrictedSettingsHelp() = Footnote(
    "On Android 13+, if the Buddy toggle is greyed out: Settings → Apps → Buddy → ⋮ (top right) → Allow restricted settings, then try again.",
)

// ---------------------------------------------------------------------------------------------------------------------
// Onboarding: four full-bleed pages with a soft brand gradient header, a page-dot indicator and one pill button each.

@Composable
private fun Onboarding(state: AppState, actions: Actions, finish: () -> Unit) {
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    val reduce = rememberReduceMotion()
    val context = LocalContext.current
    fun next() { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(colors.accent.copy(alpha = 0.28f), Color.Transparent))))
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                // pages cross-fade as they swipe (plain pages when animations are off)
                val fade = if (reduce) 1f else pageFade((pager.currentPage + pager.currentPageOffsetFraction) - page)
                Column(Modifier.fillMaxSize().graphicsLayer { alpha = fade }.verticalScroll(rememberScrollState())) {
                    when (page) {
                        0 -> {
                            Spacer(Modifier.height(72.dp))
                            ScreenTitle("Meet Buddy")
                            Text("Claude, floating over your phone.", style = MaterialTheme.typography.titleLarge, color = colors.labelSecondary, modifier = Modifier.padding(start = 4.dp))
                            Spacer(Modifier.height(32.dp))
                            GroupCard {
                                SettingRow("Hold the bubble and ask", "Talk about whatever is on your screen", Icons.Default.Call, Tint.Brand)
                                Hairline()
                                SettingRow("Claude answers", "Out loud, with circles and arrows drawn on your screen", Icons.Default.Star, Tint.Orange)
                                Hairline()
                                SettingRow("Let Claude do it", "With Claude API it can tap, type and open apps for you", Icons.Default.Build, Tint.Blue)
                            }
                        }
                        1 -> {
                            Spacer(Modifier.height(48.dp))
                            ScreenTitle("Choose the brain")
                            Text("You can change this any time.", style = MaterialTheme.typography.bodyLarge, color = colors.labelSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 16.dp))
                            GroupCard {
                                SettingRow("Claude app", "Free. Uses the Claude app you're signed in to.", Icons.Default.Star, Tint.Orange, onClick = { state.pick(Provider.CLAUDE_APP) }) {
                                    if (state.provider == Provider.CLAUDE_APP) CheckMark()
                                }
                                Hairline()
                                SettingRow("Claude API", "Fast. Claude can control the phone. Pay per use.", Icons.Default.Build, Tint.Blue, onClick = { state.pick(Provider.CLAUDE) }) {
                                    if (state.provider == Provider.CLAUDE) CheckMark()
                                }
                                if (state.provider == Provider.CLAUDE) { Hairline(); ApiKeyField(state) }
                                if (state.provider == Provider.CLAUDE_APP && !state.claudeInstalled) {
                                    Hairline()
                                    SettingRow("Get the Claude app", "It isn't installed yet", Icons.Default.Info, Tint.Grey, onClick = { context.openClaudeInPlayStore() }) { Chevron() }
                                }
                            }
                            state.modeNote?.let { Footnote(it) }
                        }
                        2 -> {
                            Spacer(Modifier.height(48.dp))
                            ScreenTitle("Allow a few things")
                            Spacer(Modifier.height(12.dp))
                            GroupCard {
                                PermissionRow("Draw over other apps", "Shows the bubble", Icons.Default.Home, Tint.Blue, state.overlayOk, actions.grantOverlay)
                                Hairline()
                                PermissionRow("Microphone", "Hears your question", Icons.Default.Call, Tint.Brand, state.micOk, actions.grantMic)
                                Hairline()
                                PermissionRow("Notifications", "Lets you close Buddy from the shade", Icons.Default.Notifications, Tint.Red, state.notifOk, actions.grantNotifications)
                                Hairline()
                                SettingRow("Accessibility", "Optional: lets Claude control the phone", Icons.Default.Settings, Tint.Grey, onClick = actions.openAccessibility) {
                                    if (state.a11yOn) CheckMark(colors.success) else Chevron()
                                }
                            }
                            if (Build.VERSION.SDK_INT >= 33 && !state.a11yOn) RestrictedSettingsHelp()
                        }
                        else -> {
                            Spacer(Modifier.height(72.dp))
                            ScreenTitle("You're ready")
                            Text("Hold the floating bubble, speak, and let go.", style = MaterialTheme.typography.titleLarge, color = colors.labelSecondary, modifier = Modifier.padding(start = 4.dp))
                            Spacer(Modifier.height(24.dp))
                            Footnote("To close Buddy: drag the bubble to the ✕, or tap Stop in the app or the notification.")
                        }
                    }
                }
            }
            Dots(pager.currentPage, 4)
            Spacer(Modifier.height(16.dp))
            if (pager.currentPage < 3) PillButton("Continue") { next() }
            else PillButton("Start Buddy") { actions.start(); finish() }
        }
    }
}

@Composable
private fun Dots(current: Int, count: Int) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(count) {
                val size by androidx.compose.animation.core.animateDpAsState(if (it == current) 8.dp else 6.dp, motion(), label = "dot")
                Box(Modifier.size(size).clip(CircleShape).background(if (it == current) colors.accent else colors.separator))
            }
        }
    }
}

/** The hero control: a big capsule that starts or stops Buddy, with the live status under it. */
@Composable
private fun StartCapsule(running: Boolean, status: String, actions: Actions) {
    val brush = if (running) Brush.horizontalGradient(listOf(Color(0xFFFF6B5E), Color(0xFFE5392F)))
    else Brush.horizontalGradient(listOf(Color(0xFFF0A07B), colors.accent, Color(0xFFC4573A)))
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, if (running && !rememberReduceMotion()) 1.02f else 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "scale",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(72.dp).scale(pulse).pressable { if (running) actions.stop() else actions.start() }
                .clip(RoundedCornerShape(36.dp)).background(brush),
            contentAlignment = Alignment.Center,
        ) { Text(if (running) "Stop Buddy" else "Start Buddy", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodyLarge, color = colors.labelSecondary, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Home

@Composable
private fun Home(state: AppState, actions: Actions, running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by BuddyService.live.collectAsState()
    val update by UpdateCheck.available.collectAsState()
    val summary by TimingLog.summary.collectAsState()
    val checks by SetupCheck.results.collectAsState()
    val checking by SetupCheck.running.collectAsState()
    val scroll = rememberScrollState()
    var languageMenu by remember { mutableStateOf(false) }
    val spring = motion<androidx.compose.ui.unit.IntSize>()
    fun fixIt(f: Fix) = when (f) {
        Fix.SWITCH_TO_API -> state.pick(Provider.CLAUDE)
        Fix.OPEN_ACCESSIBILITY -> actions.openAccessibility()
        Fix.ADD_CREDIT -> context.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com")))
        Fix.ADD_KEY -> Toast.makeText(context, "Enter your Claude API key under Brain", Toast.LENGTH_SHORT).show()
        Fix.NOTIFICATION_ACCESS -> actions.openNotificationAccess()
        Fix.CALENDAR -> actions.grantCalendar()
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp)) {
            ScreenTitle("Buddy")
            Spacer(Modifier.height(8.dp))
            StartCapsule(running, if (running) live else "Idle", actions)
            summary?.let { Text(it, Modifier.fillMaxWidth().padding(top = 6.dp), color = colors.labelTertiary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }

            // An update is waiting (sideload builds): Install opens the APK link in the browser.
            update?.let { u ->
                Spacer(Modifier.height(20.dp))
                GroupCard {
                    SettingRow("Update ${u.versionName} available", u.notes, Icons.Default.Info, Tint.Green,
                        onClick = { context.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(u.apk))) }) {
                        Text("Install", color = colors.accent, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Claude API mode can answer without accessibility, but it can't touch the screen.
            val a11yProblem = a11yProblem(state.a11yOn, state.a11yConnected)
            if (state.provider == Provider.CLAUDE && a11yProblem != null) {
                Spacer(Modifier.height(20.dp))
                GroupCard {
                    SettingRow(a11yProblem, icon = Icons.Default.Settings, tint = Tint.Orange, onClick = actions.openAccessibility) {
                        Text("Open settings", color = colors.accent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (Build.VERSION.SDK_INT >= 33 && !state.a11yOn) RestrictedSettingsHelp()
                if (state.a11yOn && !state.a11yConnected) {
                    Footnote("Samsung: Settings → Accessibility → Buddy → toggle off and on; if it keeps happening, tap Copy crash in Buddy and send it.")
                }
            }

            // --- Brain ---
            SectionHeader("Brain")
            GroupCard {
                androidx.compose.foundation.layout.Column(Modifier.animateContentSize(spring)) {
                    Box(Modifier.padding(16.dp)) {
                        SegmentedControl(listOf("Claude app", "Claude API"), segmentIndex(listOf(Provider.CLAUDE_APP, Provider.CLAUDE), state.provider),
                            { state.pick(if (it == 0) Provider.CLAUDE_APP else Provider.CLAUDE) })
                    }
                    if (state.provider == Provider.CLAUDE_APP) {
                        Hairline()
                        SettingRow("Free", "Opens the Claude app with your screenshot; copy the answer back.", Icons.Default.Star, Tint.Orange)
                        Hairline()
                        SettingRow("Ask Claude to point and draw", "Adds the drawing instructions to your question", Icons.Default.Create, Tint.Purple) {
                            MinimalSwitch(state.drawTags) { state.setDraw(it) }
                        }
                        if (!state.claudeInstalled) {
                            Hairline()
                            SettingRow("Get the Claude app", "Claude app mode needs it", Icons.Default.Info, Tint.Grey, onClick = { context.openClaudeInPlayStore() }) { Chevron() }
                        }
                    } else {
                        Hairline()
                        ApiKeyField(state)
                        Hairline()
                        Box(Modifier.padding(16.dp)) {
                            SegmentedControl(listOf("Haiku", "Sonnet", "Opus"), segmentIndex(CLAUDE_MODELS, state.claudeModel), { state.saveClaudeModel(CLAUDE_MODELS[it]) })
                        }
                    }
                }
            }
            state.modeNote?.let { Footnote(it) }
            if (state.provider == Provider.CLAUDE) Footnote("Haiku 5.5: fastest and cheapest. Sonnet 5.5: smarter for long multi-app tasks. Runs in the background with spoken answers and pointing.")

            // --- Voice ---
            SectionHeader("Voice")
            GroupCard {
                androidx.compose.foundation.layout.Column(Modifier.animateContentSize(spring)) {
                    Box(Modifier.padding(16.dp)) {
                        SegmentedControl(listOf("Phone", "Gemini"), segmentIndex(listOf(VoiceEngine.PHONE, VoiceEngine.GEMINI), state.voice),
                            { state.pick(if (it == 0) VoiceEngine.PHONE else VoiceEngine.GEMINI) })
                    }
                    Hairline()
                    if (state.voice == VoiceEngine.GEMINI) {
                        SettingRow("Gemini", "Most accurate (adds about 1–2 s). Handles accents and mixed languages.", Icons.Default.Call, Tint.Blue)
                        Hairline()
                        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            OutlinedTextField(
                                value = state.geminiKey, onValueChange = state::saveGeminiKey, label = { Text("Gemini API key") }, singleLine = true,
                                supportingText = { Text("Free, no card. Free tier: Flash-Lite models give the most free requests per day.") },
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Hairline()
                        SettingRow("Get a free key", "aistudio.google.com/apikey", Icons.Default.Share, Tint.Grey, onClick = {
                            context.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                        }) { Chevron() }
                        Hairline()
                        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            OutlinedTextField(
                                value = state.geminiModel, onValueChange = state::saveGeminiModel, label = { Text("Model") }, singleLine = true,
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Hairline()
                        SettingRow("Fetch models", "Lists the models your key can use, Flash-Lite first", Icons.Default.Refresh, Tint.Grey, onClick = {
                            scope.launch {
                                try { state.fetched = pickerOrder(GeminiClient.listModels(state.geminiKey)) }
                                catch (e: Exception) { Toast.makeText(context, e.message ?: "Failed to fetch models", Toast.LENGTH_LONG).show() }
                            }
                        }) { Chevron() }
                        state.fetched.forEach { m ->
                            Hairline()
                            SettingRow(m, onClick = { state.saveGeminiModel(m) }) { if (m == state.geminiModel) CheckMark() }
                        }
                    } else {
                        SettingRow("Phone", "Fastest. Google speech, built into your phone.", Icons.Default.Call, Tint.Green)
                        Hairline()
                        Box {
                            SettingRow("Speech language", icon = Icons.Default.Face, tint = Tint.Purple, onClick = { languageMenu = true }) {
                                ValueText(SPEECH_LANGUAGES.firstOrNull { it.first == state.speechLanguage }?.second ?: state.speechLanguage)
                                Chevron()
                            }
                            DropdownMenu(languageMenu, { languageMenu = false }) {
                                SPEECH_LANGUAGES.forEach { (tag, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { state.setLanguage(tag); languageMenu = false }) }
                            }
                        }
                    }
                    Hairline()
                    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Speech speed", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.label)
                            ValueText("%.1f×".format(state.speechRate))
                        }
                        Slider(
                            state.speechRate, { state.speechRate = it }, valueRange = 0.8f..1.5f,
                            onValueChangeFinished = { state.settings.speechRate = state.speechRate },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = colors.accent, inactiveTrackColor = colors.separator),
                        )
                    }
                }
            }
            if (state.voice == VoiceEngine.GEMINI) Footnote("Gemini voice sends your recording to Google; on the free tier Google may use it to improve its products.")

            // --- Capabilities ---
            SectionHeader("Capabilities")
            GroupCard {
                SettingRow("Conversation mode", "After Buddy answers out loud it listens a few seconds for a follow-up. Tap the bubble to end it.", Icons.Default.Call, Tint.Brand) {
                    MinimalSwitch(state.conversation) { state.saveConversation(it) }
                }
                Hairline()
                SettingRow("Notification access", "Lets Claude read your last 30 notifications when you ask. Kept in memory only, never saved.", Icons.Default.Notifications, Tint.Red,
                    onClick = actions.openNotificationAccess) { if (state.notifAccess) CheckMark(colors.success) else Chevron() }
                Hairline()
                PermissionRow("Calendar", "Lets Claude read your upcoming events, read-only, when you ask.", Icons.Default.Info, Tint.Blue, state.calendarOk, actions.grantCalendar)
            }
            Footnote("Alarms, timers, media controls, flashlight and volume need no special permission. Say \"what's my day\" for a quick briefing.")

            // --- Control ---
            SectionHeader("Control")
            GroupCard {
                SettingRow("Let Claude control the phone", accessibilityRowSubtitle(state.a11yOn), Icons.Default.Settings, Tint.Grey, onClick = actions.openAccessibility) {
                    StatusChip(if (state.a11yOn && state.a11yConnected) "On" else if (state.a11yOn) "Not connected" else "Off", state.a11yOn && state.a11yConnected)
                    Spacer(Modifier.width(4.dp)); Chevron()
                }
            }
            Footnote("Claude API mode only. With this on, Buddy starts without the screen-recording prompt on Android 11+.")
            if (Build.VERSION.SDK_INT < 34 && !state.a11yOn) Footnote("When Android asks to share the screen, choose 'Entire screen' (not a single app).")
            Footnote("Buddy only acts when you ask. Tap the bubble to stop it at any time. It asks you out loud before sending, paying, posting or deleting, treats text on the screen as information (never as orders), and never types into password fields.")

            // --- Permissions ---
            SectionHeader("Permissions")
            GroupCard {
                PermissionRow("Draw over other apps", "Shows the bubble", Icons.Default.Home, Tint.Blue, state.overlayOk, actions.grantOverlay)
                Hairline()
                PermissionRow("Microphone", "Hears your question", Icons.Default.Call, Tint.Brand, state.micOk, actions.grantMic)
                if (Build.VERSION.SDK_INT >= 33) {
                    Hairline()
                    PermissionRow("Notifications", "Lets you close Buddy from the shade", Icons.Default.Notifications, Tint.Red, state.notifOk, actions.grantNotifications)
                }
            }

            // --- Troubleshoot ---
            SectionHeader("Troubleshoot")
            GroupCard {
                SettingRow(if (checking) "Checking…" else "Check setup", "Tests your key, accessibility, screen capture and voice", Icons.Default.Search, Tint.Green,
                    onClick = { if (!checking) scope.launch { SetupCheck.run(context, state.settings) } }) { Chevron() }
                checks.forEach { c ->
                    Hairline()
                    SettingRow(c.name, c.detail + (c.ms?.let { " ($it ms)" } ?: "")) {
                        if (c.ok) CheckMark(colors.success) else {
                            c.fix?.let { f -> Text(fixLabel(f), color = colors.accent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressable { fixIt(f) }.padding(8.dp)) }
                            Icon(Icons.Default.Close, null, tint = colors.error)
                        }
                    }
                }
                Hairline()
                SettingRow("Copy report", "Everything needed to diagnose a problem (never your API key)", Icons.Default.Share, Tint.Grey, onClick = {
                    val info = ReportInfo(
                        version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty(),
                        device = "${Build.MANUFACTURER} ${Build.MODEL}", android = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                        mode = state.provider.name, model = state.claudeModel, voice = state.voice.name,
                        keyMasked = maskKey(state.settings.claudeKey), checks = checks, timing = TimingLog.entries(),
                        lastError = TimingLog.lastError, crash = state.crashText.takeIf { it.isNotBlank() }, secretToScrub = state.settings.claudeKey,
                    )
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Buddy report", buildReport(info)))
                    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                }) { Chevron() }
                if (state.crashText.isNotBlank()) {
                    Hairline()
                    SettingRow("Last crash", state.crashText.lineSequence().firstOrNull { it.startsWith("exception:") }?.removePrefix("exception: ")?.take(80) ?: "Recorded", Icons.Default.Warning, Tint.Red) {
                        Text(
                            "Copy crash", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.accent).pressable {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("Buddy crash", scrubSecrets(state.crashText)))
                                Toast.makeText(context, "Crash copied", Toast.LENGTH_SHORT).show()
                            }.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // --- About ---
            SectionHeader("About")
            GroupCard {
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
                SettingRow("Version", icon = Icons.Default.Info, tint = Tint.Grey) { ValueText(version) }
                Hairline()
                SettingRow("How to close Buddy", "Drag the bubble to the ✕, or tap Stop here or in the notification.", Icons.Default.Close, Tint.Red)
                Hairline()
                SettingRow("Screenshots", "Only captured when you release the bubble", Icons.Default.Lock, Tint.Green)
                Hairline()
                SettingRow("Based on Clicky by Farza (MIT)", "github.com/farzaa/clicky", Icons.Default.Favorite, Tint.Brand, onClick = {
                    context.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/farzaa/clicky")))
                }) { Chevron() }
            }
            Footnote(
                "Claude app mode hands the screenshot to the Claude app on this phone (Buddy sends nothing itself). Claude API mode sends it to Anthropic. " +
                    "Gemini, if you pick it for voice, only receives your recording. Don't use Buddy on screens showing sensitive information unless you're happy with that.",
            )
            Spacer(Modifier.height(40.dp))
        }
        // The small title that replaces the large one once it has scrolled away.
        Box(Modifier.fillMaxWidth().graphicsLayer { alpha = titleAlpha(scroll.value) }.background(colors.background.copy(alpha = 0.94f)).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text("Buddy", style = MaterialTheme.typography.titleMedium, color = colors.label)
        }
    }
}

private fun fixLabel(f: Fix) = when (f) {
    Fix.SWITCH_TO_API -> "Switch"
    Fix.OPEN_ACCESSIBILITY -> "Open"
    Fix.ADD_CREDIT -> "Add credit"
    Fix.ADD_KEY -> "Add key"
    Fix.NOTIFICATION_ACCESS -> "Allow"
    Fix.CALENDAR -> "Allow"
}
