package app.deskling.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.deskling.care.Need
import app.deskling.data.Store
import app.deskling.sync.Sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

// the dial's palette
val Bg = Color(0xFF111A2C)
val Panel = Color(0xFF0B1220)
val Line = Color(0xFF2A3A5A)
val Accent = Color(0xFF9FD8F7)
val TextC = Color(0xFFE8EEF8)
val Dim = Color(0xFF8A94A8)
val Ok = Color(0xFF7EE0A0)
val Bad = Color(0xFFFF8F8F)

@Composable
fun DesklingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Bg, background = Bg, onBackground = TextC, surface = Panel, onSurface = TextC,
            surfaceVariant = Color(0xFF1E2A42), onSurfaceVariant = Dim, outline = Line, secondary = Accent, error = Bad,
        ),
        content = content,
    )
}

enum class Activity(val title: String) { EYES("Eye break"), STRETCH("Stretch"), MOVE("Out for a walk"), PLAY("Catch") }

/** What the screens share: the clock, Wisp's reactions and toasts, and the activity in progress. */
class AppState(val ctx: Context, val scope: CoroutineScope) {
    val store = Store.get(ctx)
    var now by mutableLongStateOf(System.currentTimeMillis())
    var reactUntil by mutableLongStateOf(0L)
    var toast by mutableStateOf<String?>(null)
    private var toastUntil = 0L
    var activity by mutableStateOf<Activity?>(null)
    var actStart by mutableLongStateOf(0L)

    fun tick() {
        now = System.currentTimeMillis()
        store.engine.advanceTo(now)
        if (toast != null && now > toastUntil) toast = null
    }

    fun say(t: String, ms: Long = 2500) { toast = t; toastUntil = System.currentTimeMillis() + ms }
    private fun react(loops: Int = 2) { reactUntil = System.currentTimeMillis() + loops * 1000L }

    fun syncSoon() { scope.launch { Sync.sync(ctx) } }

    /** Log a break (here, and queued for the dial), with a happy moment. */
    fun done(n: Need, msg: String) {
        store.logDone(n)
        react(3)
        say(msg)
        syncSoon()
    }

    fun start(a: Activity) { activity = a; actStart = System.currentTimeMillis() }
    fun stop(msg: String? = null) { activity = null; msg?.let { say(it) } }

    fun water() {
        done(Need.WATER, "Glass ${store.engine.st.today[Need.WATER.ordinal] + 1} today")
    }

    /** A tap on Wisp: answers what Wisp is asking for, or a pat when nothing is due (as on the dial). */
    fun tapWisp() {
        val e = store.engine
        val u = if (e.paused(now) || e.mood(now) == app.deskling.care.Mood.ASLEEP) null else e.mostUrgent()
        when (u) {
            Need.WATER -> water()
            Need.EYES -> start(Activity.EYES)
            Need.STRETCH -> start(Activity.STRETCH)
            Need.MOVE -> start(Activity.MOVE)
            null -> {
                if (e.mood(now) == app.deskling.care.Mood.ASLEEP) { say("Shh, ${store.pet.title} is asleep"); return }
                if (e.pet(now)) {
                    store.queue(JSONObject().put("op", "pet"))
                    store.saveCare()
                    say("${store.pet.title} likes that", 1500)
                }
                react(2)
            }
        }
    }

    fun played(hits: Int, rounds: Int) {
        store.engine.played(hits)
        store.queue(JSONObject().put("op", "played").put("hits", hits).put("rounds", rounds))
        store.saveCare()
        activity = null
        say("$hits of $rounds caught")
        syncSoon()
    }

    fun pause(minutes: Int) {
        store.engine.pause(minutes, now)
        store.queue(JSONObject().put("op", "pause").put("minutes", minutes))
        store.saveCare()
        say(if (minutes > 0) "Paused for an hour" else "Reminders back on")
        syncSoon()
    }

    fun bedtime(on: Boolean) {
        store.engine.setBedtime(on)
        store.queue(JSONObject().put("op", "bedtime").put("on", on))
        store.saveCare()
        say(if (on) "Goodnight" else "Good morning")
        syncSoon()
    }
}
