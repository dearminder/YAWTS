package com.dearminder.yawts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {

    @Test
    fun newerVersionComparesNumbersPartByPart() {
        assertTrue(isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(isNewerVersion("1.10.0", "1.9.0"))
        assertTrue(isNewerVersion("2.0", "1.9.9"))
        assertTrue(isNewerVersion("v1.1.0", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(isNewerVersion("1.0", "1.0.0"))
        assertFalse(isNewerVersion("0.9.0", "1.0.0"))
    }

    @Test
    fun releaseBeatsItsOwnPreRelease() {
        assertTrue(isNewerVersion("1.0.0", "1.0.0-beta1"))
        assertFalse(isNewerVersion("1.0.0-beta1", "1.0.0"))
        assertTrue(isNewerVersion("1.0.1-beta1", "1.0.0"))
    }
}
