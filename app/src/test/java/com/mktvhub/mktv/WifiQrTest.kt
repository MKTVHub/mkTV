package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WifiQrTest {
    @Test fun wpa() = assertEquals("WIFI:T:WPA;S:Home;P:secret123;;", WifiQr.text("Home", "secret123"))
    @Test fun open() = assertEquals("WIFI:T:nopass;S:Cafe;;", WifiQr.text("Cafe", ""))
    @Test fun hidden() = assertEquals("WIFI:T:WPA;S:H;P:12345678;H:true;;", WifiQr.text("H", "12345678", hidden = true))
    @Test fun escapes() = assertEquals("""a\;b\,c\:d\"e\\f""", WifiQr.escape("""a;b,c:d"e\f"""))
    @Test fun ssid() {
        assertEquals("Home 5G", WifiQr.cleanSsid("\"Home 5G\""))
        assertNull(WifiQr.cleanSsid("<unknown ssid>"))
        assertNull(WifiQr.cleanSsid(null))
    }
    @Test fun password() {
        assertNull(WifiQr.passwordProblem(""))
        assertNull(WifiQr.passwordProblem("12345678"))
        assertNotNull(WifiQr.passwordProblem("1234567"))
    }
}
