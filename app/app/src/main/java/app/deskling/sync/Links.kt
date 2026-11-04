package app.deskling.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** A way to send the dial one JSON request and get its answer (see the firmware's src/api.h). */
interface DialLink {
    val kind: String
    suspend fun request(op: JSONObject): JSONObject
    fun close() {}
}

class LinkError(msg: String) : Exception(msg)

// ---------------------------------------------------------------- Wi-Fi
class WifiLink(private val host: String, private val key: String?) : DialLink {
    override val kind = "Wi-Fi"

    override suspend fun request(op: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val c = URL("http://$host/api").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 2000
            c.readTimeout = 8000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (key != null) c.setRequestProperty("X-Deskling-Key", key)
            c.outputStream.use { it.write(op.toString().toByteArray()) }
            if (c.responseCode != 200) throw LinkError("HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().readText())
        } catch (e: LinkError) {
            throw e
        } catch (e: Exception) {
            throw LinkError(e.message ?: "no answer")
        } finally {
            c.disconnect()
        }
    }
}

// ---------------------------------------------------------------- Bluetooth
object DialUuids {
    val SERVICE: UUID = UUID.fromString("6d7a0001-5f2b-4b6c-9e2d-3a1f0c0d5e11")
    val RX: UUID = UUID.fromString("6d7a0002-5f2b-4b6c-9e2d-3a1f0c0d5e11")     // the phone writes here
    val TX: UUID = UUID.fromString("6d7a0003-5f2b-4b6c-9e2d-3a1f0c0d5e11")     // the dial notifies here
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

data class FoundDial(val name: String, val address: String, val rssi: Int)

/** Dials advertising nearby, for pairing and for finding the paired one again. */
@SuppressLint("MissingPermission")
fun scanDials(ctx: Context): Flow<FoundDial> = callbackFlow {
    val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    val scanner = adapter?.bluetoothLeScanner
    if (scanner == null || !adapter.isEnabled) { close(LinkError("Bluetooth is off")); return@callbackFlow }
    val cb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name ?: "Deskling"
            trySend(FoundDial(name, r.device.address, r.rssi))
        }
        override fun onScanFailed(errorCode: Int) { close(LinkError("Bluetooth scan failed ($errorCode)")) }
    }
    val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(DialUuids.SERVICE)).build()
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    scanner.startScan(listOf(filter), settings, cb)
    awaitClose { runCatching { scanner.stopScan(cb) } }
}

@SuppressLint("MissingPermission")
class BleLink private constructor(private val gatt: BluetoothGatt, private val cb: Callback) : DialLink {
    override val kind = "Bluetooth"
    private val lock = Mutex()
    val connected get() = cb.connected
    /** This connection has said hello with the phone's key (the dial asks again on every new connection). */
    @Volatile var authed = false

    override suspend fun request(op: JSONObject): JSONObject = lock.withLock {
        if (!cb.connected) throw LinkError("Bluetooth disconnected")
        val reply = CompletableDeferred<String>()
        cb.reply = reply
        val bytes = (op.toString() + "\n").toByteArray()
        val chunk = (cb.mtu - 3).coerceIn(20, 240)
        val rx = cb.rx ?: throw LinkError("not a Deskling")
        var p = 0
        while (p < bytes.size) {
            val part = bytes.copyOfRange(p, minOf(bytes.size, p + chunk))
            val w = CompletableDeferred<Boolean>()
            cb.write = w
            val started = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(rx, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; rx.value = part; gatt.writeCharacteristic(rx) }
            }
            if (!started || withTimeoutOrNull(3000) { w.await() } != true) throw LinkError("Bluetooth write failed")
            p += part.size
        }
        val text = withTimeoutOrNull(8000) { reply.await() } ?: throw LinkError("the dial didn't answer")
        try { JSONObject(text) } catch (e: Exception) { throw LinkError("garbled answer") }
    }

    override fun close() { cb.keep = false; runCatching { gatt.disconnect(); gatt.close() } }

    class Callback : BluetoothGattCallback() {
        @Volatile var connected = false
        @Volatile var mtu = 23
        var rx: BluetoothGattCharacteristic? = null
        @Volatile var ready = CompletableDeferred<Boolean>()
        var write: CompletableDeferred<Boolean>? = null
        var reply: CompletableDeferred<String>? = null
        private val buf = ByteArrayOutputStream()
        /** For a held link: each time a (re)connection is set up and can take requests. */
        var onReady: (() -> Unit)? = null
        var onDown: (() -> Unit)? = null
        /** Held links: after any drop, ask Android to reconnect whenever the dial is in range again. */
        @Volatile var keep = false
        /** Messages the dial sends unasked ({"event":"changed"}): they have "event" and never "ok". */
        var onEvent: ((JSONObject) -> Unit)? = null

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (ready.isCompleted) ready = CompletableDeferred()      // a held link reconnecting
                connected = true
                g.requestMtu(247)
            } else {
                val was = connected
                connected = false
                ready.complete(false)
                write?.complete(false)
                reply?.completeExceptionally(LinkError("Bluetooth disconnected"))
                if (was) onDown?.invoke()
                if (keep) runCatching { g.connect() }     // background reconnect: waits for the dial to come back in range
            }
        }
        private fun setUp(ok: Boolean) {
            ready.complete(ok)
            if (ok) onReady?.invoke()
        }
        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = m
            g.discoverServices()
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(DialUuids.SERVICE)
            val tx = svc?.getCharacteristic(DialUuids.TX)
            rx = svc?.getCharacteristic(DialUuids.RX)
            if (tx == null || rx == null) { setUp(false); return }
            g.setCharacteristicNotification(tx, true)
            val d = tx.getDescriptor(DialUuids.CCCD)
            if (d == null) { setUp(true); return }
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            setUp(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            write?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = received(value)
        @Deprecated("before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) received(c.value ?: return)
        }
        @Synchronized private fun received(v: ByteArray) {
            for (b in v) {
                if (b == '\n'.code.toByte()) {
                    val line = buf.toString(Charsets.UTF_8.name())
                    buf.reset()
                    val ev = eventOf(line)
                    if (ev != null) onEvent?.invoke(ev) else reply?.complete(line)
                } else buf.write(b.toInt())
            }
        }
    }

    companion object {
        /** A line the dial sent unasked ({"event":...}, never "ok"), or null for a reply to a request. */
        fun eventOf(line: String): JSONObject? =
            if (!line.contains("\"event\"")) null else runCatching { JSONObject(line) }.getOrNull()?.takeIf { it.has("event") && !it.has("ok") }

        suspend fun connect(ctx: Context, address: String): BleLink {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: throw LinkError("no Bluetooth")
            if (!adapter.isEnabled) throw LinkError("Bluetooth is off")
            val dev: BluetoothDevice = adapter.getRemoteDevice(address)
            val cb = Callback()
            val gatt = withContext(Dispatchers.Main) { dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) }
                ?: throw LinkError("couldn't connect")
            val ok = withTimeoutOrNull(12_000) { cb.ready.await() } == true
            if (!ok) { runCatching { gatt.close() }; throw LinkError("couldn't reach the dial over Bluetooth") }
            return BleLink(gatt, cb)
        }

        /**
         * A connection kept for as long as it's open: a direct connect first (autoConnect's first attempt can take
         * minutes), then after any drop Android reconnects by itself whenever the dial is in range again.
         * Returns at once; [Callback.onReady] says when it can take requests.
         */
        fun hold(ctx: Context, address: String, cb: Callback): BleLink {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: throw LinkError("no Bluetooth")
            cb.keep = true
            val gatt = adapter.getRemoteDevice(address).connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                ?: throw LinkError("couldn't connect")
            return BleLink(gatt, cb)
        }
    }
}

// ---------------------------------------------------------------- finding the dial on your network
/** Dials announcing themselves on the local network (mDNS "_deskling._tcp"), as name to IP. */
fun discoverOnWifi(ctx: Context): Flow<Pair<String, String>> = callbackFlow {
    val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) { close() }
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
        override fun onServiceLost(s: NsdServiceInfo) {}
        override fun onServiceFound(s: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                override fun onServiceResolved(r: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val ip = r.host?.hostAddress ?: return
                    trySend(r.serviceName to ip)
                }
            })
        }
    }
    nsd.discoverServices("_deskling._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
}

suspend fun <T> withTimeoutResult(ms: Long, block: suspend () -> T): T = withTimeout(ms) { block() }
