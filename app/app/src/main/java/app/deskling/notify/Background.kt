package app.deskling.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.deskling.MainActivity
import app.deskling.R
import app.deskling.care.Need
import app.deskling.data.NotifyMode
import app.deskling.data.Store
import app.deskling.steps.Steps
import app.deskling.sync.Sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Reminders on the phone: when a need falls due (and every 15 minutes while it stays due), if you're away from the dial. */
object Reminders {
    private const val CHANNEL = "reminders"
    private val PROMPT = mapOf(
        Need.WATER to "%s is thirsty: time for some water",
        Need.EYES to "Time for an eye break: look 20 feet away for 20 seconds",
        Need.STRETCH to "Time to stretch",
        Need.MOVE to "Time to get up and move",
    )

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Break reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Water, eye breaks, stretching and moving, when you're away from the dial"
        })
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Post or clear notifications for what's due now, then set the alarm for the next check. */
    fun check(ctx: Context) {
        val s = Store.get(ctx)
        val now = System.currentTimeMillis()
        s.engine.advanceTo(now)
        s.saveCare()
        val nm = NotificationManagerCompat.from(ctx)
        val away = now - s.lastNearby > 10 * 60_000
        val allowed = when (s.notifyMode) { NotifyMode.ALWAYS -> true; NotifyMode.AWAY -> away; NotifyMode.NEVER -> false }
        val last = s.lastNotified
        var anyDue = false
        for (n in Need.entries) {
            val id = 100 + n.ordinal
            if (!s.engine.due(n) || !s.engine.active(now)) { nm.cancel(id); last.remove(n.key); continue }
            anyDue = true
            if (!allowed || !canNotify(ctx)) continue
            if (now - last.optLong(n.key) < 15 * 60_000) continue
            last.put(n.key, now)
            val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val done = PendingIntent.getBroadcast(ctx, id, Intent(ctx, DoneReceiver::class.java).putExtra("need", n.key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_wisp)
                .setContentTitle(n.title)
                .setContentText(PROMPT[n]?.format(Store.get(ctx).pet.title))
                .setColor(n.color.toInt())
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, "Done", done)
            try { nm.notify(id, b.build()) } catch (e: SecurityException) { }
        }
        s.lastNotified = last
        schedule(ctx, if (anyDue) now + 15 * 60_000 else null)
    }

    private fun schedule(ctx: Context, soon: Long?) {
        val s = Store.get(ctx)
        val now = System.currentTimeMillis()
        val next = (Need.entries.mapNotNull { s.engine.dueAt(it, now) } + listOfNotNull(soon)).filter { it > now }.minOrNull()
            ?: (now + 60 * 60_000)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 5_000, pi)
    }
}

private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** The next reminder check: a quick look for the dial nearby first, so "away" is current. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val r = goAsync()
        bgScope.launch {
            try {
                withTimeoutOrNull(20_000) { Sync.sync(ctx) }
                Reminders.check(ctx)
            } finally { r.finish() }
        }
    }
}

/** "Done" on a reminder. */
class DoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val n = Need.of(intent.getStringExtra("need")) ?: return
        Store.get(ctx).logDone(n)
        NotificationManagerCompat.from(ctx).cancel(100 + n.ordinal)
        SyncWorker.now(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Reminders.check(ctx)
        SyncWorker.schedule(ctx)
    }
}

/** Every 15 minutes: read the step counter, sync with the dial if it's reachable, and check reminders. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Steps.read(ctx)?.let { Steps.record(ctx, it) }
        withTimeoutOrNull(30_000) { Sync.sync(ctx) }
        Reminders.check(ctx)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync",
                ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build())
        }
        fun now(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().build())
        }
    }
}
