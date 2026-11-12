package app.deskling.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import app.deskling.data.Pet
import app.deskling.care.Need
import app.deskling.data.Store
import app.deskling.sync.Sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One palette per pet, the same as the dial's (firmware include/theme.h). */
class Palette(val bg: Color, val panel: Color, val raised: Color, val line: Color, val accent: Color, val text: Color,
              val dim: Color, val ok: Color, val bad: Color, val shadow: Color)

/** Wisp: night sky. Navy and ice blue; the shadow is a soft glow. */
val WispPalette = Palette(Color(0xFF111A2C), Color(0xFF0B1220), Color(0xFF1E2A42), Color(0xFF2A3A5A), Color(0xFF9FD8F7),
    Color(0xFFE8EEF8), Color(0xFF8A94A8), Color(0xFF7EE0A0), Color(0xFFFF8F8F), Color(70, 105, 160, 110))
/** Crumb: a pond at dusk. Moss and loam, the amber of his shell, the cream of his face; a shadow on the ground. */
val CrumbPalette = Palette(Color(0xFF1A2C20), Color(0xFF101B13), Color(0xFF26392D), Color(0xFF3A5644), Color(0xFFF3B54A),
    Color(0xFFF2ECDA), Color(0xFF9AA88F), Color(0xFF9ED98A), Color(0xFFFF9A85), Color(4, 9, 5, 150))

/** Sprig: blossom at dusk. Plum shade, the green of its leaves, the cream of its face. */
val SprigPalette = Palette(Color(0xFF32223A), Color(0xFF21152A), Color(0xFF412F4B), Color(0xFF56405F), Color(0xFFA9DB7F),
    Color(0xFFF5F1E3), Color(0xFFAB9AAE), Color(0xFF9FE3B0), Color(0xFFFF8FA3), Color(10, 4, 14, 150))

fun paletteOf(pet: Pet) = when (pet) { Pet.WISP -> WispPalette; Pet.CRUMB -> CrumbPalette; Pet.SPRIG -> SprigPalette }

/** The palette on screen now; the names below read it, so everything recolours when the pet changes. */
object Theme { var palette by mutableStateOf(WispPalette) }
val Bg: Color get() = Theme.palette.bg
val Panel: Color get() = Theme.palette.panel
val Raised: Color get() = Theme.palette.raised
val Line: Color get() = Theme.palette.line
val Accent: Color get() = Theme.palette.accent
val TextC: Color get() = Theme.palette.text
val Dim: Color get() = Theme.palette.dim
val Ok: Color get() = Theme.palette.ok
val Bad: Color get() = Theme.palette.bad

@Composable
fun DesklingTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val v by store.version.collectAsState()
    val p = remember(v) { paletteOf(store.pet) }
    if (Theme.palette !== p) Theme.palette = p
    val view = LocalView.current
    SideEffect { (view.context as? android.app.Activity)?.window?.setBackgroundDrawable(ColorDrawable(p.bg.toArgb())) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = p.accent, onPrimary = p.bg, background = p.bg, onBackground = p.text, surface = p.panel, onSurface = p.text,
            surfaceVariant = p.raised, onSurfaceVariant = p.dim, outline = p.line, outlineVariant = p.line,
            secondary = p.accent, onSecondary = p.bg, error = p.bad,
            // the selected tab's pill, slider tracks and switches draw from these: keep them in the pet's colours
            secondaryContainer = p.raised, onSecondaryContainer = p.text, primaryContainer = p.raised, onPrimaryContainer = p.text,
            surfaceContainer = p.panel, surfaceContainerHigh = p.raised, surfaceContainerHighest = p.raised,
            surfaceContainerLow = p.panel, surfaceContainerLowest = p.bg,
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
                if (e.postureDue() && !e.paused(now)) { switchPosture(); return }
                if (e.pet(now)) {
                    store.queueForDial(JSONObject().put("op", "pet"))
                    store.saveCare()
                    say("${store.pet.title} likes that", 1500)
                }
                react(2)
            }
        }
    }

    fun played(hits: Int, rounds: Int) {
        store.engine.played(hits)
        store.queueForDial(JSONObject().put("op", "played").put("hits", hits).put("rounds", rounds))
        store.saveCare()
        activity = null
        say("$hits of $rounds caught")
        syncSoon()
    }

    /** You switched between sitting and standing (here, and queued for the dial). */
    fun switchPosture() {
        val e = store.engine
        val stand = !e.st.standing
        val wasDue = e.postureDue()
        e.setPosture(stand, now)
        store.queueForDial(JSONObject().put("op", "posture").put("standing", stand))
        store.saveCare()
        if (wasDue) react(2)
        say(if (stand) "Standing: sit in ${e.config.standMin} min" else "Sitting: stand in ${e.config.sitMin} min")
        syncSoon()
    }

    fun pause(minutes: Int) {
        store.engine.pause(minutes, now)
        store.queueForDial(JSONObject().put("op", "pause").put("minutes", minutes))
        store.saveCare()
        say(if (minutes > 0) "Paused for an hour" else "Reminders back on")
        syncSoon()
    }

    fun bedtime(on: Boolean) {
        store.engine.setBedtime(on)
        store.queueForDial(JSONObject().put("op", "bedtime").put("on", on))
        store.saveCare()
        say(if (on) "Goodnight" else "Good morning")
        syncSoon()
    }
}
