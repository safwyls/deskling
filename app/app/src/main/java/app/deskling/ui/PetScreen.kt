package app.deskling.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.deskling.care.CareEngine
import app.deskling.care.Mood
import app.deskling.care.Need
import app.deskling.steps.Steps
import app.deskling.sync.Sync
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The pet tab, on one screen: the companion in the middle (tap it for the activity wheel around it), what's due
 * underneath, steps, and taking it along at the bottom.
 */
@Composable
fun WispScreen(app: AppState) {
    val v by app.store.version.collectAsState()
    val link by Sync.link.collectAsState()
    v.hashCode()
    if (!app.store.travel.here) { AwayScreen(app); return }
    val now = app.now
    val e = app.store.engine
    val name = app.store.pet.title
    val mood = e.mood(now)
    val urgent = if (e.paused(now)) null else e.mostUrgent()
    val postureDue = e.postureDue() && !e.paused(now)
    var wheel by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(mood.title, color = TextC, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            LinkChip(app, link)
        }
        Stage(app, wheel, { wheel = it }, poseFor(mood, urgent, now < app.reactUntil, postureDue, app.store.pet), Modifier.weight(1f))
        val hint = when {
            wheel -> "Tap $name for a pat, or anywhere else to close"
            mood == Mood.ASLEEP -> if (e.st.bedtime) "Asleep: bedtime. Tap $name to wake it" else "Asleep until ${e.config.hourStart}:00. Tap $name to wake it"
            e.paused(now) -> "Paused, ${mmss((e.st.pauseUntil - now) / 1000)} left"
            urgent != null -> "${urgent.title} is due: tap $name"
            postureDue -> "Time to ${if (e.st.standing) "sit down" else "stand up"}: tap $name"
            else -> "Tap $name for activities"
        }
        Text(hint, color = Dim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        for (n in Need.entries) NeedRow(app, n)
        if (e.config.postureOn) PostureRow(app)
        StepsRow(app)
        Spacer(Modifier.height(10.dp))
        TravelBar(app)
    }
}

private class WheelItem(val label: String, val icon: ImageVector, val color: Color, val due: Boolean, val act: () -> Unit)

/** The companion, and the wheel of activities that opens around it. */
@Composable
private fun Stage(app: AppState, open: Boolean, setOpen: (Boolean) -> Unit, pose: Pose, modifier: Modifier) {
    val e = app.store.engine
    val now = app.now
    val t by animateFloatAsState(if (open) 1f else 0f, tween(220), label = "wheel")
    val items = buildList {
        fun need(n: Need, label: String, icon: ImageVector, act: () -> Unit) =
            add(WheelItem(label, icon, Color(n.color), e.due(n) && !e.paused(now), act))
        need(Need.WATER, "Water", Icons.Filled.LocalDrink) { app.water() }
        need(Need.EYES, "Eyes", Icons.Filled.Visibility) { app.start(Activity.EYES) }
        need(Need.STRETCH, "Stretch", Icons.Filled.SelfImprovement) { app.start(Activity.STRETCH) }
        need(Need.MOVE, "Move", Icons.Filled.DirectionsWalk) { app.start(Activity.MOVE) }
        if (e.config.postureOn) add(WheelItem(if (e.st.standing) "Sit" else "Stand", if (e.st.standing) Icons.Filled.Chair else Icons.Filled.Accessibility,
            Color(CareEngine.POSTURE_COLOR), e.postureDue() && !e.paused(now)) { app.switchPosture() })
        add(WheelItem("Play", Icons.Filled.SportsEsports, Accent, false) { app.start(Activity.PLAY) })
        val asleep = e.mood(now) == Mood.ASLEEP
        add(WheelItem(if (asleep) "Wake" else "Bedtime", if (asleep) Icons.Filled.WbSunny else Icons.Filled.Bedtime, Accent, false) {
            if (asleep) app.wake() else app.bedtime(true)
        })
        add(WheelItem(if (e.paused(now)) "Resume" else "Pause", if (e.paused(now)) Icons.Filled.PlayArrow else Icons.Filled.Pause, Accent, false) {
            app.pause(if (e.paused(now)) 0 else 60)
        })
    }
    BoxWithConstraints(modifier.fillMaxWidth()
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = open) { setOpen(false) },
        contentAlignment = Alignment.Center) {
        val d = min(maxWidth, maxHeight)                    // the wheel's diameter
        val item = (d * 0.2f).coerceIn(48.dp, 64.dp)
        val r = d / 2 - item / 2
        // the pet fills the stage when the wheel is shut, and steps back inside it when open
        val petW = min(maxWidth, maxHeight * 1.2f) * (0.95f - 0.33f * t)
        WispView(pose, Modifier.width(petW).semantics { contentDescription = "companion" }) {
            if (open) { setOpen(false); app.pat() } else setOpen(true)
        }
        if (t > 0f) items.forEachIndexed { i, it ->
            val a = -Math.PI / 2 + 2 * Math.PI * i / items.size
            val x = r * (cos(a).toFloat() * t); val y = r * (sin(a).toFloat() * t)
            Box(Modifier.offset(x, y).size(item).scale(0.6f + 0.4f * t).alpha(t)
                .background(if (it.due) it.color else Panel, CircleShape)
                .border(1.5.dp, if (it.due) it.color else Line, CircleShape)
                .clickable { setOpen(false); it.act() },
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(it.icon, null, tint = if (it.due) Bg else it.color, modifier = Modifier.size(item * 0.36f))
                    Text(it.label, color = if (it.due) Bg else TextC, fontSize = 10.sp, maxLines = 1,
                        fontWeight = if (it.due) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        app.toast?.let {
            Text(it, color = Accent, fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).background(Bg.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun NeedRow(app: AppState, n: Need) {
    val e = app.store.engine
    val col = Color(n.color)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(n.title, color = TextC, fontSize = 14.sp, modifier = Modifier.width(78.dp))
        LinearProgressIndicator(progress = { if (e.enabled(n)) e.level(n) / 100f else 0f }, color = if (e.due(n)) Bad else col,
            trackColor = Line, strokeCap = StrokeCap.Round, modifier = Modifier.weight(1f).height(7.dp))
        val t = when { !e.enabled(n) -> "off"; e.due(n) -> "due"; else -> "in " + mmss(e.left(n)) }
        Text(t, color = if (e.due(n)) Bad else Dim, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
        Text("${e.st.today[n.ordinal]}", color = col, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.width(24.dp))
    }
}

/** Sit/stand: where you are and how long until the switch (the switch itself is on the wheel). */
@Composable
private fun PostureRow(app: AppState) {
    val e = app.store.engine
    val col = Color(CareEngine.POSTURE_COLOR)
    val due = e.postureDue()
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (e.st.standing) "Standing" else "Sitting", color = TextC, fontSize = 14.sp, modifier = Modifier.width(78.dp))
        Text(if (due) "${e.st.postureElapsed / 60} min: time to ${if (e.st.standing) "sit" else "stand"}"
             else "${e.st.postureElapsed / 60} min, ${if (e.st.standing) "sit" else "stand"} in ${mmss(e.postureLeft())}",
            color = if (due) col else Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StepsRow(app: AppState) {
    val (_, steps) = Steps.today(app.ctx)
    val goal = app.store.stepGoal
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Steps", color = Dim, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        if (Steps.permitted(app.ctx)) {
            LinearProgressIndicator(progress = { min(1f, steps.toFloat() / goal) }, color = Color(Need.MOVE.color),
                trackColor = Line, strokeCap = StrokeCap.Round, modifier = Modifier.weight(1f).height(6.dp))
            Spacer(Modifier.width(10.dp))
            Text("%,d / %,d".format(steps, goal), color = TextC, fontSize = 14.sp)
        } else Text("allow physical activity in Settings", color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

