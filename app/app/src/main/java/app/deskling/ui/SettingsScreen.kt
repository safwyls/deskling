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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deskling.data.Pet
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

@Composable
fun SettingsScreen(app: AppState) {
    val v by app.store.version.collectAsState()
    v.hashCode()
    var pairing by remember { mutableStateOf(false) }
    var wifi by remember { mutableStateOf(false) }
    if (pairing) { PairScreen(app) { pairing = false }; return }
    if (wifi) { DialWifiScreen(app) { wifi = false }; return }
    val scope = rememberCoroutineScope()
    val dial = app.store.dial
    val msg by Sync.message.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Header("Dial")
        if (dial == null) {
            Text("Pair the app with your Deskling to share one pet: breaks you log on either count on both.", color = Dim, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { pairing = true }) { Text("Pair a dial") }
        } else {
            val st = app.store.dialState
            val w = st.optJSONObject("wifi")
            Item(dial.name, if (app.store.lastSync == 0L) "not synced yet" else "synced over ${app.store.lastLink}" + (msg.takeIf { it.isNotBlank() }?.let { "  •  $it" } ?: ""))
            Item("Wi-Fi", if (w?.optBoolean("up") == true) "${w.optString("ssid")}  •  ${w.optString("ip")}" else "not connected", "Change") { wifi = true }
            Row {
                OutlinedButton(onClick = { scope.launch { if (!Sync.sync(app.ctx)) app.say("Couldn't reach the dial") } }) { Text("Sync now") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { Sync.forget(app.ctx) }) { Text("Forget this dial") }
            }
            DialSettings(app)
        }
        AppSettings(app)
        Header("About")
        Text("Deskling 1.6  •  ${app.store.pet.title} and the care game, shared with your dial over Bluetooth or Wi-Fi.", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
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

/** The dial's own settings, read from it and written straight back. */
@Composable
private fun DialSettings(app: AppState) {
    val s = app.store.dialSettings
    val scope = rememberCoroutineScope()
    fun set(k: String, v: Any) {
        scope.launch {
            val r = Sync.call(app.ctx, JSONObject().put("op", "set").put(k, v))
            if (r?.optBoolean("ok") == true) { app.store.dialSettings = r; Sync.sync(app.ctx) } else app.say("Couldn't reach the dial")
        }
    }
    if (!s.has("remind")) { Text("Sync once to see the dial's settings.", color = Dim, fontSize = 14.sp); return }
    Header("Reminders")
    val rem = s.optJSONArray("remind") ?: JSONArray()
    val every = listOf(0) + (5..120 step 5).toList()
    val names = listOf("Water", "Eye break", "Stretch", "Move")
    for (i in 0 until 4) {
        val cur = rem.optInt(i)
        Choice(names[i], every.map { if (it == 0) "off" else "every $it min" }, every.indexOf(cur).coerceAtLeast(0)) { pick ->
            val a = JSONArray(); for (j in 0 until 4) a.put(if (j == i) every[pick] else rem.optInt(j)); set("remind", a)
        }
    }
    Toggle("Sit/stand reminders", s.optBoolean("postureOn")) { set("postureOn", it) }
    if (s.optBoolean("postureOn")) {
        val sit = (15..120 step 5).toList(); val stand = (5..90 step 5).toList()
        Choice("Sit for", sit.map { "$it min, then stand" }, sit.indexOf(s.optInt("sitMin", 45)).coerceAtLeast(0)) { set("sitMin", sit[it]) }
        Choice("Stand for", stand.map { "$it min, then sit" }, stand.indexOf(s.optInt("standMin", 15)).coerceAtLeast(0)) { set("standMin", stand[it]) }
    }
    val hours = (0..24).map { if (it == 24) "midnight" else "%02d:00".format(it) }
    Choice("Active from", hours.take(24), s.optInt("hourStart")) { set("hourStart", it) }
    Choice("Active until", hours.drop(1), s.optInt("hourEnd") - 1) { set("hourEnd", it + 1) }
    val tz = s.optJSONArray("tzNames")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
    Choice("Time zone", tz, s.optInt("tz")) { set("tz", it) }

    Header("Dial home screen")
    Choice("Clock", listOf("Off", "12-hour", "24-hour"), s.optInt("clockMode")) { set("clockMode", it) }
    val styles = s.optJSONArray("clockStyles")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
    Choice("Clock style", styles, s.optInt("clockStyle")) { set("clockStyle", it) }
    Toggle("Show the date", s.optBoolean("showDate")) { set("showDate", it) }
    Toggle("Wi-Fi icon", s.optBoolean("showStatus")) { set("showStatus", it) }
    Toggle("Next reminder", s.optBoolean("showNext")) { set("showNext", it) }

    Header("Dial")
    SliderRow("Screen brightness", s.optInt("bright", 70), 10..100, { "$it%" }) { set("bright", it) }
    SliderRow("LED brightness", s.optInt("ledBright", 60), 5..255, { "${it * 100 / 255}%" }) { set("ledBright", it) }
    SliderRow("Nap after", s.optInt("napMin", 10), 0..60, { if (it == 0) "never" else "$it min" }) { set("napMin", it) }
    SliderRow("LED alignment", s.optInt("ledOffset"), -16..16, { "%+.0f°".format(it * 11.25f) }) { set("ledOffset", it) }
    Toggle("Reverse the knob", s.optBoolean("encInvert")) { set("encInvert", it) }
}

@Composable
private fun AppSettings(app: AppState) {
    val s = app.store
    val perms = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) } else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { s.changed() }
    Header("This phone")
    if (s.dial == null) {
        Choice("Pet", Pet.entries.map { it.title }, s.pet.ordinal) { s.pet = Pet.entries[it] }
        // no dial to hold the setting: the app's own
        val cfg = s.engine.config
        fun put(c: app.deskling.care.CareConfig) { s.engine.config = c; s.saveCare(); s.changed() }
        Toggle("Sit/stand reminders", cfg.postureOn) { put(cfg.copy(postureOn = it)) }
        if (cfg.postureOn) {
            val sit = (15..120 step 5).toList(); val stand = (5..90 step 5).toList()
            Choice("Sit for", sit.map { "$it min, then stand" }, sit.indexOf(cfg.sitMin).coerceAtLeast(0)) { put(cfg.copy(sitMin = sit[it])) }
            Choice("Stand for", stand.map { "$it min, then sit" }, stand.indexOf(cfg.standMin).coerceAtLeast(0)) { put(cfg.copy(standMin = stand[it])) }
        }
    }
    else Item("Pet", "${s.pet.title}: the paired dial's firmware decides")
    if (s.dial != null) {
        Toggle("Stay connected to the dial", s.holdLink) { s.holdLink = it; LinkService.update(app.ctx) }
        Text(if (s.holdLink) "Breaks sync the moment you log them, here or on the dial, while you're nearby. Android shows a quiet notification while it's on."
             else "Syncs every few minutes instead, and when you open the app.", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
    Choice("Reminders on the phone", NotifyMode.entries.map { it.title }, s.notifyMode.ordinal) { s.notifyMode = NotifyMode.entries[it] }
    SliderRow("A walk is", s.walkSteps, 100..1000, { "$it steps in 10 min" }) { s.walkSteps = (it / 50) * 50 }
    SliderRow("Daily step goal", s.stepGoal, 2000..20000, { "%,d".format(it) }) { s.stepGoal = (it / 500) * 500 }
    val missing = buildList {
        if (!Sync.hasBlePermission(app.ctx)) add("Bluetooth")
        if (!Steps.permitted(app.ctx)) add("physical activity (steps)")
        if (!Steps.available(app.ctx)) add("(this phone has no step counter)")
    }
    Item("Permissions", if (missing.isEmpty()) "all set" else "missing: " + missing.joinToString(", "), if (missing.isEmpty()) null else "Allow") { ask.launch(perms) }
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
