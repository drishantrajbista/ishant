package dev.buddy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

/**
 * Optional "power mode": a helper process started through Shizuku injects taps, swipes and a few keys instead of the
 * accessibility service. Typing and screenshots never go through it. Everything here is best effort: any failure just means the
 * accessibility path is used (see [routeInput]).
 */
object PowerMode {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4217

    /** What the card in settings shows. */
    val status = MutableStateFlow(PowerStatus.NOT_INSTALLED)

    @Volatile private var service: IInputService? = null
    @Volatile private var degraded = false
    private var started = false
    private var binding = false
    private var app: Context? = null
    private var wanted = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = binder?.takeIf { it.pingBinder() }?.let { IInputService.Stub.asInterface(it) }
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; binding = false; refresh() }
    }

    /** Hooks up Shizuku's listeners once. Safe to call from anywhere, any number of times. */
    @Synchronized fun start(context: Context) {
        app = context.applicationContext
        if (started) return
        started = true
        try {
            Shizuku.addBinderReceivedListenerSticky { degraded = false; refresh() }
            Shizuku.addBinderDeadListener { service = null; binding = false; refresh() }
            Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        } catch (e: Throwable) {
            android.util.Log.w("Buddy", "shizuku listeners", e)
        }
    }

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false } catch (e: RuntimeException) { false }

    private fun running(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    private fun granted(): Boolean = try {
        running() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    /** Re-reads Shizuku's state, binds the helper when it should be bound and drops it when power mode is off. */
    @Synchronized fun refresh(context: Context? = null, enabled: Boolean? = null) {
        context?.let { app = it.applicationContext }
        enabled?.let { wanted = it }
        val c = app ?: return
        val ready = running() && granted()
        if (wanted && ready && service == null && !binding) bind(c)
        if (!wanted && (service != null || binding)) unbind()
        status.value = powerStatus(installed(c), running(), granted(), degraded)
    }

    private fun args(c: Context) = Shizuku.UserServiceArgs(ComponentName(c.packageName, InputService::class.java.name))
        .tag("buddy-input").processNameSuffix("input").version(BuildConfig.VERSION_CODE).daemon(false).debuggable(false)

    private fun bind(c: Context) {
        binding = true
        try { Shizuku.bindUserService(args(c), connection) } catch (e: Throwable) { binding = false; degraded = true }
    }

    private fun unbind() {
        val c = app ?: return
        try { Shizuku.unbindUserService(args(c), connection, true) } catch (e: Throwable) { /* already gone */ }
        service = null; binding = false
    }

    /** Asks Shizuku for permission (it shows its own dialog). */
    fun requestPermission() {
        try { if (running() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE) } catch (e: Throwable) { degraded = true }
    }

    /** The helper if power mode should carry the next action ([chooseInputPath]), else null. */
    fun serviceFor(powerOn: Boolean): IInputService? {
        val s = service
        val path = chooseInputPath(powerOn, running(), granted(), s?.asBinder()?.isBinderAlive == true)
        return if (path == InputPath.SHIZUKU) s else null
    }

    fun markDegraded() { degraded = true; app?.let { status.value = powerStatus(installed(it), running(), granted(), true) } }
    fun markWorked() { if (degraded) { degraded = false; refresh() } }

    /** Opens Shizuku's own app. */
    fun openShizuku(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        return try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: RuntimeException) { false }
    }

    /** The Play Store listing, else the download page. */
    fun getShizuku(context: Context) {
        for (uri in listOf("market://details?id=$SHIZUKU_PACKAGE", "https://shizuku.rikka.app/download/")) {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: RuntimeException) { /* try the next */ }
        }
    }
}
