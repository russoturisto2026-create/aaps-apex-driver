package app.aaps.pump.atc3.state

import app.aaps.pump.atc3.protocol.Atc3Settings
import app.aaps.pump.atc3.protocol.Atc3StatusV1

/** The status the tests start from: running, nothing raised, no temporary basal. */
fun testStatus(): Atc3StatusV1 = Atc3StatusV1(
    activeProfileIndex = 0,
    snapshotTime = 0L,
    reservoirUnits = 0.0,
    deliveredTodayUnits = 0.0,
    lastStop = null,
    tbrElapsedMinutes = 0,
    scheduledBasalRate = 0.0,
    suspended = false,
    locked = false,
    tbrActive = false,
    tbrRate = 0.0,
    tbrMode = 0,
    activeAlarmCodes = emptyList(),
    tbrDurationMinutes = 0
)

/**
 * Put a changed status in place, as a new report would: what [change] does not touch stays as it
 * was. Whether the pump counts as heard from is left as it was, too.
 */
fun Atc3PumpState.editStatus(
    readAtMs: Long = statusReadAtMs,
    settings: Atc3Settings? = this.settings,
    change: (Atc3StatusV1) -> Atc3StatusV1 = { it }
) {
    val heardFrom = lastConnection
    applyStatus(change(lastStatus ?: testStatus()), readAtMs, settings)
    lastConnection = heardFrom
}

/** The status report in place, a default one when the test has not set any. */
fun Atc3PumpState.currentCard(): Atc3PumpState.StatusCard = statusCard ?: run {
    editStatus()
    statusCard!!
}
