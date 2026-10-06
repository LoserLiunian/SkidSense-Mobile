package com.skidsense.mobile.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyNotYetValidException
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.skidsense.mobile.rc.CryptoError
import com.skidsense.mobile.rc.Primitives
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets sealed with a key that lives in the Android Keystore and cannot be
 * exported: the device's static X25519 private key, the access token, the
 * refresh cookie, cached grants.
 *
 * Why wrap rather than encrypt each value with the Keystore key directly: a
 * Keystore AES-GCM key can be used without being readable, which is what we
 * want, but it is bound to the device and lost on uninstall *or on a Keystore
 * reset* — and it cannot be backed up. So the design is the brief's:
 *
 *   - a random 32-byte **master key** is generated once, in software;
 *   - the master key itself is sealed with a **non-exportable Keystore key**
 *     (`EncryptionKey` below) and only that ciphertext is written to disk;
 *   - each secret is sealed under the master key with AES-256-GCM and stored
 *     in the app's private shared preferences, which on its own is world-
 *     inaccessible but readable on a rooted or debuggable device.
 *
 * Two failure shapes are told apart, because the fix is opposite:
 *
 *   - **Definitely undecryptable** — the unwrap fails the tag check
 *     (`AEADBadTagException`) or the alias is gone while a wrapped master is
 *     still on disk. This is the migration case: Android 12+'s D2D copy and
 *     vendor clone tools carry the shared prefs but never the Keystore key, so
 *     the master here can *never* be unwrapped again. The whole store is
 *     dropped and rebuilt ([BrokenStoreRestorer.restoreIfBroken]): every later
 *     save failing with the same error is the alternative, and the caller
 *     treats the result as signed-out and unpaired.
 *   - **Transiently failing** — anything else the Keystore throws. Then the
 *     error propagates: a `put` must not pretend the store is empty, and the
 *     identity read above all must not regenerate the device key over the old
 *     one — when the next call succeeds the hosts would meet a stranger.
 *
 * No user authentication requirement is set on the Keystore key, so a locked
 * phone can still reconnect in the background; the app-level biometric lock
 * (see `BiometricGate`) is a separate, user-controlled gate.
 */
class AndroidSecretStore(private val context: Context) : SecretStore, BrokenStoreRestorer {
    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "skidsense-mobile-master"
        const val WRAP_AAD = "skidsense-mobile master v1"
        const val PREFS = "skidsense-secrets"
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** The Keystore key that seals the master key. Created on first use. */
    private fun encryptionKey(): javax.crypto.SecretKey {
        val store = keystore()
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun hasAlias(): Boolean = runCatching { keystore().containsAlias(KEY_ALIAS) }.getOrDefault(false)

    /** The software master key, unsealed from the Keystore, created on first use. */
    private fun masterKey(): ByteArray {
        val stored = prefs.getString("master", null)
        if (stored != null) {
            try {
                val blob = Base64.decode(stored, Base64.NO_WRAP)
                if (blob.size < 12 + 16) throw CryptoError("broken-store", "主密钥数据无效")
                val key = encryptionKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
                cipher.updateAAD(WRAP_AAD.encodeToByteArray())
                val unwrapped = cipher.doFinal(blob, 12, blob.size - 12)
                if (unwrapped.size != 32) throw CryptoError("broken-store", "主密钥数据无效")
                return unwrapped
            } catch (error: Throwable) {
                throw classify(error)
            }
        }
        try {
            val fresh = Primitives.randomBytes(32)
            val key = encryptionKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(WRAP_AAD.encodeToByteArray())
            val sealed = cipher.iv + cipher.doFinal(fresh)
            prefs.edit().putString("master", Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
            return fresh
        } catch (error: Throwable) {
            throw CryptoError("keystore", "系统密钥库暂时不可用，请重试", error)
        }
    }

    /**
     * `"broken-store"` when the master can never be unwrapped again and the
     * store should be thrown away; `"keystore"` (retryable) for everything
     * transient. A destroyed alias counts as broken only while a wrapped
     * master still waits for it — with no master at all, a fresh one is made.
     */
    private fun classify(error: Throwable): CryptoError {
        val broken = when (causeOf<AEADBadTagException>(error)) {
            true -> true
            false -> causeOf<KeyPermanentlyInvalidatedException>(error) || !hasAlias()
        }
        return if (broken) {
            CryptoError("broken-store", "系统密钥库无法解密已保存的密钥", error)
        } else {
            CryptoError("keystore", "系统密钥库暂时不可用，请重试", error)
        }
    }

    private inline fun <reified T : Throwable> causeOf(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is T) return true
            if (current.cause === current) break
            current = current.cause
        }
        return false
    }

    override fun get(name: String): ByteArray? {
        val stored = prefs.getString(name, null) ?: return null
        val blob = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (blob.size < 12 + 16) return null
        // A broken or unavailable Keystore throws here rather than reads as
        // null — above all the identity key, which must never regenerate over
        // a read that merely failed (S27).
        val master = masterKey()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(master, "AES"), GCMParameterSpec(128, blob, 0, 12))
            cipher.updateAAD(name.encodeToByteArray())
            cipher.doFinal(blob, 12, blob.size - 12)
        } catch (_: Exception) {
            // Tampered, or a different master key: forget the value rather than
            // hand back something that did not authenticate.
            prefs.edit().remove(name).apply()
            null
        }
    }

    override fun put(name: String, value: ByteArray) {
        val master = try {
            masterKey()
        } catch (error: CryptoError) {
            if (error.code == "broken-store") {
                throw CryptoError("no-keystore", "系统密钥库不可用，无法保存密钥", error)
            }
            throw CryptoError("no-keystore", "系统密钥库暂时不可用，请重试", error)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(master, "AES"))
        cipher.updateAAD(name.encodeToByteArray())
        val sealed = cipher.iv + cipher.doFinal(value)
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
    }

    override fun delete(name: String) {
        prefs.edit().remove(name).commit()
    }

    /**
     * Wipe and rebuild *only* when the store demonstrably cannot be decrypted
     * by this install (S27): the wrapped master is there and fails the tag
     * check, or its alias is gone. The caller treats true as signed-out and
     * unpaired. A transient Keystore error throws instead — nothing is wiped
     * on a maybe.
     */
    override fun restoreIfBroken(): Boolean {
        val stored = prefs.getString("master", null) ?: return false
        val blob = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }
        val broken = when {
            blob == null || blob.size < 12 + 16 -> true
            !hasAlias() -> true
            else -> try {
                val key = encryptionKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
                cipher.updateAAD(WRAP_AAD.encodeToByteArray())
                cipher.doFinal(blob, 12, blob.size - 12)
                false
            } catch (error: Throwable) {
                when {
                    causeOf<AEADBadTagException>(error) -> true
                    causeOf<KeyPermanentlyInvalidatedException>(error) -> true
                    causeOf<UserNotAuthenticatedException>(error) -> false
                    causeOf<KeyNotYetValidException>(error) -> false
                    // Unknown: do not gamble the user's pairings on a guess.
                    else -> throw CryptoError("keystore", "系统密钥库暂时不可用，请重试", error)
                }
            }
        }
        if (!broken) return false
        prefs.edit().clear().commit()
        runCatching { keystore().deleteEntry(KEY_ALIAS) }
        return true
    }
}

/**
 * App-private files: paired hosts (not secret — the host public key is on the
 * QR code anyway), cached ciphertext blobs, settings. Stored as one file per
 * space under `filesDir`, which on Android is private to the app and excluded
 * from the media scanner.
 */
class AndroidFileStore(private val context: Context, private val dir: String = "skidsense") : FileStore {
    private fun file(name: String) = java.io.File(context.filesDir, "$dir/$name").apply {
        parentFile?.mkdirs()
    }

    override fun read(name: String): String? = file(name).takeIf { it.isFile }?.readText()

    override fun write(name: String, text: String) {
        val target = file(name)
        val temp = java.io.File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
    }

    override fun delete(name: String) {
        file(name).delete()
    }

    override fun list(prefix: String): List<String> {
        val root = java.io.File(context.filesDir, dir)
        return (root.listFiles() ?: emptyArray()).map { it.name }.filter { it.startsWith(prefix) }.sorted()
    }
}
