package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.protocol.ApexBolusRecord

/**
 * The key of a bolus row in AAPS, made from the pump's record itself: the minute of its stamp, what it
 * asked for, what it delivered, and the repeat, the count of records with the same three before it in
 * the journal. The same record always gives the same key: after a long break, a new phone or a lost
 * driver base the row lays over itself in AAPS. Our record number and the time of reading are not in it.
 * The seconds of the stamp are not either: the pump may write them again.
 *
 * Read in decimal: minute since 2000, then 5 digits asked, 5 digits delivered, 1 digit repeat.
 */
object BolusKey {

    /** The three parts of the key that come from the record; two records with the same ones differ by their repeat. */
    fun sameAs(record: ApexBolusRecord): Triple<Long, Int, Int> =
        Triple(Math.floorDiv(record.clockSeconds, MINUTE_SECONDS), record.rawRequested, record.rawDelivered)

    /** The key of [record], the [repeat]th of its kind in the journal. */
    fun of(record: ApexBolusRecord, repeat: Int): Long {
        val (minute, asked, delivered) = sameAs(record)
        val sinceStart = Math.floorMod(minute - START_MINUTE, MINUTES_KEPT)
        return ((sinceStart * RAW_SLOT + asked % RAW_SLOT) * RAW_SLOT + delivered % RAW_SLOT) * REPEAT_SLOT + repeat.coerceAtMost(REPEAT_SLOT.toInt() - 1)
    }

    private const val MINUTE_SECONDS = 60L

    /** 2000-01-01 00:00 on the stamp's calendar, in minutes: the pump's years start there. */
    private const val START_MINUTE = 946_684_800L / MINUTE_SECONDS

    /** Raw amounts take 5 digits each, the repeat 1: the minute keeps the rest of a Long. */
    private const val RAW_SLOT = 100_000L
    private const val REPEAT_SLOT = 10L

    /** About 170 years of minutes: every stamp a pump writes fits, a broken one is folded in. */
    private const val MINUTES_KEPT = 90_000_000L
}
