package app.deskling.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.deskling.data.Pet

/**
 * The app icon follows the companion. The manifest has one launcher entry (an activity-alias of Home) per pet,
 * each with that pet's icon; exactly one is enabled at a time. Wisp's keeps the old ".MainActivity" name.
 *
 * Call it when the app goes to the background: some launchers drop the task that was started from the entry
 * being switched off, which is harmless then and jarring while you're using the app.
 */
object LauncherIcon {
    private val ENTRY = mapOf(Pet.WISP to "app.deskling.MainActivity", Pet.CRUMB to "app.deskling.LauncherCrumb",
        Pet.SPRIG to "app.deskling.LauncherSprig")
    private const val DEFAULT = "app.deskling.MainActivity"        // the one enabled in the manifest

    fun sync(ctx: Context, pet: Pet) {
        val pm = ctx.packageManager
        // switch the new icon on before the old one off, so there's never a moment with no launcher entry
        val order = listOf(pet) + Pet.entries.filter { it != pet }
        for (p in order) {
            val name = ENTRY.getValue(p)
            val cn = ComponentName(ctx.packageName, name)
            val on = when (pm.getComponentEnabledSetting(cn)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> name == DEFAULT
                else -> false
            }
            val want = p == pet
            if (on != want) {
                pm.setComponentEnabledSetting(cn,
                    if (want) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
            }
        }
    }
}
