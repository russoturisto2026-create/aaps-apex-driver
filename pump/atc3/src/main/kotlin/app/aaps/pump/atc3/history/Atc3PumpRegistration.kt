package app.aaps.pump.atc3.history

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.atc3.R
import app.aaps.pump.atc3.state.Atc3PumpState
import javax.inject.Inject
import javax.inject.Singleton

/** That AAPS has adopted this pump before anything is written for it. */
@Singleton
class Atc3PumpRegistration @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val pumpSync: PumpSync,
    private val pumpState: Atc3PumpState
) {

    private val serial: String get() = pumpState.serialNumber

    /**
     * Make sure AAPS has adopted this pump: it refuses treatments of a pump it has not registered, and the
     * first bolus imported would be refused and never offered again.
     *
     * @return true when AAPS accepts treatments for this pump
     */
    suspend fun ensureRegistered(): Boolean {
        if (pumpSync.verifyPumpIdentification(PumpType.ATC3, serial)) return true
        aapsLogger.debug(LTag.PUMP, "ATC3: registering the pump with AAPS before importing anything")
        pumpSync.insertAnnouncement(
            error = rh.gs(R.string.atc3_history_start),
            pumpId = null,
            pumpType = PumpType.ATC3,
            pumpSerial = serial
        )
        // Fails while AAPS holds another pump: said once here.
        val registered = pumpSync.verifyPumpIdentification(PumpType.ATC3, serial)
        if (!registered) {
            aapsLogger.error(
                LTag.PUMP,
                "ATC3: AAPS has not adopted this pump, so it will refuse everything written for it. " +
                    "Nothing is imported and nothing is marked as counted, so the pump's history is " +
                    "still there once that is resolved."
            )
        }
        return registered
    }
}
