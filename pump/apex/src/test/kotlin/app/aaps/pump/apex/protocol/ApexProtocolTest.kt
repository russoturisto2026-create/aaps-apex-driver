package app.aaps.pump.apex.protocol

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApexProtocolTest {

    @Test
    fun `a dose read from steps is the same as the one asked, a neighbour step is not`() {
        // 40 steps of 0.025 U do not make exactly 1.0 in a Double.
        assertTrue(ApexProtocol.sameDose(40 * ApexProtocol.DOSE_SCALE, 1.0))
        assertTrue(ApexProtocol.sameDose(0.1 + 0.2, 0.3))
        assertFalse(ApexProtocol.sameDose(1.025, 1.0))
        assertFalse(ApexProtocol.sameDose(0.975, 1.0))
    }
}
