package app.deskling.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.deskling.care.Mood
import app.deskling.care.Need
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max

/** The animation loops, named as exported by the firmware's tools/export_app_assets.py. */
enum class Loop(val dir: String) {
    WALK("walk"), RUN("run"), JUMP("jump"), SLEEP("sleep"), HAPPY("happy"), SAD("sad"), MAD("mad"),
    THIRSTY("thirsty"), TIRED("tired"), STRETCH("stretch"), MOVE("move")
}

/** What Wisp shows: a loop and maybe a thought bubble (assets/emotes/<name>.png). */
data class Pose(val loop: Loop, val emote: String?)

/** The same choices as the dial's pet screen. The happy heart only shows right after a pat or a break. */
fun poseFor(mood: Mood, urgent: Need?, reacting: Boolean): Pose = when {
    reacting -> Pose(Loop.HAPPY, "emote_happy")
    else -> when (mood) {
        Mood.CONTENT -> Pose(Loop.WALK, "emote_content")
        Mood.HAPPY -> Pose(Loop.HAPPY, null)
        Mood.THIRSTY -> Pose(Loop.THIRSTY, null)                 // the clip has its own bubble
        Mood.TIRED -> Pose(Loop.TIRED, "emote_eyes")
        Mood.RESTLESS -> if (urgent == Need.MOVE) Pose(Loop.MOVE, "emote_move") else Pose(Loop.STRETCH, "emote_stretch")
        Mood.SAD -> Pose(Loop.SAD, "emote_sad")
        Mood.MAD -> Pose(Loop.MAD, "emote_mad")
        Mood.ASLEEP -> Pose(Loop.SLEEP, "emote_asleep")
    }
}

class LoopData(val meta: JSONObject, val frames: List<ImageBitmap>) {
    val fps = meta.optInt("fps", 12)
    val k = meta.optDouble("scale", 2.0).toFloat()          // frame px per dial px
    val pingpong = meta.optBoolean("pingpong")
    fun f(key: String) = meta.optDouble(key).toFloat()
    fun arr(key: String, i: Int) = meta.optJSONArray(key)?.optDouble(i)?.toFloat() ?: 0f

    companion object {
        suspend fun load(ctx: Context, loop: Loop): LoopData = withContext(Dispatchers.IO) {
            val meta = JSONObject(ctx.assets.open("wisp/${loop.dir}/meta.json").bufferedReader().readText())
            val frames = (0 until meta.getInt("count")).map { i ->
                ctx.assets.open("wisp/${loop.dir}/%03d.webp".format(i)).use { BitmapFactory.decodeStream(it).asImageBitmap() }
            }
            LoopData(meta, frames)
        }
    }
}

private object EmoteCache {
    val map = HashMap<String, ImageBitmap>()
    fun get(ctx: Context, name: String): ImageBitmap =
        map.getOrPut(name) { ctx.assets.open("emotes/$name.png").use { BitmapFactory.decodeStream(it).asImageBitmap() } }
}

/**
 * Wisp on a stage laid out in the dial's own units (360 wide, ground at y 262), so placement and the
 * bubble's path match the dial.
 */
@Composable
fun WispView(pose: Pose, modifier: Modifier = Modifier, onTap: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    // keep showing the old loop until the new one has loaded, so a pose change never blanks Wisp
    var data by remember { mutableStateOf<LoopData?>(null) }
    LaunchedEffect(pose.loop) { data = LoopData.load(ctx, pose.loop) }
    val emote = remember(pose.emote) { pose.emote?.let { EmoteCache.get(ctx, it) } }
    var frame by remember(pose.loop) { mutableIntStateOf(0) }
    LaunchedEffect(data) {
        val d = data ?: return@LaunchedEffect
        var dir = 1
        frame = 0
        while (true) {
            delay(1000L / d.fps)
            val n = d.frames.size
            frame = if (!d.pingpong || n < 2) (frame + 1) % n else {
                var x = frame + dir
                if (x >= n) { dir = -1; x = n - 2 } else if (x < 0) { dir = 1; x = 1 }
                x
            }
        }
    }
    val tap = if (onTap != null) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTap() } else Modifier
    Canvas(modifier.fillMaxWidth().aspectRatio(360f / 300f).then(tap)) {
        val d = data ?: return@Canvas
        val u = size.width / 360f                               // screen px per dial px
        val i = frame.coerceIn(0, d.frames.size - 1)
        val img = d.frames[i]
        val k = d.k
        val ground = 262f
        val left = 180f - d.f("cx") / k
        val top = ground - d.f("ground") / k
        // shadow: narrower and fainter the higher Wisp is off the ground (as make_assets.py bakes it on the dial)
        val devW = img.width / k
        val hgt = max(0f, (d.f("ground") - d.arr("bottoms", i)) / k)
        val kk = max(0.45f, 1f - hgt / 160f)
        val sw = max(28f, 0.32f * devW) * kk
        val sh = sw * 0.17f
        drawOval(Color(70, 105, 160, (110 * kk).toInt()), Offset((180f - sw) * u, (ground - sh) * u), Size(2 * sw * u, 2 * sh * u))
        drawImage(img, dstOffset = IntOffset((left * u).toInt(), (top * u).toInt()),
            dstSize = IntSize((img.width / k * u).toInt(), (img.height / k * u).toInt()))
        if (emote != null) {
            val fx = left + (d.f("ax") + d.arr("hx", i)) / k
            val fy = top + (d.f("ay") + d.arr("hy", i)) / k
            val x = (fx + 25f).coerceAtMost(360f - 50f)
            val y = (fy - 87f).coerceAtLeast(2f)
            drawImage(emote, dstOffset = IntOffset((x * u).toInt(), (y * u).toInt()), dstSize = IntSize((44 * u).toInt(), (58 * u).toInt()))
        }
    }
}
