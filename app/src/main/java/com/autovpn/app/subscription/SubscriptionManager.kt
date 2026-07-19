package com.autovpn.app.subscription

import android.util.Base64
import com.autovpn.app.model.ProxyConfig
import com.autovpn.app.parser.ShareLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object SubscriptionManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Downloads every given subscription URL fresh and returns all parsed configs (deduplicated). */
    suspend fun fetchAll(urls: List<String>): List<ProxyConfig> = withContext(Dispatchers.IO) {
        val deferreds = urls.map { url -> async { safeFetch(url) } }
        val bodies = deferreds.awaitAll()
        val links = mutableListOf<String>()
        for (body in bodies) {
            if (body.isNullOrBlank()) continue
            links.addAll(extractLinks(body))
        }
        links.distinct().mapNotNull { ShareLinkParser.parse(it) }
    }

    private fun safeFetch(url: String): String? {
        val direct = tryFetch(url)
        if (direct != null) return direct

        // raw.githubusercontent.com is blocked by several Iranian mobile carriers.
        // jsDelivr mirrors every public GitHub file and is rarely blocked (too many
        // ordinary websites load their JS libraries from it for carriers to block it
        // outright), so if the direct fetch fails, retry through that mirror.
        val jsDelivrUrl = toJsDelivrMirror(url) ?: return null
        return tryFetch(jsDelivrUrl)
    }

    private fun tryFetch(url: String): String? = try {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Converts https://raw.githubusercontent.com/USER/REPO/BRANCH/PATH
     * into      https://cdn.jsdelivr.net/gh/USER/REPO@BRANCH/PATH
     * Returns null if the URL isn't a raw.githubusercontent.com link.
     */
    private fun toJsDelivrMirror(url: String): String? {
        val prefix = "https://raw.githubusercontent.com/"
        if (!url.startsWith(prefix)) return null
        val rest = url.removePrefix(prefix) // USER/REPO/BRANCH/PATH...
        val parts = rest.split("/", limit = 4)
        if (parts.size < 4) return null
        val (user, repo, branch, path) = parts
        return "https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path"
    }

    private fun extractLinks(body: String): List<String> {
        val trimmed = body.trim()
        val looksLikeLinks = trimmed.lines().any {
            it.startsWith("vmess://") || it.startsWith("vless://") ||
            it.startsWith("trojan://") || it.startsWith("ss://")
        }
        val text = if (looksLikeLinks) trimmed else decodeBase64Safe(trimmed) ?: trimmed
        return text.lines()
            .map { it.trim() }
            .filter {
                it.startsWith("vmess://") || it.startsWith("vless://") ||
                it.startsWith("trojan://") || it.startsWith("ss://")
            }
    }

    private fun decodeBase64Safe(s: String): String? = try {
        val cleaned = s.replace("\n", "").replace("\r", "")
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        String(Base64.decode(padded, Base64.DEFAULT))
    } catch (e: Exception) {
        null
    }
}
