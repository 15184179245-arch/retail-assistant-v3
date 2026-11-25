package com.retaildemo.v2

import com.retaildemo.v2.data.remote.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigUrlTest {

    @Test
    fun testCleanServerUrlRemovesTrailingSlashes() {
        assertEquals("http://192.0.2.10:18767", AppConfig.cleanServerUrl("http://192.0.2.10:18767/"))
        assertEquals("http://192.0.2.10:18767", AppConfig.cleanServerUrl("http://192.0.2.10:18767///"))
    }

    @Test
    fun testCleanServerUrlRemovesPathAndFragment() {
        val url = "http://192.0.2.10:18767/dashboard#overview"
        assertEquals("http://192.0.2.10:18767", AppConfig.cleanServerUrl(url))
    }

    @Test
    fun testCleanServerUrlPrependsHttp() {
        assertEquals("http://192.0.2.10:18767", AppConfig.cleanServerUrl("192.0.2.10:18767"))
        assertEquals("http://127.0.0.1:18767", AppConfig.cleanServerUrl("127.0.0.1:18767"))
    }

    @Test
    fun testCleanServerUrlPreservesHttps() {
        assertEquals("https://service.example.invalid", AppConfig.cleanServerUrl("https://service.example.invalid"))
        assertEquals("https://service.example.invalid", AppConfig.cleanServerUrl("https://service.example.invalid/api/health"))
    }

    @Test
    fun testCleanServerUrlBlankFallback() {
        assertEquals(AppConfig.DEFAULT_SERVER_URL, AppConfig.cleanServerUrl(""))
        assertEquals(AppConfig.DEFAULT_SERVER_URL, AppConfig.cleanServerUrl("   "))
    }
}
