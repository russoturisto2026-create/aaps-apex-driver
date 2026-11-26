package app.aaps.pump.apex.exchange

/**
 * How many times one object of the pump is read, whatever the object. Only reads: a command is never
 * sent twice. What makes a read worth reading again is in `ReadAgain`.
 */
object ReadRule {

    /** How many times one object is read in all before the driver stops trying. */
    const val READS = 3

    /** True when a single object is read again after [asked] reads: the last one brought nothing sound, [fault], and reads are left. */
    fun asksAgain(fault: Boolean, asked: Int): Boolean = fault && asked < READS

    /** True when one of [READS] asks, [ask] given the number of the ask from 1, was answered: for an answer of several frames with no records to judge. */
    fun untilAnswered(ask: (Int) -> Boolean): Boolean = (1..READS).any(ask)
}
