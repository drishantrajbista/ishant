package dev.buddy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

/**
 * Optional "power mode": a helper process started through Shizuku injects taps, swipes and a few keys instead of the
 * accessibility service. Typing and screenshots never go through it. Any failure just means the accessibility path is used
 * (see [routeInput]); failures are recorded in [errors] instead of being swallowed, so the report can say why.
 */
object PowerMode {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4217
    private const val HELPER_TIMEOUT_MS = 6000L

    /** What the card in settings shows. */
    val status = MutableStateFlow(PowerStatus.NOT_INSTALLED)

    private val handler = Handler(Looper.getMainLooper())
    private val log = ArrayDeque<PowerError>()

    @Volatile private var service: IInputService? = null
    @Volatile private var degraded = false
    @Volatile private var helperFailed = false
    @Volatile private var binderReceivedAt: Long? = null
    private var started = false
    private var binding = false
    private var app: Context? = null
    private var wanted = false

    /** The last few failures (class and message), oldest first. */
    val errors: List<PowerError> @Synchronized get() = log.toList()

    @Synchronized private fun record(where: String, e: Throwable) {
        log.addLast(PowerError(System.currentTimeMillis(), "$where: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"))
        while (log.size > MAX_POWER_ERRORS) log.removeFirst()
        android.util.Log.w("Buddy", "shizuku $where", e)
    }

    @Synchronized private fun note(text: String) {
        log.addLast(PowerError(System.currentTimeMillis(), text))
        while (log.size > MAX_POWER_ERRORS) log.removeFirst()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            handler.removeCallbacks(timeout)
            binding = false
            service = binder?.takeIf { it.pingBinder() }?.let { IInputService.Stub.asInterface(it) }
            helperFailed = service == null
            if (service == null) note("helper: connected but its binder isn't alive")
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; binding = false; refresh() }
    }

    /** No connection within 6 s of binding: the helper didn't start. */
    private val timeout = Runnable {
        if (service == null && binding) { binding = false; helperFailed = true; note("helper: no connection within 6 s"); refresh() }
    }

    /** Hooks up Shizuku's listeners once. Safe to call from anywhere, any number of times. */
    @Synchronized fun start(context: Context) {
        app = context.applicationContext
        if (started) return
        started = true
        try {
            Shizuku.addBinderReceivedListenerSticky {
                binderReceivedAt = System.currentTimeMillis(); degraded = false
                synchronized(this) { log.clear() }
                refresh()
                handler.postDelayed({ refresh() }, 2000)   // once more after Shizuku has settled
            }
            Shizuku.addBinderDeadListener { service = null; binding = false; refresh() }
            Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        } catch (e: Throwable) {
            record("listener registration", e)
        }
    }

    fun installedVersion(context: Context): String? = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) { null } catch (e: RuntimeException) { record("package check", e); null }

    fun installed(context: Context): Boolean = installedVersion(context) != null

    private fun running(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { record("pingBinder", e); false }

    private fun permissionCode(): Int? = try {
        if (running() && !Shizuku.isPreV11()) Shizuku.checkSelfPermission() else null
    } catch (e: Throwable) { record("checkSelfPermission", e); null }

    private fun granted(): Boolean = permissionCode() == PackageManager.PERMISSION_GRANTED

    private fun rationale(): Boolean? = try {
        if (running() && !Shizuku.isPreV11()) Shizuku.shouldShowRequestPermissionRationale() else null
    } catch (e: Throwable) { record("shouldShowRequestPermissionRationale", e); null }

    private fun helperBound(): Boolean = service?.asBinder()?.let { it.isBinderAlive && it.pingBinder() } == true

    /** Re-reads everything, binds the helper when it should be bound and drops it when power mode is off. */
    @Synchronized fun refresh(context: Context? = null, enabled: Boolean? = null) {
        context?.let { app = it.applicationContext }
        enabled?.let { wanted = it }
        val c = app ?: return
        val granted = granted()
        if (!wanted && (service != null || binding)) unbind()
        if (wanted && granted && service == null && !binding && !helperFailed) bind(c)
        status.value = powerStatus(
            installed(c), running(), granted, !granted && rationale() == true, wanted, helperBound(), helperFailed, degraded,
        )
    }

    private fun args(c: Context) = Shizuku.UserServiceArgs(ComponentName(c.packageName, InputService::class.java.name))
        .tag("buddy-input").processNameSuffix("input").version(BuildConfig.VERSION_CODE).daemon(false).debuggable(false)

    private fun bind(c: Context) {
        binding = true
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, HELPER_TIMEOUT_MS)
        try {
            Shizuku.bindUserService(args(c), connection)
        } catch (e: Throwable) {
            binding = false; helperFailed = true; handler.removeCallbacks(timeout)
            record("bindUserService", e)
        }
    }

    private fun unbind() {
        val c = app ?: return
        handler.removeCallbacks(timeout)
        try { Shizuku.unbindUserService(args(c), connection, true) } catch (e: Throwable) { record("unbindUserService", e) }
        service = null; binding = false
    }

    /** Asks Shizuku for permission (it shows its own dialog). */
    fun requestPermission() {
        try { if (running() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE) } catch (e: Throwable) { record("requestPermission", e) }
    }

    /** Drops the helper, waits 300 ms, binds it again, and asks for permission again if it is missing. */
    fun reconnect(context: Context) {
        app = context.applicationContext
        synchronized(this) { helperFailed = false; degraded = false; unbind() }
        refresh()
        handler.postDelayed({
            if (!granted() && running()) requestPermission()
            refresh()
        }, 300)
    }

    /** The helper if power mode should carry the next action ([chooseInputPath]), else null. */
    fun serviceFor(powerOn: Boolean): IInputService? {
        val s = service
        val path = chooseInputPath(powerOn, running(), granted(), helperBound())
        return if (path == InputPath.SHIZUKU) s else null
    }

    fun markDegraded() { degraded = true; refresh() }
    fun markWorked() { if (degraded) { degraded = false; refresh() } }

    fun diagnostics(context: Context, switchOn: Boolean): ShizukuDiag {
        val up = running()
        fun <T> safe(block: () -> T): T? = try { if (up) block() else null } catch (e: Throwable) { record("diagnostics", e); null }
        return ShizukuDiag(
            installedVersion(context), up, safe { Shizuku.isPreV11() }, safe { Shizuku.getVersion() }, safe { Shizuku.getUid() },
            permissionCode(), rationale(), helperBound(), binderReceivedAt, switchOn, status.value, errors,
        )
    }

    /** Opens Shizuku's own app. */
    fun openShizuku(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        return try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: RuntimeException) { record("open Shizuku", e); false }
    }

    /** The Play Store listing, else the download page. */
    fun getShizuku(context: Context) {
        for (uri in listOf("market://details?id=$SHIZUKU_PACKAGE", "https://shizuku.rikka.app/download/")) {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: RuntimeException) { record("open $uri", e) }
        }
    }
}
