package app.aaps.pump.apex.account

import app.aaps.pump.apex.account.basal.BasalPeriodKeeper
import app.aaps.pump.apex.account.basal.PeriodBoluses
import app.aaps.pump.apex.account.bolus.BolusAccount
import app.aaps.pump.apex.account.bolus.BolusCountMark
import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.account.store.Snapshot
import app.aaps.pump.apex.account.store.SnapshotKind

/**
 * What one heartbeat keeps for the next one to count from: the day of the statistics, the pump's
 * count, our boluses settled since, the basal period under way and its boluses. A span that began
 * one way must not end another way,
 * so when the way changes, or the account of boluses starts clean, they are all dropped together.
 * The next heartbeat then keeps them anew, as a first heartbeat does, whatever objects the pump has.
 * The copy of the bolus journal and the status of the last reconciliation are not among them.
 */
object ReferencePoints {

    fun drop(store: ApexStore, serial: String) {
        // An empty copy reads as no day kept.
        store.saveSnapshot(serial, SnapshotKind.DAILY, Snapshot(emptyList(), 0L))
        store.putState(serial, BolusAccount.STATE_SETTLED_SINCE_HEARTBEAT, null)
        store.putState(serial, BolusCountMark.STATE_KEY, null)
        store.putState(serial, BasalPeriodKeeper.STATE_PERIOD, null)
        store.putState(serial, PeriodBoluses.STATE_KEY, null)
    }
}
