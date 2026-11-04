package app.deskling.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkEventTest {
    @Test fun theDialsChangeNoticeIsAnEvent() {
        assertEquals("changed", BleLink.eventOf("{\"event\":\"changed\"}")?.optString("event"))
    }

    @Test fun repliesAreNotEventsEvenIfTheyMentionOne() {
        assertNull(BleLink.eventOf("{\"ok\":true,\"event\":\"x\"}"))
        assertNull(BleLink.eventOf("{\"ok\":true,\"mood\":\"Content\"}"))
        assertNull(BleLink.eventOf("{\"ok\":false,\"err\":\"not paired\"}"))
    }

    @Test fun garbleIsNotAnEvent() {
        assertNull(BleLink.eventOf("{\"event\":"))
    }
}
