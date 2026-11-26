package app.aaps.pump.apex.protocol

import app.aaps.pump.apex.state.ApexPumpState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** All the driver takes from the version is the protocol: whether it works with the pump, and where the battery comes from. */
class ApexVersionProtocolTest {

    /** Setting a version sets the form of the stamps for the whole run; leave it plain for the next test. */
    @AfterEach
    fun plainStamps() {
        ApexStamp.digits = false
    }

    private fun version(major: Int, minor: Int) = ApexVersion(listOf(6, 25), major, minor, listOf(0, 0))

    @Test
    fun `the driver works from protocol 4_10 on`() {
        assertTrue(version(4, 10).supported)
        assertTrue(version(4, 12).supported)
        assertFalse(version(4, 9).supported)
        assertFalse(version(3, 20).supported)
    }

    @Test
    fun `Status V2 from 4_11 - a 4_10 pump gives the battery by Status V1`() {
        assertFalse(version(4, 10).hasStatusV2)
        assertTrue(version(4, 11).hasStatusV2)
        assertTrue(version(5, 0).hasStatusV2)
    }

    @Test
    fun `a pump the driver does not work with takes no command`() {
        val state = ApexPumpState()
        assertFalse(state.unsupported)
        state.version = version(4, 9)
        assertTrue(state.unsupported)
        assertTrue(state.takesNoCommands)
        state.version = version(4, 10)
        assertFalse(state.unsupported)
    }
}
