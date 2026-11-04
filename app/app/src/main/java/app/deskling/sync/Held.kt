package app.deskling.sync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.deskling.MainActivity
import app.deskling.R
import app.deskling.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The Bluetooth link the phone keeps open to its dial while they're near each other, so both stay in step at
 * once: what you log on the phone is written straight down it, and the dial says {"event":"changed"} as soon as
 * something happens on the knob, which makes the phone sync. Android re-establishes the connection by itself
 * whenever the dial comes back in range.
 */
@SuppressLint("MissingPermission")
object Held {
    enum class State { OFF, WAITING, LINKED }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var link: BleLink? = null
    private var address = ""

    /** The link when it can take requests right now, else null. */
    fun current(): BleLink? = link?.takeIf { it.connected && it.authed }
    val active get() = link != null

    @Synchronized fun start(ctx: Context) {
        val app = ctx.applicationContext
        val d = Store.get(app).dial
        if (d == null || d.bleAddress.isBlank() || !Sync.hasBlePermission(app)) { stop(); return }
        if (link != null && address == d.bleAddress) return
        stop()
        address = d.bleAddress
        val cb = BleLink.Callback()
        cb.onReady = { scope.launch { introduce(app) } }
        cb.onDown = { link?.authed = false; _state.value = State.WAITING }
        cb.onEvent = { scope.launch { Sync.sync(app) } }
        link = runCatching { BleLink.hold(app, address, cb) }.getOrNull()
        _state.value = if (link != null) State.WAITING else State.OFF
    }

    @Synchronized fun stop() {
        link?.close()
        link = null
        address = ""
        _state.value = State.OFF
    }

    /** A fresh connection: say hello with the phone's key, then catch up both ways. */
    private suspend fun introduce(ctx: Context) {
        val l = link ?: return
        val d = Store.get(ctx).dial ?: return
        val r = runCatching {
            l.request(JSONObject().put("op", "hello").put("key", d.key).put("now", System.currentTimeMillis() / 1000))
        }.getOrNull()
        if (r?.optBoolean("ok") != true) return
        l.authed = true
        _state.value = State.LINKED
        Sync.sync(ctx)
    }
}

/** Keeps [Held] running while the app is in the background. Android requires a notification for that. */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel(this)
        val ok = runCatching {
            ServiceCompat.startForeground(this, ID, note(Held.state.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        Held.start(this)
        if (!Held.active) { stopSelf(); return START_NOT_STICKY }
        watch?.cancel()
        watch = scope.launch {
            Held.state.collect { s ->
                getSystemService(NotificationManager::class.java).notify(ID, note(s))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Held.stop()
        super.onDestroy()
    }

    private fun note(s: Held.State): Notification {
        val name = Store.get(this).dial?.name ?: "your dial"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_wisp)
            .setContentTitle(if (s == Held.State.LINKED) "Linked to $name" else "Waiting for $name")
            .setContentText(if (s == Held.State.LINKED) "What you do here or on the dial syncs straight away" else "Syncs as soon as you're back in range")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL = "link"
        private const val ID = 7

        private fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Link to the dial", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while the app stays connected to your dial for instant sync"
                    setShowBadge(false)
                })
            }
        }

        /** Start or stop the link to match the settings and the pairing. Safe to call any time. */
        fun update(ctx: Context) {
            val s = Store.get(ctx)
            val want = s.holdLink && s.dial?.bleAddress?.isNotBlank() == true && Sync.hasBlePermission(ctx)
            val i = Intent(ctx, LinkService::class.java)
            if (want) runCatching { ContextCompat.startForegroundService(ctx, i) }     // refused from the background on newer Android: tried again next time the app opens
            else { ctx.stopService(i); Held.stop() }
        }
    }
}
