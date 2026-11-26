package app.amipro.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingTest {
    private fun labels(s: PartnerStatus?, loc: Boolean, shared: Boolean = false) =
        Sharing.items(s, loc, shared).map { if (it is ShareItem.Share) it.label else (it as ShareItem.Stop).label }

    @Test fun connectedShowsThePartnersName() = assertEquals(listOf("Share with Sam"), labels(PartnerStatus("connected", "Sam"), true))
    @Test fun notConnectedStatesOfferSharePartner() {
        for (st in listOf("none", "requested", "incoming")) assertEquals(listOf("Share with partner…"), labels(PartnerStatus(st, "Sam"), true))
    }
    @Test fun filenMissingHidesTheItem() = assertTrue(labels(null, true).isEmpty())
    @Test fun noLocatorAddsEllipsisAndNeedsFilen() {
        assertEquals(listOf("Share with Sam…"), labels(PartnerStatus("connected", "Sam"), false))
        assertTrue((Sharing.items(PartnerStatus("connected", "Sam"), false, false)[0] as ShareItem.Share).needsFilen)
    }
    @Test fun stopOnlyWhenKnownSharedAndConnected() {
        assertEquals(listOf("Share with Sam", "Stop sharing with Sam"), labels(PartnerStatus("connected", "Sam"), true, true))
        assertEquals(listOf("Share with partner…"), labels(PartnerStatus("none", ""), true, true))
    }
    @Test fun resultMessages() {
        assertEquals("Shared with Sam", Sharing.resultMessage(true, true, false, "Sam", null, null))
        assertEquals("Stopped sharing", Sharing.resultMessage(true, false, true, "Sam", null, null))
        assertNull(Sharing.resultMessage(false, null, false, "Sam", null, null))
        assertNull(Sharing.resultMessage(false, null, false, "Sam", "user_cancelled", null))
        assertEquals("Couldn't share: No connection.", Sharing.resultMessage(false, null, false, "Sam", "offline", "No connection."))
        assertEquals("Couldn't share: try again.", Sharing.resultMessage(false, null, false, "Sam", "failed", null))
    }
}
