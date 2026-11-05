package app.deskling.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.deskling.data.Pet
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The launcher shows exactly one Deskling entry, with the chosen companion's icon. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LauncherIconTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun launcherEntries(): List<Pair<String, Int>> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(ctx.packageName)
        return pm.queryIntentActivities(main, PackageManager.MATCH_ALL).filter { r ->
            val cn = android.content.ComponentName(ctx.packageName, r.activityInfo.name)
            val st = pm.getComponentEnabledSetting(cn)
            st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && r.activityInfo.enabled)
        }.map { it.activityInfo.name to it.activityInfo.iconResource }
    }

    @Test fun startsAsWispUnderTheOldName() {
        val entries = launcherEntries()
        assertEquals(listOf("app.deskling.MainActivity"), entries.map { it.first })
        assertEquals(app.deskling.R.mipmap.ic_launcher_wisp, entries.single().second)
    }

    @Test fun followsEachCompanionAndBack() {
        val icons = mapOf(Pet.WISP to app.deskling.R.mipmap.ic_launcher_wisp, Pet.CRUMB to app.deskling.R.mipmap.ic_launcher_crumb,
            Pet.SPRIG to app.deskling.R.mipmap.ic_launcher_sprig)
        for (pet in listOf(Pet.CRUMB, Pet.SPRIG, Pet.WISP, Pet.SPRIG)) {
            LauncherIcon.sync(ctx, pet)
            val entries = launcherEntries()
            assertEquals("one launcher entry for $pet", 1, entries.size)
            assertEquals("$pet's icon", icons.getValue(pet), entries.single().second)
        }
        LauncherIcon.sync(ctx, Pet.SPRIG)          // already there: nothing changes
        assertEquals(1, launcherEntries().size)
    }
}
