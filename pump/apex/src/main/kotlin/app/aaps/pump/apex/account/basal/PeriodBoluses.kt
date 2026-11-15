package app.aaps.pump.apex.account.basal

import app.aaps.pump.apex.account.store.ApexStore
import app.aaps.pump.apex.protocol.ApexProtocol

/**
 * The bolus rows written since the period under way began, as pairs of row id and amount in pump
 * steps, kept in the store. A period counted from the pump's count takes these off the move of the
 * count. A row is counted by when it is written, not by its own time: a bolus that runs across a
 * heartbeat is in the count before its row exists, and the row, written later, carries the time the
 * bolus started, which lies in a period that is already closed.
 */
class PeriodBoluses(private val store: ApexStore, private val serial: String) {

    /** Add the row [rowId] of [units], or replace the amount when the row is already held. */
    fun add(rowId: Long, units: Double) {
        val rows = load().toMutableMap()
        rows[rowId] = Math.round(units / ApexProtocol.DOSE_SCALE)
        save(rows)
    }

    /** The sum of the rows held, U. */
    fun sum(): Double = load().values.sum() * ApexProtocol.DOSE_SCALE

    /** Hold nothing. */
    fun clear() = save(emptyMap())

    private fun load(): Map<Long, Long> =
        store.state(serial, STATE_KEY)?.split(PAIR_SEPARATOR)?.mapNotNull { pair ->
            val parts = pair.split(ID_SEPARATOR)
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val steps = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            id to steps
        }?.toMap() ?: emptyMap()

    private fun save(rows: Map<Long, Long>) =
        store.putState(serial, STATE_KEY, rows.takeIf { it.isNotEmpty() }?.entries?.joinToString(PAIR_SEPARATOR) { "${it.key}$ID_SEPARATOR${it.value}" })

    companion object {

        /** The store's key of the rows held. */
        const val STATE_KEY = "periodBoluses"

        private const val PAIR_SEPARATOR = ";"
        private const val ID_SEPARATOR = ":"
    }
}
