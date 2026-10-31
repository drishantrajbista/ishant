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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent { BuddyTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { BuddyApp(settings) } } }
    }
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
    var modeNote by mutableStateOf<String?>(null)

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
}

private class Actions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val grantOverlay: () -> Unit,
    val grantMic: () -> Unit,
    val grantNotifications: () -> Unit,
    val openAccessibility: () -> Unit,
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
            context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        },
        grantMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        grantNotifications = { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        openAccessibility = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
    )

    if (onboarding) Onboarding(state, actions) { settings.onboarded = true; onboarding = false }
    else Home(state, actions, running)
}

// ---------------------------------------------------------------------------------------------------------------------
// Shared pieces

@Composable
private fun ApiKeyField(state: AppState) {
    val bad = state.claudeKey.isNotEmpty() && !looksLikeClaudeKey(state.claudeKey)
    OutlinedTextField(
        value = state.claudeKey, onValueChange = state::saveClaudeKey,
        label = { Text("Anthropic API key") }, singleLine = true, isError = bad,
        supportingText = { if (bad) Text("this doesn't look like a Claude API key (starts with sk-ant-)") else Text("From console.anthropic.com, with prepaid credit.") },
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun accessibilityRowSubtitle(on: Boolean) = if (on) "On: Claude can tap, type and scroll when you ask" else "Off"

@Composable
private fun RestrictedSettingsHelp() = Note(
    "On Android 13+, if the Buddy toggle is greyed out: Settings → Apps → Buddy → ⋮ (top right) → Allow restricted settings, " +
        "then try again.",
)

// ---------------------------------------------------------------------------------------------------------------------
// Onboarding

@Composable
private fun Onboarding(state: AppState, actions: Actions, finish: () -> Unit) {
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    fun next() { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (page) {
                    0 -> {
                        Spacer(Modifier.height(40.dp))
                        LargeTitle("Meet Buddy")
                        Text("Claude, floating over your phone.", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(24.dp))
                        Group {
                            SettingRow("Hold the bubble and ask", "Talk about whatever is on your screen", Icons.Default.Call)
                            RowDivider()
                            SettingRow("Claude answers", "Spoken aloud, with circles and arrows drawn on your screen", Icons.Default.Star)
                            RowDivider()
                            SettingRow("Let Claude do it", "With Claude API, it can tap, type and open apps for you", Icons.Default.Build)
                        }
                    }
                    1 -> {
                        LargeTitle("Choose the brain")
                        Group {
                            SettingRow("Claude app", "Free. Uses the Claude app you're signed in to.", onClick = { state.pick(Provider.CLAUDE_APP) }) {
                                if (state.provider == Provider.CLAUDE_APP) CheckMark()
                            }
                            RowDivider()
                            SettingRow("Claude API", "Fast. Claude can control the phone. Pay per use.", onClick = { state.pick(Provider.CLAUDE) }) {
                                if (state.provider == Provider.CLAUDE) CheckMark()
                            }
                            if (state.provider == Provider.CLAUDE) { RowDivider(); ApiKeyField(state) }
                            if (state.provider == Provider.CLAUDE_APP && !state.claudeInstalled) {
                                val context = LocalContext.current
                                RowDivider()
                                SettingRow("Get the Claude app", "It isn't installed yet", onClick = { context.openClaudeInPlayStore() }) { Chevron() }
                            }
                        }
                    }
                    2 -> {
                        LargeTitle("Allow a few things")
                        Group {
                            PermissionRow("Draw over other apps", "Shows the bubble", Icons.Default.Home, state.overlayOk, actions.grantOverlay)
                            RowDivider()
                            PermissionRow("Microphone", "Hears your question", Icons.Default.Call, state.micOk, actions.grantMic)
                            RowDivider()
                            PermissionRow("Notifications", "Lets you stop Buddy from the shade", Icons.Default.Notifications, state.notifOk, actions.grantNotifications)
                            RowDivider()
                            SettingRow("Accessibility", "Optional: lets Claude control the phone", Icons.Default.Settings, onClick = actions.openAccessibility) {
                                if (state.a11yOn) CheckMark(Green) else Chevron()
                            }
                        }
                        if (Build.VERSION.SDK_INT >= 33 && !state.a11yOn) RestrictedSettingsHelp()
                    }
                    else -> {
                        Spacer(Modifier.height(40.dp))
                        LargeTitle("You're ready")
                        Text("Hold the floating bubble, speak, and let go.", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(32.dp))
                        StartButton(false, "Ready", actions) {}
                    }
                }
            }
        }
        Dots(pager.currentPage, 4)
        Spacer(Modifier.height(16.dp))
        if (pager.currentPage < 3) PrimaryButton("Continue") { next() }
        else PrimaryButton("Start Buddy") { haptic.performHapticFeedback(HapticFeedbackType.LongPress); actions.start(); finish() }
    }
}

@Composable
private fun Dots(current: Int, count: Int) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(count) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (it == current) Accent else MaterialTheme.colorScheme.outline))
            }
        }
    }
}

/** The big round Start/Stop button with the live status underneath. */
@Composable
private fun StartButton(running: Boolean, status: String, actions: Actions, onClick: () -> Unit = { if (running) actions.stop() else actions.start() }) {
    val haptic = LocalHapticFeedback.current
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, if (running) 1.05f else 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "scale",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(168.dp).scale(pulse).clip(CircleShape)
                .background(if (running) Accent else MaterialTheme.colorScheme.surface)
                .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (running) "Stop" else "Start", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = if (running) Color.White else Accent)
        }
        Spacer(Modifier.height(14.dp))
        Text(status, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Home

@Composable
private fun Home(state: AppState, actions: Actions, running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val live by BuddyService.live.collectAsState()
    var languageMenu by remember { mutableStateOf(false) }
    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    val update by UpdateCheck.available.collectAsState()
    val summary by TimingLog.summary.collectAsState()
    val checks by SetupCheck.results.collectAsState()
    val checking by SetupCheck.running.collectAsState()
    fun fixIt(f: Fix) = when (f) {
        Fix.SWITCH_TO_API -> state.pick(Provider.CLAUDE)
        Fix.OPEN_ACCESSIBILITY -> actions.openAccessibility()
        Fix.ADD_CREDIT -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com")))
        Fix.ADD_KEY -> Toast.makeText(context, "Enter your Claude API key under Brain", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        LargeTitle("Buddy")
        Spacer(Modifier.height(12.dp))
        StartButton(running, if (running) live else "Tap to start", actions)
        summary?.let { Text(it, Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center) }

        // An update is waiting (sideload builds): the install button opens the APK link in the browser.
        update?.let { u ->
            Spacer(Modifier.height(16.dp))
            Group {
                SettingRow("Update ${u.versionName} available", u.notes, Icons.Default.Info,
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.apk))) }) {
                    Text("Install", color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Claude API mode can answer without accessibility, but it can't touch the screen.
        val a11yProblem = a11yProblem(state.a11yOn, state.a11yConnected)
        if (state.provider == Provider.CLAUDE && a11yProblem != null) {
            Spacer(Modifier.height(16.dp))
            Group {
                SettingRow(a11yProblem, icon = Icons.Default.Settings, onClick = actions.openAccessibility) {
                    Text("Open settings", color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
            if (Build.VERSION.SDK_INT >= 33 && !state.a11yOn) RestrictedSettingsHelp()
        }

        // --- Setup check ---
        SectionLabel("Setup")
        Group {
            SettingRow(if (checking) "Checking…" else "Check setup", "Tests your key, accessibility, screen capture and voice", Icons.Default.Settings,
                onClick = { if (!checking) scope.launch { SetupCheck.run(context, state.settings) } }) { Chevron() }
            checks.forEach { c ->
                RowDivider()
                SettingRow(c.name, c.detail + (c.ms?.let { " ($it ms)" } ?: "")) {
                    if (c.ok) CheckMark(Green) else {
                        c.fix?.let { f -> TextButton(onClick = { fixIt(f) }) { Text(fixLabel(f), color = Accent) } }
                        Icon(Icons.Default.Close, null, tint = Color(0xFFFF3B30))
                    }
                }
            }
            RowDivider()
            SettingRow("Copy report", "Everything needed to diagnose a problem (never your API key)", onClick = {
                val info = ReportInfo(
                    version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty(),
                    device = "${Build.MANUFACTURER} ${Build.MODEL}", android = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                    mode = state.provider.name, model = state.claudeModel, voice = state.voice.name,
                    keyMasked = maskKey(state.settings.claudeKey), checks = checks, timing = TimingLog.entries(),
                    lastError = TimingLog.lastError, secretToScrub = state.settings.claudeKey,
                )
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Buddy report", buildReport(info)))
                Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
            }) { Chevron() }
        }

        // --- Brain ---
        SectionLabel("Brain")
        Group {
            SettingRow("Claude app", "Free. Opens the Claude app; you copy the answer back.", Icons.Default.Star, onClick = { tick(); state.pick(Provider.CLAUDE_APP) }) {
                if (state.provider == Provider.CLAUDE_APP) CheckMark()
            }
            RowDivider()
            SettingRow("Claude API", "Pay per use. Runs in the background and can control the phone.", Icons.Default.Build, onClick = { tick(); state.pick(Provider.CLAUDE) }) {
                if (state.provider == Provider.CLAUDE) CheckMark()
            }
            if (state.provider == Provider.CLAUDE_APP) {
                RowDivider()
                SettingRow("Ask Claude to point and draw", "Adds the drawing instructions to your question") {
                    Switch(state.drawTags, { tick(); state.setDraw(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                }
                if (!state.claudeInstalled) {
                    RowDivider()
                    SettingRow("Get the Claude app", "Claude app mode needs it", onClick = { context.openClaudeInPlayStore() }) { Chevron() }
                }
            } else {
                RowDivider()
                ApiKeyField(state)
                CLAUDE_MODELS.forEach { m ->
                    RowDivider()
                    SettingRow(claudeModelLabel(m), onClick = { tick(); state.saveClaudeModel(m) }) { if (state.claudeModel == m) CheckMark() }
                }
            }
        }
        state.modeNote?.let { Note(it) }
        if (state.provider == Provider.CLAUDE) Note("Haiku 5.5: fastest. Sonnet 5.5: smarter for long multi-app tasks.")

        // --- Voice ---
        SectionLabel("Voice")
        Group {
            SettingRow("Gemini", "Most accurate (adds about 1–2 s). Handles accents and mixed languages.", Icons.Default.Call, onClick = { tick(); state.pick(VoiceEngine.GEMINI) }) {
                if (state.voice == VoiceEngine.GEMINI) CheckMark()
            }
            RowDivider()
            SettingRow("Phone", "Fastest. Google speech, built into your phone.", Icons.Default.Call, onClick = { tick(); state.pick(VoiceEngine.PHONE) }) {
                if (state.voice == VoiceEngine.PHONE) CheckMark()
            }
            if (state.voice == VoiceEngine.GEMINI) {
                RowDivider()
                OutlinedTextField(
                    value = state.geminiKey, onValueChange = state::saveGeminiKey,
                    label = { Text("Gemini API key") }, singleLine = true,
                    supportingText = { Text("Free, no card. Free tier: Flash-Lite models give the most free requests per day.") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                RowDivider()
                SettingRow("Get a free key", "aistudio.google.com/apikey", onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                }) { Chevron() }
                RowDivider()
                OutlinedTextField(
                    value = state.geminiModel, onValueChange = state::saveGeminiModel, label = { Text("Model") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                RowDivider()
                SettingRow("Fetch models", "Lists the models your key can use, Flash-Lite first", onClick = {
                    scope.launch {
                        try { state.fetched = pickerOrder(GeminiClient.listModels(state.geminiKey)) }
                        catch (e: Exception) { Toast.makeText(context, e.message ?: "Failed to fetch models", Toast.LENGTH_LONG).show() }
                    }
                }) { Chevron() }
                state.fetched.forEach { m ->
                    RowDivider()
                    SettingRow(m, onClick = { tick(); state.saveGeminiModel(m) }) { if (m == state.geminiModel) CheckMark() }
                }
            } else {
                RowDivider()
                Box {
                    SettingRow("Speech language", onClick = { languageMenu = true }) {
                        Text(SPEECH_LANGUAGES.firstOrNull { it.first == state.speechLanguage }?.second ?: state.speechLanguage, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        Chevron()
                    }
                    DropdownMenu(languageMenu, { languageMenu = false }) {
                        SPEECH_LANGUAGES.forEach { (tag, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { state.setLanguage(tag); languageMenu = false })
                        }
                    }
                }
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speech speed", Modifier.weight(1f), fontSize = 16.sp)
                    Text("%.1f×".format(state.speechRate), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    state.speechRate, { state.speechRate = it }, valueRange = 0.8f..1.5f,
                    onValueChangeFinished = { state.settings.speechRate = state.speechRate },
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                )
            }
        }
        if (state.voice == VoiceEngine.GEMINI) Note("Gemini voice sends your recording to Google; on the free tier Google may use it to improve its products.")

        // --- Control ---
        SectionLabel("Control")
        Group {
            SettingRow("Let Claude control the phone", accessibilityRowSubtitle(state.a11yOn), Icons.Default.Settings, onClick = actions.openAccessibility) {
                Text(if (state.a11yOn) "On" else "Off", color = if (state.a11yOn) Green else MaterialTheme.colorScheme.onSurfaceVariant)
                Chevron()
            }
        }
        Note("Claude API mode only. With this on, Buddy starts without the screen-recording prompt on Android 11+.")
        if (Build.VERSION.SDK_INT >= 33 && !state.a11yOn) RestrictedSettingsHelp()
        if (Build.VERSION.SDK_INT < 34 && !state.a11yOn) Note("When Android asks to share the screen, choose 'Entire screen' (not a single app).")
        Note(
            "Buddy only acts when you ask. Tap the bubble to stop it at any time. It asks you out loud before sending, paying, " +
                "posting or deleting, treats text on the screen as information (never as orders), and never types into password fields.",
        )

        // --- Permissions ---
        SectionLabel("Permissions")
        Group {
            PermissionRow("Draw over other apps", "Shows the bubble", Icons.Default.Home, state.overlayOk, actions.grantOverlay)
            RowDivider()
            PermissionRow("Microphone", "Hears your question", Icons.Default.Call, state.micOk, actions.grantMic)
            if (Build.VERSION.SDK_INT >= 33) {
                RowDivider()
                PermissionRow("Notifications", "Lets you stop Buddy from the shade", Icons.Default.Notifications, state.notifOk, actions.grantNotifications)
            }
        }

        // --- Privacy ---
        SectionLabel("Privacy")
        Group {
            SettingRow("Screenshots", "Only captured when you release the bubble", Icons.Default.Lock)
        }
        Note(
            "Claude app mode hands the screenshot to the Claude app on this phone (Buddy sends nothing itself). Claude API mode " +
                "sends it to Anthropic. Gemini, if you pick it for voice, only receives your recording. Don't use Buddy on screens " +
                "showing sensitive information unless you're happy with that.",
        )

        // --- About ---
        SectionLabel("About")
        Group {
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
            SettingRow("Version", icon = Icons.Default.Info) { Text(version, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            RowDivider()
            SettingRow("Based on Clicky by Farza (MIT)", "github.com/farzaa/clicky", onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/farzaa/clicky")))
            }) { Chevron() }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun fixLabel(f: Fix) = when (f) {
    Fix.SWITCH_TO_API -> "Switch"
    Fix.OPEN_ACCESSIBILITY -> "Open"
    Fix.ADD_CREDIT -> "Add credit"
    Fix.ADD_KEY -> "Add key"
}

private fun claudeModelLabel(id: String) = when (id) {
    "claude-haiku-5-5" -> "Haiku 5.5 (fastest, cheapest)"
    "claude-sonnet-5-5" -> "Sonnet 5.5"
    "claude-opus-5-5" -> "Opus 5.5"
    else -> id
}
