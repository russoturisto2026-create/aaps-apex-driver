package app.aaps.pump.apex.basal

/**
 * How the basal is written into AAPS: as the commands ordered it, or as the pump counted it from
 * one heartbeat to the next, see [BasalPeriod]. The length of the period is the period of the
 * heartbeat the user chose, see `ApexIntKey.HeartbeatPeriod`.
 */
enum class ExactBasalMode(val value: String) {

    /** The rows stay as the commands made them. */
    OFF("off"),

    /** A period is one heartbeat to the next, whatever the heartbeat's period is. */
    HEARTBEAT("heartbeat");

    companion object {

        /** The mode the setting holds; anything else is off. */
        fun of(value: String): ExactBasalMode = entries.firstOrNull { it.value == value } ?: OFF
    }
}
