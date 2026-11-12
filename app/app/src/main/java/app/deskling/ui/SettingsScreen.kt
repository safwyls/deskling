package app.deskling.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deskling.data.CompanionRecord
import app.deskling.data.Pet
import app.deskling.data.WidgetBg
import app.deskling.data.NotifyMode
import app.deskling.steps.Steps
import app.deskling.sync.FoundDial
import app.deskling.sync.LinkService
import app.deskling.sync.Sync
import app.deskling.sync.discoverOnWifi
import app.deskling.sync.scanDials
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

private enum class SettingsTab(val title: String) { COMPANION("Companion"), REMINDERS("Reminders"), DIAL("Dial"), PHONE("Phone") }

/** Settings in four tabs: the companion, reminders, the dials, and this phone. */
@Composable
fun SettingsScreen(app: AppState) {
    val v by app.store.version.collectAsState()
    v.hashCode()
    var pairing by remember { mutableStateOf(false) }
    var wifi by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(SettingsTab.COMPANION) }
    if (pairing) { PairScreen(app) { pairing = false }; return }
    if (wifi) { DialWifiScreen(app) { wifi = false }; return }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal, containerColor = Bg, contentColor = Accent) {
            // the content form of Tab: the text form pads each side by 16 dp, which cuts "Companion" short on a phone
            for (t in SettingsTab.entries) Tab(selected = tab == t, onClick = { tab = t }, selectedContentColor = Accent, unselectedContentColor = Dim) {
                Text(t.title, fontSize = 13.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(vertical = 13.dp))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            when (tab) {
                SettingsTab.COMPANION -> CompanionSettings(app)
                SettingsTab.REMINDERS -> ReminderSettings(app)
                SettingsTab.DIAL -> DialTab(app, pair = { pairing = true }, wifi = { wifi = true })
                SettingsTab.PHONE -> PhoneSettings(app)
            }
        }
    }
}

@Composable
private fun Header(t: String) {
    Spacer(Modifier.height(18.dp))
    Text(t, color = Accent, fontSize = 15.sp)
    HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Item(title: String, detail: String, action: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextC, fontSize = 16.sp)
            if (detail.isNotBlank()) Text(detail, color = Dim, fontSize = 13.sp)
        }
        if (action != null) Text(action, color = Accent)
    }
}

/** A row that opens a menu of choices. */
@Composable
private fun Choice(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Item(title, options.getOrElse(selected) { "" }, "Change") { open = true }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

@Composable
private fun Toggle(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextC, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(on, onChange)
    }
}

@Composable
private fun SliderRow(title: String, value: Int, range: IntRange, label: (Int) -> String, onDone: (Int) -> Unit) {
    var pos by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row { Text(title, color = TextC, fontSize = 16.sp, modifier = Modifier.weight(1f)); Text(label(pos.toInt()), color = Dim) }
        Slider(pos, { pos = it }, valueRange = range.first.toFloat()..range.last.toFloat(), onValueChangeFinished = { onDone(pos.toInt()) })
    }
}

/** Writes one of the current dial's settings, then catches up with it. */
@Composable
private fun rememberDialSetter(app: AppState): (String, Any) -> Unit {
    val scope = rememberCoroutineScope()
    return { k, v ->
        scope.launch {
            val r = Sync.call(app.ctx, JSONObject().put("op", "set").put(k, v))
            if (r?.optBoolean("ok") == true) { app.store.dialSettings = r; Sync.sync(app.ctx) } else app.say("Couldn't reach the dial")
        }
    }
}

/** How often each break comes up and when, from the dial's settings (or the phone's own without a dial). */
@Composable
private fun ReminderSettings(app: AppState) {
    val store = app.store
    val s = store.dialSettings
    val set = rememberDialSetter(app)
    if (store.dial != null && !s.has("remind")) {
        Header("Breaks"); Text("Sync with ${store.dial?.name} once to see its reminders.", color = Dim, fontSize = 14.sp)
    } else if (store.dial != null) {
        Header("Breaks")
        Text("Set on ${store.dial?.name}, and used wherever your companion is.", color = Dim, fontSize = 12.sp)
        val rem = s.optJSONArray("remind") ?: JSONArray()
        val every = listOf(0) + (5..120 step 5).toList()
        val names = listOf("Water", "Eye break", "Stretch", "Move")
        for (i in 0 until 4) {
            val cur = rem.optInt(i)
            Choice(names[i], every.map { if (it == 0) "off" else "every $it min" }, every.indexOf(cur).coerceAtLeast(0)) { pick ->
                val a = JSONArray(); for (j in 0 until 4) a.put(if (j == i) every[pick] else rem.optInt(j)); set("remind", a)
            }
        }
        Header("Sit and stand")
        Toggle("Sit/stand reminders", s.optBoolean("postureOn")) { set("postureOn", it) }
        if (s.optBoolean("postureOn")) {
            val sit = (15..120 step 5).toList(); val stand = (5..90 step 5).toList()
            Choice("Sit for", sit.map { "$it min, then stand" }, sit.indexOf(s.optInt("sitMin", 45)).coerceAtLeast(0)) { set("sitMin", sit[it]) }
            Choice("Stand for", stand.map { "$it min, then sit" }, stand.indexOf(s.optInt("standMin", 15)).coerceAtLeast(0)) { set("standMin", stand[it]) }
        }
        Header("Hours")
        val hours = (0..24).map { if (it == 24) "midnight" else "%02d:00".format(it) }
        Choice("Active from", hours.take(24), s.optInt("hourStart")) { set("hourStart", it) }
        Choice("Active until", hours.drop(1), s.optInt("hourEnd") - 1) { set("hourEnd", it + 1) }
        val tz = s.optJSONArray("tzNames")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        Choice("Time zone", tz, s.optInt("tz")) { set("tz", it) }
    } else {
        // no dial to hold the settings: the app's own
        Header("Sit and stand")
        val cfg = store.engine.config
        fun put(c: app.deskling.care.CareConfig) { store.engine.config = c; store.saveCare(); store.changed() }
        Toggle("Sit/stand reminders", cfg.postureOn) { put(cfg.copy(postureOn = it)) }
        if (cfg.postureOn) {
            val sit = (15..120 step 5).toList(); val stand = (5..90 step 5).toList()
            Choice("Sit for", sit.map { "$it min, then stand" }, sit.indexOf(cfg.sitMin).coerceAtLeast(0)) { put(cfg.copy(sitMin = sit[it])) }
            Choice("Stand for", stand.map { "$it min, then sit" }, stand.indexOf(cfg.standMin).coerceAtLeast(0)) { put(cfg.copy(standMin = stand[it])) }
        }
    }
    Header("On the phone")
    Choice("Reminder notifications", NotifyMode.entries.map { it.title }, store.notifyMode.ordinal) { store.notifyMode = NotifyMode.entries[it] }
}

/** The paired dials: the current one's link, Wi-Fi, screen and lights. */
@Composable
private fun DialTab(app: AppState, pair: () -> Unit, wifi: () -> Unit) {
    val store = app.store
    val scope = rememberCoroutineScope()
    val dial = store.dial
    val dials = store.dials
    val msg by Sync.message.collectAsState()
    Header(if (dials.size > 1) "Dials" else "Dial")
    if (dial == null) {
        Text("Pair the app with your Deskling: your companion can then live on the dial, and come along on the phone when you leave.", color = Dim, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = pair) { Text("Pair a dial") }
        return
    }
    Item(dial.name + if (dials.size > 1) "  (settings below)" else "",
        if (store.lastSync == 0L) "not synced yet" else "synced over ${store.lastLink}" + (msg.takeIf { it.isNotBlank() }?.let { "  •  $it" } ?: ""))
    for (d in dials.filter { it.name != dial.name }) Item(d.name, "paired", "Show") { Sync.use(app.ctx, d); scope.launch { Sync.sync(app.ctx) } }
    Row {
        OutlinedButton(onClick = { scope.launch { if (!Sync.sync(app.ctx)) app.say("Couldn't reach the dial") } }) { Text("Sync now") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = pair) { Text("Pair another") }
        TextButton(onClick = { Sync.forget(app.ctx) }) { Text("Forget") }
    }
    Toggle("Stay connected", store.holdLink) { store.holdLink = it; LinkService.update(app.ctx) }
    Text(if (store.holdLink) "Syncs the moment anything happens, here or on the dial, while you're nearby. Android shows a quiet notification while it's on."
         else "Syncs every few minutes instead, and when you open the app.", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
    val w = store.dialState.optJSONObject("wifi")
    Item("Wi-Fi", if (w?.optBoolean("up") == true) "${w.optString("ssid")}  •  ${w.optString("ip")}" else "not connected", "Change", wifi)

    val s = store.dialSettings
    if (!s.has("remind")) { Text("Sync once to see the dial's settings.", color = Dim, fontSize = 14.sp); return }
    val set = rememberDialSetter(app)
    Header("Home screen")
    Choice("Clock", listOf("Off", "12-hour", "24-hour"), s.optInt("clockMode")) { set("clockMode", it) }
    val styles = s.optJSONArray("clockStyles")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
    Choice("Clock style", styles, s.optInt("clockStyle")) { set("clockStyle", it) }
    Toggle("Show the date", s.optBoolean("showDate")) { set("showDate", it) }
    Toggle("Wi-Fi icon", s.optBoolean("showStatus")) { set("showStatus", it) }
    Toggle("Next reminder", s.optBoolean("showNext")) { set("showNext", it) }
    Header("Screen, lights and knob")
    SliderRow("Screen brightness", s.optInt("bright", 70), 10..100, { "$it%" }) { set("bright", it) }
    SliderRow("LED brightness", s.optInt("ledBright", 60), 5..255, { "${it * 100 / 255}%" }) { set("ledBright", it) }
    SliderRow("Nap after", s.optInt("napMin", 10), 0..60, { if (it == 0) "never" else "$it min" }) { set("napMin", it) }
    SliderRow("LED alignment", s.optInt("ledOffset"), -16..16, { "%+.0f°".format(it * 11.25f) }) { set("ledOffset", it) }
    Toggle("Reverse the knob", s.optBoolean("encInvert")) { set("encInvert", it) }
}

/** Steps, permissions and the app itself. */
@Composable
private fun PhoneSettings(app: AppState) {
    val s = app.store
    val perms = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) } else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { s.changed() }
    Header("Steps")
    SliderRow("A walk is", s.walkSteps, 100..1000, { "$it steps in 10 min" }) { s.walkSteps = (it / 50) * 50 }
    SliderRow("Daily step goal", s.stepGoal, 2000..20000, { "%,d".format(it) }) { s.stepGoal = (it / 500) * 500 }
    Header("Home screen widget")
    Choice("Background", WidgetBg.entries.map { it.title }, s.widgetBg.ordinal) { s.widgetBg = WidgetBg.entries[it] }
    Text("Add it from your home screen's widget list: Deskling. It shows ${s.pet.title}, how it's doing and today's steps.", color = Dim, fontSize = 12.sp)
    Header("Permissions")
    val missing = buildList {
        if (!Sync.hasBlePermission(app.ctx)) add("Bluetooth")
        if (!Steps.permitted(app.ctx)) add("physical activity (steps)")
        if (!Steps.available(app.ctx)) add("(this phone has no step counter)")
    }
    Item(if (missing.isEmpty()) "All set" else "Missing", if (missing.isEmpty()) "Bluetooth, steps and notifications" else missing.joinToString(", "),
        if (missing.isEmpty()) null else "Allow") { ask.launch(perms) }
    Header("About")
    Text("Deskling 1.11  •  ${s.pet.title} and the care game, travelling between your dials and this phone over Bluetooth or Wi-Fi. Open source under the MIT licence: github.com/safwyls/deskling", color = Dim, fontSize = 13.sp)
}

/** Which companion it is, and taking one in from a link. */
@Composable
private fun CompanionSettings(app: AppState) {
    val s = app.store
    val t = s.travel
    val scope = rememberCoroutineScope()
    var paste by remember { mutableStateOf("") }
    Header("Your companion")
    val atCurrent = !t.here && s.dial?.name == t.where && s.dialSettings.has("pets")
    when {
        t.here -> Choice("Who", Pet.entries.map { it.title }, s.pet.ordinal) { s.pet = Pet.entries[it] }
        atCurrent -> Choice("Who", Pet.entries.map { it.title }, s.pet.ordinal) { i ->
            scope.launch {
                val r = Sync.call(app.ctx, JSONObject().put("op", "set").put("pet", Pet.entries[i].id))
                if (r?.optBoolean("ok") == true) { s.pet = Pet.entries[i]; app.say("${t.where} is restarting with ${Pet.entries[i].title}", 4000) }
                else app.say("Couldn't reach the dial")
            }
        }
        else -> Item("Who", "${s.pet.title}: change it where it is (with ${t.where})")
    }
    Text("Same level, streak and breaks whichever it is." + if (atCurrent) " The dial restarts to switch." else "", color = Dim, fontSize = 12.sp)
    Item("Where", if (t.here) "on this phone" else if (t.where == Sync.SHARED) "left by a link" else "with ${t.where}")
    Spacer(Modifier.height(6.dp))
    Text("Given a companion's link? Paste it here (or just open it on this phone).", color = Dim, fontSize = 13.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(paste, { paste = it }, label = { Text("Link") }, singleLine = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextButton(enabled = paste.isNotBlank(), onClick = {
            val r = CompanionRecord.fromLink(paste)
            if (r == null) app.say("That isn't a companion's link") else { Inbox.incoming.value = r; paste = "" }
        }) { Text("Open") }
    }
}

// ---------------------------------------------------------------- pairing
@Composable
private fun PairScreen(app: AppState, close: () -> Unit) {
    val found = remember { mutableStateListOf<FoundDial>() }
    val onLan = remember { mutableStateListOf<Pair<String, String>>() }
    var pick by remember { mutableStateOf<Pair<String, String>?>(null) }   // (name, "ble:<addr>" | "ip:<ip>")
    var code by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (Sync.hasBlePermission(app.ctx)) launch {
            runCatching { withTimeoutOrNull(20_000) { scanDials(app.ctx).collect { d -> if (found.none { it.address == d.address }) found.add(d) } } }
                .onFailure { msg = it.message }
        }
        launch { runCatching { withTimeoutOrNull(20_000) { discoverOnWifi(app.ctx).collect { p -> if (p !in onLan) onLan.add(p) } } } }
    }
    var showIp by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pair a dial", color = TextC, fontSize = 22.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = close) { Text("Cancel") }
        }
        Text("The dial's code is needed whichever way the phone connects.", color = Dim, fontSize = 14.sp)

        PairStep(1, "Enter the code from the dial")
        Text("On the dial: Settings > Phone app > Pair a phone.", color = Dim, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(code, { code = it.filter { c -> c.isDigit() }.take(6) }, label = { Text("6-digit code") }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, letterSpacing = 6.sp, color = TextC),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

        PairStep(2, "Pick your dial")
        Text("Bluetooth or Wi-Fi: either works.", color = Dim, fontSize = 14.sp)
        if (!Sync.hasBlePermission(app.ctx)) Text("Allow Bluetooth in Settings to find dials nearby.", color = Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        SubHead("Nearby over Bluetooth")
        if (found.isEmpty()) Text("Looking...", color = Dim, fontSize = 14.sp)
        for (d in found) Item(d.name, "signal ${d.rssi} dBm", if (pick?.second == "ble:${d.address}") "Picked" else "Pick") { pick = d.name to "ble:${d.address}" }
        SubHead("On this Wi-Fi network")
        if (onLan.isEmpty()) Text("None found yet", color = Dim, fontSize = 14.sp)
        for ((n, ip) in onLan) Item(n, ip, if (pick?.second == "ip:$ip") "Picked" else "Pick") { pick = n to "ip:$ip" }
        if (!showIp) TextButton(onClick = { showIp = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Not listed? Enter its IP address") }
        else OutlinedTextField(manualIp, { manualIp = it; pick = if (it.isBlank()) null else "Deskling" to "ip:$it" }, label = { Text("IP address (on the dial: Settings > About)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

        Spacer(Modifier.height(20.dp))
        Button(enabled = !busy && pick != null && code.length == 6, modifier = Modifier.fillMaxWidth(), onClick = {
            val (name, where) = pick ?: return@Button
            busy = true; msg = null
            scope.launch {
                val err = if (where.startsWith("ble:")) Sync.pair(app.ctx, name, code, address = where.removePrefix("ble:"))
                          else Sync.pair(app.ctx, name, code, ip = where.removePrefix("ip:"))
                busy = false
                if (err == null) { Sync.sync(app.ctx); app.say("Paired with ${app.store.dial?.name}"); close() } else msg = err
            }
        }) { Text(if (busy) "Pairing..." else pick?.let { "Pair with ${it.first}" } ?: "Pair") }
        val missing = listOfNotNull(if (code.length < 6) "the code" else null, if (pick == null) "a dial" else null)
        if (!busy && missing.isNotEmpty()) Text("Needs ${missing.joinToString(" and ")}", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        msg?.let { Text(it, color = Bad, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun PairStep(n: Int, title: String) {
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).background(Accent, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = Bg, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, color = TextC, fontSize = 17.sp)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SubHead(t: String) {
    Spacer(Modifier.height(14.dp))
    Text(t, color = Accent, fontSize = 14.sp)
    HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 4.dp))
}

// ---------------------------------------------------------------- the dial's Wi-Fi, set from the phone
@Composable
private fun DialWifiScreen(app: AppState, close: () -> Unit) {
    val nets = remember { mutableStateListOf<JSONObject>() }
    var ssid by remember { mutableStateOf(app.store.dialSettings.optString("ssid")) }
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        Sync.call(app.ctx, JSONObject().put("op", "scan"))
        repeat(15) {
            delay(1000)
            val r = Sync.call(app.ctx, JSONObject().put("op", "nets")) ?: return@repeat
            if (r.optString("state") == "done") {
                nets.clear()
                r.optJSONArray("nets")?.let { a -> for (i in 0 until a.length()) nets.add(a.getJSONObject(i)) }
                return@LaunchedEffect
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dial Wi-Fi", color = TextC, fontSize = 22.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = close) { Text("Close") }
        }
        Text("The dial joins the network you pick; nothing to type on the knob.", color = Dim, fontSize = 14.sp)
        Header("Networks the dial can see")
        if (nets.isEmpty()) Text("Scanning...", color = Dim)
        for (n in nets) {
            val name = n.optString("ssid")
            val strength = n.optInt("rssi").let { if (it > -55) "strong" else if (it > -67) "good" else if (it > -78) "weak" else "very weak" }
            Item(name, strength + if (n.optBoolean("open")) "  •  open" else "", if (name == ssid) "Picked" else "Pick") { ssid = name; pass = "" }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(ssid, { ssid = it }, label = { Text("Network") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            placeholder = { if (ssid == app.store.dialSettings.optString("ssid")) Text("saved: leave empty to keep it") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(enabled = !busy && ssid.isNotBlank(), onClick = {
            busy = true; msg = "Asking the dial to join $ssid..."
            scope.launch {
                val r = Sync.call(app.ctx, JSONObject().put("op", "wifi").put("ssid", ssid).put("pass", pass))
                if (r?.optBoolean("ok") != true) { msg = r?.optString("err") ?: "Couldn't reach the dial"; busy = false; return@launch }
                // the dial may drop off Wi-Fi while it tries: ask over whichever link still works
                repeat(40) {
                    delay(1500)
                    val st = Sync.call(app.ctx, JSONObject().put("op", "state"))?.optJSONObject("wifi") ?: return@repeat
                    when (st.optString("try")) {
                        "ok" -> { msg = "Connected: ${st.optString("tryMsg")}"; busy = false; Sync.sync(app.ctx); return@launch }
                        "fail" -> { msg = st.optString("tryMsg"); busy = false; return@launch }
                    }
                }
                msg = "No answer: the dial's screen shows what happened"; busy = false
            }
        }) { Text("Connect") }
        msg?.let { Text(it, color = if (it.startsWith("Connected")) Ok else Dim, modifier = Modifier.padding(top = 8.dp).background(Panel, RoundedCornerShape(8.dp)).padding(8.dp)) }
    }
}
