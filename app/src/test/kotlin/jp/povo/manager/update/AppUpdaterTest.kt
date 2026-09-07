package jp.povo.manager.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the version comparison behind "an update is available".
 *
 * This is the one piece of the updater worth testing without a network: it
 * decides whether a person is prompted to install something, and the obvious
 * implementation — comparing the strings — is wrong in a way that only shows up
 * after ten releases.
 */
class AppUpdaterTest {

    @Test
    fun `a higher version is newer`() {
        assertTrue(AppUpdater.isNewer("v0.2.0", "0.1.0"))
        assertTrue(AppUpdater.isNewer("0.1.1", "0.1.0"))
        assertTrue(AppUpdater.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(AppUpdater.isNewer("v0.1.0", "0.1.0"))
        assertFalse(AppUpdater.isNewer("0.1.0", "v0.1.0"))
        // Trailing zeroes are the same version, not a later one.
        assertFalse(AppUpdater.isNewer("0.1", "0.1.0"))
        assertFalse(AppUpdater.isNewer("0.1.0.0", "0.1"))
    }

    @Test
    fun `an older version is not newer`() {
        assertFalse(AppUpdater.isNewer("v0.1.0", "0.2.0"))
        assertFalse(AppUpdater.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `components compare numerically rather than as text`() {
        // The bug this guards: "0.10.0" sorts below "0.9.0" as a string, so a
        // tenth release would look older than the ninth and never be offered.
        assertTrue(AppUpdater.isNewer("0.10.0", "0.9.0"))
        assertFalse(AppUpdater.isNewer("0.9.0", "0.10.0"))
        assertTrue(AppUpdater.isNewer("2.0.0", "1.11.0"))
    }

    @Test
    fun `a tag that cannot be read is never treated as newer`() {
        // Nobody should be prompted to install something on the strength of a
        // tag we failed to parse.
        assertFalse(AppUpdater.isNewer("nightly", "0.1.0"))
        assertFalse(AppUpdater.isNewer("", "0.1.0"))
        assertFalse(AppUpdater.isNewer("v", "0.1.0"))
    }

    @Test
    fun `a prerelease suffix compares on its numbers`() {
        // Tags like v0.2.0-rc1 are read up to the first non-numeric character,
        // so the release number still decides.
        assertTrue(AppUpdater.isNewer("v0.2.0-rc1", "0.1.0"))
        assertFalse(AppUpdater.isNewer("v0.1.0-rc1", "0.1.0"))
    }
}
