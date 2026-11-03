package app.deskling.steps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.deskling.care.Need
import app.deskling.data.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Steps from the phone's hardware step counter (it counts in the background on its own, so the app only has to
 * read it now and then). A walk, [Store.walkSteps] steps within 10 minutes, counts as the Move break.
 */
object Steps {
    fun permitted(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun available(ctx: Context) = (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** (yyyymmdd, steps today) as last recorded. */
    fun today(ctx: Context): Pair<Int, Long> {
        val s = Store.get(ctx)
        val day = s.engine.dateOf(System.currentTimeMillis())
        return if (s.stepsDay == day) day to s.stepsToday else day to 0L
    }

    /** Read the counter once (registers, waits for the first value, unregisters). */
    suspend fun read(ctx: Context): Float? {
        if (!permitted(ctx)) return null
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val got = CompletableDeferred<Float>()
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { got.complete(e.values[0]) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        val v = withTimeoutOrNull(5000) { got.await() }
        sm.unregisterListener(l)
        return v
    }

    /** Fold a new counter reading into today's total, the history, and the walk check. */
    fun record(ctx: Context, raw: Float, now: Long = System.currentTimeMillis()) {
        val s = Store.get(ctx)
        val day = s.engine.dateOf(now)
        val last = s.stepsLastRaw
        val delta = when {
            last < 0 -> 0L                         // first reading: just the baseline
            raw >= last -> (raw - last).toLong()
            else -> raw.toLong()                   // the phone rebooted and the counter started over
        }
        s.stepsLastRaw = raw
        if (s.stepsDay != day) { s.stepsDay = day; s.stepsToday = 0 }
        if (delta > 0) s.stepsToday = s.stepsToday + delta
        val h = s.stepsHistory
        h.put(day.toString(), s.stepsToday)
        val keys = h.keys().asSequence().sorted().toList()
        for (k in keys.dropLast(30)) h.remove(k)
        s.stepsHistory = h
        checkWalk(ctx, now, delta)
    }

    private fun checkWalk(ctx: Context, now: Long, delta: Long) {
        val s = Store.get(ctx)
        // keep (time, cumulative) samples from the last 30 minutes
        val recent = s.stepsRecent
        val total = (if (recent.length() > 0) recent.getJSONArray(recent.length() - 1).getLong(1) else 0L) + delta
        val keep = JSONArray()
        for (i in 0 until recent.length()) { val e = recent.getJSONArray(i); if (now - e.getLong(0) < 30 * 60_000) keep.put(e) }
        keep.put(JSONArray().put(now).put(total))
        s.stepsRecent = keep
        // steps in the last 10 minutes (a sample from up to 20 minutes back stands in when readings are sparse)
        var base = total
        var baseAt = now
        for (i in 0 until keep.length()) {
            val e = keep.getJSONArray(i)
            if (now - e.getLong(0) <= 20 * 60_000) { base = e.getLong(1); baseAt = e.getLong(0); break }
        }
        val walked = total - base
        val window = maxOf(1L, (now - baseAt) / 60_000)
        if (walked < s.walkSteps || walked * 10 / window < s.walkSteps / 2) return
        // credit Move once per walk: not again within half its interval
        val eng = s.engine
        val half = eng.interval(Need.MOVE) * 500
        if (!eng.enabled(Need.MOVE) || now - s.lastWalkCredit < maxOf(half, 10 * 60_000L)) return
        s.lastWalkCredit = now
        s.logDone(Need.MOVE, now)
        s.stepsRecent = JSONArray().put(JSONArray().put(now).put(total))   // a fresh window for the next walk
    }

    /** Steps by day, oldest first, for the stats screen. */
    fun history(ctx: Context, days: Int = 7): List<Pair<Int, Long>> {
        val h: JSONObject = Store.get(ctx).stepsHistory
        return h.keys().asSequence().sorted().toList().takeLast(days).map { it.toInt() to h.optLong(it) }
    }
}
