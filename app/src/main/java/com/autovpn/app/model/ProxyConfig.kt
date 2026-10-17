package com.autovpn.app.model

/**
 * A single parsed proxy node (from a vmess://, vless://, trojan:// or ss:// link).
 * xrayOutboundJson holds the ready-to-use Xray "outbound" JSON object for this node.
 */
data class ProxyConfig(
    val raw: String,          // original share-link, kept for debugging/export
    val protocol: String,     // vmess | vless | trojan | shadowsocks
    val name: String,         // display name (remark)
    val address: String,      // server host, used for ping / sorting
    val port: Int,
    val xrayOutboundJson: String,
    var pingMs: Long = -1     // -1 = not tested yet, -2 = failed
)
