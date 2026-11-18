package app.aaps.pump.apex.state

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.manager.ApexManager
import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.trace.ApexTrace
import app.aaps.pump.apex.trace.ApexTraceCat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import app.aaps.pump.apex.ApexConst
import app.aaps.pump.apex.link.ApexBLE

/**
 * Finds out at every link setup which optional objects a pump has, see [ApexAbilities], so that
 * an object taken as missing by mistake is found again. Each is asked for: any
 * frame of that object in answer, whatever it holds, says the pump has it. When none came, Status V1
 * is read: if it is silent too, the link is the problem, nothing is stored, and the probe runs again
 * at the next link setup. If it answered, the object is asked once more, and when that is silent
 * again and Status V1 answers again, the object is missing on this pump. So an object is stored as
 * missing only after two rounds of silence with Status V1 answering after each.
 * The silence of a probe is not counted toward giving up the link, see `ApexExchange`.
 *
 * Then, for the user only, what else the firmware has, see [FirmwareDiagnosis]: the temporary basal
 * history and the last bolus record alone are asked the same way, and the password is told by the link
 * setup and the protocol. The account does not use these.
 */
@SingleIn(AppScope::class)
@Inject
class AbilityProbe(
    private val aapsLogger: AAPSLogger,
    private val manager: ApexManager,
    private val store: ApexStore,
    private val pumpState: ApexPumpState,
    private val apexBLE: ApexBLE,
    private val trace: ApexTrace
) {

    /** Probe when the pump and its firmware are known. What is stored changes only when the answer does. */
    fun probe() {
        val serial = pumpState.serialNumber
        val firmware = pumpState.version?.firmwareText
        if (serial.isEmpty() || firmware == null) return
        val lastTbrCommand = has("last temporary basal command") { manager.answersRead(ApexProtocol.ReadOpcode.TBR_ACTIVE) } ?: return
        val finishedTbr = has("last finished temporary basal") { manager.answersRead(ApexProtocol.ReadOpcode.TBR_FINISHED) } ?: return
        val dayRecord = has("daily statistics") { manager.answersLatestRecords(ApexProtocol.HistoryOpcode.LATEST_DAILY_STATS) } ?: return
        val statusV2 = has("Status V2") { manager.answersRead(ApexProtocol.ReadOpcode.STATUS_V2) } ?: return
        keep(serial, firmware, ApexAbilities(lastTbrCommand, finishedTbr, dayRecord, statusV2))
        val tbrHistory = has("temporary basal history") { manager.answersLatestRecords(ApexProtocol.HistoryOpcode.TBR_HISTORY) }
        val lastBolusRecord = has("last bolus record") { manager.answersLastBolusRecord() }
        pumpState.diagnosis = FirmwareDiagnosis(
            probedAtMs = System.currentTimeMillis(),
            statusV2 = statusV2, lastTbrCommand = lastTbrCommand, finishedTbr = finishedTbr, dayRecord = dayRecord,
            tbrHistory = tbrHistory, lastBolusRecord = lastBolusRecord,
            passwordService = apexBLE.authServiceFound,
            passwordProtocol = pumpState.version?.protocolAtLeast(ApexConst.PASSWORD_PROTOCOL_MAJOR, ApexConst.PASSWORD_PROTOCOL_MINOR),
            passwordSent = apexBLE.passwordSent
        )
        aapsLogger.info(LTag.PUMP, "Apex: the firmware also has: temporary basal history $tbrHistory, last bolus record $lastBolusRecord; password service ${apexBLE.authServiceFound}")
    }

    /** The abilities the account goes by, [abilities], stored for this pump and firmware. */
    private fun keep(serial: String, firmware: String, abilities: ApexAbilities) {
        ApexAbilities.store(aapsLogger, store, serial, firmware, abilities)
        aapsLogger.info(LTag.PUMP, "Apex: the pump has: ${abilities.text}")
        trace.event(
            ApexTraceCat.STATE, "abilities",
            "lastTbrCommand" to abilities.lastTbrCommand, "finishedTbr" to abilities.finishedTbr, "dayRecord" to abilities.dayRecord, "statusV2" to abilities.statusV2
        )
    }

    /**
     * Whether the pump has the object [what], by [asked], which tells whether any frame of it came.
     * Null when that cannot be told because Status V1 is silent.
     */
    private fun has(what: String, asked: () -> Boolean): Boolean? {
        if (asked()) return true
        if (manager.readStatus() == null) return cannotTell(what)
        aapsLogger.info(LTag.PUMP, "Apex: no answer to the read of the $what, asking once more")
        if (asked()) return true
        if (manager.readStatus() == null) return cannotTell(what)
        aapsLogger.info(LTag.PUMP, "Apex: the pump does not answer the read of the $what")
        return false
    }

    private fun cannotTell(what: String): Boolean? {
        aapsLogger.warn(LTag.PUMP, "Apex: no answer to the probe of the $what and Status V1 is silent too; the pump is probed again at the next link setup")
        return null
    }
}
