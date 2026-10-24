package com.sukanth.leanlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChipTest {

    @Test
    fun `active wifi shows a connected wifi chip`() {
        val chip = networkChip(
            activeWifi = true,
            activeEthernet = false,
            internetAvailable = true,
            wifiConnected = true,
            wifiEnabled = true,
        )

        assertEquals(R.string.status_wifi, chip.labelRes)
        assertEquals(R.drawable.ic_wifi, chip.iconRes)
        assertTrue(chip.connected)
    }

    @Test
    fun `wifi association without internet still counts as connected`() {
        val chip = networkChip(
            activeWifi = true,
            activeEthernet = false,
            internetAvailable = false,
            wifiConnected = true,
            wifiEnabled = true,
        )

        assertEquals(R.string.status_wifi, chip.labelRes)
        assertTrue(chip.connected)
    }

    @Test
    fun `wifi switched off shows the slashed icon`() {
        val chip = networkChip(
            activeWifi = false,
            activeEthernet = false,
            internetAvailable = false,
            wifiConnected = false,
            wifiEnabled = false,
        )

        assertEquals(R.string.status_wifi_off, chip.labelRes)
        assertEquals(R.drawable.ic_wifi_off, chip.iconRes)
        assertFalse(chip.connected)
    }

    @Test
    fun `wifi on but disconnected shows not connected`() {
        val chip = networkChip(
            activeWifi = false,
            activeEthernet = false,
            internetAvailable = false,
            wifiConnected = false,
            wifiEnabled = true,
        )

        assertEquals(R.string.status_not_connected, chip.labelRes)
        assertEquals(R.drawable.ic_wifi, chip.iconRes)
        assertFalse(chip.connected)
    }

    @Test
    fun `ethernet is used when wifi is off`() {
        val chip = networkChip(
            activeWifi = false,
            activeEthernet = true,
            internetAvailable = true,
            wifiConnected = false,
            wifiEnabled = false,
        )

        assertEquals(R.string.status_ethernet, chip.labelRes)
        assertTrue(chip.connected)
    }

    @Test
    fun `other internet transport shows online`() {
        val chip = networkChip(
            activeWifi = false,
            activeEthernet = false,
            internetAvailable = true,
            wifiConnected = false,
            wifiEnabled = true,
        )

        assertEquals(R.string.status_online, chip.labelRes)
        assertTrue(chip.connected)
    }
}
