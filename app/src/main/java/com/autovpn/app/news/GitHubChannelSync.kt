package com.autovpn.app.news

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Adds a channel name to scripts/channels.txt in the GitHub repo (via the Contents API)
 * so the scheduled scraper (.github/workflows/news.yml) picks it up and starts
 * publishing news/<channel>.json - which the app then reads through jsDelivr, same as
 * the default channel.
 */
object GitHubChannelSync {

    private const val REPO = "sayidbagdeli7-art/Sebvpn2"
    private const val FILE_PATH = "scripts/channels.txt"
    private const val BRANCH = "main"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        object Success : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun addChannel(token: String, channel: String): Result = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result.Error("توکن گیت‌هاب وارد نشده")

        val apiUrl = "https://api.github.com/repos/$REPO/contents/$FILE_PATH"
        try {
            // 1) Get current file content + sha
            val getReq = Request.Builder()
                .url("$apiUrl?ref=$BRANCH")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .build()

            val (currentText, sha) = client.newCall(getReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.Error("خواندن فایل ناموفق بود (${resp.code})")
                }
                val body = JSONObject(resp.body?.string() ?: "{}")
                val contentB64 = body.getString("content").replace("\n", "")
                val decoded = String(Base64.decode(contentB64, Base64.DEFAULT))
                decoded to body.getString("sha")
            }

            val existingLines = currentText.lines().map { it.trim() }
            if (existingLines.any { it.equals(channel, ignoreCase = true) }) {
                return@withContext Result.Success // already there
            }

            val newText = if (currentText.isBlank() || currentText.endsWith("\n")) {
                currentText + channel + "\n"
            } else {
                currentText + "\n" + channel + "\n"
            }
            val newContentB64 = Base64.encodeToString(newText.toByteArray(), Base64.NO_WRAP)

            val putBody = JSONObject().apply {
                put("message", "add channel $channel via app")
                put("content", newContentB64)
                put("sha", sha)
                put("branch", BRANCH)
            }.toString().toRequestBody("application/json".toMediaType())

            val putReq = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .put(putBody)
                .build()

            client.newCall(putReq).execute().use { resp ->
                if (resp.isSuccessful) Result.Success
                else Result.Error("ثبت تغییر ناموفق بود (${resp.code})")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "خطای ناشناخته")
        }
    }
}
