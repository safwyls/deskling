package app.deskling.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.deskling.care.Need
import app.deskling.data.DialInfo
import app.deskling.data.Pet
import app.deskling.data.Store
import app.deskling.data.Travel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the real screens on the JVM (Robolectric's native graphics) to build/shots/, to look at without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi", application = android.app.Application::class)
class ScreensRenderTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        Thread.sleep(400)                                // the loop's frames decode off the main thread
        compose.mainClock.advanceTimeBy(500)
        val dir = File("build/shots").apply { mkdirs() }
        // draw the window ourselves: captureToImage waits for a frame that a paused test clock never delivers
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Click a text, then let the screen settle (the clock is paused, so nothing moves on by itself). */
    private fun tap(text: String, scroll: Boolean = false) {
        compose.mainClock.advanceTimeBy(300)
        val n = compose.onNodeWithText(text)
        (if (scroll) n.performScrollTo() else n).performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private var appRef: AppState? = null

    @Test fun screens() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val s = Store.get(ctx)
        val now = System.currentTimeMillis()
        // a believable afternoon: eye break overdue, a couple of glasses of water logged, a paired dial
        s.engine.config = s.engine.config.copy(hourStart = 0, hourEnd = 24)
        s.engine.advanceTo(now)
        s.engine.st.elapsed[Need.EYES.ordinal] = 25 * 60
        s.engine.st.elapsed[Need.WATER.ordinal] = 20 * 60
        s.engine.st.elapsed[Need.MOVE.ordinal] = 35 * 60
        s.engine.st.today[Need.WATER.ordinal] = 2
        s.engine.st.today[Need.STRETCH.ordinal] = 1
        s.engine.st.xp = 260
        s.engine.st.streak = 3
        s.engine.config = s.engine.config.copy(postureOn = true)
        s.engine.st.postureElapsed = 50 * 60; s.engine.st.standsToday = 2
        s.saveCare()
        s.stepsDay = s.engine.dateOf(now); s.stepsToday = 5234
        s.stepsHistory = JSONObject().also { h ->
            listOf(7400, 9100, 4300, 8800, 11200, 6100).forEachIndexed { i, v -> h.put(s.engine.dateOf(now - (6 - i) * 86_400_000L).toString(), v) }
            h.put(s.engine.dateOf(now).toString(), 5234)
        }
        s.dial = DialInfo("Deskling-3456", "0123456789abcdef0123456789abcdef", "192.168.1.42")
        s.travel = Travel("0011223344556677", 2, true)          // brought home from the dial
        s.dialState = JSONObject().put("wifi", JSONObject().put("up", true).put("ssid", "Home Wi-Fi").put("ip", "192.168.1.42"))
        s.dialSettings = JSONObject().put("remind", JSONArray(listOf(45, 20, 50, 60))).put("hourStart", 8).put("hourEnd", 20).put("tz", 0)
            .put("tzNames", JSONArray(listOf("US Pacific", "US Mountain"))).put("clockMode", 1).put("clockStyle", 3)
            .put("clockStyles", JSONArray(listOf("Classic", "Big", "Split", "Analog", "Rim", "Words"))).put("showDate", false).put("showStatus", true)
            .put("showNext", true).put("bright", 70).put("ledBright", 60).put("napMin", 10).put("ledOffset", -2).put("encInvert", false)
            .put("ssid", "Home Wi-Fi")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DesklingTheme {
                val scope = rememberCoroutineScope()
                val app = remember { AppState(ctx, scope).also { it.tick(); appRef = it } }
                MainUi(app)
            }
        }
        shot("1_wisp")
        tap("Stats"); shot("2_stats")
        tap("Settings"); shot("3_settings")
        tap("Reminders"); shot("3c_settings_reminders")
        tap("Dial"); shot("3d_settings_dial")
        tap("Phone"); shot("3e_settings_phone")
        tap("Stats"); tap("Wisp"); shot("3b_back")
        compose.onNodeWithContentDescription("companion").performClick(); compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        shot("1b_wheel")
        tap("Eyes"); shot("5_eye_break")
        tap("Stop")
        appRef?.toast = null
        // a phone with one day of steps, as on a fresh install
        s.stepsHistory = JSONObject().put(s.engine.dateOf(now).toString(), 995); s.stepsToday = 995
        s.engine.st.today.fill(0); s.engine.st.today[Need.WATER.ordinal] = 1; s.engine.st.streak = 0; s.saveCare()
        tap("Stats"); shot("6_stats_fresh")
        tap("Settings"); tap("Dial")
        tap("Forget", scroll = true)
        tap("Pair a dial", scroll = true); shot("4_pair")
        tap("Cancel", scroll = true)
        // no dial: the app's own pet choice applies
        s.pet = Pet.CRUMB
        s.engine.st.today[Need.WATER.ordinal] = 3; s.engine.st.elapsed[Need.WATER.ordinal] = 0; s.saveCare()
        tap("Stats"); tap("Crumb"); shot("7_crumb")
        s.engine.st.elapsed[Need.WATER.ordinal] = 60 * 60; s.saveCare(); compose.mainClock.advanceTimeBy(2000); shot("8_crumb_thirsty")
        tap("Stats"); shot("9_crumb_stats")
        tap("Settings"); shot("10_crumb_settings")
        // the third pet, thirsty: its clip has no bubble, so it gets the water one
        s.pet = Pet.SPRIG
        tap("Stats"); tap("Sprig"); shot("11_sprig_thirsty")
        tap("Stats"); shot("12_sprig_stats")
        // left at a dial: the pet tab says where, and offers to bring it home
        s.pet = Pet.WISP
        s.dial = DialInfo("Deskling-3456", "0123456789abcdef0123456789abcdef", "192.168.1.42")
        s.dialState = JSONObject().put("mood", "Content").put("level", 3).put("happy", 74)
        s.travel = Travel("0011223344556677", 3, false, "Deskling-3456")
        tap("Stats"); tap("Wisp"); shot("13_at_dial")
        tap("Settings"); shot("14_settings_at_dial")
    }
}
