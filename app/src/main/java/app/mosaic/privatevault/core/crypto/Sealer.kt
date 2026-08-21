package app.mosaic.privatevault.core.crypto

/**
 * Narrow seam over [KeystoreCrypto] so that everything above it can be unit
 * tested on the JVM, where there is no Android Keystore.
 */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

class KeystoreSealer(private val crypto: KeystoreCrypto) : Sealer {
    override fun seal(plain: ByteArray): ByteArray = crypto.seal(plain)
    override fun open(sealed: ByteArray): ByteArray = crypto.open(sealed)
}
