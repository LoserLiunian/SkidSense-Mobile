package com.skidsense.mobile.platform

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.skidsense.mobile.store.AndroidFileStore
import com.skidsense.mobile.store.AndroidSecretStore
import com.skidsense.mobile.store.FileStore
import com.skidsense.mobile.store.SecretStore
import java.util.UUID

class AndroidDevicePlatform(private val context: Context) : DevicePlatform {
    override val secrets: SecretStore by lazy { AndroidSecretStore(context) }
    override val files: FileStore by lazy { AndroidFileStore(context) }
    override val name: String = "android"
    override val model: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    override fun installId(): String = files.read("install-id") ?: UUID.randomUUID().toString().also { files.write("install-id", it) }
}

@Composable
actual fun rememberPairScanner(): PairScanner? {
    // The result callback is a lambda the launcher captures once, so it needs
    // somewhere out of composition to find the current caller.
    val pending = remember { ScannerHolder() }
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        pending.current?.invoke(result.contents)
        pending.current = null
    }
    return remember(launcher) {
        PairScanner { onResult ->
            pending.current = onResult
            launcher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .setPrompt("把电脑上的二维码放进取景框")
            )
        }
    }
}

private class ScannerHolder {
    var current: ((String?) -> Unit)? = null
}
