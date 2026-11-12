package app.deskling.notify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.deskling.care.Need
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The glow at full strength on a phone-sized screen (build/shots/edge_flash.png). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi", application = android.app.Application::class)
class EdgeFlashRenderTest {
    @Test fun glow() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val v = EdgeFlash.Glow(ctx).apply { color = Need.WATER.color.toInt(); level = 1f }
        val w = 1200; val h = 2580
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFF1B1F24.toInt()); v.draw(c)
        File("build/shots").mkdirs()
        File("build/shots/edge_flash.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
