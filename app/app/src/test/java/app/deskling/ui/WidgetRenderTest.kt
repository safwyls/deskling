package app.deskling.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import app.deskling.data.Pet
import app.deskling.data.Store
import app.deskling.data.WidgetBg
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The home screen widget, drawn over a wallpaper with each background (build/shots/widget_*.png). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi", application = android.app.Application::class)
class WidgetRenderTest {
    @Test fun backgrounds() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val s = Store.get(ctx)
        val dp = ctx.resources.displayMetrics.density
        val w = (300 * dp).toInt(); val h = (130 * dp).toInt()
        val content = Widget.Content(Pet.WISP, true, Loop.THIRSTY, "Wisp", "Thirsty", "Water is due", 5234, 8000, true)
        for (bg in WidgetBg.entries) {
            s.widgetBg = bg
            val parent = FrameLayout(ctx)
            val v = Widget.render(ctx, content).apply(ctx, parent)
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val pad = (24 * dp).toInt()
            val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            // a wallpaper: something with light and dark in it
            c.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), 0xFF3B6E8F.toInt(), 0xFFE0A86A.toInt(), Shader.TileMode.CLAMP)
            })
            c.translate(pad.toFloat(), pad.toFloat())
            v.draw(c)
            File("build/shots").mkdirs()
            File("build/shots/widget_${bg.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(true)
    }
}
