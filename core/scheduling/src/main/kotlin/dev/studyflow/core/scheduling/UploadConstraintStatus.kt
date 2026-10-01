package dev.studyflow.core.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What the device can currently offer an upload, in the same terms WorkManager's constraints are
 * expressed in.
 *
 * @property connected there is a validated network of some kind.
 * @property unmetered that network is one the user is not paying by the megabyte for.
 * @property batteryLow the platform considers the battery low, which parks `setRequiresBatteryNotLow`.
 */
public data class UploadConstraints(
    val connected: Boolean,
    val unmetered: Boolean,
    val batteryLow: Boolean,
)

/**
 * The device conditions a queued upload is waiting on.
 *
 * WorkManager tells a caller that work is `ENQUEUED`, never *why* it has not started, so the
 * constraints have to be evaluated a second time here to turn "queued" into a sentence a user can
 * act on. A port rather than a direct `ConnectivityManager` read so the decision stays testable
 * without a device.
 */
public fun interface UploadConstraintStatus {
    public fun observe(): Flow<UploadConstraints>
}

/** [UploadConstraintStatus] backed by `ConnectivityManager` and the platform's battery broadcasts. */
public class AndroidUploadConstraintStatus(
    private val context: Context,
) : UploadConstraintStatus {
    override fun observe(): Flow<UploadConstraints> =
        callbackFlow {
            val connectivity = context.getSystemService<ConnectivityManager>()
            var batteryLow = isBatteryLow()

            fun publish() {
                trySend(current(connectivity, batteryLow))
            }

            val networkCallback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = publish()

                    override fun onLost(network: Network) = publish()

                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) = publish()
                }
            val batteryReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        receiverContext: Context?,
                        intent: Intent?,
                    ) {
                        batteryLow = intent?.action == Intent.ACTION_BATTERY_LOW
                        publish()
                    }
                }
            ContextCompat.registerReceiver(
                context,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_LOW).apply { addAction(Intent.ACTION_BATTERY_OKAY) },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            connectivity?.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
            publish()

            awaitClose {
                runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
                runCatching { context.unregisterReceiver(batteryReceiver) }
            }
        }.conflate().distinctUntilChanged()

    private fun current(
        connectivity: ConnectivityManager?,
        batteryLow: Boolean,
    ): UploadConstraints {
        val capabilities = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return UploadConstraints(
            connected = connected,
            unmetered = connected && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            batteryLow = batteryLow,
        )
    }

    private fun isBatteryLow(): Boolean {
        val sticky =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return false
        return sticky.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false)
    }
}
