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
 * The home screen widget: the companion in its current pose, its mood and what it wants next, and today's
 * steps toward the goal. Redrawn when the app's state changes (at most once a second, and only when what it
 * shows has changed), at each reminder check and sync, and every half hour by the system. Tapping it opens
 * the app.
 */
class CompanionWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widget.update(ctx, force = true)
    override fun onEnabled(ctx: Context) = Widget.update(ctx, force = true)
}

object Widget {
    private val main = Handler(Looper.getMainLooper())
    private var pending = false
    private var shown = ""                                    // what the widget shows now, to skip identical redraws
    private var frame: Pair<String, Bitmap?> = "" to null     // the last pose's picture

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
        val key = c.toString() + Store.get(ctx).widgetBg
        synchronized(this) { if (!force && key == shown) return; shown = key }
        val views = render(ctx, c)
        runCatching { mgr.updateAppWidget(ids, views) }
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

    internal fun render(ctx: Context, c: Content): RemoteViews {
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
        picture(ctx, c.pet, c.loop)?.let { v.setImageViewBitmap(R.id.w_pet, it) }
        v.setInt(R.id.w_pet, "setImageAlpha", if (c.here) 255 else 90)
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

    /** The first frame of the loop: the pose, still. */
    private fun picture(ctx: Context, pet: Pet, loop: Loop): Bitmap? {
        val k = "${pet.id}/${loop.dir}"
        synchronized(this) { if (frame.first == k) return frame.second }
        val b = runCatching { ctx.assets.open("pets/$k/000.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        synchronized(this) { frame = k to b }
        return b
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
