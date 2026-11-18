package app.aaps.pump.apex.state

/**
 * What the probe at the last link setup found in the pump's firmware, for the user to see, see
 * [AbilityProbe]. Only the screen reads it: the account asks [ApexAbilities]. Each object is true
 * when the pump answered its read, false when it stayed silent while Status V1 answered, null when
 * that could not be told (Status V1 was silent too).
 */
data class FirmwareDiagnosis(
    /** When the probe ran, phone clock. */
    val probedAtMs: Long,
    /** Status V2 (0x0C). */
    val statusV2: Boolean?,
    /** The last temporary basal command (0x0A). */
    val lastTbrCommand: Boolean?,
    /** The last finished temporary basal (0x0B). */
    val finishedTbr: Boolean?,
    /** The daily statistics (0x26). */
    val dayRecord: Boolean?,
    /** The temporary basal history (0x27). */
    val tbrHistory: Boolean?,
    /** The last bolus record alone: the bolus journal (0x01) asked with parameter 00. */
    val lastBolusRecord: Boolean?,
    /** The pump has the authorisation service (ffc0); null before a link was set up. */
    val passwordService: Boolean?,
    /** The pump's protocol is the password protocol or newer; null while the version is not known. */
    val passwordProtocol: Boolean?,
    /** The link was set up with a password. */
    val passwordSent: Boolean?
)
