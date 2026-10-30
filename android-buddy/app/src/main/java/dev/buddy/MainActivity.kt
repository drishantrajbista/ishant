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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

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
    var provider by remember { mutableStateOf(settings.provider) }
    var geminiKey by remember { mutableStateOf(settings.geminiKey) }
    var geminiModel by remember { mutableStateOf(settings.geminiModel) }
    var claudeKey by remember { mutableStateOf(settings.claudeKey) }
    var claudeModel by remember { mutableStateOf(settings.claudeModel) }
    var proxy by remember { mutableStateOf(settings.proxyUrl) }
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var overlayOk by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    var micOk by remember { mutableStateOf(context.has(Manifest.permission.RECORD_AUDIO)) }
    var notifOk by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || context.has(Manifest.permission.POST_NOTIFICATIONS))
    }
    val running by BuddyService.running.collectAsState()

    // Permissions can change while we're in system settings, so re-check on resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        overlayOk = AndroidSettings.canDrawOverlays(context)
        micOk = context.has(Manifest.permission.RECORD_AUDIO)
        notifOk = Build.VERSION.SDK_INT < 33 || context.has(Manifest.permission.POST_NOTIFICATIONS)
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
            !overlayOk -> "Grant the overlay permission first"
            !micOk -> "Grant the microphone permission first"
            else -> null
        }
        if (problem != null) Toast.makeText(context, problem, Toast.LENGTH_SHORT).show()
        else projectionLauncher.launch(
            (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(),
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Buddy", style = MaterialTheme.typography.headlineMedium)
        Text("Hold the floating bubble, ask about your screen, and let go. Buddy answers out loud and can point at things.")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(provider == Provider.GEMINI, { provider = Provider.GEMINI; settings.provider = provider }, { Text("Gemini (free)") })
            FilterChip(provider == Provider.CLAUDE, { provider = Provider.CLAUDE; settings.provider = provider }, { Text("Claude (paid)") })
        }

        if (provider == Provider.GEMINI) {
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
        } else {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CLAUDE_MODELS.forEach { m ->
                    FilterChip(selected = claudeModel == m, onClick = { claudeModel = m; settings.claudeModel = m }, label = { Text(m) })
                }
            }
        }

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
            "Screenshots are only captured and sent to your chosen provider (Google Gemini or Anthropic) when you release the bubble.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PermissionButton(label: String, granted: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !granted, modifier = Modifier.fillMaxWidth()) {
        Text(if (granted) "$label: granted" else "Grant $label")
    }
}
