package com.skidsense.mobile.platform

import androidx.compose.runtime.Composable
import com.skidsense.mobile.store.FileStore
import com.skidsense.mobile.store.MemoryFileStore
import com.skidsense.mobile.store.MemorySecretStore
import com.skidsense.mobile.store.SecretStore
import platform.Foundation.NSUserDefaults
import kotlin.random.Random

/**
 * iOS: the Keychain for secrets and the app container for files are TODO.
 * TODO(iOS): back `secrets` with the Keychain (`kSecClassGenericPassword`,
 * accessible-after-first-unlock) and `files` with `NSFileManager` under
 * `NSApplicationSupportDirectory`; the temporary in-memory stores below mean
 * nothing survives a relaunch on iOS yet.
 */
class IosDevicePlatform : DevicePlatform {
    override val secrets: SecretStore = MemorySecretStore()
    override val files: FileStore = MemoryFileStore()
    override val name: String = "ios"
    override val model: String = platform.UIKit.UIDevice.currentDevice.name
    override fun installId(): String {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.stringForKey("skidsense-install-id")?.let { return it }
        val fresh = List(32) { Random.nextInt(16).toString(16) }.joinToString("")
        defaults.setObject(fresh, "skidsense-install-id")
        return fresh
    }
}

/** TODO(iOS): a VisionKit `VNDocumentCameraViewController` / `AVCaptureSession` scanner. */
@Composable
actual fun rememberPairScanner(): PairScanner? = null
