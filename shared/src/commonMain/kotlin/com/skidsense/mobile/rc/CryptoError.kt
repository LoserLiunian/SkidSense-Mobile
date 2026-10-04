package com.skidsense.mobile.rc

/**
 * A protocol or cryptographic failure. [code] is machine-readable and matches
 * the desktop reference's codes (`bad-encoding`, `bad-key`, `bad-frame`,
 * `handshake-failed`, `replayed`, `out-of-order`, `rekey`…); the message is the
 * user-facing Chinese text.
 */
class CryptoError(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)
