package app.deskling

import android.Manifest
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.deskling.data.Store
import app.deskling.notify.Reminders
import app.deskling.notify.SyncWorker
import app.deskling.steps.Steps
import app.deskling.sync.LinkService
import app.deskling.sync.Sync
import app.deskling.ui.AppState
import app.deskling.ui.DesklingTheme
import app.deskling.ui.LauncherIcon
import app.deskling.ui.MainUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DesklingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Reminders.channel(this)
        SyncWorker.schedule(this)
        Reminders.check(this)
    }
}

class Home : ComponentActivity() {
    private var lastStepSave = 0L
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val now = System.currentTimeMillis()
            if (now - lastStepSave < 5000) return       // the counter fires every step or two: fold it in now and then
            lastStepSave = now
            Steps.record(this@Home, e.values[0], now)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val askFirst = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Store.get(this).changed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("deskling", MODE_PRIVATE)
        if (!prefs.getBoolean("askedPermissions", false)) {
            prefs.edit().putBoolean("askedPermissions", true).apply()
            askFirst.launch(buildList {
                if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) } else add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
        }
        setContent {
            DesklingTheme {
                val scope = rememberCoroutineScope()
                val app = remember { AppState(applicationContext, scope) }
                LaunchedEffect(Unit) {                  // the clock the screens run on
                    var saved = 0L
                    while (true) {
                        app.tick()
                        if (app.now - saved > 30_000) { saved = app.now; app.store.saveCare() }
                        delay(1000)
                    }
                }
                MainUi(app)
            }
        }
        // while the app is open: stay in step with the dial and count steps live
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                LinkService.update(this@Home)      // the held Bluetooth link (needs the app in front to start)
                val sm = getSystemService(SENSOR_SERVICE) as SensorManager
                val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                if (sensor != null && Steps.permitted(this@Home)) sm.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                try {
                    while (true) {
                        Sync.sync(this@Home)
                        delay(15_000)
                    }
                } finally {
                    sm.unregisterListener(stepListener)
                    Reminders.check(this@Home)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // the app icon follows the companion; switch it now that we're out of sight
        LauncherIcon.sync(this, Store.get(this).pet)
    }
}
