package app.amipro.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingTest {
    private fun labels(s: PartnerStatus?, loc: Boolean, state: ShareState? = ShareState(false, null)) =
        Sharing.items(s, loc, state).map { when (it) { is ShareItem.Share -> it.label; is ShareItem.Stop -> it.label; is ShareItem.Via -> it.label } }

    @Test fun connectedShowsThePartnersName() = assertEquals(listOf("Share with Sam"), labels(PartnerStatus("connected", "Sam"), true))
    @Test fun notConnectedStatesOfferSharePartner() {
        for (st in listOf("none", "requested", "incoming")) assertEquals(listOf("Share with partner…"), labels(PartnerStatus(st, "Sam"), true))
    }
    @Test fun filenMissingHidesTheItem() = assertTrue(labels(null, true).isEmpty())
    @Test fun noLocatorAddsEllipsisAndNeedsFilen() {
        assertEquals(listOf("Share with Sam…"), labels(PartnerStatus("connected", "Sam"), false))
        assertTrue((Sharing.items(PartnerStatus("connected", "Sam"), false, null)[0] as ShareItem.Share).needsFilen)
    }
    @Test fun sharedOffersStopOnly() = assertEquals(listOf("Stop sharing with Sam"), labels(PartnerStatus("connected", "Sam"), true, ShareState(true, null)))
    @Test fun sharedViaFolderIsADisabledNote() {
        assertEquals(listOf("Shared with Sam in Taxes"), labels(PartnerStatus("connected", "Sam"), true, ShareState(false, "Taxes")))
        assertTrue(Sharing.items(PartnerStatus("connected", "Sam"), true, ShareState(false, "Taxes"))[0] is ShareItem.Via)
    }
    @Test fun unknownStateOffersShare() = assertEquals(listOf("Share with Sam"), labels(PartnerStatus("connected", "Sam"), true, null))
    @Test fun notConnectedNeverShowsStop() = assertEquals(listOf("Share with partner…"), labels(PartnerStatus("none", ""), true, ShareState(true, null)))
    @Test fun resultMessages() {
        assertEquals("Shared with Sam", Sharing.resultMessage(true, true, false, "Sam", null, null))
        assertEquals("Stopped sharing", Sharing.resultMessage(true, false, true, "Sam", null, null))
        assertNull(Sharing.resultMessage(false, null, false, "Sam", null, null))
        assertNull(Sharing.resultMessage(false, null, false, "Sam", "user_cancelled", null))
        assertEquals("Couldn't share: No connection.", Sharing.resultMessage(false, null, false, "Sam", "offline", "No connection."))
        assertEquals("Couldn't share: try again.", Sharing.resultMessage(false, null, false, "Sam", "failed", null))
    }
    @Test fun stackSearchItemIsNotAShareItem() {
        assertEquals("Show in Stack search", Sharing.SHOW_IN_STACK)
        val partnerLabels = listOf(PartnerStatus("connected", "Sam"), PartnerStatus("none", "")).flatMap { labels(it, true) + labels(it, false) }
        assertTrue(partnerLabels.none { it == Sharing.SHOW_IN_STACK || it.contains("Stack") })
    }
}
