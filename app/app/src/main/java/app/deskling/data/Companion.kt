package app.deskling.data

import app.deskling.care.CareEngine
import app.deskling.care.CareState
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Where the companion is, as this phone knows it.
 *
 * The companion lives on one device at a time: a dial, this phone, another phone. It has an id (made by
 * whichever device had it first) and an epoch that goes up by one at every move, so whenever two devices
 * disagree about where it is, the higher epoch is the newer news. The dial keeps the same three things
 * (firmware care.cpp, "travel").
 */
data class Travel(val cid: String, val epoch: Long, val here: Boolean, val where: String = "") {
    fun toJson() = JSONObject().put("cid", cid).put("epoch", epoch).put("here", here).put("where", where)

    companion object {
        fun fromJson(o: JSONObject?) = o?.let { Travel(it.optString("cid"), it.optLong("epoch"), it.optBoolean("here"), it.optString("where")) }
        fun newCid(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        /** A phone that has never seen a companion makes its own. */
        fun fresh() = Travel(newCid(), 1, true)
        fun isCid(s: String) = s.length == 16 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}

/** A dial's side of it, from its hello, pair or state reply. */
data class DialTravel(val cid: String, val epoch: Long, val here: Boolean, val where: String, val pet: Pet?) {
    companion object {
        /** Null for firmware from before companions travelled (no "cid"). */
        fun of(o: JSONObject): DialTravel? {
            val cid = o.optString("cid")
            if (!Travel.isCid(cid)) return null
            return DialTravel(cid, o.optLong("epoch"), o.optBoolean("here", true), o.optString("where"), Pet.of(o.optString("pet")))
        }
    }
}

object Travels {
    /**
     * What the phone should believe after a dial named [dial] reported [d].
     * atDial: the companion is at that dial, so its state is the companion's (take it, and send it breaks).
     * tellWhere: the dial's news is older than the phone's: tell it (op "where") where the companion is now.
     */
    data class Merge(val travel: Travel, val atDial: Boolean, val tellWhere: String? = null)

    fun merge(mine: Travel, dial: String, d: DialTravel, phoneName: String): Merge {
        if (mine.cid.isEmpty()) {                                   // from before the companion travelled: it's the dial's
            return Merge(Travel(d.cid, d.epoch, false, if (d.here) dial else d.where), d.here)
        }
        if (d.cid != mine.cid) return Merge(mine, false)            // the dial has a companion of its own
        if (d.here) {
            // here as well as there: only a phone restored from an old backup gets into this; the newer one wins
            if (mine.here && mine.epoch > d.epoch) return Merge(mine, false)
            return Merge(Travel(mine.cid, d.epoch, false, dial), true)
        }
        if (mine.epoch > d.epoch) return Merge(mine, false, if (mine.here) phoneName else mine.where)
        if (!mine.here && d.epoch > mine.epoch) return Merge(mine.copy(epoch = d.epoch, where = d.where), false)
        return Merge(mine, false)
    }
}

/**
 * The record a move carries: who the companion is, its kind, the move count and its care state. The same
 * shape as the dial's (firmware api.cpp put_companion), so it passes between dials and phones unchanged.
 */
data class CompanionRecord(val cid: String, val pet: Pet, val epoch: Long, val care: JSONObject) {
    fun toJson(): JSONObject = JSONObject().put("cid", cid).put("pet", pet.id).put("epoch", epoch).put("care", care)

    /** The care state it carries, ready to run on this phone from [now]. */
    fun careState(engine: CareEngine, now: Long): CareState {
        val s = CareState()
        val el = care.optJSONArray("elapsed"); val td = care.optJSONArray("today")
        for (i in 0 until 4) { s.elapsed[i] = el?.optLong(i) ?: 0; s.today[i] = minOf(999, td?.optInt(i) ?: 0) }
        s.happy = care.optDouble("happy", 70.0).coerceIn(0.0, 100.0)
        s.xp = care.optLong("xp")
        s.day = care.optInt("day")
        s.streak = care.optInt("streak")
        s.bedtime = care.optBoolean("bedtime")
        s.standing = care.optBoolean("standing")
        s.postureElapsed = care.optLong("postSec")
        s.standsToday = care.optInt("stands")
        val pause = care.optLong("pause").coerceIn(0, 24 * 3600)
        s.pauseUntil = if (pause > 0) now + pause * 1000 else 0
        s.lastUpdate = now
        s.wokeEarly = care.optBoolean("woke") && !engine.rawInHours(now)
        s.wasInHours = s.wokeEarly || engine.rawInHours(now)       // arriving isn't the start of a new active day
        return s
    }

    /** As a link another phone can open: the website shows who's coming and hands it to the app. */
    fun link(): String = "$SITE#companion=" + encode(toJson())

    companion object {
        const val SITE = "https://safwyls.github.io/deskling/"

        fun of(cid: String, pet: Pet, epoch: Long, st: CareState, now: Long) = CompanionRecord(cid, pet, epoch, JSONObject()
            .put("elapsed", JSONArray(st.elapsed.toList())).put("today", JSONArray(st.today.toList()))
            .put("happy", st.happy).put("xp", st.xp).put("day", st.day).put("streak", st.streak).put("bedtime", st.bedtime)
            .put("standing", st.standing).put("postSec", st.postureElapsed).put("stands", st.standsToday)
            .put("pause", maxOf(0L, (st.pauseUntil - now) / 1000)).put("woke", st.wokeEarly))

        /** Null unless it's a whole, sensible record. */
        fun fromJson(o: JSONObject?): CompanionRecord? {
            o ?: return null
            val cid = o.optString("cid"); val pet = Pet.of(o.optString("pet")); val epoch = o.optLong("epoch")
            val care = o.optJSONObject("care") ?: return null
            if (!Travel.isCid(cid) || pet == null || epoch <= 0) return null
            if (care.optJSONArray("elapsed")?.length() != 4 || care.optJSONArray("today")?.length() != 4) return null
            return CompanionRecord(cid.lowercase(), pet, epoch, care)
        }

        fun encode(o: JSONObject): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray())

        /** From a shared link (the website's, or deskling://companion?r=...), or just the code in it. */
        fun fromLink(text: String): CompanionRecord? {
            val t = text.trim()
            val code = Regex("""(?:#companion=|[?&]r=)([A-Za-z0-9_-]+)""").find(t)?.groupValues?.get(1)
                ?: t.takeIf { it.matches(Regex("[A-Za-z0-9_-]{40,}")) } ?: return null
            val json = runCatching { String(java.util.Base64.getUrlDecoder().decode(code)) }.getOrNull() ?: return null
            return fromJson(runCatching { JSONObject(json) }.getOrNull())
        }
    }
}
