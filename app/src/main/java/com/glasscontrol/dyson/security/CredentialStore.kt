package com.glasscontrol.dyson.security

/**
 * Where the local MQTT credential lives.
 *
 * An interface so the control path can be exercised end to end in tests without
 * a hardware-backed Keystore, which no JVM test host provides.
 */
interface CredentialStore {

    /** @return true when the credential was stored and can be read back. */
    suspend fun putCredential(serial: String, credential: String): Boolean

    suspend fun getCredential(serial: String): String?

    suspend fun clear()
}
