package ru.nordkey.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.nordkey.app.net.Keenetic
import ru.nordkey.app.ui.setup.RouterSetup

/** Логика мастера без роутера и сервера. */
class SetupLogicTest {
    @Test fun authHashMatchesKeeneticWeb() {
        // SHA256(challenge + MD5("login:realm:password")) — значение посчитано отдельно (Python hashlib)
        assertEquals("fd856a31f7519c3bc9dc8957877a61c41c26e0a2487c0e4b302264c8ef11f55d",
                     Keenetic.authHash("admin", "Secret123", "Keenetic Giga", "ABCDEF0123"))
    }

    @Test fun rciErrors() {
        val r = """[{"parse":{"status":[{"status":"error","code":"7405600","ident":"Command::Base","message":"no such command: foo"}]}}]"""
        assertEquals(listOf("no such command: foo"), Keenetic.errors(r))
        assertEquals(emptyList<String>(), Keenetic.errors("""[{"parse":{}}]"""))
    }

    private val cfg = """
interface Wireguard0
    description home-vpn
    ip address 10.8.0.2 255.255.255.0
    wireguard peer AAAAothersAAAA=
!
interface Wireguard2
    description NordKey
    ip address 10.66.66.77 255.255.255.0
    wireguard peer ${RouterSetup.SRV_KEY}
!
interface Wireguard1
    wireguard peer qUd0UkUPGKHpnrNPfZM193Bv84R/cM8w0GJ9x23qfgI=
!
"""

    @Test fun findsNordKeyTunnelAmongOthers() {
        val t = RouterSetup.findTunnel(cfg)!!
        assertEquals("Wireguard2", t.first)
        assertEquals("10.66.66.77", t.second)
        assertNull(RouterSetup.findTunnel("interface Wireguard0\n    wireguard peer AAAA=\n"))
    }

    @Test fun freeWireguardSkipsUsed() {
        assertEquals("Wireguard3", RouterSetup.freeWireguard(cfg))
        assertEquals("Wireguard0", RouterSetup.freeWireguard("interface GigabitEthernet1\n"))
    }

    @Test fun passwordIsLongAndRandom() {
        val a = RouterSetup.password(); val b = RouterSetup.password()
        assertEquals(24, a.length)
        assert(a != b)
    }
}
