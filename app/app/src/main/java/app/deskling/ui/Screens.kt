package app.deskling.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deskling.care.CareEngine
import app.deskling.care.Mood
import app.deskling.care.Need
import app.deskling.steps.Steps
import app.deskling.sync.Held
import app.deskling.sync.Sync
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class Tab(val title: String, val icon: ImageVector) {
    WISP("Pet", Icons.Filled.Pets), STATS("Stats", Icons.Filled.Insights), SETTINGS("Settings", Icons.Filled.Settings)
}

@Composable
fun MainUi(app: AppState) {
    var tab by remember { mutableStateOf(Tab.WISP) }
    val v by app.store.version.collectAsState()          // the pet's name is on the first tab
    val petName = remember(v) { app.store.pet.title }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (app.activity == null) NavigationBar(containerColor = Panel) {
                for (t in Tab.entries) NavigationBarItem(selected = tab == t, onClick = { tab = t },
                    icon = { Icon(t.icon, null) }, label = { Text(if (t == Tab.WISP) petName else t.title) })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Bg)) {
            val a = app.activity
            if (a != null) ActivityScreen(app, a)
            else when (tab) {
                Tab.WISP -> WispScreen(app)
                Tab.STATS -> StatsScreen(app)
                Tab.SETTINGS -> SettingsScreen(app)
            }
        }
    }
}

private fun mmss(s: Long) = if (s >= 3600) "%dh %02dm".format(s / 3600, s / 60 % 60) else "%d min".format((s + 59) / 60)

@Composable
fun WispScreen(app: AppState) {
    val v by app.store.version.collectAsState()
    val link by Sync.link.collectAsState()
    val now = app.now
    val e = app.store.engine
    v.hashCode()
    val mood = e.mood(now)
    val urgent = if (e.paused(now)) null else e.mostUrgent()
    val postureDue = e.postureDue() && !e.paused(now)
    val pose = poseFor(mood, urgent, now < app.reactUntil, postureDue)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(mood.title, color = TextC, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            LinkChip(app, link)
        }
        Box(contentAlignment = Alignment.BottomCenter) {
            WispView(pose, Modifier.padding(top = 4.dp)) { app.tapWisp() }
            app.toast?.let { Text(it, color = Accent, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 2.dp)) }
        }
        val hint = when {
            mood == Mood.ASLEEP -> if (e.st.bedtime) "Asleep: bedtime" else "Asleep until ${e.config.hourStart}:00"
            e.paused(now) -> "Paused, ${mmss((e.st.pauseUntil - now) / 1000)} left"
            urgent != null -> "${urgent.title} is due: tap ${app.store.pet.title}"
            postureDue -> "Time to ${if (e.st.standing) "sit down" else "stand up"}: tap ${app.store.pet.title}"
            else -> "Tap ${app.store.pet.title} for a pat"
        }
        Text(hint, color = Dim, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        for (n in Need.entries) NeedRow(app, n)
        if (e.config.postureOn) PostureRow(app)
        Spacer(Modifier.height(12.dp))
        StepsRow(app)
        Spacer(Modifier.height(16.dp))
        Text("Activities", color = Dim, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Pill("Water", Need.WATER.color, Modifier.weight(1f)) { app.water() }
            Pill("Eyes", Need.EYES.color, Modifier.weight(1f)) { app.start(Activity.EYES) }
            Pill("Stretch", Need.STRETCH.color, Modifier.weight(1f)) { app.start(Activity.STRETCH) }
            Pill("Move", Need.MOVE.color, Modifier.weight(1f)) { app.start(Activity.MOVE) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            IconPill("Play", Icons.Filled.PlayArrow, Modifier.weight(1f)) { app.start(Activity.PLAY) }
            IconPill(if (e.st.bedtime) "Wake" else "Bedtime", Icons.Filled.Bedtime, Modifier.weight(1f)) { app.bedtime(!e.st.bedtime) }
            IconPill(if (e.paused(now)) "Resume" else "Pause 1h", Icons.Filled.Pause, Modifier.weight(1f)) { app.pause(if (e.paused(now)) 0 else 60) }
        }
    }
}

@Composable
private fun LinkChip(app: AppState, link: Sync.Link) {
    val dial = app.store.dial
    val held by Held.state.collectAsState()
    val pending = app.store.pending().isNotEmpty()
    val (icon, text) = when {
        dial == null -> Icons.Filled.CloudOff to "no dial"
        // the held link: what's logged here is on the dial within a second or two
        held == Held.State.LINKED -> Icons.Filled.Bluetooth to if (pending) "syncing" else "in sync"
        link == Sync.Link.BLUETOOTH -> Icons.Filled.Bluetooth to dial.name
        link == Sync.Link.WIFI -> Icons.Filled.Wifi to dial.name
        else -> Icons.Filled.CloudOff to "dial away"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(Panel, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        val on = held == Held.State.LINKED || link != Sync.Link.NONE
        Icon(icon, null, tint = if (on) Accent else Dim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (on) TextC else Dim, fontSize = 13.sp)
    }
}

@Composable
private fun NeedRow(app: AppState, n: Need) {
    val e = app.store.engine
    val col = Color(n.color)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(n.title, color = TextC, fontSize = 15.sp, modifier = Modifier.width(84.dp))
        LinearProgressIndicator(progress = { if (e.enabled(n)) e.level(n) / 100f else 0f }, color = if (e.due(n)) Bad else col,
            trackColor = Line, strokeCap = StrokeCap.Round, modifier = Modifier.weight(1f).height(8.dp))
        val t = when { !e.enabled(n) -> "off"; e.due(n) -> "due"; else -> "in " + mmss(e.left(n)) }
        Text(t, color = if (e.due(n)) Bad else Dim, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(78.dp))
        Text("${e.st.today[n.ordinal]}", color = col, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.width(28.dp))
    }
}

/** Sit/stand: where you are, how long, when to switch, and the switch itself. */
@Composable
private fun PostureRow(app: AppState) {
    val e = app.store.engine
    val col = Color(CareEngine.POSTURE_COLOR)
    val due = e.postureDue()
    val mins = e.st.postureElapsed / 60
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (e.st.standing) "Standing" else "Sitting", color = TextC, fontSize = 15.sp, modifier = Modifier.width(84.dp))
        Text(if (due) "$mins min: time to ${if (e.st.standing) "sit" else "stand"}"
             else "$mins min, ${if (e.st.standing) "sit" else "stand"} in ${mmss(e.postureLeft())}",
            color = if (due) col else Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { app.switchPosture() }, shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = col)) {
            Text(if (e.st.standing) "Sit down" else "Stand up", fontSize = 13.sp)
        }
    }
}

@Composable
private fun StepsRow(app: AppState) {
    val (_, steps) = Steps.today(app.ctx)
    val goal = app.store.stepGoal
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Steps today", color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("%,d".format(steps) + " / " + "%,d".format(goal), color = TextC, fontSize = 15.sp)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { min(1f, steps.toFloat() / goal) }, color = Need.MOVE.color.let { Color(it) },
            trackColor = Line, strokeCap = StrokeCap.Round, modifier = Modifier.fillMaxWidth().height(6.dp))
        if (!Steps.permitted(app.ctx)) Text("Allow physical activity in Settings to count steps", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Pill(text: String, color: Long, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(color))) { Text(text, fontSize = 14.sp, maxLines = 1) }
}

@Composable
private fun IconPill(text: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        Icon(icon, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(text, fontSize = 13.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- activities
private val STRETCHES = listOf("Reach up high", "Roll your shoulders back", "Twist left and right", "Tilt your head side to side")
private const val EYES_MS = 20_000L
private const val STRETCH_MS = 60_000L
private const val MOVE_MIN_MS = 120_000L

@Composable
fun ActivityScreen(app: AppState, a: Activity) {
    val el = app.now - app.actStart
    val loop = when (a) { Activity.EYES -> Loop.SLEEP; Activity.STRETCH -> Loop.JUMP; else -> Loop.RUN }
    // finish the timed ones by themselves
    LaunchedEffect(a, app.now) {
        if (a == Activity.EYES && el >= EYES_MS) { app.stop(); app.done(Need.EYES, "Eyes rested") }
        if (a == Activity.STRETCH && el >= STRETCH_MS) { app.stop(); app.done(Need.STRETCH, "Nice stretch") }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (a == Activity.STRETCH) "Stretch with ${app.store.pet.title}" else a.title, color = TextC, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        if (a == Activity.PLAY) { CatchGame(app); return@Column }
        val prompt = when (a) {
            Activity.EYES -> "Look at something 20 feet away"
            Activity.STRETCH -> STRETCHES[min(3L, el / 15_000).toInt()]
            else -> "Get up and move about"
        }
        Text(prompt, color = Dim, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        val (total, col) = when (a) { Activity.EYES -> EYES_MS to Need.EYES.color; Activity.STRETCH -> STRETCH_MS to Need.STRETCH.color; else -> 0L to Need.MOVE.color }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 12.dp)) {
            if (total > 0) Canvas(Modifier.size(300.dp)) {
                val sw = 10.dp.toPx()
                drawArc(Line, -90f, 360f, false, style = Stroke(sw, cap = StrokeCap.Round))
                drawArc(Color(col), -90f, 360f * (1f - el.toFloat() / total).coerceIn(0f, 1f), false, style = Stroke(sw, cap = StrokeCap.Round))
            }
            WispView(Pose(loop, null), Modifier.width(260.dp))
        }
        val t = if (total > 0) "${((total - el + 999) / 1000).coerceAtLeast(0)}" else "%d:%02d".format(el / 60_000, el / 1000 % 60)
        Text(t, color = TextC, fontSize = 40.sp, fontWeight = FontWeight.Light)
        if (a == Activity.MOVE) Text("%,d steps today".format(Steps.today(app.ctx).second), color = Dim, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { app.stop("Stopped") }) { Text("Stop") }
            Button(onClick = {
                when (a) {
                    Activity.EYES -> if (el >= 15_000) { app.stop(); app.done(Need.EYES, "Eyes rested") } else app.stop("Eye break stopped")
                    Activity.STRETCH -> if (el >= 30_000) { app.stop(); app.done(Need.STRETCH, "Nice stretch") } else app.stop("Stretch stopped")
                    Activity.MOVE -> if (el >= MOVE_MIN_MS) { app.stop(); app.done(Need.MOVE, "Welcome back: stretch and eyes too") } else app.stop("Back already? Not counted")
                    else -> {}
                }
            }) { Text(if (a == Activity.MOVE) "I'm back" else "Done") }
        }
    }
}

/** The dial's catch game: a dot runs around the ring; tap when it passes the mark at the bottom. */
@Composable
private fun CatchGame(app: AppState) {
    val rounds = 5
    var round by remember { mutableIntStateOf(0) }
    var hits by remember { mutableIntStateOf(0) }
    var start by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pauseUntil by remember { mutableLongStateOf(0L) }
    var angle by remember { mutableFloatStateOf(180f) }
    var flash by remember { mutableStateOf<Color?>(null) }
    fun period(): Long { var p = 1900L; repeat(round) { p = p * 82 / 100 }; return p }
    LaunchedEffect(Unit) {
        while (round < rounds) {
            delay(16)
            val t = System.currentTimeMillis()
            if (pauseUntil != 0L) { if (t < pauseUntil) continue; pauseUntil = 0; start = t; flash = null }
            val el = t - start
            if (el > period() * 3) { round++; flash = Bad; start = t; if (round >= rounds) break; continue }   // three laps: a miss
            angle = (180f + 360f * el / period()) % 360f
        }
        app.played(hits, rounds)
    }
    Text("Round ${min(round + 1, rounds)} of $rounds  •  $hits caught", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 16.dp).size(320.dp).clickable {
        if (pauseUntil != 0L || round >= rounds) return@clickable
        val d = min(angle, 360 - angle)
        val hit = d <= 24
        if (hit) hits++
        flash = if (hit) Ok else Bad
        round++
        pauseUntil = System.currentTimeMillis() + 600
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 - 16.dp.toPx()
            val c = center
            drawCircle(Line, r, c, style = Stroke(6.dp.toPx()))
            drawCircle(flash ?: Accent, 12.dp.toPx(), Offset(c.x, c.y + r))                  // the mark, at the bottom
            val rad = Math.toRadians((angle - 90).toDouble())
            drawCircle(Color.White, 10.dp.toPx(), Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat()))
        }
        WispView(Pose(Loop.RUN, null), Modifier.width(200.dp))
    }
    TextButton(onClick = { app.stop("Stopped") }) { Text("Stop") }
}

// ---------------------------------------------------------------- stats
@Composable
fun StatsScreen(app: AppState) {
    val v by app.store.version.collectAsState()
    v.hashCode()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        GrowthBlock(app)
        Spacer(Modifier.height(32.dp))
        TodayBlock(app)
        Spacer(Modifier.height(32.dp))
        StepsWeek(app)
        Spacer(Modifier.height(28.dp))
        val last = app.store.lastSync
        Text(if (last == 0L) "Not synced with a dial yet" else "Synced with the dial ${mmss((System.currentTimeMillis() - last) / 1000)} ago over ${app.store.lastLink}",
            color = Dim, fontSize = 12.sp)
    }
}

/** Level with xp in real numbers, then mood with its own bar, each bar captioned so they can't be confused. */
@Composable
private fun GrowthBlock(app: AppState) {
    val e = app.store.engine
    val lvl = e.level()
    val (into, need) = e.levelXp()
    Row(verticalAlignment = Alignment.Bottom) {
        Text("Level $lvl", color = TextC, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${need - into} xp to level ${lvl + 1}", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
    }
    Spacer(Modifier.height(8.dp))
    Meter(into.toFloat() / need, Accent)
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${app.store.pet.title} is ${e.mood(app.now).title.lowercase()}", color = TextC, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("${e.happiness()}% happy", color = Dim, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
    Meter(e.happiness() / 100f, Ok)
}

@Composable
private fun Meter(f: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(6.dp).background(Line, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(6.dp).background(color, RoundedCornerShape(3.dp)))
    }
}

/** One pip per break taken today, in the need's colour: the count is something you can see, not just read. */
@Composable
private fun TodayBlock(app: AppState) {
    val e = app.store.engine
    val total = e.st.today.sum()
    Row(verticalAlignment = Alignment.Bottom) {
        Text("Breaks today", color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(when (e.st.streak) { 0 -> "No streak yet"; 1 -> "1-day streak"; else -> "${e.st.streak}-day streak" }, color = Dim, fontSize = 13.sp)
    }
    Spacer(Modifier.height(10.dp))
    for (n in Need.entries) {
        val c = e.st.today[n.ordinal]
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n.title, color = TextC, fontSize = 15.sp, modifier = Modifier.width(88.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c == 0) Box(Modifier.size(10.dp).border(1.5.dp, Line, CircleShape))
                for (i in 0 until min(c, 12)) Box(Modifier.size(10.dp).background(Color(n.color), CircleShape))
                if (c > 12) Text("+${c - 12}", color = Color(n.color), fontSize = 12.sp)
            }
            Text("$c", color = if (c > 0) TextC else Dim, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.width(32.dp))
        }
    }
    if (e.config.postureOn) {
        val c = e.st.standsToday
        val col = Color(CareEngine.POSTURE_COLOR)
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Stood up", color = TextC, fontSize = 15.sp, modifier = Modifier.width(88.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c == 0) Box(Modifier.size(10.dp).border(1.5.dp, Line, CircleShape))
                for (i in 0 until min(c, 12)) Box(Modifier.size(10.dp).background(col, CircleShape))
                if (c > 12) Text("+${c - 12}", color = col, fontSize = 12.sp)
            }
            Text("$c", color = if (c > 0) TextC else Dim, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.width(32.dp))
        }
    }
    Spacer(Modifier.height(6.dp))
    val left = 4 - total
    Text(when {
        left <= 0 -> "Today counts toward the streak"
        e.st.streak > 0 -> "$left more ${if (left == 1) "break" else "breaks"} today to keep the streak"
        else -> "$left more ${if (left == 1) "break" else "breaks"} today to start a streak"
    }, color = if (left <= 0) Ok else Dim, fontSize = 13.sp)
}

/** The last seven days, always seven slots ending today, with the goal drawn as a line. Tap a bar to read that day. */
@Composable
private fun StepsWeek(app: AppState) {
    val goal = app.store.stepGoal.toLong()
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val hist = Steps.history(app.ctx, 14).toMap()
    val (todayKey, todaySteps) = Steps.today(app.ctx)
    val steps = days.map { d -> val k = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth; if (k == todayKey) maxOf(todaySteps, hist[k] ?: 0L) else hist[k] ?: 0L }
    var sel by remember { mutableIntStateOf(6) }
    val move = Color(Need.MOVE.color)
    val s = steps[sel]
    val dayName = days[sel].dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

    Text("Steps", color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text("%,d".format(s), color = TextC, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text(when (sel) { 6 -> " today"; 5 -> " yesterday"; else -> " on $dayName" }, color = Dim, fontSize = 15.sp, modifier = Modifier.padding(bottom = 5.dp))
    }
    Text(when {
        s >= goal -> "Reached the %,d goal".format(goal)
        sel == 6 -> "%,d to go for the %,d goal".format(goal - s, goal)
        else -> "%,d short of the %,d goal".format(goal - s, goal)
    }, color = if (s >= goal) Ok else Dim, fontSize = 13.sp)
    Spacer(Modifier.height(16.dp))

    val top = maxOf(goal * 6 / 5, (steps.maxOrNull() ?: 0L) * 21 / 20).toFloat()
    val label = if (goal % 1000 == 0L) "${goal / 1000}k goal" else "%.1fk goal".format(goal / 1000f)
    val summary = days.indices.joinToString { i -> "${days[i].dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${steps[i]}" }
    Row(Modifier.fillMaxWidth().height(150.dp)) {
        Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "Steps for the last 7 days, goal $goal: $summary" }
            .pointerInput(Unit) { detectTapGestures { o -> sel = (o.x / (size.width / 7f)).toInt().coerceIn(0, 6) } }) {
            val slot = size.width / 7f
            val bw = slot * 0.56f
            val gy = size.height * (1f - goal / top)
            for (i in 0 until 7) {
                val x = slot * i + (slot - bw) / 2f
                val v = steps[i]
                if (v <= 0L) {
                    drawRoundRect(Line, Offset(x, size.height - 4.dp.toPx()), Size(bw, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                    continue
                }
                val h = maxOf(4.dp.toPx(), size.height * v / top)
                val a = if (i == sel) 1f else if (v >= goal) 0.75f else 0.4f
                drawRoundRect(move.copy(alpha = a), Offset(x, size.height - h), Size(bw, h), CornerRadius(4.dp.toPx()))
            }
            drawLine(Dim, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        }
        Box(Modifier.width(44.dp).fillMaxHeight()) {
            Text(label, color = Dim, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.padding(start = 6.dp, top = ((150f * (1f - goal / top)).dp - 8.dp).coerceAtLeast(0.dp)))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        for (i in 0 until 7) Text(if (i == 6) "Today" else days[i].dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            color = if (i == sel) TextC else Dim, fontSize = 12.sp, fontWeight = if (i == sel) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f).clickable { sel = i })
        Spacer(Modifier.width(44.dp))
    }
    Spacer(Modifier.height(16.dp))
    val counted = steps.filter { it > 0 }
    Row(Modifier.fillMaxWidth()) {
        Fact("%,d".format(steps.sum()), "this week", Modifier.weight(1f))
        Fact(if (counted.isEmpty()) "–" else "%,d".format(counted.sum() / counted.size), "a day on average", Modifier.weight(1f))
        Fact("${steps.count { it >= goal }} of 7", "days at goal", Modifier.weight(1f))
    }
    when {
        !Steps.available(app.ctx) -> Note("This phone has no step counter")
        !Steps.permitted(app.ctx) -> Note("Allow physical activity in Settings to count steps")
        counted.isEmpty() -> Note("Steps appear here once the phone has counted some")
    }
}

@Composable
private fun Fact(value: String, what: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(what, color = Dim, fontSize = 12.sp)
    }
}

@Composable
private fun Note(t: String) = Text(t, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
