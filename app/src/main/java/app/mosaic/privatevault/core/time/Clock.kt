package app.mosaic.privatevault.core.time

/** Injectable clock so cooldown and dedup windows are testable without waiting. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
