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

    private fun safeFetch(url: String): String? = try {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
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
