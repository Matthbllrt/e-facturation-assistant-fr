package com.glasscontrol.dyson.core

/** Every failure the user can plausibly hit, expressed in domain terms. */
sealed class DysonError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No usable route to the device: wrong IP, device asleep, different Wi-Fi. */
    class Unreachable(cause: Throwable? = null) : DysonError("Appareil injoignable", cause)

    /** The broker rejected our serial/credential pair. */
    class InvalidCredential : DysonError("Identifiants locaux refusés par l'appareil")

    /** Connected, but the device never answered within the timeout. */
    class Timeout : DysonError("L'appareil n'a pas répondu à temps")

    /** The phone is not on a Wi-Fi/local network at all. */
    class NoLocalNetwork : DysonError("Aucun réseau local disponible")

    /** Cloud account problems, surfaced verbatim to the onboarding UI. */
    class Cloud(message: String, cause: Throwable? = null) : DysonError(message, cause)

    /** Nothing configured yet. */
    class NotConfigured : DysonError("Aucun Dyson configuré")
}
