package com.autovpn.app.news

import com.autovpn.app.model.NewsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads the news/<channel>.json file that .github/workflows/news.yml keeps updated,
 * through the jsDelivr GitHub mirror (works even where raw.githubusercontent.com or
 * t.me itself are filtered, since jsDelivr is rarely blocked).
 */
object NewsRepository {

    // >>> Change this if you fork the repo under a different owner/name <<<
    private const val GITHUB_REPO = "sayidbagdeli7-art/Sebvpn2"
    private const val BRANCH = "main"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchChannel(channel: String): List<NewsMessage> = withContext(Dispatchers.IO) {
        val url = "https://cdn.jsdelivr.net/gh/$GITHUB_REPO@$BRANCH/news/${channel.lowercase()}.json"
        val body = try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            null
        } ?: return@withContext emptyList()

        try {
            val root = JSONObject(body)
            val arr = root.getJSONArray("messages")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                if (o.isNull("id")) return@mapNotNull null
                NewsMessage(
                    id = o.getLong("id"),
                    text = o.optString("text", ""),
                    date = if (o.has("date") && !o.isNull("date")) o.getString("date") else null,
                    link = if (o.has("link") && !o.isNull("link")) o.getString("link") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
