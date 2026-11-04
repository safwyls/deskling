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

/** The dial this phone is paired with. */
data class DialInfo(val name: String, val key: String, val ip: String = "", val bleAddress: String = "") {
    fun toJson() = JSONObject().put("name", name).put("key", key).put("ip", ip).put("ble", bleAddress)
    companion object {
        fun fromJson(o: JSONObject?) = o?.let { DialInfo(it.optString("name"), it.optString("key"), it.optString("ip"), it.optString("ble")) }
    }
}

enum class NotifyMode(val title: String) { AWAY("When I'm away from the dial"), ALWAYS("Always"), NEVER("Never") }

/**
 * Everything the app keeps: the care state (its own copy of Wisp), the paired dial, a mirror of the dial's
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

    // ---------- the dial ----------
    var dial: DialInfo?
        get() = DialInfo.fromJson(json("dial"))
        set(v) { p.edit().putString("dial", v?.toJson()?.toString()).apply(); changed() }

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
    /** The pet: the paired dial's (set on every sync), or the one picked in Settings without a dial. */
    var pet: Pet
        get() = Pet.of(p.getString("pet", null)) ?: Pet.WISP
        set(v) { if (v != pet) { p.edit().putString("pet", v.id).apply(); changed() } }
    /** Keep a Bluetooth link open to the dial while near it, so both sync at once (a foreground service). */
    var holdLink: Boolean
        get() = p.getBoolean("holdLink", true)
        set(v) { p.edit().putBoolean("holdLink", v).apply(); changed() }
    var notifyMode: NotifyMode
        get() = NotifyMode.entries.getOrElse(p.getInt("notifyMode", 0)) { NotifyMode.AWAY }
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
    /** Log a break here and queue it for the dial. */
    fun logDone(n: Need, now: Long = System.currentTimeMillis(), ageS: Long = 0) {
        if (ageS > 0) engine.doneAt(n, ageS, now) else engine.done(n, now)
        queue(JSONObject().put("op", "done").put("need", n.key).put("at", (now / 1000) - ageS))
        saveCare()
    }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }
    }
}
