package app.deskling.notify

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * A glow around the edge of the screen in a need's colour, alongside its reminder: the phone's version of the
 * dial's LED ring. Drawn in an overlay that takes no touches ("Display over other apps"), only while the screen
 * is on, and gone in a couple of seconds. Several needs at once flash one after another.
 */
object EdgeFlash {
    private val main = Handler(Looper.getMainLooper())
    private var showing: View? = null

    fun permitted(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** The system page where the user allows it. */
    fun permissionIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun show(ctx: Context, colors: List<Int>) {
        if (colors.isEmpty() || !permitted(ctx)) return
        if (ctx.getSystemService(PowerManager::class.java)?.isInteractive != true) return     // screen off: nothing to see
        val app = ctx.applicationContext
        main.post { runCatching { add(app, if (colors.size == 1) colors + colors else colors) } }
    }

    private fun add(ctx: Context, colors: List<Int>) {
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        showing?.let { runCatching { wm.removeView(it) } }
        val v = Glow(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        // over the status and navigation bars and into the corners
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            lp.fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        lp.title = "Deskling edge flash"
        wm.addView(v, lp)
        showing = v
        // each colour fades in and out once, 1.1 s apiece
        ValueAnimator.ofFloat(0f, colors.size.toFloat()).apply {
            duration = colors.size * 1100L
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val i = t.toInt().coerceAtMost(colors.size - 1)
                v.color = colors[i]
                v.level = sin(PI * (t - i)).toFloat().coerceAtLeast(0f)
                v.invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    runCatching { wm.removeView(v) }
                    if (showing === v) showing = null
                }
            })
            start()
        }
    }

    /** The glow: rounded rectangles following the screen's corners, brightest at the edge, fading inward. */
    internal class Glow(ctx: Context) : View(ctx) {
        var color = 0
        var level = 0f
        private val dp = ctx.resources.displayMetrics.density
        private var corner = 36 * dp            // until the screen says otherwise
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val r = RectF()

        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            if (Build.VERSION.SDK_INT >= 31) insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.let { corner = it.radius.toFloat() }
            return super.onApplyWindowInsets(insets)
        }

        override fun onDraw(c: Canvas) {
            if (level <= 0f) return
            val bands = 7
            val w = 3.2f * dp
            for (b in 0 until bands) {
                val inset = w / 2 + b * w
                paint.strokeWidth = w + 0.5f
                val fall = 1f - b.toFloat() / bands
                paint.color = color
                paint.alpha = (255 * level * fall * fall).toInt()
                r.set(inset, inset, width - inset, height - inset)
                val rad = (corner - inset).coerceAtLeast(0f)
                c.drawRoundRect(r, rad, rad, paint)
            }
        }
    }
}
