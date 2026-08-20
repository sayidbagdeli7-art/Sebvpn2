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
        // Also add a cache-busting query param since some CDN edge nodes key their
        // cache on the exact URL - this alone isn't guaranteed to bypass jsDelivr's
        // cache, so we still fetch raw GitHub in parallel below as the real fix.
        val rawUrl = "https://raw.githubusercontent.com/$REPO/$BRANCH/$FILE_PATH?t=${System.currentTimeMillis()}"

        // jsDelivr's purge-on-send is best-effort and can occasionally lag behind
        // (rate limits, propagation delay) - rather than only falling back to raw
        // GitHub when jsDelivr fails outright, fetch both every time and merge, so
        // whichever one happens to be fresher wins.
        val jsDelivrBody = tryFetch(jsDelivrUrl)
        val rawBody = tryFetch(rawUrl)

        val fromJsDelivr = parseMessages(jsDelivrBody)
        val fromRaw = parseMessages(rawBody)

        (fromJsDelivr + fromRaw).distinctBy { it.ciphertext }.sortedBy { it.timestamp }
    }

    private fun parseMessages(body: String?): List<ChatMessage> {
        if (body.isNullOrBlank()) return emptyList()
        return try {
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
        val req = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
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

    suspend fun deleteMessage(token: String, ciphertext: String): SendResult = withContext(Dispatchers.IO) {
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
                } else {
                    return@withContext SendResult.Error("خواندن فایل ناموفق بود (${resp.code})")
                }
            }

            val filtered = JSONArray()
            for (i in 0 until currentArr.length()) {
                val o = currentArr.getJSONObject(i)
                if (o.optString("c") != ciphertext) filtered.put(o)
            }

            val newContentB64 = Base64.encodeToString(filtered.toString(2).toByteArray(), Base64.NO_WRAP)
            val putBodyJson = JSONObject().apply {
                put("message", "delete chat message")
                put("content", newContentB64)
                put("branch", BRANCH)
                put("sha", sha)
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
                    SendResult.Error("حذف ناموفق بود (${resp.code})")
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

    /** Same as sendMessage, but goes through a Cloudflare Worker relay instead of
     *  using a personal GitHub token directly - the Worker holds the real token as
     *  a secret, so nobody using this needs (or ever sees) it. */
    suspend fun sendMessageViaRelay(workerUrl: String, accessKey: String, ciphertext: String): SendResult =
        callRelay(workerUrl.trimEnd('/') + "/send", accessKey, ciphertext)

    suspend fun deleteMessageViaRelay(workerUrl: String, accessKey: String, ciphertext: String): SendResult =
        callRelay(workerUrl.trimEnd('/') + "/delete", accessKey, ciphertext)

    private suspend fun callRelay(url: String, accessKey: String, ciphertext: String): SendResult =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext SendResult.Error("آدرسِ Worker وارد نشده")
            if (accessKey.isBlank()) return@withContext SendResult.Error("کلیدِ دسترسی وارد نشده")
            try {
                val bodyJson = JSONObject().apply {
                    put("key", accessKey)
                    put("ciphertext", ciphertext)
                }.toString().toRequestBody("application/json".toMediaType())

                val req = Request.Builder().url(url).post(bodyJson).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) SendResult.Success
                    else SendResult.Error("Worker خطا داد (${resp.code})")
                }
            } catch (e: Exception) {
                SendResult.Error(e.message ?: "خطای ناشناخته")
            }
        }
}
