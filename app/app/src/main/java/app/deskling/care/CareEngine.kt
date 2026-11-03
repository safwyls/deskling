package app.deskling.care

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.min

// The care model, ported from the dial's care.cpp so the phone's Wisp behaves the same while you're away
// from the desk. When the dial is reachable its state replaces this one (see applyDialState).

enum class Need(val key: String, val title: String, val color: Long) {
    WATER("water", "Water", 0xFF3FA9FF),
    EYES("eyes", "Eye break", 0xFFFFC457),
    STRETCH("stretch", "Stretch", 0xFF5CF08A),
    MOVE("move", "Move", 0xFFC77DFF);

    companion object {
        fun of(key: String?): Need? = entries.firstOrNull { it.key == key }
    }
}

enum class Mood(val title: String) {
    CONTENT("Content"), HAPPY("Happy"), THIRSTY("Thirsty"), TIRED("Tired"), RESTLESS("Restless"),
    SAD("Sad"), MAD("Mad"), ASLEEP("Asleep");

    companion object {
        fun of(title: String?): Mood = entries.firstOrNull { it.title == title } ?: CONTENT
    }
}

data class CareConfig(
    val remindMin: IntArray = intArrayOf(45, 20, 50, 60),   // water, eyes, stretch, move; 0 = off
    val hourStart: Int = 8,
    val hourEnd: Int = 20,
) {
    fun toJson() = JSONObject().put("remind", JSONArray(remindMin.toList())).put("hourStart", hourStart).put("hourEnd", hourEnd)

    companion object {
        fun fromJson(o: JSONObject?): CareConfig {
            if (o == null) return CareConfig()
            val r = o.optJSONArray("remind")
            val rem = IntArray(4) { i -> r?.optInt(i, CareConfig().remindMin[i]) ?: CareConfig().remindMin[i] }
            return CareConfig(rem, o.optInt("hourStart", 8), o.optInt("hourEnd", 20))
        }
    }

    override fun equals(other: Any?) = other is CareConfig && remindMin.contentEquals(other.remindMin) &&
        hourStart == other.hourStart && hourEnd == other.hourEnd
    override fun hashCode() = remindMin.contentHashCode() * 31 + hourStart * 7 + hourEnd
}

class CareState {
    var elapsed = LongArray(4)        // active seconds since each need was last done
    var happy = 70.0
    var xp = 0L
    var today = IntArray(4)
    var day = 0                       // local yyyymmdd the counts belong to
    var streak = 0
    var bedtime = false
    var pauseUntil = 0L               // epoch ms
    var lastPet = 0L                  // epoch ms
    var lastUpdate = 0L               // epoch ms the timers were last advanced to
    var wasInHours = true

    fun toJson(): JSONObject = JSONObject()
        .put("elapsed", JSONArray(elapsed.toList())).put("happy", happy).put("xp", xp)
        .put("today", JSONArray(today.toList())).put("day", day).put("streak", streak).put("bedtime", bedtime)
        .put("pauseUntil", pauseUntil).put("lastPet", lastPet).put("lastUpdate", lastUpdate).put("wasInHours", wasInHours)

    companion object {
        fun fromJson(o: JSONObject?): CareState {
            val s = CareState()
            if (o == null) return s
            o.optJSONArray("elapsed")?.let { a -> for (i in 0 until 4) s.elapsed[i] = a.optLong(i) }
            s.happy = o.optDouble("happy", 70.0)
            s.xp = o.optLong("xp")
            o.optJSONArray("today")?.let { a -> for (i in 0 until 4) s.today[i] = a.optInt(i) }
            s.day = o.optInt("day")
            s.streak = o.optInt("streak")
            s.bedtime = o.optBoolean("bedtime")
            s.pauseUntil = o.optLong("pauseUntil")
            s.lastPet = o.optLong("lastPet")
            s.lastUpdate = o.optLong("lastUpdate")
            s.wasInHours = o.optBoolean("wasInHours", true)
            return s
        }
    }
}

class CareEngine(
    var config: CareConfig = CareConfig(),
    var st: CareState = CareState(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    // ---------- clock ----------
    private fun local(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone())
    fun dateOf(ms: Long): Int = local(ms).let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }

    fun inHours(ms: Long): Boolean {
        val (a, b) = config.hourStart to config.hourEnd
        if (a == b) return true
        val h = local(ms).hour
        return if (a < b) h in a until b else (h >= a || h < b)
    }

    fun paused(ms: Long) = st.pauseUntil > ms
    fun active(ms: Long) = inHours(ms) && !st.bedtime && !paused(ms)

    // ---------- needs ----------
    fun interval(n: Need): Long = config.remindMin[n.ordinal] * 60L
    fun enabled(n: Need) = config.remindMin[n.ordinal] > 0
    fun due(n: Need) = enabled(n) && st.elapsed[n.ordinal] >= interval(n)
    fun overdue(n: Need): Long = if (due(n)) st.elapsed[n.ordinal] - interval(n) else 0
    fun left(n: Need): Long = if (enabled(n) && !due(n)) interval(n) - st.elapsed[n.ordinal] else 0
    fun level(n: Need): Int {
        if (!enabled(n)) return 100
        val iv = interval(n)
        return if (st.elapsed[n.ordinal] >= iv) 0 else (100 - st.elapsed[n.ordinal] * 100 / iv).toInt()
    }

    fun mostUrgent(): Need? {
        var best: Need? = null
        var ratio = 0.0
        for (n in Need.entries) {
            if (!due(n)) continue
            val r = st.elapsed[n.ordinal].toDouble() / interval(n)
            if (best == null || r > ratio) { best = n; ratio = r }
        }
        return best
    }

    fun mood(ms: Long = System.currentTimeMillis()): Mood {
        if (st.bedtime || !inHours(ms)) return Mood.ASLEEP
        val u = if (paused(ms)) null else mostUrgent()
        if (u != null) {
            val longTime = max(30 * 60L, interval(u))
            if (overdue(u) >= longTime && st.happy < 30) return Mood.MAD
        }
        if (st.happy < 25) return Mood.SAD
        return when (u) {
            Need.WATER -> Mood.THIRSTY
            Need.EYES -> Mood.TIRED
            Need.STRETCH, Need.MOVE -> Mood.RESTLESS
            null -> if (st.happy >= 80) Mood.HAPPY else Mood.CONTENT
        }
    }

    fun happiness() = (st.happy + 0.5).toInt()
    fun level(): Int { var l = 1; var need = 100L; var xp = st.xp; while (xp >= need) { xp -= need; l++; need += 100 }; return l }
    fun levelProgress(): Int { var need = 100L; var xp = st.xp; while (xp >= need) { xp -= need; need += 100 }; return (xp * 100 / need).toInt() }

    // ---------- time passing ----------
    /** Advance the timers to [now], minute by minute so active hours, pauses and day changes land where they should. */
    fun advanceTo(now: Long) {
        if (st.lastUpdate == 0L || now < st.lastUpdate) { st.lastUpdate = now; checkDay(now); st.wasInHours = inHours(now); return }
        var t = st.lastUpdate
        val limit = min(now, t + 3L * 24 * 3600 * 1000)      // a long absence: three days is plenty
        while (t < limit) {
            val step = min(60_000L, limit - t)
            val from = t                                      // a minute counts as active by how it started
            t += step
            checkDay(t)
            val ih = inHours(t)
            if (ih && !st.wasInHours) {                      // a new active day: fresh timers, bedtime over
                st.bedtime = false
                st.elapsed.fill(0)
                if (st.happy < 60) st.happy = 60.0
            }
            st.wasInHours = ih
            if (active(from)) {
                val secs = step / 1000
                var neglected = false
                for (n in Need.entries) {
                    if (!enabled(n)) continue
                    st.elapsed[n.ordinal] += secs
                    neglected = neglected || (due(n) && overdue(n) > 10 * 60)
                }
                addHappy(-1.0 / 360 * secs)                   // about 10 points an hour
                if (neglected) addHappy(-0.25 / 60 * secs)    // plus 15 an hour while something is ignored
            }
        }
        st.lastUpdate = now
    }

    private fun checkDay(ms: Long) {
        val today = dateOf(ms)
        if (today == st.day) return
        if (st.day != 0) {
            val total = st.today.sum()
            val prev = LocalDate.of(st.day / 10000, st.day / 100 % 100, st.day % 100)
            val next = prev.plusDays(1).let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }
            st.streak = if (total >= 4 && next == today) st.streak + 1 else if (total >= 4) 1 else 0
        }
        st.day = today
        st.today.fill(0)
    }

    private fun addHappy(d: Double) { st.happy = (st.happy + d).coerceIn(0.0, 100.0) }

    // ---------- actions ----------
    private fun score(n: Need, elapsedThen: Long) {
        val iv = interval(n)
        val wasDue = enabled(n) && elapsedThen >= iv
        when {
            !enabled(n) || (wasDue && elapsedThen - iv < 10 * 60) || (!wasDue && elapsedThen * 2 >= iv) -> { addHappy(12.0); st.xp += 10 }
            wasDue -> { addHappy(8.0); st.xp += 6 }
            else -> { addHappy(3.0); st.xp += 2 }
        }
    }

    fun done(n: Need, now: Long = System.currentTimeMillis()) {
        advanceTo(now)
        score(n, st.elapsed[n.ordinal])
        st.elapsed[n.ordinal] = 0
        if (st.today[n.ordinal] < 999) st.today[n.ordinal]++
    }

    /** A break done [ageS] seconds ago (from a notification action or a walk spotted late). */
    fun doneAt(n: Need, ageS: Long, now: Long = System.currentTimeMillis()) {
        advanceTo(now)
        if (ageS < 5) { done(n, now); return }
        val i = n.ordinal
        if (ageS >= st.elapsed[i]) { addHappy(3.0); st.xp += 2 }
        else { score(n, st.elapsed[i] - ageS); st.elapsed[i] = ageS }
        if (dateOf(now - ageS * 1000) == st.day && st.today[i] < 999) st.today[i]++
    }

    /** A pat; false while on its one-minute cooldown. */
    fun pet(now: Long = System.currentTimeMillis()): Boolean {
        if (st.lastPet != 0L && now - st.lastPet < 60_000) return false
        st.lastPet = now
        addHappy(2.0); st.xp += 1
        return true
    }

    fun played(hits: Int) { addHappy(min(20, hits * 4).toDouble()); st.xp += hits }
    fun pause(minutes: Int, now: Long = System.currentTimeMillis()) { st.pauseUntil = if (minutes > 0) now + minutes * 60_000L else 0 }
    fun setBedtime(on: Boolean) { st.bedtime = on }

    /** Active seconds until [n] falls due turned into a wall-clock time, walking forward through active hours. */
    fun dueAt(n: Need, now: Long = System.currentTimeMillis()): Long? {
        if (!enabled(n)) return null
        var need = left(n)
        if (need <= 0) return now
        var t = now
        val end = now + 3L * 24 * 3600 * 1000
        while (t < end) {
            val step = 60_000L
            if (active(t)) { need -= 60; if (need <= 0) return t + step + need * 1000 }
            t += step
        }
        return null
    }

    // ---------- the dial ----------
    /** Take the dial's state (its op "state") as the truth. */
    fun applyDialState(o: JSONObject, now: Long = System.currentTimeMillis()) {
        val needs = o.optJSONArray("needs") ?: return
        val rem = config.remindMin.copyOf()
        for (i in 0 until min(4, needs.length())) {
            val x = needs.getJSONObject(i)
            st.elapsed[i] = x.optLong("elapsed")
            st.today[i] = x.optInt("today")
            rem[i] = (x.optLong("interval") / 60).toInt()
        }
        config = config.copy(remindMin = rem)
        st.happy = o.optDouble("happy", st.happy)
        st.xp = o.optLong("xp", st.xp)
        st.streak = o.optInt("streak", st.streak)
        st.bedtime = o.optBoolean("bedtime", st.bedtime)
        val p = o.optLong("paused")
        st.pauseUntil = if (p > 0) now + p * 1000 else 0
        o.optInt("day").takeIf { it != 0 }?.let { st.day = it }
        st.lastUpdate = now
        st.wasInHours = inHours(now)
    }
}
