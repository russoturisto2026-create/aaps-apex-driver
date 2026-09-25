package app.aaps.pump.atc3.ble

import android.content.Context
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.atc3.keys.Atc3BooleanKey
import app.aaps.pump.atc3.trace.Atc3Trace
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

/**
 * That a link held open is a link still being watched.
 *
 * The pump sends a heartbeat every 180 s whether or not anything else is happening, and that is
 * what makes holding the link between exchanges safe rather than hopeful. Without a signal from the
 * far side, a driver reporting itself connected on a link that had quietly died would let AAPS
 * believe its commands were reaching a pump that is not listening - and a temporary basal that was
 * never delivered is worse than one that visibly failed.
 */
class Atc3BleHeartbeatTest : TestBase() {

    @Mock lateinit var context: Context
    @Mock lateinit var preferences: Preferences

    private lateinit var ble: Atc3BLE

    @BeforeEach
    fun setup() {
        whenever(preferences.get(Atc3BooleanKey.Trace)).thenReturn(false)
        ble = Atc3BLE(aapsLogger, context, Atc3Trace(aapsLogger, preferences))
    }

    @Test
    fun `a pump that has never been heard from has no silence to measure`() {
        assertThat(ble.quietForMs).isEqualTo(-1L)
    }

    @Test
    fun `a heartbeat is remembered as the moment the pump was last heard`() {
        ble.noteHeartbeat()

        val age = ble.quietForMs
        assertThat(age).isAtLeast(0L)
        assertThat(age).isLessThan(1_000L)
    }

    @Test
    fun `each heartbeat replaces the last, so the silence is measured from the newest`() {
        ble.noteHeartbeat()
        Thread.sleep(20)
        ble.noteHeartbeat()

        assertThat(ble.quietForMs).isLessThan(20L)
    }

    /**
     * An answer to a read proves the pump is there exactly as well as an unprompted beat does, and
     * while anything is being asked, answers are all there is. Counting only beats would call a
     * busy link quiet.
     */
    @Test
    fun `an answer proves the link just as a heartbeat does`() {
        ble.noteHeardFrom()

        assertThat(ble.quietForMs).isAtLeast(0L)
        assertThat(ble.quietForMs).isLessThan(1_000L)
    }

    /**
     * A link that ended takes its history with it. Carrying a stale timestamp into the next
     * connection would make a fresh link look proved before the pump had said anything on it.
     */
    @Test
    fun `an ended link leaves no heartbeat behind it`() {
        ble.noteHeartbeat()

        ble.disconnect()

        assertThat(ble.quietForMs).isEqualTo(-1L)
    }
}
