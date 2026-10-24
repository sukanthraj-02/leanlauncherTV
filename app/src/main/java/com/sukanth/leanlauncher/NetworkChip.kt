package com.sukanth.leanlauncher

/**
 * What the top-bar network chip should show for one connectivity snapshot.
 *
 * Kept free of Android types so every branch can be exercised by a plain unit test.
 */
internal data class NetworkChip(
    val labelRes: Int,
    val iconRes: Int,
    val connected: Boolean,
)

internal fun networkChip(
    activeWifi: Boolean,
    activeEthernet: Boolean,
    internetAvailable: Boolean,
    wifiConnected: Boolean,
    wifiEnabled: Boolean,
): NetworkChip = when {
    // An association counts as connected even if the access point has no internet:
    // that is a different problem from being disconnected.
    activeWifi || wifiConnected ->
        NetworkChip(R.string.status_wifi, R.drawable.ic_wifi, connected = true)
    activeEthernet ->
        NetworkChip(R.string.status_ethernet, R.drawable.ic_wifi, connected = true)
    internetAvailable ->
        NetworkChip(R.string.status_online, R.drawable.ic_wifi, connected = true)
    !wifiEnabled ->
        NetworkChip(R.string.status_wifi_off, R.drawable.ic_wifi_off, connected = false)
    else ->
        NetworkChip(R.string.status_not_connected, R.drawable.ic_wifi, connected = false)
}
