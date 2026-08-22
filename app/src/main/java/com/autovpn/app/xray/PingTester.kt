package com.autovpn.app.xray

import com.autovpn.app.model.ProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicInteger

/** Live snapshot of the ping test progress, for showing on screen. */
data class PingProgress(
    val total: Int,
    val succeeded: Int,
    val failed: Int
)

/**
 * Uses Xray's own MeasureOutboundDelay (exposed by AndroidLibXrayLite) to test the real
 * round-trip latency of each candidate through its own outbound - this is far more
 * accurate than a raw TCP ping because it accounts for TLS/websocket/handshake overhead.
 */
object PingTester {

    private const val TEST_URL = "https://www.gstatic.com/generate_204"
    // 30-way parallelism turned out to cause resource contention on the phone itself
    // (too many simultaneous TLS handshakes), which showed up as false failures.
    // This is a calmer middle ground - still much faster than the original 6.
    private const val MAX_PARALLEL = 14
    private const val PER_TEST_TIMEOUT_MS = 9000L

    /**
     * Tests every config concurrently and returns the same list, sorted best-ping-first.
     * onProgress is called after every single config finishes (success or fail) with the
     * running totals so the UI can show live numbers.
     */
    suspend fun testAll(
        configs: List<ProxyConfig>,
        onProgress: ((PingProgress) -> Unit)? = null
    ): List<ProxyConfig> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(MAX_PARALLEL)
        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val total = configs.size

        val jobs = configs.map { cfg ->
            async {
                semaphore.withPermit {
                    cfg.pingMs = withTimeoutOrNull(PER_TEST_TIMEOUT_MS) {
                        try {
                            Libv2ray.measureOutboundDelay(XrayConfigBuilder.buildForPing(cfg), TEST_URL)
                        } catch (e: Exception) {
                            -2L
                        }
                    } ?: -2L
                    if (cfg.pingMs > 0) succeeded.incrementAndGet() else failed.incrementAndGet()
                    onProgress?.invoke(PingProgress(total, succeeded.get(), failed.get()))
                }
            }
        }
        jobs.awaitAll()
        configs.filter { it.pingMs > 0 }.sortedBy { it.pingMs }
    }

    /** Convenience: fetch + ping + return the single best config, or null if none reachable. */
    suspend fun bestOf(
        configs: List<ProxyConfig>,
        onProgress: ((PingProgress) -> Unit)? = null
    ): ProxyConfig? = testAll(configs, onProgress).firstOrNull()
}
