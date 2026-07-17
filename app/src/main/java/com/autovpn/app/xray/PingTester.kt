package com.autovpn.app.xray

import com.autovpn.app.model.ProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray

/**
 * Uses Xray's own MeasureOutboundDelay (exposed by AndroidLibXrayLite) to test the real
 * round-trip latency of each candidate through its own outbound - this is far more
 * accurate than a raw TCP ping because it accounts for TLS/websocket/handshake overhead.
 */
object PingTester {

    private const val TEST_URL = "https://www.gstatic.com/generate_204"
    private const val MAX_PARALLEL = 6

    /** Tests every config concurrently and returns the same list, sorted best-ping-first. */
    suspend fun testAll(configs: List<ProxyConfig>): List<ProxyConfig> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(MAX_PARALLEL)
        val jobs = configs.map { cfg ->
            async {
                semaphore.withPermit {
                    cfg.pingMs = try {
                        Libv2ray.measureOutboundDelay(XrayConfigBuilder.buildForPing(cfg), TEST_URL)
                    } catch (e: Exception) {
                        -2L
                    }
                }
            }
        }
        jobs.awaitAll()
        configs.filter { it.pingMs > 0 }.sortedBy { it.pingMs }
    }

    /** Convenience: fetch + ping + return the single best config, or null if none reachable. */
    suspend fun bestOf(configs: List<ProxyConfig>): ProxyConfig? = testAll(configs).firstOrNull()
}
