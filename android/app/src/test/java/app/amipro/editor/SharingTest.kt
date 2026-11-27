package app.amipro.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingTest {
    private fun labels(s: PartnerStatus?, loc: Boolean, state: ShareState? = ShareState(false, null)) =
        Sharing.items(s, loc, state).map { when (it) { is ShareItem.Share -> it.label; is ShareItem.Stop -> it.label; is ShareItem.Via -> it.label; is ShareItem.Update -> it.label; is ShareItem.Get -> it.label } }

    @Test fun gateShowsUpdateWhenContractMissingOrLow() {
        assertEquals(ShareItem.Update(), Sharing.gate(true, null))
        assertEquals(ShareItem.Update(), Sharing.gate(true, 0))
        assertEquals("Update Filen Files to share with your partner", ShareItem.Update().label)
    }
    @Test fun gateShowsGetWhenFilenNotInstalled() {
        assertEquals(ShareItem.Get(), Sharing.gate(false, null))
        assertEquals(ShareItem.Get(), Sharing.gate(false, 1))
        assertEquals("Get Filen Files", ShareItem.Get().label)
        assertEquals("https://github.com/nchiker/Filen-Filemanager/releases/latest", Sharing.FILEN_RELEASES)
    }
    @Test fun gateOpensAtLevelOneOrMore() { assertNull(Sharing.gate(true, 1)); assertNull(Sharing.gate(true, 2)) }
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
    @Test fun cleanStripsControlAndBidiAndCaps() {
        assertEquals("Sam", Sharing.clean("S\u202Ea\u0000m\u200F", 40))
        assertEquals("a b", Sharing.clean("a\n\tb", 40))
        assertEquals(40, Sharing.clean("x".repeat(100), Sharing.MAX_NAME).length)
        assertEquals(200, Sharing.clean("y".repeat(500), Sharing.MAX_MESSAGE).length)
        assertEquals("", Sharing.clean(null, 40))
    }
    @Test fun longNameAndMessageAreCapped() {
        val long = "n".repeat(100)
        assertEquals(listOf("Share with " + "n".repeat(40)), labels(PartnerStatus("connected", long), true))
        assertEquals("Couldn't share: " + "m".repeat(200), Sharing.resultMessage(false, null, false, "Sam", "failed", "m".repeat(500)))
        assertEquals("Couldn't share: ab", Sharing.resultMessage(false, null, false, "Sam", "failed", "a\u202Eb"))
    }
    @Test fun pinNeedsPackageAndOneMatchingCertificate() {
        assertTrue(FilenPin.decide(FilenPin.PACKAGE, listOf(FilenPin.CERT)))
        assertEquals(false, FilenPin.decide("evil.app", listOf(FilenPin.CERT)))
        assertEquals(false, FilenPin.decide(FilenPin.PACKAGE, listOf("00")))
        assertEquals(false, FilenPin.decide(FilenPin.PACKAGE, listOf(FilenPin.CERT, "00")))
        assertEquals(false, FilenPin.decide(FilenPin.PACKAGE, emptyList()))
        assertEquals(false, FilenPin.decide(null, listOf(FilenPin.CERT)))
    }
}
