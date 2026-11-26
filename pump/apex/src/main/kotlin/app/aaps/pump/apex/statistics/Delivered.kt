package app.aaps.pump.apex.statistics

import app.aaps.pump.apex.protocol.ApexDailyRecord

/**
 * What the pump delivered up to a heartbeat, by kind: its daily statistics as read, newest day first.
 * The basal is the move of two of their fields. [broken]: they came with the same broken-looking day at every read, see `ReadAgain`.
 */
class Delivered(val days: List<ApexDailyRecord>, val broken: Boolean = false)
