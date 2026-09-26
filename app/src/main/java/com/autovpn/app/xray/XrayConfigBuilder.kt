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

    fun buildFull(
        proxy: ProxyConfig,
        fragmentEnabled: Boolean = false,
        fragmentLength: String = "10-20",
        fragmentInterval: String = "10-20"
    ): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        // Needed for CoreController.queryStats() to return real numbers instead of 0 -
        // "stats" turns the stats manager on, "policy" tells it to actually count
        // traffic per outbound.
        root.put("stats", JSONObject())
        root.put("policy", JSONObject().apply {
            put("system", JSONObject().apply {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        })

        // This is the inbound that actually reads IP packets from the TUN file
        // descriptor (the fd is passed separately via CoreController.startLoop / the
        // xray.tun.fd env var, not through this JSON). Without this exact inbound,
        // Xray never touches the TUN device at all - which is why pings could succeed
        // (they open their own direct connection, bypassing the tunnel entirely) while
        // real browsing traffic through the VPN interface went nowhere.
        val tunInbound = JSONObject().apply {
            put("port", 0)
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray0")
                put("MTU", 1500)
            })
        }
        root.put("inbounds", JSONArray().put(tunInbound))

        val proxyOutbound = JSONObject(proxy.xrayOutboundJson)

        val directOutbound = JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        }
        val blockOutbound = JSONObject().apply {
            put("protocol", "blackhole")
            put("tag", "block")
        }

        val outbounds = JSONArray()
        if (fragmentEnabled) {
            // "Fragment" splits the TLS ClientHello into small pieces to help get past
            // DPI-based censorship (commonly needed inside Iran). It's a separate freedom
            // outbound that the proxy outbound dials through via sockopt.dialerProxy.
            val streamSettings = if (proxyOutbound.has("streamSettings")) {
                proxyOutbound.getJSONObject("streamSettings")
            } else {
                JSONObject().also { proxyOutbound.put("streamSettings", it) }
            }
            val sockopt = if (streamSettings.has("sockopt")) {
                streamSettings.getJSONObject("sockopt")
            } else {
                JSONObject().also { streamSettings.put("sockopt", it) }
            }
            sockopt.put("dialerProxy", "fragment-out")

            val fragmentOutbound = JSONObject().apply {
                put("protocol", "freedom")
                put("tag", "fragment-out")
                put("settings", JSONObject().apply {
                    put("fragment", JSONObject().apply {
                        put("packets", "tlshello")
                        put("length", fragmentLength.ifBlank { "10-20" })
                        put("interval", fragmentInterval.ifBlank { "10-20" })
                    })
                })
            }
            outbounds.put(fragmentOutbound)
        }

        outbounds.put(proxyOutbound)
        outbounds.put(directOutbound)
        outbounds.put(blockOutbound)
        root.put("outbounds", outbounds)

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
}
