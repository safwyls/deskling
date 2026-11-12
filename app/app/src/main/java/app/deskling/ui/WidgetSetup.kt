package app.deskling.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deskling.data.Store
import app.deskling.data.WidgetBg

/** Shown when the widget is added (and from the widget's own settings): what goes behind it. */
class WidgetSetup : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))   // backing out adds nothing
        val store = Store.get(this)
        setContent {
            DesklingTheme {
                Column(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
                    Text("Widget background", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("You can change it later in Settings > Phone, or from the widget's settings.", color = Dim, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                    for (b in WidgetBg.entries) {
                        val on = store.widgetBg == b
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)
                            .background(if (on) Raised else Panel, RoundedCornerShape(12.dp))
                            .border(1.dp, if (on) Accent else Line, RoundedCornerShape(12.dp))
                            .clickable {
                                store.widgetBg = b
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                finish()
                            }.padding(16.dp)) {
                            Text(b.title, color = TextC, fontSize = 17.sp)
                            Text(when (b) {
                                WidgetBg.SOLID -> "A panel in ${store.pet.title}'s colours"
                                WidgetBg.GLASS -> "A faint frosted panel: the wallpaper shows through"
                                WidgetBg.NONE -> "No panel: the companion and its words straight on the wallpaper"
                            }, color = Dim, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
