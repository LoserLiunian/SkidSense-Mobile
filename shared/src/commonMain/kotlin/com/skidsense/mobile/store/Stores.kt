package com.skidsense.mobile.store

/**
 * Secrets at rest: the device's static X25519 private key, the login tokens,
 * cached grants. On Android every value is sealed with an AES-256-GCM key that
 * lives in the Android Keystore and cannot be exported; only the wrapped blob
 * touches app-private storage. See `AndroidSecretStore`.
 */
interface SecretStore {
    fun get(name: String): ByteArray?
    fun put(name: String, value: ByteArray)
    fun delete(name: String)
}

/** Non-secret app-private files (paired hosts, cached ciphertext). */
interface FileStore {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun delete(name: String)
    /** Names under [prefix] (a `dir/` style prefix). */
    fun list(prefix: String): List<String>
}

/** For tests and previews. Not persistent. */
class MemorySecretStore : SecretStore {
    private val map = HashMap<String, ByteArray>()
    override fun get(name: String): ByteArray? = map[name]?.copyOf()
    override fun put(name: String, value: ByteArray) { map[name] = value.copyOf() }
    override fun delete(name: String) { map.remove(name) }
}

class MemoryFileStore : FileStore {
    private val map = HashMap<String, String>()
    override fun read(name: String): String? = map[name]
    override fun write(name: String, text: String) { map[name] = text }
    override fun delete(name: String) { map.remove(name) }
    override fun list(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }.sorted()
}

fun SecretStore.getString(name: String): String? = get(name)?.decodeToString()
fun SecretStore.putString(name: String, value: String) = put(name, value.encodeToByteArray())
