package com.bello.assistant.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The messages are the ones exchanged with the STB8 in the house on 2026-09-18 (docs/sfr-tv-box.md). */
class TvBoxProtocolTest {

    @Test fun `a request carries the action, the client id and a request id`() {
        val json = JSONObject(TvBoxProtocol.keyRequest("volUp", 1789745977404))
        assertEquals("buttonEvent", json.getString("action"))
        assertEquals("bello", json.getString("deviceId"))
        assertEquals(1789745977404, json.getLong("requestId"))
        assertEquals("volUp", json.getJSONObject("params").getString("key"))
        assertFalse(JSONObject(TvBoxProtocol.request("getStatus", 7)).has("params"))
    }

    @Test fun `the real replies of the box are read`() {
        val status = TvBoxProtocol.parse(
            """{ "action" : "getStatus", "data" : { "power" : "powerOff" }, "deviceId" : "bello",
               "message" : "", "remoteResponseCode" : "OK", "requestId" : 1789745933063 }"""
        )!!
        assertEquals(1789745933063, status.requestId)
        assertTrue(status.ok)
        assertEquals(false, TvBoxProtocol.power(status))

        val versions = TvBoxProtocol.parse(
            """{ "action" : "getVersions", "data" : { "boxName" : "SFR_STB8_39DC", "boxType" : "STB8",
               "macAddress" : "6035C0EE39DC", "remoteControlVersion" : "1.2.0" }, "deviceId" : "bello",
               "message" : "", "remoteResponseCode" : "OK", "requestId" : 1789745937118 }"""
        )!!
        assertEquals("STB8", versions.data.getString("boxType"))
        assertNull(TvBoxProtocol.power(versions))

        val key = TvBoxProtocol.parse(
            """{"action": "buttonEvent", "data": {}, "deviceId": "bello", "message": "",
                "remoteResponseCode": "OK", "requestId": 1789745977404}"""
        )!!
        assertTrue(key.ok)
        assertEquals("buttonEvent", key.action)
    }

    @Test fun `a notification or noise is not a reply`() {
        assertNull(TvBoxProtocol.parse("""{"data": {"status": "powerOn"}}"""))
        assertNull(TvBoxProtocol.parse("not json"))
        assertFalse(TvBoxProtocol.parse("""{"requestId": 3, "remoteResponseCode": "KO"}""")!!.ok)
    }

    @Test fun `a channel number is its digits, one key each`() {
        assertEquals(listOf("3"), TvBoxProtocol.digits(3))
        assertEquals(listOf("1", "2"), TvBoxProtocol.digits(12))
        assertTrue(TvBoxProtocol.KEYS.containsAll(TvBoxProtocol.digits(120)))
        assertTrue(TvBoxProtocol.KEYS.contains("channelUp"))
    }
}
