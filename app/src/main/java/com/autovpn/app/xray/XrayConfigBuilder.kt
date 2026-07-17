package com.autovpn.app.xray

import com.autovpn.app.model.ProxyConfig
import org.json.JSONObject
import org.json.JSONArray

/**
 * Builds a complete Xray-core JSON configuration.
 * `withTunInbound = true` -> used for the real running tunnel (traffic comes from the
 * TUN file-descriptor via the socks inbound below, this is standard for AndroidLibXrayLite).
 * `withTunInbound = false` -> a minimal config with no inbound at all, only used for
 * MeasureOutboundDelay() ping testing (that call only needs the outbound).
 */
object XrayConfigBuilder {

    private const val SOCKS_PORT = 10808

    fun buildFull(proxy: ProxyConfig): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        val inbound = JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", SOCKS_PORT)
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("udp", true)
                put("auth", "noauth")
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("http", "tls")))
            })
        }
        root.put("inbounds", JSONArray().put(inbound))

        val proxyOutbound = JSONObject(proxy.xrayOutboundJson)
        val directOutbound = JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        }
        val blockOutbound = JSONObject().apply {
            put("protocol", "blackhole")
            put("tag", "block")
        }
        root.put("outbounds", JSONArray().apply {
            put(proxyOutbound)
            put(directOutbound)
            put(blockOutbound)
        })

        return root.toString()
    }

    /** Minimal config (outbound only) for MeasureOutboundDelay ping testing. */
    fun buildForPing(proxy: ProxyConfig): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "none"))
        root.put("inbounds", JSONArray())
        root.put("outbounds", JSONArray().put(JSONObject(proxy.xrayOutboundJson)))
        return root.toString()
    }

    fun socksPort() = SOCKS_PORT
}
