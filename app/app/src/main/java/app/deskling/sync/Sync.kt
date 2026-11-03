package app.deskling.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import app.deskling.data.DialInfo
import app.deskling.data.Store
import app.deskling.steps.Steps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Keeps the phone's Wisp and the dial's in step. The dial holds the truth while it can be reached: the phone
 * sends the breaks it logged (with when they happened) and its step count, then takes the dial's state.
 * Away from the dial the phone runs its own copy of the care model and queues what you log.
 */
object Sync {
    enum class Link { NONE, WIFI, BLUETOOTH }

    private val _link = MutableStateFlow(Link.NONE)
    val link: StateFlow<Link> = _link
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    private val mutex = Mutex()
    private var ble: BleLink? = null

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

    /** A working link to the paired dial: Wi-Fi first (when the phone is on Wi-Fi), then Bluetooth. */
    private suspend fun open(ctx: Context, d: DialInfo, allowBle: Boolean): DialLink? {
        if (d.ip.isNotBlank() && onWifi(ctx)) {
            val w = WifiLink(d.ip, d.key)
            val ok = runCatching { w.request(JSONObject().put("op", "hello").put("key", d.key)).optBoolean("ok") }.getOrDefault(false)
            if (ok) return w
        }
        if (!allowBle || !hasBlePermission(ctx)) return null
        ble?.let { if (it.connected) return it }
        ble = null
        var address = d.bleAddress
        if (address.isBlank()) {
            address = withTimeoutOrNull(6000) { scanDials(ctx).firstOrNull { it.name == d.name } }?.address ?: return null
        }
        val b = runCatching { BleLink.connect(ctx, address) }.getOrNull() ?: return null
        val hello = runCatching { b.request(JSONObject().put("op", "hello").put("key", d.key).put("now", System.currentTimeMillis() / 1000)) }.getOrNull()
        if (hello?.optBoolean("ok") != true) { b.close(); _message.value = hello?.optString("err") ?: "Bluetooth hello failed"; return null }
        if (address != d.bleAddress) Store.get(ctx).dial = d.copy(bleAddress = address)
        hello.optString("ip").takeIf { it.isNotBlank() }?.let { ip -> Store.get(ctx).dial?.let { if (it.ip != ip) Store.get(ctx).dial = it.copy(ip = ip) } }
        ble = b
        return b
    }

    /** Push what the phone logged, then take the dial's state and settings. False if the dial can't be reached. */
    suspend fun sync(ctx: Context, allowBle: Boolean = true): Boolean = mutex.withLock {
        val store = Store.get(ctx)
        val d = store.dial ?: run { _link.value = Link.NONE; return false }
        val l = open(ctx, d, allowBle) ?: run { _link.value = Link.NONE; return false }
        try {
            val now = { System.currentTimeMillis() / 1000 }
            // breaks and pats logged here, oldest first
            for (op in store.pending()) {
                op.put("now", now())
                val r = l.request(op)
                if (!r.optBoolean("ok") && r.optString("err") == "not paired") { _message.value = "The dial no longer knows this phone: pair again"; return false }
                store.dropFirst(1)
            }
            Steps.today(ctx).let { (day, count) -> if (day != 0) l.request(JSONObject().put("op", "steps").put("day", day).put("count", count)) }
            val st = l.request(JSONObject().put("op", "state").put("now", now()))
            if (st.optBoolean("ok")) {
                store.engine.applyDialState(st)
                store.dialState = st
                st.optJSONObject("wifi")?.let { w ->
                    val ip = w.optString("ip")
                    if (w.optBoolean("up") && ip.isNotBlank() && ip != d.ip) store.dial = (store.dial ?: d).copy(ip = ip)
                }
            }
            val se = l.request(JSONObject().put("op", "settings"))
            if (se.optBoolean("ok")) {
                store.dialSettings = se
                store.engine.config = store.engine.config.copy(hourStart = se.optInt("hourStart", 8), hourEnd = se.optInt("hourEnd", 20))
            }
            store.saveCare()
            store.lastSync = System.currentTimeMillis()
            store.lastLink = l.kind
            if (l is BleLink) store.lastNearby = System.currentTimeMillis()
            _link.value = if (l is BleLink) Link.BLUETOOTH else Link.WIFI
            _message.value = ""
            return true
        } catch (e: LinkError) {
            _message.value = e.message ?: "lost the dial"
            if (l is BleLink) { l.close(); ble = null }
            _link.value = Link.NONE
            return false
        }
    }

    /** One request to the paired dial (a setting, a Wi-Fi join), outside the regular sync. */
    suspend fun call(ctx: Context, op: JSONObject): JSONObject? = mutex.withLock {
        val d = Store.get(ctx).dial ?: return null
        val l = open(ctx, d, true) ?: return null
        return try { l.request(op.put("now", System.currentTimeMillis() / 1000)) } catch (e: LinkError) {
            _message.value = e.message ?: ""
            if (l is BleLink) { l.close(); ble = null }
            null
        }
    }

    /** Pair with a dial showing [code] (Settings > Phone app > Pair a phone). Over Bluetooth if [address] is set. */
    suspend fun pair(ctx: Context, name: String, code: String, address: String = "", ip: String = ""): String? = mutex.withLock {
        val phone = Build.MODEL ?: "phone"
        val op = JSONObject().put("op", "pair").put("code", code.filter { it.isDigit() }).put("phone", phone)
        val link: DialLink = when {
            address.isNotBlank() -> runCatching { BleLink.connect(ctx, address) }.getOrElse { return it.message ?: "couldn't connect" }
            ip.isNotBlank() -> WifiLink(ip, null)
            else -> return "pick a dial first"
        }
        val r = try { link.request(op) } catch (e: LinkError) { link.close(); return e.message ?: "no answer" }
        if (!r.optBoolean("ok")) { link.close(); return r.optString("err", "refused") }
        val key = r.optString("key")
        var dialIp = ip
        if (link is BleLink) {
            ble?.close()
            ble = link
            runCatching { link.request(JSONObject().put("op", "hello").put("key", key)) }.getOrNull()?.optString("ip")?.let { if (it.isNotBlank()) dialIp = it }
        }
        Store.get(ctx).dial = DialInfo(r.optString("name", name), key, dialIp, address)
        null
    }

    fun forget(ctx: Context) {
        ble?.close(); ble = null
        Store.get(ctx).dial = null
        _link.value = Link.NONE
    }
}
