package com.autovpn.app.parser

import android.util.Base64
import com.autovpn.app.model.ProxyConfig
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Parses vmess:// vless:// trojan:// ss:// share-links (the format used by almost every
 * public V2Ray/Xray subscription) into a ProxyConfig that already contains a ready
 * Xray "outbound" JSON block.
 */
object ShareLinkParser {

    fun parse(link: String): ProxyConfig? {
        val trimmed = link.trim()
        return try {
            when {
                trimmed.startsWith("vmess://") -> parseVmess(trimmed)
                trimmed.startsWith("vless://") -> parseVless(trimmed)
                trimmed.startsWith("trojan://") -> parseTrojan(trimmed)
                trimmed.startsWith("ss://") -> parseShadowsocks(trimmed)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun b64(s: String): String {
        val fixed = s.replace('-', '+').replace('_', '/')
        val padded = fixed + "=".repeat((4 - fixed.length % 4) % 4)
        return String(Base64.decode(padded, Base64.DEFAULT))
    }

    private fun streamSettings(net: String, host: String?, path: String?, security: String?,
                                sni: String?, fp: String?, pbk: String?, sid: String?, alpn: String?,
                                serviceName: String?): JSONObject {
        val ss = JSONObject()
        ss.put("network", net.ifBlank { "tcp" })
        when (net) {
            "ws" -> {
                val ws = JSONObject()
                if (!path.isNullOrBlank()) ws.put("path", path)
                if (!host.isNullOrBlank()) {
                    val headers = JSONObject(); headers.put("Host", host)
                    ws.put("headers", headers)
                }
                ss.put("wsSettings", ws)
            }
            "grpc" -> {
                val grpc = JSONObject()
                grpc.put("serviceName", serviceName ?: "")
                ss.put("grpcSettings", grpc)
            }
            "tcp" -> { /* raw tcp, nothing extra needed */ }
        }
        if (security == "tls" || security == "reality") {
            ss.put("security", security)
            val tlsObj = JSONObject()
            if (!sni.isNullOrBlank()) tlsObj.put("serverName", sni)
            if (!alpn.isNullOrBlank()) tlsObj.put("alpn", alpn.split(",").let {
                org.json.JSONArray(it)
            })
            if (security == "reality") {
                if (!pbk.isNullOrBlank()) tlsObj.put("publicKey", pbk)
                if (!sid.isNullOrBlank()) tlsObj.put("shortId", sid)
                if (!fp.isNullOrBlank()) tlsObj.put("fingerprint", fp)
                ss.put("realitySettings", tlsObj)
            } else {
                if (!fp.isNullOrBlank()) tlsObj.put("fingerprint", fp)
                ss.put("tlsSettings", tlsObj)
            }
        }
        return ss
    }

    private fun parseVmess(link: String): ProxyConfig {
        val json = JSONObject(b64(link.removePrefix("vmess://")))
        val add = json.optString("add")
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port")
        val id = json.optString("id")
        val aid = json.optString("aid", "0")
        val net = json.optString("net", "tcp")
        val type = json.optString("type", "")
        val host = json.optString("host", "")
        val path = json.optString("path", "")
        val tlsFlag = json.optString("tls", "")
        val sni = json.optString("sni", host)
        val ps = json.optString("ps", "$add:$port")

        val user = JSONObject().apply {
            put("id", id)
            put("alterId", aid.toIntOrNull() ?: 0)
            put("security", "auto")
        }
        val vnext = JSONObject().apply {
            put("address", add)
            put("port", port)
            put("users", org.json.JSONArray().put(user))
        }
        val settings = JSONObject().put("vnext", org.json.JSONArray().put(vnext))
        val outbound = JSONObject().apply {
            put("protocol", "vmess")
            put("settings", settings)
            put("streamSettings", streamSettings(net, host.ifBlank { null }, path.ifBlank { null },
                tlsFlag.ifBlank { null }, sni.ifBlank { null }, null, null, null, null, path.ifBlank { null }))
            put("tag", "proxy")
        }
        return ProxyConfig(link, "vmess", ps, add, port, outbound.toString())
    }

    /** Splits scheme://userinfo@host:port?query#fragment without java.net.URI, which is
     *  far too strict for real-world links (raw emoji/spaces in the fragment, "&amp;"
     *  instead of "&", etc. - all things v2rayNG-style subscriptions commonly contain
     *  and that URI() would throw on, silently dropping the whole config). */
    private data class RawParts(val userinfo: String, val host: String, val port: Int, val query: String, val fragment: String?)

    private fun splitLink(link: String, scheme: String): RawParts {
        val noScheme = link.removePrefix(scheme)
        val hashIdx = noScheme.indexOf('#')
        val beforeHash = if (hashIdx >= 0) noScheme.substring(0, hashIdx) else noScheme
        val fragment = if (hashIdx >= 0) noScheme.substring(hashIdx + 1) else null

        val atIdx = beforeHash.indexOf('@')
        val userinfo = if (atIdx >= 0) beforeHash.substring(0, atIdx) else ""
        val afterAt = if (atIdx >= 0) beforeHash.substring(atIdx + 1) else beforeHash

        val qIdx = afterAt.indexOf('?')
        val hostPort = if (qIdx >= 0) afterAt.substring(0, qIdx) else afterAt
        val query = if (qIdx >= 0) afterAt.substring(qIdx + 1) else ""

        val host = hostPort.substringBeforeLast(':').ifBlank { hostPort }
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: 443

        return RawParts(userinfo, host, port, query, fragment)
    }

    private fun decodeNameSafe(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        return try { URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
    }

    private fun parseVless(link: String): ProxyConfig {
        val parts = splitLink(link, "vless://")
        val uuid = parts.userinfo
        val host = parts.host
        val port = parts.port
        val q = parseQuery(parts.query)
        val name = decodeNameSafe(parts.fragment, "$host:$port")

        val user = JSONObject().apply {
            put("id", uuid)
            put("encryption", q["encryption"] ?: "none")
        }
        val vnext = JSONObject().apply {
            put("address", host)
            put("port", port)
            put("users", org.json.JSONArray().put(user))
        }
        val settings = JSONObject().put("vnext", org.json.JSONArray().put(vnext))
        val net = q["type"] ?: "tcp"
        val outbound = JSONObject().apply {
            put("protocol", "vless")
            put("settings", settings)
            put("streamSettings", streamSettings(
                net, q["host"], q["path"], q["security"], q["sni"], q["fp"], q["pbk"], q["sid"], q["alpn"], q["serviceName"]
            ))
            put("tag", "proxy")
        }
        return ProxyConfig(link, "vless", name, host, port, outbound.toString())
    }

    private fun parseTrojan(link: String): ProxyConfig {
        val parts = splitLink(link, "trojan://")
        val password = parts.userinfo
        val host = parts.host
        val port = parts.port
        val q = parseQuery(parts.query)
        val name = decodeNameSafe(parts.fragment, "$host:$port")

        val server = JSONObject().apply {
            put("address", host)
            put("port", port)
            put("password", password)
        }
        val settings = JSONObject().put("servers", org.json.JSONArray().put(server))
        val net = q["type"] ?: "tcp"
        val security = q["security"]?.ifBlank { "tls" } ?: "tls" // trojan defaults to tls
        val outbound = JSONObject().apply {
            put("protocol", "trojan")
            put("settings", settings)
            put("streamSettings", streamSettings(
                net, q["host"], q["path"], security, q["sni"], q["fp"], q["pbk"], q["sid"], q["alpn"], q["serviceName"]
            ))
            put("tag", "proxy")
        }
        return ProxyConfig(link, "trojan", name, host, port, outbound.toString())
    }

    private fun parseShadowsocks(link: String): ProxyConfig {
        val body = link.removePrefix("ss://")
        val hashIdx = body.indexOf('#')
        val name: String
        val core: String
        if (hashIdx >= 0) {
            core = body.substring(0, hashIdx)
            name = decodeNameSafe(body.substring(hashIdx + 1), "shadowsocks")
        } else {
            core = body
            name = "shadowsocks"
        }
        val atIdx = core.lastIndexOf('@')
        val methodPass: String
        val hostPort: String
        if (atIdx >= 0) {
            // ss://base64(method:pass)@host:port  OR  ss://method:pass@host:port (SIP002 plain)
            val left = core.substring(0, atIdx)
            hostPort = core.substring(atIdx + 1)
            methodPass = try { b64(left) } catch (e: Exception) { left }
        } else {
            // legacy ss://base64(method:pass@host:port)
            val decoded = b64(core)
            val idx = decoded.lastIndexOf('@')
            methodPass = decoded.substring(0, idx)
            return buildSsConfig(methodPass, decoded.substring(idx + 1), name, link)
        }
        return buildSsConfig(methodPass, hostPort, name, link)
    }

    private fun buildSsConfig(methodPass: String, hostPort: String, name: String, link: String): ProxyConfig {
        val (method, pass) = methodPass.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val hp = hostPort.substringBefore('/').substringBefore('?')
        val host = hp.substringBeforeLast(':')
        val port = hp.substringAfterLast(':').toIntOrNull() ?: 443

        val server = JSONObject().apply {
            put("address", host)
            put("port", port)
            put("method", method)
            put("password", pass)
        }
        val settings = JSONObject().put("servers", org.json.JSONArray().put(server))
        val outbound = JSONObject().apply {
            put("protocol", "shadowsocks")
            put("settings", settings)
            put("tag", "proxy")
        }
        return ProxyConfig(link, "shadowsocks", name, host, port, outbound.toString())
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val normalized = raw.replace("&amp;", "&")
        return normalized.split("&").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) {
                val key = try { URLDecoder.decode(parts[0], "UTF-8") } catch (e: Exception) { parts[0] }
                val value = try { URLDecoder.decode(parts[1], "UTF-8") } catch (e: Exception) { parts[1] }
                key to value
            } else null
        }.toMap()
    }
}
