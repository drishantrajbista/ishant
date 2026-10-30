package dev.buddy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { SetupScreen(settings) }
            }
        }
    }
}

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun SetupScreen(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(settings.provider) }
    var voiceChoice by remember { mutableStateOf(settings.voiceChoice) }
    var speechLanguage by remember { mutableStateOf(settings.speechLanguage) }
    var geminiKey by remember { mutableStateOf(settings.geminiKey) }
    var geminiModel by remember { mutableStateOf(settings.geminiModel) }
    var claudeKey by remember { mutableStateOf(settings.claudeKey) }
    var claudeModel by remember { mutableStateOf(settings.claudeModel) }
    var proxy by remember { mutableStateOf(settings.proxyUrl) }
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var languageMenu by remember { mutableStateOf(false) }
    var claudeInstalled by remember { mutableStateOf(context.isClaudeAppInstalled()) }
    var overlayOk by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    var micOk by remember { mutableStateOf(context.has(Manifest.permission.RECORD_AUDIO)) }
    var notifOk by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || context.has(Manifest.permission.POST_NOTIFICATIONS))
    }
    val running by BuddyService.running.collectAsState()

    // Gemini voice is the default as soon as a Gemini key is saved, unless the user picked an engine.
    val voice = voiceChoice ?: if (geminiKey.isNotBlank()) VoiceEngine.GEMINI else VoiceEngine.PHONE

    // Permissions can change while we're in system settings, so re-check on resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        overlayOk = AndroidSettings.canDrawOverlays(context)
        micOk = context.has(Manifest.permission.RECORD_AUDIO)
        notifOk = Build.VERSION.SDK_INT < 33 || context.has(Manifest.permission.POST_NOTIFICATIONS)
        claudeInstalled = context.isClaudeAppInstalled()
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == android.app.Activity.RESULT_OK && data != null) {
            BuddyService.start(context, r.resultCode, data)
        }
    }

    fun start() {
        val problem = when {
            !settings.isConfigured -> "Enter an API key first"
            !settings.voiceReady -> "Enter a Gemini API key, or switch voice recognition to Phone"
            !overlayOk -> "Grant the overlay permission first"
            !micOk -> "Grant the microphone permission first"
            else -> null
        }
        if (problem != null) Toast.makeText(context, problem, Toast.LENGTH_SHORT).show()
        else projectionLauncher.launch(
            (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(),
        )
    }

    @Composable
    fun chip(label: String, selected: Boolean, onClick: () -> Unit) =
        FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.fillMaxWidth())

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Buddy", style = MaterialTheme.typography.headlineMedium)
        Text("Hold the floating bubble, ask a question about your screen, and let go.")

        // --- Mode ---
        Text("Mode", style = MaterialTheme.typography.titleSmall)
        chip("Claude app (free)", provider == Provider.CLAUDE_APP) { provider = Provider.CLAUDE_APP; settings.provider = provider }
        chip("Gemini (free, spoken answers)", provider == Provider.GEMINI) { provider = Provider.GEMINI; settings.provider = provider }
        chip("Claude API (pay per use)", provider == Provider.CLAUDE) { provider = Provider.CLAUDE; settings.provider = provider }

        when (provider) {
            Provider.CLAUDE_APP -> {
                Text(
                    "Uses the Claude app on this phone with your normal Claude account. No API key, no extra cost. " +
                        "Buddy opens Claude with your screenshot and question; you tap send.",
                )
                if (!claudeInstalled) {
                    OutlinedButton(onClick = { context.openClaudeInPlayStore() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Get the Claude app")
                    }
                }
            }
            Provider.GEMINI -> {}
            Provider.CLAUDE -> {
                Text(
                    "Runs in the background with spoken answers and pointing. Needs an API key from console.anthropic.com " +
                        "with prepaid credit. Haiku 5.5 is the fastest and cheapest.",
                )
                OutlinedTextField(
                    value = claudeKey, onValueChange = { claudeKey = it; settings.claudeKey = it },
                    label = { Text("Anthropic API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = proxy, onValueChange = { proxy = it; settings.proxyUrl = it },
                    label = { Text("Proxy base URL (optional)") },
                    supportingText = { Text("If set, requests go to {proxy}/chat with no key header.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text("Model", style = MaterialTheme.typography.titleSmall)
                CLAUDE_MODELS.forEach { m ->
                    chip(claudeModelLabel(m), claudeModel == m) { claudeModel = m; settings.claudeModel = m }
                }
            }
        }

        // --- Voice recognition ---
        Text("Voice recognition", style = MaterialTheme.typography.titleSmall)
        chip("Gemini (most accurate)", voice == VoiceEngine.GEMINI) {
            voiceChoice = VoiceEngine.GEMINI; settings.voiceChoice = VoiceEngine.GEMINI
        }
        chip("Phone (Google speech)", voice == VoiceEngine.PHONE) {
            voiceChoice = VoiceEngine.PHONE; settings.voiceChoice = VoiceEngine.PHONE
        }
        if (voice == VoiceEngine.GEMINI) {
            Text(
                "Gemini voice sends your recording to Google; on the free tier Google may use it to improve its products.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Box {
                OutlinedButton(onClick = { languageMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Speech language: ${SPEECH_LANGUAGES.firstOrNull { it.first == speechLanguage }?.second ?: speechLanguage}")
                }
                DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                    SPEECH_LANGUAGES.forEach { (tag, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { speechLanguage = tag; settings.speechLanguage = tag; languageMenu = false },
                        )
                    }
                }
            }
        }

        // --- Gemini key: needed by the Gemini mode and by Gemini voice, whatever the mode ---
        if (provider == Provider.GEMINI || voice == VoiceEngine.GEMINI) {
            Text("Gemini", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = geminiKey, onValueChange = { geminiKey = it; settings.geminiKey = it },
                label = { Text("Gemini API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text("The key is free and needs no card.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            }) { Text("Get a key at aistudio.google.com/apikey") }
            OutlinedTextField(
                value = geminiModel, onValueChange = { geminiModel = it; settings.geminiModel = it },
                label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                scope.launch {
                    try { fetched = GeminiClient.listModels(geminiKey) }
                    catch (e: Exception) { Toast.makeText(context, e.message ?: "Failed to fetch models", Toast.LENGTH_LONG).show() }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Fetch models") }
            fetched.forEach { m ->
                TextButton(onClick = { geminiModel = m; settings.geminiModel = m }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (m == geminiModel) "$m (selected)" else m)
                }
            }
        }

        // --- Permissions ---
        Text("Permissions", style = MaterialTheme.typography.titleSmall)
        PermissionButton("Overlay (draw over other apps)", overlayOk) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            )
        }
        PermissionButton("Microphone", micOk) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        if (Build.VERSION.SDK_INT >= 33) {
            PermissionButton("Notifications", notifOk) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }

        if (running) {
            Button(onClick = { BuddyService.stop(context) }, modifier = Modifier.fillMaxWidth()) { Text("Stop Buddy") }
        } else {
            Button(onClick = ::start, modifier = Modifier.fillMaxWidth()) { Text("Start Buddy") }
        }
        Text(
            "Screenshots are only captured when you release the bubble. Claude app mode hands the screenshot to the " +
                "Claude app on this phone (Buddy sends nothing itself). Gemini mode sends it to Google, whose free tier " +
                "may use it to improve its products. Claude API mode sends it to Anthropic (or your proxy). " +
                "Don't use Buddy on screens showing sensitive information unless you're happy with that.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun claudeModelLabel(id: String) = when (id) {
    "claude-haiku-5-5" -> "Haiku 5.5 (fastest, cheapest)"
    "claude-sonnet-5-5" -> "Sonnet 5.5"
    "claude-opus-5-5" -> "Opus 5.5"
    else -> id
}

@Composable
private fun PermissionButton(label: String, granted: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !granted, modifier = Modifier.fillMaxWidth()) {
        Text(if (granted) "$label: granted" else "Grant $label")
    }
}
