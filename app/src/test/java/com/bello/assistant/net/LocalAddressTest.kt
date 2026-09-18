package com.bello.assistant.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class LocalAddressTest {

    private fun nic(name: String, vararg addresses: String, up: Boolean = true) =
        LocalAddress.Candidate(name, up, addresses.map { InetAddress.getByName(it) })

    @Test fun `the wireless site-local address wins, loopback and the others never`() {
        val tablet = listOf(
            nic("lo", "127.0.0.1"),
            nic("p2p0"),
            nic("rmnet0", "10.0.0.5", up = false),
            nic("eth0", "192.168.5.5"),
            nic("wlan0", "fe80::1", "192.168.1.199"),
        )
        assertEquals("192.168.1.199", LocalAddress.pick(tablet))
        assertEquals("192.168.5.5", LocalAddress.pick(listOf(nic("lo", "127.0.0.1"), nic("eth0", "192.168.5.5"))))
        assertNull(LocalAddress.pick(listOf(nic("lo", "127.0.0.1"), nic("wlan0", "fe80::1"))))
        assertNull(LocalAddress.pick(listOf(nic("wlan0", "192.168.1.199", up = false))))
        assertNull(LocalAddress.pick(listOf(nic("wlan0", "8.8.8.8"))))
    }
}
