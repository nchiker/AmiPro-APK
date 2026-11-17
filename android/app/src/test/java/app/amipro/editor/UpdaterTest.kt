package app.amipro.editor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdaterTest {
    @Test fun newerTagIsNewer() { assertTrue(Updater.isNewer("v0.2.0", "0.1.0")); assertTrue(Updater.isNewer("0.1.1", "0.1.0")); assertTrue(Updater.isNewer("v1.0", "0.9.9")) }
    @Test fun sameOrOlderIsNotNewer() { assertFalse(Updater.isNewer("v0.1.0", "0.1.0")); assertFalse(Updater.isNewer("v0.1", "0.1.0")); assertFalse(Updater.isNewer("v0.0.9", "0.1.0")) }
    @Test fun unreadableTagIsNotNewer() { assertFalse(Updater.isNewer("latest", "0.1.0")) }
    @Test fun tenIsNewerThanNine() { assertTrue(Updater.isNewer("v0.10.0", "0.9.0")) }
}
