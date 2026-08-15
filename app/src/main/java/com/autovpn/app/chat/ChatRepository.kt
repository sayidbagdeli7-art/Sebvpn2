package com.autovpn.app.chat

import android.util.Base64
import com.autovpn.app.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reading uses jsDelivr (fast, usually not filtered). Sending needs the GitHub
 * Contents API directly (jsDelivr is read-only), using the user's own token.
 */
object ChatRepository {

    // >>> Change this if you fork the repo under a different owner/name <<<
    private const val REPO = "sayidbagdeli7-art/Sebvpn2"
    // The chat lives on its OWN branch (not main) so a scheduled cleanup job can
    // periodically rewrite this branch's history to actually erase old messages,
    // without touching the app's real code history on main.
    private const val BRANCH = "chat-data"
    private const val FILE_PATH = "chat/messages.json"
    private const val MAX_MESSAGES = 200

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class SendResult {
        object Success : SendResult()
        data class Error(val message: String) : SendResult()
    }

    suspend fun fetchMessages(): List<ChatMessage> = withContext(Dispatchers.IO) {
        val jsDelivrUrl = "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/$FILE_PATH"
        val body = tryFetch(jsDelivrUrl) ?: tryFetch(
            "https://raw.githubusercontent.com/$REPO/$BRANCH/$FILE_PATH"
        ) ?: return@withContext emptyList()

        try {
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatMessage(ciphertext = o.getString("c"), timestamp = o.optLong("t", 0L))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun tryFetch(url: String): String? = try {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    suspend fun sendMessage(token: String, ciphertext: String): SendResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext SendResult.Error("توکن گیت‌هاب وارد نشده")

        val apiUrl = "https://api.github.com/repos/$REPO/contents/$FILE_PATH"
        try {
            val getReq = Request.Builder()
                .url("$apiUrl?ref=$BRANCH")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .build()

            var currentArr = JSONArray()
            var sha: String? = null

            client.newCall(getReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val respBody = JSONObject(resp.body?.string() ?: "{}")
                    val contentB64 = respBody.getString("content").replace("\n", "")
                    val decoded = String(Base64.decode(contentB64, Base64.DEFAULT))
                    currentArr = try { JSONArray(decoded) } catch (e: Exception) { JSONArray() }
                    sha = respBody.getString("sha")
                } else if (resp.code != 404) {
                    return@withContext SendResult.Error("خواندن فایل ناموفق بود (${resp.code})")
                }
                // 404 is fine - the file doesn't exist yet, we'll create it.
            }

            val newEntry = JSONObject().apply {
                put("c", ciphertext)
                put("t", System.currentTimeMillis())
            }
            currentArr.put(newEntry)

            // Keep only the most recent MAX_MESSAGES entries.
            val trimmed = if (currentArr.length() > MAX_MESSAGES) {
                JSONArray().apply {
                    for (i in (currentArr.length() - MAX_MESSAGES) until currentArr.length()) {
                        put(currentArr.get(i))
                    }
                }
            } else currentArr

            val newContentB64 = Base64.encodeToString(trimmed.toString(2).toByteArray(), Base64.NO_WRAP)

            val putBodyJson = JSONObject().apply {
                put("message", "chat message")
                put("content", newContentB64)
                put("branch", BRANCH)
                if (sha != null) put("sha", sha)
            }.toString().toRequestBody("application/json".toMediaType())

            val putReq = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .put(putBodyJson)
                .build()

            client.newCall(putReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    purgeJsDelivrCache()
                    SendResult.Success
                } else {
                    SendResult.Error("ثبتِ پیام ناموفق بود (${resp.code})")
                }
            }
        } catch (e: Exception) {
            SendResult.Error(e.message ?: "خطای ناشناخته")
        }
    }

    private fun purgeJsDelivrCache() {
        // jsDelivr caches aggressively, so without this a message you just sent can
        // stay invisible (to yourself and the other person) for a long time even
        // though it's really on GitHub already.
        try {
            val purgeUrl = "https://purge.jsdelivr.net/gh/$REPO@$BRANCH/$FILE_PATH"
            val req = Request.Builder().url(purgeUrl).build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            // best-effort only
        }
    }
}
