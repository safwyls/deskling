package app.deskling.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.deskling.care.Need
import app.deskling.data.DialInfo
import app.deskling.data.Store
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
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreensRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        Thread.sleep(400)                                // the loop's frames decode off the main thread
        compose.mainClock.advanceTimeBy(500)
        val dir = File("build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

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
        s.saveCare()
        s.stepsDay = s.engine.dateOf(now); s.stepsToday = 5234
        s.stepsHistory = JSONObject().also { h ->
            listOf(7400, 9100, 4300, 8800, 11200, 6100).forEachIndexed { i, v -> h.put(s.engine.dateOf(now - (6 - i) * 86_400_000L).toString(), v) }
            h.put(s.engine.dateOf(now).toString(), 5234)
        }
        s.dial = DialInfo("Deskling-3456", "0123456789abcdef0123456789abcdef", "192.168.1.42")
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
                val app = remember { AppState(ctx, scope).also { it.tick() } }
                MainUi(app)
            }
        }
        shot("1_wisp")
        compose.onNodeWithText("Stats").performClick(); shot("2_stats")
        compose.onNodeWithText("Settings").performClick(); shot("3_settings")
        compose.onNodeWithText("Wisp").performClick()
        compose.onNodeWithText("Eyes").performClick(); shot("5_eye_break")
        compose.onNodeWithText("Stop").performClick()
        // a phone with one day of steps, as on a fresh install
        s.stepsHistory = JSONObject().put(s.engine.dateOf(now).toString(), 995); s.stepsToday = 995
        s.engine.st.today.fill(0); s.engine.st.today[Need.WATER.ordinal] = 1; s.engine.st.streak = 0; s.saveCare()
        compose.onNodeWithText("Stats").performClick(); shot("6_stats_fresh")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Forget this dial").performClick()
        compose.onNodeWithText("Pair a dial").performClick(); shot("4_pair")
    }
}
