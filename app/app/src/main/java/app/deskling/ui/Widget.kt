package app.deskling.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import app.deskling.Home
import app.deskling.R
import app.deskling.care.Mood
import app.deskling.care.Need
import app.deskling.data.Pet
import app.deskling.data.Store
import app.deskling.data.WidgetBg
import app.deskling.steps.Steps
import app.deskling.sync.Sync

/**
 * The home screen widget: the companion playing its current loop, its mood and what it wants next, and today's
 * steps toward the goal. Redrawn when the app's state changes (at most once a second, and only when what it
 * shows has changed), at each reminder check and sync, and every half hour by the system. Tapping it opens
 * the app.
 *
 * A widget can't run code, so the loop goes to the home screen as a ViewFlipper with one frame per child: the
 * launcher flips through them itself, and only while the widget is on screen. The frames are only sent when
 * the loop changes; changes to the words go as a partial update on top.
 */
class CompanionWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widget.update(ctx, force = true)
    override fun onEnabled(ctx: Context) = Widget.update(ctx, force = true)
}

object Widget {
    private val main = Handler(Looper.getMainLooper())
    private var pending = false
    private var shown = ""                                    // what the widget shows now, to skip identical redraws
    private var frames: Pair<String, Frames?> = "" to null    // the last loop's frames, scaled for the widget
    private var sentLoop = ""                                 // the loop the home screen has now

    /** Redraw soon: called on every change to the app's state, so it waits a second and coalesces. */
    fun request(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) { if (pending) return; pending = true }
        main.postDelayed({ synchronized(this) { pending = false }; update(app) }, 1000)
    }

    fun update(ctx: Context, force: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, CompanionWidget::class.java)) }.getOrNull() ?: return
        if (ids.isEmpty()) return
        val c = content(ctx)
        val s = Store.get(ctx)
        val key = c.toString() + s.widgetBg
        val loop = "${c.pet.id}/${c.loop.dir}/${c.here}/${s.widgetAnimate}"
        val full: Boolean
        synchronized(this) {
            if (!force && key == shown) return
            shown = key
            full = force || loop != sentLoop
            sentLoop = loop
        }
        if (full) runCatching { mgr.updateAppWidget(ids, render(ctx, c)) }.onFailure { synchronized(this) { sentLoop = "" } }
        else runCatching { mgr.partiallyUpdateAppWidget(ids, render(ctx, c, pet = false)) }
    }

    /** Everything the widget shows, worked out from the app's state. */
    internal data class Content(val pet: Pet, val here: Boolean, val loop: Loop, val name: String, val headline: String,
                                val status: String, val steps: Long, val goal: Int, val stepsKnown: Boolean)

    internal fun content(ctx: Context): Content {
        val s = Store.get(ctx)
        val now = System.currentTimeMillis()
        val e = s.engine
        val t = s.travel
        val pet = s.pet
        val (_, steps) = Steps.today(ctx)
        val stepsKnown = Steps.permitted(ctx)
        if (!t.here) {
            // at a dial (or on its way somewhere): show the dial's view of it when that's the current dial
            val at = if (t.where == Sync.SHARED) "Left by a link" else "At ${t.where}"
            val st = s.dialState
            val mood = if (s.dial?.name == t.where && st.has("mood")) st.optString("mood") else ""
            return Content(pet, false, Loop.SLEEP, pet.title, at, if (mood.isNotBlank()) "$mood, level ${st.optInt("level")}" else "Not on this phone",
                steps, s.stepGoal, stepsKnown)
        }
        e.advanceTo(now)
        val mood = e.mood(now)
        val urgent = if (e.paused(now)) null else e.mostUrgent()
        val postureDue = e.postureDue() && !e.paused(now)
        val pose = poseFor(mood, urgent, false, postureDue, pet)
        val status = when {
            mood == Mood.ASLEEP -> if (e.st.bedtime) "Bedtime" else "Asleep until ${e.config.hourStart}:00"
            e.paused(now) -> "Paused, ${(e.st.pauseUntil - now + 59_999) / 60_000} min left"
            urgent != null -> "${urgent.title} is due"
            postureDue -> "Time to ${if (e.st.standing) "sit down" else "stand up"}"
            else -> Need.entries.filter { e.enabled(it) }.minByOrNull { e.left(it) }
                ?.let { "Next: ${it.title.lowercase()} in ${minutes(e.left(it))}" } ?: "All looked after"
        }
        return Content(pet, true, pose.loop, pet.title, mood.title, status, steps, s.stepGoal, stepsKnown)
    }

    private fun minutes(secs: Long): String {
        val m = (secs + 59) / 60
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    /** All of it, or (pet = false) everything but the companion, for a partial update over what's there. */
    internal fun render(ctx: Context, c: Content, pet: Boolean = true): RemoteViews {
        val p = paletteOf(c.pet)
        val v = RemoteViews(ctx.packageName, R.layout.widget_companion)
        val bg = Store.get(ctx).widgetBg
        // on glass or bare wallpaper the quiet colours vanish: light text throughout, with the layout's shadow under it
        val solid = bg == WidgetBg.SOLID
        val text = if (solid) p.text.toArgb() else 0xFFFFFFFF.toInt()
        val quiet = if (solid) p.dim.toArgb() else 0xE6FFFFFF.toInt()
        val track = if (solid) p.line.toArgb() else 0x59FFFFFF
        when (bg) {
            WidgetBg.SOLID -> {
                v.setViewVisibility(R.id.w_bg, View.VISIBLE)
                v.setImageViewResource(R.id.w_bg, R.drawable.widget_bg)
                v.setInt(R.id.w_bg, "setColorFilter", p.panel.toArgb())
                v.setInt(R.id.w_bg, "setImageAlpha", 235)
            }
            WidgetBg.GLASS -> {
                v.setViewVisibility(R.id.w_bg, View.VISIBLE)
                v.setImageViewResource(R.id.w_bg, R.drawable.widget_glass)
                v.setInt(R.id.w_bg, "setColorFilter", 0)                // 0: leaves the glass as drawn
                v.setInt(R.id.w_bg, "setImageAlpha", 255)
            }
            WidgetBg.NONE -> v.setViewVisibility(R.id.w_bg, View.GONE)
        }
        if (pet) companion(ctx, v, c)
        v.setTextViewText(R.id.w_name, c.name)
        v.setTextColor(R.id.w_name, p.accent.toArgb())
        v.setTextViewText(R.id.w_mood, c.headline)
        v.setTextColor(R.id.w_mood, text)
        v.setTextViewText(R.id.w_status, c.status)
        v.setTextColor(R.id.w_status, quiet)
        v.setTextViewText(R.id.w_steps, if (c.stepsKnown) "%,d steps of %,d".format(c.steps, c.goal) else "Steps: allow in the app")
        v.setTextColor(R.id.w_steps, text)
        v.setImageViewBitmap(R.id.w_bar, bar(c.steps.toFloat() / c.goal, track, Need.MOVE.color.toInt()))
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, Home::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.w_root, open)
        return v
    }

    /** A loop ready for the widget: its frames in playing order (a ping-pong loop's come back the same pictures) and the time each shows. */
    internal class Frames(val order: List<Bitmap>, val ms: Int)

    /** The companion: its loop playing (or its first frame, still, when it's away or animation is off). */
    private fun companion(ctx: Context, v: RemoteViews, c: Content) {
        val f = frames(ctx, c.pet, c.loop, Store.get(ctx).widgetAnimate && c.here) ?: return
        v.removeAllViews(R.id.w_pet)
        for (b in f.order) {
            val one = RemoteViews(ctx.packageName, R.layout.widget_frame)
            one.setImageViewBitmap(R.id.w_frame, b)
            if (!c.here) one.setInt(R.id.w_frame, "setImageAlpha", 90)
            v.addView(R.id.w_pet, one)
        }
        v.setDisplayedChild(R.id.w_pet, 0)
        v.setInt(R.id.w_pet, "setFlipInterval", if (f.order.size > 1) f.ms else 3_600_000)   // a still picture: hardly ever
    }

    // Every frame travels to the launcher as a picture, and Android caps a widget's pictures at about 1.5 screens'
    // worth, less on small phones: so at most 30 frames (long loops skip every other one, at the same speed),
    // scaled down to fit a budget well under that.
    private const val MAX_FRAMES = 30
    private const val BUDGET = 4 * 1024 * 1024
    private const val MAX_W = 300

    internal fun frames(ctx: Context, pet: Pet, loop: Loop, animate: Boolean): Frames? {
        val k = "${pet.id}/${loop.dir}/$animate"
        synchronized(this) { if (frames.first == k) return frames.second }
        val f = runCatching {
            val dir = "pets/${pet.id}/${loop.dir}"
            val meta = org.json.JSONObject(ctx.assets.open("$dir/meta.json").bufferedReader().readText())
            val count = if (animate) meta.getInt("count") else 1
            val fps = meta.optInt("fps", 12).coerceAtLeast(1)
            val step = (count + MAX_FRAMES - 1) / MAX_FRAMES
            val picks = (0 until count step step).toList()
            val w0 = meta.optInt("w", 368); val h0 = meta.optInt("h", 235)
            // as wide as fits: the budget shared between the frames, never wider than the widget needs
            val w = minOf(MAX_W, w0, kotlin.math.sqrt(BUDGET / (picks.size * 4.0) * w0 / h0).toInt()).coerceAtLeast(64)
            val h = (w.toFloat() * h0 / w0).toInt().coerceAtLeast(1)
            val pics = picks.map { i ->
                val src = ctx.assets.open("$dir/%03d.webp".format(i)).use { BitmapFactory.decodeStream(it) }
                if (src.width == w) src else Bitmap.createScaledBitmap(src, w, h, true).also { src.recycle() }
            }
            val order = if (meta.optBoolean("pingpong") && pics.size > 2) pics + pics.subList(1, pics.size - 1).reversed() else pics
            Frames(order, 1000 * step / fps)
        }.getOrNull()
        synchronized(this) { frames = k to f }
        return f
    }

    /** The steps bar: a small picture, since a widget's progress bar can't take the companion's colours everywhere. */
    private fun bar(f: Float, track: Int, fill: Int): Bitmap {
        val w = 300; val h = 12
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = track
        cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, paint)
        val x = (f.coerceIn(0f, 1f) * w).coerceAtLeast(if (f > 0) h.toFloat() else 0f)
        if (x > 0) { paint.color = fill; cv.drawRoundRect(RectF(0f, 0f, x, h.toFloat()), h / 2f, h / 2f, paint) }
        return b
    }
}
