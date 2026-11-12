package app.deskling.data

import android.content.Context
import android.content.SharedPreferences
import app.deskling.care.CareConfig
import app.deskling.care.CareEngine
import app.deskling.care.CareState
import app.deskling.care.Need
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A dial this phone is paired with. */
data class DialInfo(val name: String, val key: String, val ip: String = "", val bleAddress: String = "") {
    fun toJson() = JSONObject().put("name", name).put("key", key).put("ip", ip).put("ble", bleAddress)
    companion object {
        fun fromJson(o: JSONObject?) = o?.let { DialInfo(it.optString("name"), it.optString("key"), it.optString("ip"), it.optString("ble")) }
    }
}

/** When the phone posts break reminders. (WITH_ME took the place of "when I'm away from the dial".) */
enum class NotifyMode(val title: String) {
    WITH_ME("When my companion is with me"), ALWAYS("Also while it's at a dial and I'm away"), NEVER("Never")
}

/**
 * Everything the app keeps: the companion's care state (its own while the companion is on the phone, a mirror of
 * the dial's while it's at one), where the companion is, the paired dials, a mirror of the current dial's
 * settings, breaks waiting to be sent to the dial, and steps. One instance for the whole process.
 */
class Store private constructor(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("deskling", Context.MODE_PRIVATE)

    val engine = CareEngine(CareConfig.fromJson(json("config")), CareState.fromJson(json("care")))

    /** Bumps whenever anything the screens show changes. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version
    fun changed() { _version.value = _version.value + 1 }

    @Synchronized fun saveCare() {
        p.edit().putString("care", engine.st.toJson().toString()).putString("config", engine.config.toJson().toString()).apply()
        changed()
    }

    // ---------- the dials ----------
    /** Every paired dial. One is current: the one synced with, held open, and whose settings show. */
    val dials: List<DialInfo>
        @Synchronized get() {
            val a = p.getString("dials", null)?.let { runCatching { JSONArray(it) }.getOrNull() }
            if (a == null) return listOfNotNull(DialInfo.fromJson(json("dial")))       // paired before there could be several
            return List(a.length()) { DialInfo.fromJson(a.getJSONObject(it))!! }
        }

    private fun putDials(l: List<DialInfo>, current: String?) {
        p.edit().putString("dials", JSONArray(l.map { it.toJson() }).toString()).remove("dial")
            .putString("currentDial", current).apply()
        changed()
    }

    /** The current dial. Setting one adds it (or updates it, by name) and makes it current; null forgets the current one. */
    var dial: DialInfo?
        @Synchronized get() { val l = dials; val c = p.getString("currentDial", null); return l.firstOrNull { it.name == c } ?: l.firstOrNull() }
        @Synchronized set(v) {
            val l = dials.toMutableList()
            if (v == null) { dial?.let { d -> l.removeAll { it.name == d.name } }; putDials(l, l.firstOrNull()?.name); return }
            val i = l.indexOfFirst { it.name == v.name }
            if (i >= 0) l[i] = v else l.add(v)
            putDials(l, v.name)
        }

    /** Update a paired dial's details (its address, say) without making it current. */
    @Synchronized fun updateDial(v: DialInfo) {
        val l = dials.toMutableList()
        val i = l.indexOfFirst { it.name == v.name }
        if (i < 0) return
        l[i] = v
        putDials(l, dial?.name)
    }

    @Synchronized fun forgetDial(name: String) {
        val cur = dial?.name
        val l = dials.filter { it.name != name }
        putDials(l, if (cur == name) l.firstOrNull()?.name else cur)
    }

    fun dialNamed(name: String) = dials.firstOrNull { it.name == name }

    // ---------- the companion ----------
    /** Where the companion is (see Travel). A phone paired before companions travelled learns it at the next sync. */
    var travel: Travel
        @Synchronized get() = Travel.fromJson(json("travel"))
            ?: (if (dials.isNotEmpty()) Travel("", 0, false, dial!!.name) else Travel.fresh().also { travel = it })
        @Synchronized set(v) { p.edit().putString("travel", v.toJson().toString()).apply(); changed() }

    /** The dial's settings as it last reported them (op "settings"). */
    var dialSettings: JSONObject
        get() = json("dialSettings") ?: JSONObject()
        set(v) { p.edit().putString("dialSettings", v.toString()).apply(); changed() }

    /** The dial's last state report (op "state"): wifi, mood. */
    var dialState: JSONObject
        get() = json("dialState") ?: JSONObject()
        set(v) { p.edit().putString("dialState", v.toString()).apply() }

    var lastSync: Long
        get() = p.getLong("lastSync", 0)
        set(v) { p.edit().putLong("lastSync", v).apply(); changed() }
    /** Last time the dial was reachable over Bluetooth: "near the desk". */
    var lastNearby: Long
        get() = p.getLong("lastNearby", 0)
        set(v) { p.edit().putLong("lastNearby", v).apply() }
    var lastLink: String
        get() = p.getString("lastLink", "") ?: ""
        set(v) { p.edit().putString("lastLink", v).apply() }

    /** The link the companion last left this phone by (to share again, or to take it back). */
    var lastShare: String
        get() = p.getString("lastShare", "") ?: ""
        set(v) { p.edit().putString("lastShare", v).apply() }

    // ---------- events waiting for the dial ----------
    @Synchronized fun queue(op: JSONObject) {
        val a = JSONArray(p.getString("queue", "[]"))
        a.put(op)
        // never let it grow without bound while the dial is away
        val keep = JSONArray()
        for (i in maxOf(0, a.length() - 200) until a.length()) keep.put(a.get(i))
        p.edit().putString("queue", keep.toString()).apply()
    }
    @Synchronized fun pending(): List<JSONObject> = JSONArray(p.getString("queue", "[]")).let { a -> List(a.length()) { a.getJSONObject(it) } }
    @Synchronized fun dropFirst(n: Int) {
        val a = JSONArray(p.getString("queue", "[]"))
        val keep = JSONArray()
        for (i in n until a.length()) keep.put(a.get(i))
        p.edit().putString("queue", keep.toString()).apply()
    }

    // ---------- steps ----------
    var stepsDay: Int
        get() = p.getInt("stepsDay", 0)
        set(v) { p.edit().putInt("stepsDay", v).apply() }
    var stepsToday: Long
        get() = p.getLong("stepsToday", 0)
        set(v) { p.edit().putLong("stepsToday", v).apply(); changed() }
    var stepsLastRaw: Float
        get() = p.getFloat("stepsLastRaw", -1f)
        set(v) { p.edit().putFloat("stepsLastRaw", v).apply() }
    /** day (yyyymmdd) -> steps, last 30 days. */
    var stepsHistory: JSONObject
        get() = json("stepsHistory") ?: JSONObject()
        set(v) { p.edit().putString("stepsHistory", v.toString()).apply() }
    /** Recent samples "time:total" for spotting a walk. */
    var stepsRecent: JSONArray
        get() = JSONArray(p.getString("stepsRecent", "[]"))
        set(v) { p.edit().putString("stepsRecent", v.toString()).apply() }
    var lastWalkCredit: Long
        get() = p.getLong("lastWalkCredit", 0)
        set(v) { p.edit().putLong("lastWalkCredit", v).apply() }

    // ---------- app preferences ----------
    /** Which companion it is (its kind): it travels with it, and can be changed wherever it is. */
    var pet: Pet
        get() = Pet.of(p.getString("pet", null)) ?: Pet.WISP
        set(v) { if (v != pet) { p.edit().putString("pet", v.id).apply(); changed() } }
    /** Keep a Bluetooth link open to the dial while near it, so both sync at once (a foreground service). */
    var holdLink: Boolean
        get() = p.getBoolean("holdLink", true)
        set(v) { p.edit().putBoolean("holdLink", v).apply(); changed() }
    var notifyMode: NotifyMode
        get() = NotifyMode.entries.getOrElse(p.getInt("notifyMode", 0)) { NotifyMode.WITH_ME }
        set(v) { p.edit().putInt("notifyMode", v.ordinal).apply(); changed() }
    /** Steps within 10 minutes that count as having moved. */
    var walkSteps: Int
        get() = p.getInt("walkSteps", 250)
        set(v) { p.edit().putInt("walkSteps", v).apply(); changed() }
    var stepGoal: Int
        get() = p.getInt("stepGoal", 8000)
        set(v) { p.edit().putInt("stepGoal", v).apply(); changed() }
    var lastNotified: JSONObject
        get() = json("lastNotified") ?: JSONObject()
        set(v) { p.edit().putString("lastNotified", v.toString()).apply() }

    private fun json(k: String): JSONObject? = p.getString(k, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    // ---------- helpers ----------
    /** Log a break here, and queue it for the dial when the companion is at one (a reminder's Done, say). */
    fun logDone(n: Need, now: Long = System.currentTimeMillis(), ageS: Long = 0) {
        if (ageS > 0) engine.doneAt(n, ageS, now) else engine.done(n, now)
        if (!travel.here) queue(JSONObject().put("op", "done").put("need", n.key).put("at", (now / 1000) - ageS))
        saveCare()
    }

    /** Queue something done here for the dial, if the companion is at one; on the phone it's done already. */
    fun queueForDial(op: JSONObject) { if (!travel.here) queue(op) }

    /** The companion as it is here now, to hand over (with the epoch it moves with). */
    fun record(epoch: Long, now: Long = System.currentTimeMillis()): CompanionRecord {
        engine.advanceTo(now)
        return CompanionRecord.of(travel.cid, pet, epoch, engine.st, now)
    }

    /** The companion moves in: it runs here from now on. */
    fun takeIn(r: CompanionRecord, now: Long = System.currentTimeMillis()) {
        engine.st = r.careState(engine, now)
        pet = r.pet
        p.edit().putString("queue", "[]").apply()          // anything for a dial is moot: it's here now
        travel = Travel(r.cid, r.epoch + 1, true)
        saveCare()
    }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }
    }
}
