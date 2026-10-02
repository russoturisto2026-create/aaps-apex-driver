package app.aaps.pump.atc3.comm

/**
 * How well the last link to the pump was protected.
 *
 * The pump's Bluetooth password is the only thing standing between it and anybody within radio
 * range: there is no pairing, no bonding and no link encryption, and acceptance lasts for the link
 * rather than for the client that earned it. A pump with no password set therefore takes commands
 * from whoever asks first, which is worth telling the user about.
 *
 * The state is worked out from what the pump did while the link was coming up rather than from
 * what is configured, because only the pump knows whether it is asking for a password: it answers
 * `000000` with "accepted" when it has none set, and refuses it when it has.
 */
enum class Atc3LinkProtection {

    /** No link has come up yet, so nothing is known. */
    UNKNOWN,

    /** The pump accepted a password of the user's own, so nobody else can talk to it. */
    PROTECTED,

    /** The pump accepted `000000`, which is what it does when no password is set on it. */
    UNPROTECTED,

    /**
     * The pump has no authorisation service at all, so no password can protect it.
     *
     * Firmware older than [app.aaps.pump.atc3.Atc3Const.PASSWORD_FIRMWARE] has no password feature,
     * and there is nothing the user can do about that from here beyond updating the pump.
     */
    UNSUPPORTED
}
