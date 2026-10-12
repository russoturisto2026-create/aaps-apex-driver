package app.aaps.pump.atc3

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.atc3.protocol.Atc3Alarm

/** The words the user sees for an alarm of the pump: one place for the notification and the history. */
object Atc3AlarmText {

    /** The name of the alarm with [code], or "alarm code N" for one the driver does not know. */
    fun label(rh: ResourceHelper, code: Int): String =
        Atc3Alarm.ofCode(code)?.let { rh.gs(labelRes(it)) } ?: rh.gs(R.string.atc3_alarm_unknown, code)

    fun labelRes(alarm: Atc3Alarm): Int = when (alarm) {
        Atc3Alarm.LOW_BATTERY            -> R.string.atc3_alarm_low_battery
        Atc3Alarm.BLOOD_GLUCOSE_REMINDER -> R.string.atc3_alarm_bg_reminder
        Atc3Alarm.BUTTON_ERROR           -> R.string.atc3_alarm_button_error
        Atc3Alarm.NO_DELIVERY            -> R.string.atc3_alarm_no_delivery
        Atc3Alarm.MOTOR_ERROR            -> R.string.atc3_alarm_motor_error
        Atc3Alarm.RESERVOIR_EMPTY        -> R.string.atc3_alarm_reservoir_empty
        Atc3Alarm.DAILY_LIMIT            -> R.string.atc3_alarm_daily_limit
    }
}

/**
 * What the user sees as the source of a limit or a stop set by this driver: AAPS shows the class name
 * of the object the reason came from, so the reason is given from here and reads "Apex: …".
 */
object Apex
