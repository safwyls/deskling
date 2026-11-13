package app.deskling.data

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renaming a dial: the app knows it by its id, and the companion's whereabouts follow the new name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DialNameTest {
    @Test fun pairedBeforeNamesKeepTheirNameAsId() {
        val d = DialInfo.fromJson(JSONObject().put("name", "Deskling-77B2").put("key", "k"))!!
        assertEquals("Deskling-77B2", d.id)
        assertEquals("Deskling-77B2", DialInfo.fromJson(d.toJson())!!.id)
    }

    @Test fun renameFollowsTheCompanion() {
        val s = Store.get(ApplicationProvider.getApplicationContext())
        s.dial = DialInfo("Deskling-77B2", "k1")
        s.dial = DialInfo("Deskling-1A2B", "k2")
        s.travel = Travel("0123456789abcdef", 3, false, "Deskling-77B2")
        s.renameDial("Deskling-77B2", "Kitchen")
        assertEquals("Kitchen", s.dials.first { it.id == "Deskling-77B2" }.name)
        assertEquals("Kitchen", s.travel.where)
        assertEquals("Deskling-1A2B", s.dial!!.id)                 // renaming doesn't change which dial is current
        // the current dial is still found after its own rename
        s.renameDial("Deskling-1A2B", "Desk")
        assertEquals("Desk", s.dial!!.name)
        assertEquals("Kitchen", s.travel.where)                     // the companion is elsewhere: untouched
        s.forgetDial("Deskling-77B2")
        assertEquals(listOf("Desk"), s.dials.map { it.name })
    }
}
