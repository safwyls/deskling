package app.deskling.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import app.deskling.care.Mood
import app.deskling.care.Need
import app.deskling.steps.Steps
import app.deskling.sync.Sync
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class Tab(val title: String, val icon: ImageVector) {
    WISP("Wisp", Icons.Filled.Pets), STATS("Stats", Icons.Filled.Insights), SETTINGS("Settings", Icons.Filled.Settings)
}

@Composable
fun MainUi(app: AppState) {
    var tab by remember { mutableStateOf(Tab.WISP) }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (app.activity == null) NavigationBar(containerColor = Panel) {
                for (t in Tab.entries) NavigationBarItem(selected = tab == t, onClick = { tab = t },
                    icon = { Icon(t.icon, null) }, label = { Text(t.title) })
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
    val pose = poseFor(mood, urgent, now < app.reactUntil)
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
            urgent != null -> "${urgent.title} is due: tap Wisp"
            else -> "Tap Wisp for a pat"
        }
        Text(hint, color = Dim, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        for (n in Need.entries) NeedRow(app, n)
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
    val (icon, text) = when {
        dial == null -> Icons.Filled.CloudOff to "no dial"
        link == Sync.Link.BLUETOOTH -> Icons.Filled.Bluetooth to dial.name
        link == Sync.Link.WIFI -> Icons.Filled.Wifi to dial.name
        else -> Icons.Filled.CloudOff to "dial away"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(Panel, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Icon(icon, null, tint = if (link == Sync.Link.NONE) Dim else Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (link == Sync.Link.NONE) Dim else TextC, fontSize = 13.sp)
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
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(color))) { Text(text, fontSize = 14.sp, maxLines = 1) }
}

@Composable
private fun IconPill(text: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(10.dp)) {
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
        Text(a.title, color = TextC, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
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
                    Activity.MOVE -> if (el >= MOVE_MIN_MS) { app.stop(); app.done(Need.MOVE, "Welcome back") } else app.stop("Back already? Not counted")
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
    val e = app.store.engine
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Level ${e.level()}", color = TextC, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(progress = { e.levelProgress() / 100f }, color = Accent, trackColor = Line, strokeCap = StrokeCap.Round,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(8.dp))
        Text("${e.mood(app.now).title}  •  ${e.happiness()}% happy", color = Dim, fontSize = 15.sp)
        LinearProgressIndicator(progress = { e.happiness() / 100f }, color = Ok, trackColor = Line, strokeCap = StrokeCap.Round,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(8.dp))
        Text(if (e.st.streak > 0) "${e.st.streak}-day streak (4+ breaks a day)" else "No streak yet: 4 breaks in a day starts one", color = Dim, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Text("Today", color = TextC, fontSize = 18.sp)
        for (n in Need.entries) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Box(Modifier.size(12.dp).background(Color(n.color), CircleShape).align(Alignment.CenterVertically))
            Spacer(Modifier.width(10.dp))
            Text(n.title, color = TextC, modifier = Modifier.weight(1f))
            Text("${e.st.today[n.ordinal]}", color = TextC)
        }
        Spacer(Modifier.height(16.dp))
        Text("Steps, last 7 days", color = TextC, fontSize = 18.sp)
        val hist = Steps.history(app.ctx)
        val max = (hist.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(app.store.stepGoal.toLong())
        Row(Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            if (hist.isEmpty()) Text("No steps recorded yet", color = Dim)
            for ((day, s) in hist) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (s >= 1000) "${s / 1000}k" else "$s", color = Dim, fontSize = 11.sp)
                Box(Modifier.fillMaxWidth().height((100f * s / max).dp.coerceAtLeast(2.dp)).background(Color(Need.MOVE.color), RoundedCornerShape(4.dp)))
                Text("${day % 100}", color = Dim, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        val last = app.store.lastSync
        Text(if (last == 0L) "Not synced with a dial yet" else "Last synced ${mmss((System.currentTimeMillis() - last) / 1000)} ago over ${app.store.lastLink}",
            color = Dim, fontSize = 13.sp, modifier = Modifier.border(0.dp, Color.Transparent))
    }
}
