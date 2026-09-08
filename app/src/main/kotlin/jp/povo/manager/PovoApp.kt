package jp.povo.manager

import android.app.Application
import android.os.Build
import android.util.Log
import jp.povo.manager.core.DeviceId
import jp.povo.manager.core.PovoAccountClient
import jp.povo.manager.notify.SuspensionNotifier
import jp.povo.manager.work.RefreshWorker

class PovoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        nativeSelfCheck()
        // Registered up front so the channel exists in system settings whether
        // or not a warning has ever fired.
        SuspensionNotifier.ensureChannel(this)
        RefreshWorker.schedule(this)
    }

    /**
     * Constructs one throwaway client so that a missing or wrong-ABI
     * `libpovo_core.so` surfaces here, at startup, with a readable message —
     * rather than as an `UnsatisfiedLinkError` from deep inside a coroutine the
     * first time someone tries to log in. No network is touched.
     */
    private fun nativeSelfCheck() {
        runCatching {
            PovoAccountClient.create(
                accountId = "selfcheck",
                deviceId = DeviceId.generate(),
            ).use { it.deviceId }
        }.fold(
            onSuccess = {
                Log.i(TAG, "povo-core loaded (abis=${Build.SUPPORTED_ABIS.joinToString()})")
            },
            onFailure = {
                Log.e(TAG, "povo-core FAILED to load for ${Build.SUPPORTED_ABIS.joinToString()}", it)
            },
        )
    }

    private companion object {
        const val TAG = "PovoCore"
    }
}
