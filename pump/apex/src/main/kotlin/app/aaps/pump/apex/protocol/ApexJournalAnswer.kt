package app.aaps.pump.apex.protocol

/** One record of a journal answer: the frame as it came and the record decoded from it. */
class ApexRead<T>(val frame: ApexResponseFrame, val record: T)

/**
 * One answer to a journal read: its records in arrival order, newest first, and the record count
 * the pump declared. A count above the records sent says the answer is the newest part only.
 */
class ApexJournalAnswer<T>(val entries: List<ApexRead<T>>, val recordCount: Int) {

    /** True when as many records came as the pump said it holds: nothing of a whole journal was lost on the way. */
    val cameWhole: Boolean get() = entries.size == recordCount

    /** The pump said it holds no record: the journal is empty. A state of the pump, not a silence. */
    val empty: Boolean get() = recordCount == 0
}
