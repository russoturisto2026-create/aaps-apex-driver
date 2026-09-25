package app.aaps.pump.atc3.manager

import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.atc3.Atc3Const
import app.aaps.pump.atc3.Atc3Pump
import app.aaps.pump.atc3.ble.Atc3BLE
import app.aaps.pump.atc3.history.Atc3ClockWatch
import app.aaps.pump.atc3.keys.Atc3StringKey
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * When a burst of records is finished, and when it only looks finished.
 *
 * Most answers say so themselves: the last record carries a flag. The periodic bolus search does
 * not, once the pump holds more records than the search returns - `35/A3/21/01` stops at
 * [Atc3Const.PERIODIC_BOLUS_FRAMES] frames while the count byte keeps counting every record stored,
 * so with more records held than that no frame is ever flagged.
 *
 * So this answer is finished by counting frames instead. The boundary matters in both directions
 * and neither is symmetric with the other:
 *
 *  - one frame too early and the driver would speak while the pump is still talking, which is the
 *    refusal this layer was fixed to stop making;
 *  - one frame too late and the wait never ends on its own.
 *
 * Note what the rule does and does not do. It does not send anything; it only chooses which
 * silence must fall first, the 200 ms owed by a finished answer rather than the 500 ms owed by one
 * that might still be arriving. A wrong cap therefore costs a shorter pause, not no pause.
 */
class Atc3ManagerBurstCapTest : TestBaseWithProfile() {

    @Mock lateinit var atc3BLE: Atc3BLE
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var manager: Atc3Manager

    @BeforeEach
    fun setup() {
        whenever(preferences.get(Atc3StringKey.Atc3SerialNumber)).thenReturn("12345678")
        whenever(atc3BLE.write(any())).thenReturn(false)
        manager = Atc3Manager(
            aapsLogger, rxBus, preferences, dateUtil, atc3BLE, Atc3Pump(),
            Atc3Trace(aapsLogger, preferences), uiInteraction, Atc3ClockWatch(), rh
        )
    }

    @Test
    fun `an answer with no fixed size is never finished by counting`() {
        // Cap zero is how every other read is asked for: the full bolus history, the alarms, the
        // profiles. Those flag their last record, and counting must not pre-empt that.
        assertThat(manager.burstCompleteByCount(frames = 1, cap = 0)).isFalse()
        assertThat(manager.burstCompleteByCount(frames = 128, cap = 0)).isFalse()
    }

    @Test
    fun `a burst short of its cap is not finished`() {
        assertThat(manager.burstCompleteByCount(frames = 9, cap = 10)).isFalse()
    }

    @Test
    fun `a burst that has reached its cap is finished`() {
        assertThat(manager.burstCompleteByCount(frames = 10, cap = 10)).isTrue()
    }

    /** A frame beyond the cap must not leave the wait hanging for want of an exact match. */
    @Test
    fun `a burst past its cap is still finished`() {
        assertThat(manager.burstCompleteByCount(frames = 11, cap = 10)).isTrue()
    }

    @Test
    fun `nothing received is not a finished burst`() {
        assertThat(manager.burstCompleteByCount(frames = 0, cap = 10)).isFalse()
    }

    /**
     * The cap belongs to this one request. If it is ever edited, the read it belongs to changes behaviour silently, so it is pinned here
     * rather than left to a comment.
     */
    @Test
    fun `the periodic search is capped at the number the protocol documents`() {
        assertThat(Atc3Const.PERIODIC_BOLUS_FRAMES).isEqualTo(10)
        assertThat(manager.burstCompleteByCount(frames = 10, cap = Atc3Const.PERIODIC_BOLUS_FRAMES)).isTrue()
        assertThat(manager.burstCompleteByCount(frames = 9, cap = Atc3Const.PERIODIC_BOLUS_FRAMES)).isFalse()
    }
}
