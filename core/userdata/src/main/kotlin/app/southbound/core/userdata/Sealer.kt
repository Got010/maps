package app.southbound.core.userdata

/** Encrypts the user data file. Pluggable so the store can be tested without a phone. */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** No encryption: tests only. */
object PlainSealer : Sealer {
    override fun seal(plain: ByteArray): ByteArray = plain
    override fun open(sealed: ByteArray): ByteArray = sealed
}
