package app.aaps.pump.apex.bolus

import app.aaps.pump.apex.protocol.ApexProtocol
import app.aaps.pump.apex.protocol.ApexResponseFrame
import java.util.Calendar
import java.util.TimeZone

/** Bolus record frames as the pump answers them, for the tests of the account. */
object BolusRecordFrames {

    /**
     * A bolus record frame stamped at [minuteKey] (a UTC minute in seconds), with raw doses and the
     * seconds byte the pump puts there, under [objectType]: the newest-records read by default, or
     * the whole journal.
     */
    fun record(
        minuteKey: Long, requestedRaw: Int, deliveredRaw: Int, extendedRaw: Int = 0, seconds: Int = 59,
        objectType: Byte = ApexProtocol.HistoryOpcode.LATEST_BOLUS
    ): BolusCells.Answered {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = minuteKey * 1000L }
        val raw = ByteArray(22)
        raw[0] = ApexResponseFrame.MARKER
        raw[1] = 0x16
        raw[2] = 0x7F
        raw[3] = 0xA3.toByte()
        raw[4] = objectType
        raw[5] = 0
        raw[6] = (c.get(Calendar.YEAR) - 2000).toByte()
        raw[7] = (c.get(Calendar.MONTH) + 1).toByte()
        raw[8] = c.get(Calendar.DAY_OF_MONTH).toByte()
        raw[9] = c.get(Calendar.HOUR_OF_DAY).toByte()
        raw[10] = c.get(Calendar.MINUTE).toByte()
        raw[11] = seconds.toByte()
        raw[12] = (requestedRaw and 0xFF).toByte(); raw[13] = (requestedRaw shr 8).toByte()
        raw[14] = (deliveredRaw and 0xFF).toByte(); raw[15] = (deliveredRaw shr 8).toByte()
        raw[16] = (extendedRaw and 0xFF).toByte(); raw[17] = (extendedRaw shr 8).toByte()
        raw[18] = (extendedRaw and 0xFF).toByte(); raw[19] = (extendedRaw shr 8).toByte()
        return BolusCells.Answered(ApexResponseFrame(raw), minuteKey + seconds)
    }
}
