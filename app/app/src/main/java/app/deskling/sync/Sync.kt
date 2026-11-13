package app.deskling.sync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.provider.Settings
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import app.deskling.data.CompanionRecord
import app.deskling.data.DialInfo
import app.deskling.data.DialTravel
import app.deskling.data.Pet
import app.deskling.data.Store
import app.deskling.data.Travel
import app.deskling.data.Travels
import app.deskling.steps.Steps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The phone's side of the link to its dials, and the companion's moves between them.
 *
 * The companion lives on one device at a time. While it's at the current dial, that dial holds the truth: the
 * phone sends what it logged (a reminder's Done, with when it happened) and its step count, then takes the
 * dial's state. While it's on the phone, the phone runs it, and a sync only exchanges settings, steps and news
 * of where the companion is.
 */
object Sync {
    enum class Link { NONE, WIFI, BLUETOOTH }

    private val _link = MutableStateFlow(Link.NONE)
    val link: StateFlow<Link> = _link
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    private val mutex = Mutex()
    private var ble: BleLink? = null
    private var bleFor = ""

    fun hasBlePermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")   // allNetworks: still the simplest "is any Wi-Fi up", and it works on every version
    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /**
     * What this phone is called on the dials ("Wisp is with Sam's S26"): its Bluetooth name, which is the name
     * the owner gave it. Without the Bluetooth permission, the device name from Settings (the same thing on most
     * phones); failing both, the model.
     */
    @SuppressLint("MissingPermission")
    fun phoneName(ctx: Context): String {
        val bt = if (hasBlePermission(ctx)) runCatching {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.name
        }.getOrNull() else null
        val dev = runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        // the dial's font is plain ASCII: straighten curly quotes, drop what it can't draw
        val ascii = { n: String -> n.replace('\u2019', '\'').replace('\u2018', '\'').replace('\u201C', '"').replace('\u201D', '"')
            .filter { it in ' '..'~' }.replace(Regex("\\s+"), " ").trim() }
        return listOf(bt, dev, Build.MODEL).map { ascii(it ?: "") }.firstOrNull { it.isNotBlank() }?.take(32) ?: "your phone"
    }

    /** A working link to a paired dial: Wi-Fi first (when the phone is on Wi-Fi), then Bluetooth. */
    private suspend fun open(ctx: Context, d: DialInfo, allowBle: Boolean): DialLink? {
        Held.current(d.bleAddress)?.let { return it }     // the link held open to the dial: quickest, both ways
        if (d.ip.isNotBlank() && onWifi(ctx)) {
            val w = WifiLink(d.ip, d.key)
            val hello = runCatching { w.request(JSONObject().put("op", "hello").put("key", d.key)) }.getOrNull()
            if (hello?.optBoolean("ok") == true) { heard(ctx, d, hello); return w }
        }
        if (!allowBle || !hasBlePermission(ctx) || Held.holding(d.bleAddress)) return null     // the held link is waiting for the dial: don't compete with it
        ble?.let { if (it.connected && bleFor == d.id) return it; it.close() }
        ble = null
        var address = d.bleAddress
        if (address.isBlank()) {
            address = withTimeoutOrNull(6000) { scanDials(ctx).firstOrNull { it.name == d.name || it.name == d.id } }?.address ?: return null
        }
        val b = runCatching { BleLink.connect(ctx, address) }.getOrNull() ?: return null
        val hello = runCatching { b.request(JSONObject().put("op", "hello").put("key", d.key).put("now", System.currentTimeMillis() / 1000)) }.getOrNull()
        if (hello?.optBoolean("ok") != true) { b.close(); _message.value = hello?.optString("err") ?: "Bluetooth hello failed"; return null }
        val store = Store.get(ctx)
        if (address != d.bleAddress) { store.updateDial(d.copy(bleAddress = address)); LinkService.update(ctx) }
        hello.optString("ip").takeIf { it.isNotBlank() }?.let { ip -> store.dials.firstOrNull { it.id == d.id }?.let { if (it.ip != ip) store.updateDial(it.copy(ip = ip)) } }
        heard(ctx, d, hello)
        ble = b
        bleFor = d.id
        return b
    }

    /** What a dial's hello says about itself: it may have been renamed since (from another phone, say). */
    private fun heard(ctx: Context, d: DialInfo, hello: JSONObject) {
        val name = hello.optString("name")
        if (name.isNotBlank() && name != d.name && hello.optString("id").let { it.isBlank() || it == d.id }) Store.get(ctx).renameDial(d.id, name)
    }

    /** Longest name a dial takes (firmware DIAL_NAME_MAX). */
    const val DIAL_NAME_MAX = 24

    /**
     * Call the current dial something else ("" gives it its id back). Null when done, else why not.
     * Plain ASCII, as the dial's font has it; the dial tidies the spaces and has the last word.
     */
    suspend fun rename(ctx: Context, name: String): String? {
        val store = Store.get(ctx)
        val d = store.dial ?: return "No dial paired"
        val r = call(ctx, JSONObject().put("op", "rename").put("name", name)) ?: return "Couldn't reach ${d.name}"
        if (!r.optBoolean("ok")) return if (r.optString("err") == "unknown op") "${d.name} needs newer firmware for this (6.7 or later)"
                                        else r.optString("err", "${d.name} said no")
        store.renameDial(d.id, r.optString("name").ifBlank { return "${d.name} didn't say" })
        return null
    }

    /** Catch up with the current dial: where the companion is, its state, settings and steps. False if it can't be reached. */
    suspend fun sync(ctx: Context, allowBle: Boolean = true): Boolean = mutex.withLock {
        val store = Store.get(ctx)
        val d = store.dial ?: run { _link.value = Link.NONE; return false }
        val l = open(ctx, d, allowBle) ?: run { _link.value = Link.NONE; return false }
        try {
            val now = { System.currentTimeMillis() / 1000 }
            var st = l.request(JSONObject().put("op", "state").put("now", now()))
            if (!st.optBoolean("ok")) {
                if (st.optString("err") == "not paired") { _message.value = "${d.name} no longer knows this phone: pair again"; return false }
            } else {
                val atDial = learn(ctx, store, d, st)
                if (atDial && store.pending().isNotEmpty()) {
                    // what was logged here while the companion was at the dial, oldest first
                    for (op in store.pending()) {
                        op.put("now", now())
                        val r = l.request(op)
                        if (!r.optBoolean("ok") && r.optString("err") == "not paired") { _message.value = "${d.name} no longer knows this phone: pair again"; return false }
                        store.dropFirst(1)
                    }
                    st = l.request(JSONObject().put("op", "state").put("now", now()))
                    if (st.optBoolean("ok")) learn(ctx, store, d, st)
                } else if (!atDial && !store.travel.here) store.dropFirst(store.pending().size)   // it isn't there to take them
                tellWhere?.let { (cid, epoch, at) -> l.request(JSONObject().put("op", "where").put("cid", cid).put("epoch", epoch).put("at", at)) }
                tellWhere = null
            }
            Steps.today(ctx).let { (day, count) -> if (day != 0) l.request(JSONObject().put("op", "steps").put("day", day).put("count", count)) }
            val se = l.request(JSONObject().put("op", "settings"))
            if (se.optBoolean("ok")) {
                store.dialSettings = se
                store.engine.config = store.engine.config.copy(hourStart = se.optInt("hourStart", 8), hourEnd = se.optInt("hourEnd", 20),
                    postureOn = se.optBoolean("postureOn", false), sitMin = se.optInt("sitMin", 45), standMin = se.optInt("standMin", 15))
                se.optJSONArray("remind")?.let { r ->
                    store.engine.config = store.engine.config.copy(remindMin = IntArray(4) { i -> r.optInt(i, store.engine.config.remindMin[i]) })
                }
            }
            store.saveCare()
            store.lastSync = System.currentTimeMillis()
            store.lastLink = l.kind
            if (l is BleLink) store.lastNearby = System.currentTimeMillis()
            _link.value = if (l is BleLink) Link.BLUETOOTH else Link.WIFI
            _message.value = ""
            return true
        } catch (e: LinkError) {
            lost(l, e)
            return false
        }
    }

    private var tellWhere: Triple<String, Long, String>? = null

    /** Take in a dial's state reply: where the companion is, and, when it's at that dial, its state. */
    private fun learn(ctx: Context, store: Store, d: DialInfo, st: JSONObject): Boolean {
        store.dialState = st
        st.optJSONObject("wifi")?.let { w ->
            val ip = w.optString("ip")
            if (w.optBoolean("up") && ip.isNotBlank() && ip != d.ip) store.dialNamed(d.name)?.let { store.updateDial(it.copy(ip = ip)) }
        }
        val dt = DialTravel.of(st)
        val atDial = if (dt == null) {
            // firmware from before companions travelled: the dial's state is the companion's, as it always was
            if (store.travel.here && store.travel.cid.isNotEmpty() && store.travel.epoch > 1) false
            else { Travel("", 0, false, d.name).let { if (store.travel != it) store.travel = it }; true }
        } else {
            val m = Travels.merge(store.travel, d.name, dt, phoneName(ctx))
            if (m.travel != store.travel) store.travel = m.travel
            m.tellWhere?.let { tellWhere = Triple(m.travel.cid, m.travel.epoch, it) }
            m.atDial
        }
        if (atDial) {
            store.engine.applyDialState(st)
            (dt?.pet ?: Pet.of(st.optString("pet")))?.let { store.pet = it }   // older firmware doesn't say: it's Wisp then
        }
        return atDial
    }

    private fun lost(l: DialLink, e: LinkError) {
        _message.value = e.message ?: "lost the dial"
        if (l === ble) { l.close(); ble = null }          // a one-off connection; the held link mends itself
        _link.value = Link.NONE
    }

    // ---------------------------------------------------------------- the companion's moves
    sealed class Move {
        data object Ok : Move()
        /** The dial has a companion of its own, which moving in would replace. */
        data class Occupied(val pet: Pet?, val level: Int) : Move()
        data class Failed(val why: String) : Move()
    }

    /** The companion comes onto the phone from dial [from] (where it is). */
    suspend fun bringHome(ctx: Context, from: DialInfo): Move = mutex.withLock {
        val store = Store.get(ctx)
        val l = open(ctx, from, true) ?: return Move.Failed("Couldn't reach ${from.name}. Bluetooth reaches it nearby; Wi-Fi when you're on the same network.")
        try {
            val r = l.request(JSONObject().put("op", "leave").put("to", phoneName(ctx)).put("now", System.currentTimeMillis() / 1000))
            val rec = CompanionRecord.fromJson(r.optJSONObject("companion"))
            if (!r.optBoolean("ok") || rec == null) {
                if (r.optString("err") == "away") DialTravel.of(r)?.let { dt -> store.travel = Travels.merge(store.travel, from.name, dt, phoneName(ctx)).travel }
                return Move.Failed(when (r.optString("err")) {
                    "away" -> "It isn't at ${from.name}: it's with ${r.optString("where").ifBlank { "someone else" }}"
                    "unknown op" -> "${from.name} needs newer firmware for this (6.0 or later)"
                    else -> r.optString("err", "${from.name} said no")
                })
            }
            store.takeIn(rec)
            // takeIn counts the move in; the dial already did, so keep its count
            store.travel = Travel(rec.cid, rec.epoch, true)
            if (store.dial?.id != from.id) store.dial = from
            return Move.Ok
        } catch (e: LinkError) { lost(l, e); return Move.Failed(e.message ?: "lost ${from.name}") }
    }

    /** The companion moves from the phone to dial [to]. [replace]: even if that dial has a companion of its own. */
    suspend fun sendTo(ctx: Context, to: DialInfo, replace: Boolean = false): Move = mutex.withLock {
        val store = Store.get(ctx)
        val t = store.travel
        if (!t.here) return Move.Failed("${store.pet.title} isn't on this phone")
        val l = open(ctx, to, true) ?: return Move.Failed("Couldn't reach ${to.name}. Bluetooth reaches it nearby; Wi-Fi when you're on the same network.")
        try {
            val rec = store.record(t.epoch)
            val r = l.request(JSONObject().put("op", "arrive").put("companion", rec.toJson()).put("replace", replace)
                .put("now", System.currentTimeMillis() / 1000))
            if (!r.optBoolean("ok")) {
                return when (r.optString("err")) {
                    "occupied" -> Move.Occupied(Pet.of(r.optString("pet")), r.optInt("level"))
                    "unknown op" -> Move.Failed("${to.name} needs newer firmware for this (6.0 or later)")
                    else -> Move.Failed(r.optString("err", "${to.name} said no"))
                }
            }
            val dt = DialTravel.of(r)
            store.travel = if (dt != null && dt.cid == t.cid && dt.here) Travel(t.cid, dt.epoch, false, to.name)
                           else if (dt != null && dt.cid == t.cid) Travel(t.cid, dt.epoch, false, dt.where)   // this copy was out of date
                           else Travel(t.cid, t.epoch + 1, false, to.name)
            store.dropFirst(store.pending().size)
            if (store.dial?.id != to.id) { store.dial = to; LinkService.update(ctx) }
            return Move.Ok
        } catch (e: LinkError) { lost(l, e); return Move.Failed(e.message ?: "lost ${to.name}") }
    }

    /** Where a companion is that left by a link and hasn't arrived anywhere this phone has heard of. */
    const val SHARED = "a link you shared"

    /** Hand the companion to another phone: it leaves this one, as a link to send. */
    fun shareOut(ctx: Context): String? {
        val store = Store.get(ctx)
        val t = store.travel
        if (!t.here) return null
        val rec = store.record(t.epoch + 1)
        store.travel = Travel(t.cid, t.epoch + 1, false, SHARED)
        return rec.link().also { store.lastShare = it }
    }

    sealed class Arrival {
        data object Ok : Arrival()
        /** Another companion is on this phone, which taking this one would replace. */
        data class Occupied(val pet: Pet, val level: Int) : Arrival()
        data object Stale : Arrival()
    }

    /** A companion shared from another phone moves in here. */
    fun takeShared(ctx: Context, rec: CompanionRecord, replace: Boolean = false): Arrival {
        val store = Store.get(ctx)
        val t = store.travel
        if (rec.cid == t.cid && t.epoch > rec.epoch) return Arrival.Stale
        if (t.here && rec.cid != t.cid && !replace) return Arrival.Occupied(store.pet, store.engine.level())
        store.takeIn(rec)
        return Arrival.Ok
    }

    /** One request to the current dial (a setting, a Wi-Fi join), outside the regular sync. */
    suspend fun call(ctx: Context, op: JSONObject): JSONObject? = mutex.withLock {
        val d = Store.get(ctx).dial ?: return null
        val l = open(ctx, d, true) ?: return null
        return try { l.request(op.put("now", System.currentTimeMillis() / 1000)) } catch (e: LinkError) {
            _message.value = e.message ?: ""
            if (l === ble) { l.close(); ble = null }
            null
        }
    }

    /** Pair with a dial showing [code] (Settings > Phone app > Pair a phone). Over Bluetooth if [address] is set. */
    suspend fun pair(ctx: Context, name: String, code: String, address: String = "", ip: String = ""): String? = mutex.withLock {
        val phone = phoneName(ctx)
        val op = JSONObject().put("op", "pair").put("code", code.filter { it.isDigit() }).put("phone", phone)
        val link: DialLink = when {
            address.isNotBlank() -> runCatching { BleLink.connect(ctx, address) }.getOrElse { return it.message ?: "couldn't connect" }
            ip.isNotBlank() -> WifiLink(ip, null)
            else -> return "pick a dial first"
        }
        val r = try { link.request(op) } catch (e: LinkError) { link.close(); return e.message ?: "no answer" }
        if (!r.optBoolean("ok")) { link.close(); return r.optString("err", "refused") }
        val key = r.optString("key")
        val dialName = r.optString("name", name)
        var dialIp = ip
        if (link is BleLink) {
            ble?.close()
            ble = link
            runCatching { link.request(JSONObject().put("op", "hello").put("key", key)) }.getOrNull()?.optString("ip")?.let { if (it.isNotBlank()) dialIp = it }
        }
        if (link is BleLink) { link.close(); ble = null }      // the held link takes over from here
        val store = Store.get(ctx)
        store.dial = DialInfo(dialName, key, dialIp, address, r.optString("id").ifBlank { dialName })
        // A phone that has been looking after its own companion keeps it; otherwise it takes up with the dial's.
        val t = store.travel
        val ownsOne = t.here && t.cid.isNotEmpty() && (t.epoch > 1 || store.engine.st.xp > 0)
        DialTravel.of(r)?.let { dt ->
            if (!ownsOne && dt.cid != t.cid) store.travel = Travel(dt.cid, dt.epoch, false, if (dt.here) dialName else dt.where)
        }
        LinkService.update(ctx)
        null
    }

    /** Forget a paired dial (the current one by default). */
    fun forget(ctx: Context, id: String? = null) {
        val store = Store.get(ctx)
        val n = id ?: store.dial?.id ?: return
        if (bleFor == n) { ble?.close(); ble = null }
        store.forgetDial(n)
        LinkService.update(ctx)
        _link.value = Link.NONE
    }

    /** Make a paired dial the current one. */
    fun use(ctx: Context, d: DialInfo) {
        val store = Store.get(ctx)
        if (store.dial?.id == d.id) return
        store.dial = d
        store.dialSettings = org.json.JSONObject()
        _link.value = Link.NONE
        LinkService.update(ctx)
    }
}
